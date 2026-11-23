package com.feijimiao.xianyuassistant.service.reply;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.entity.bo.KeywordReplyRuleBO;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.service.KeywordReplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ReplyStrategyResolver {

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private KeywordReplyService keywordReplyService;

    @Autowired
    private KeywordReplyStrategy keywordReplyStrategy;

    @Autowired
    private KeywordWithAIPolishStrategy keywordWithAIPolishStrategy;

    @Autowired
    private AIReplyStrategy aiReplyStrategy;

    public ReplyStrategy resolve(List<ChatMessageData> messageList) {
        ChatMessageData lastMessage = messageList.get(messageList.size() - 1);
        Long accountId = lastMessage.getXianyuAccountId();
        String xyGoodsId = lastMessage.getXyGoodsId();

        return resolve(goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId));
    }

    /**
     * 根据已加载的商品配置解析回复策略
     *
     * <p>供调用方复用同一份配置快照，避免同一次回复过程中重复查询 xianyu_goods_config。</p>
     *
     * @param config 商品配置，可为 null（视为全部关闭）
     * @return 回复策略，全部关闭时返回 null
     */
    public ReplyStrategy resolve(XianyuGoodsConfig config) {
        boolean keywordReplyOn = config != null && config.getXianyuKeywordReplyOn() != null && config.getXianyuKeywordReplyOn() == 1;
        boolean aiReplyOn = config != null && config.getXianyuAutoReplyOn() != null && config.getXianyuAutoReplyOn() == 1;

        if (keywordReplyOn && aiReplyOn) {
            return keywordWithAIPolishStrategy;
        } else if (keywordReplyOn) {
            return keywordReplyStrategy;
        } else if (aiReplyOn) {
            return aiReplyStrategy;
        }

        return null;
    }
}
