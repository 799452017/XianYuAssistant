package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.reply.AIReplyStrategy;
import com.feijimiao.xianyuassistant.service.reply.KeywordReplyStrategy;
import com.feijimiao.xianyuassistant.service.reply.KeywordWithAIPolishStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 自动回复上下文构建测试
 *
 * <p>覆盖：角色识别、本次触发消息去重、时间正序、跨天断链、图片跳过、字符预算截断。</p>
 */
@ExtendWith(MockitoExtension.class)
class AutoReplyServiceImplContextTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final String SID = "55435931514@goofish";
    private static final String OWN_USER = "own-001";

    @Mock
    private XianyuChatMessageMapper chatMessageMapper;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private AutoReplyServiceImpl service;

    @Test
    @DisplayName("按角色拼接历史、排除本次触发消息、按时间正序输出")
    void buildsRolesInChronologicalOrder() {
        when(accountService.getXianyuUserId(ACCOUNT_ID)).thenReturn(OWN_USER);
        long now = System.currentTimeMillis();
        List<XianyuChatMessage> desc = List.of(
                msg("a1", 888, "在的，您看哪方面", now - 1000, OWN_USER),
                msg("t1", 1, "价格还能少吗", now - 2000, "buyer-1"),
                msg("u1", 1, "128G还有货吗", now - 3000, "buyer-1"),
                msg("s1", 999, "有货，现货", now - 4000, OWN_USER)
        );
        when(chatMessageMapper.findRecentBySId(eq(SID), anyInt(), eq(0))).thenReturn(desc);

        String ctx = service.buildContextMessages(ACCOUNT_ID, SID, List.of(trigger("t1")));

        assertEquals("seller: 有货，现货\nuser: 128G还有货吗\nassistant: 在的，您看哪方面", ctx);
    }

    @Test
    @DisplayName("间隔超过24小时视为上一段对话，不再往前取")
    void stopsAtLargeTimeGap() {
        when(accountService.getXianyuUserId(ACCOUNT_ID)).thenReturn(OWN_USER);
        long now = System.currentTimeMillis();
        List<XianyuChatMessage> desc = List.of(
                msg("n1", 999, "今天发货", now, OWN_USER),
                msg("o1", 1, "上次说的还算数吗", now - 25 * 3600 * 1000L, "buyer-1")
        );
        when(chatMessageMapper.findRecentBySId(eq(SID), anyInt(), eq(0))).thenReturn(desc);

        assertEquals("seller: 今天发货", service.buildContextMessages(ACCOUNT_ID, SID, List.of()));
    }

    @Test
    @DisplayName("历史里只有本次触发消息时返回 null（首次对话不带上下文）")
    void returnsNullWhenOnlyTriggerMessages() {
        when(accountService.getXianyuUserId(ACCOUNT_ID)).thenReturn(OWN_USER);
        List<XianyuChatMessage> desc = List.of(
                msg("t1", 1, "你好", System.currentTimeMillis(), "buyer-1")
        );
        when(chatMessageMapper.findRecentBySId(eq(SID), anyInt(), eq(0))).thenReturn(desc);

        assertNull(service.buildContextMessages(ACCOUNT_ID, SID, List.of(trigger("t1"))));
    }

    @Test
    @DisplayName("跳过图片消息，超出字符预算时丢弃最早的消息")
    void skipsImagesAndTrimsOldest() {
        when(accountService.getXianyuUserId(ACCOUNT_ID)).thenReturn(OWN_USER);
        long now = System.currentTimeMillis();
        List<XianyuChatMessage> desc = new ArrayList<>();
        desc.add(msg("i1", 887, "[图片]http://example.com/a.jpg", now, OWN_USER));
        for (int i = 0; i < 15; i++) {
            desc.add(msg("m" + i, 1, "很早的消息" + i + "x".repeat(180), now - (i + 1) * 1000L, "buyer-1"));
        }
        when(chatMessageMapper.findRecentBySId(eq(SID), anyInt(), eq(0))).thenReturn(desc);

        String ctx = service.buildContextMessages(ACCOUNT_ID, SID, List.of());

        assertNotNull(ctx);
        assertFalse(ctx.contains("[图片]"));
        assertTrue(ctx.length() <= 2000, "上下文总长度应受预算限制，实际=" + ctx.length());
        assertTrue(ctx.contains("很早的消息0"), "最新的消息应保留");
        assertFalse(ctx.contains("很早的消息14"), "最老的消息应被丢弃");
    }

    @Test
    @DisplayName("未获取到闲鱼用户ID时不构建上下文，避免把卖家自己的消息当成买家说的")
    void returnsNullWhenOwnUserIdMissing() {
        when(accountService.getXianyuUserId(ACCOUNT_ID)).thenReturn(null);
        List<XianyuChatMessage> desc = List.of(
                msg("u1", 1, "在吗", System.currentTimeMillis(), "buyer-1")
        );
        when(chatMessageMapper.findRecentBySId(eq(SID), anyInt(), eq(0))).thenReturn(desc);

        assertNull(service.buildContextMessages(ACCOUNT_ID, SID, List.of()));
    }

    @Test
    @DisplayName("携带上下文开关：1=开、0=关、未配置默认开、无配置为关")
    void contextSwitchSemantics() {
        XianyuGoodsConfig on = new XianyuGoodsConfig();
        on.setXianyuAutoReplyContextOn(1);
        XianyuGoodsConfig off = new XianyuGoodsConfig();
        off.setXianyuAutoReplyContextOn(0);
        XianyuGoodsConfig unset = new XianyuGoodsConfig();

        assertTrue(service.isContextEnabled(on));
        assertFalse(service.isContextEnabled(off));
        assertTrue(service.isContextEnabled(unset), "未配置时应默认开启");
        assertFalse(service.isContextEnabled(null));
    }

    @Test
    @DisplayName("只有 AI 相关策略需要上下文，纯关键词策略不需要")
    void onlyAiStrategiesNeedContext() {
        assertTrue(new AIReplyStrategy().needsContext());
        assertTrue(new KeywordWithAIPolishStrategy().needsContext());
        assertFalse(new KeywordReplyStrategy().needsContext());
    }

    private static XianyuChatMessage msg(String pnmId, int contentType, String content, long time, String sender) {
        XianyuChatMessage message = new XianyuChatMessage();
        message.setPnmId(pnmId);
        message.setContentType(contentType);
        message.setMsgContent(content);
        message.setMessageTime(time);
        message.setSenderUserId(sender);
        return message;
    }

    private static ChatMessageData trigger(String pnmId) {
        ChatMessageData data = new ChatMessageData();
        data.setPnmId(pnmId);
        return data;
    }
}
