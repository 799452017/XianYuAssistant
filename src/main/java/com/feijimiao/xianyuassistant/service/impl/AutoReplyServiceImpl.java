package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.constants.ChatMessageConstants;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.bo.AutoReplyTriggerContext;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.service.AIService;
import com.feijimiao.xianyuassistant.service.AutoReplyService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.bo.RAGReplyResult;
import com.feijimiao.xianyuassistant.service.reply.ReplyStrategy;
import com.feijimiao.xianyuassistant.service.reply.ReplyStrategyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class AutoReplyServiceImpl implements AutoReplyService {
    
    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;
    
    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    
    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;
    
    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;
    
    @Autowired
    private WebSocketService webSocketService;
    
    @Autowired(required = false)
    private AIService aiService;

    @Autowired
    private DynamicAIChatClientManager dynamicAIChatClientManager;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.SentMessageSaveService sentMessageSaveService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.AccountService accountService;

    @Autowired
    private ReplyStrategyResolver replyStrategyResolver;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 会话上下文最多取多少条历史消息 */
    private static final int MAX_CONTEXT_MESSAGES = 20;

    /** 会话上下文总字符数上限，超出后从最早的消息开始丢弃 */
    private static final int MAX_CONTEXT_CHARS = 2000;

    /** 单条历史消息最大字符数，超出截断（避免一条超长消息占满预算） */
    private static final int MAX_CONTEXT_MSG_CHARS = 200;

    /** 相邻两条消息间隔超过该值时视为新会话，不再继续往前取（24小时） */
    private static final long MAX_CONTEXT_GAP_MS = 24 * 60 * 60 * 1000L;
    
    @Override
    public void executeAutoReply(ChatMessageData messageData) {
        if (messageData == null) {
            log.warn("消息数据为空，无法执行自动回复");
            return;
        }
        executeAutoReply(Collections.singletonList(messageData));
    }
    
    @Override
    public void executeAutoReply(List<ChatMessageData> messageList) {
        if (messageList == null || messageList.isEmpty()) {
            log.warn("消息列表为空，无法执行自动回复");
            return;
        }
        
        ChatMessageData lastMessage = messageList.get(messageList.size() - 1);
        Long accountId = lastMessage.getXianyuAccountId();
        String xyGoodsId = lastMessage.getXyGoodsId();
        String sId = lastMessage.getSId();
        String pnmId = lastMessage.getPnmId();
        
        String buyerMessage = messageList.stream()
                .map(ChatMessageData::getMsgContent)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        
        log.info("【账号{}】开始执行自动回复: xyGoodsId={}, sId={}, 触发消息数={}, buyerMessage={}", 
                accountId, xyGoodsId, sId, messageList.size(), buyerMessage);
        
        try {
            // 1. 加载商品配置（一次查询，后续开关判断、固定资料都复用这份快照）
            XianyuGoodsConfig goodsConfig = goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId);
            if (!isAnyReplyEnabled(goodsConfig)) {
                log.info("【账号{}】商品未开启任何回复开关: xyGoodsId={}", accountId, xyGoodsId);
                return;
            }
            
            // 2. 获取商品本地ID
            XianyuGoodsInfo goodsInfo = goodsInfoMapper.selectOne(
                    new LambdaQueryWrapper<XianyuGoodsInfo>()
                            .eq(XianyuGoodsInfo::getXyGoodId, xyGoodsId)
                            .eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
            );
            if (goodsInfo == null) {
                log.warn("【账号{}】未找到商品信息: xyGoodsId={}", accountId, xyGoodsId);
                return;
            }
            
            // 3. 解析回复策略
            ReplyStrategy strategy = replyStrategyResolver.resolve(goodsConfig);
            if (strategy == null) {
                log.info("【账号{}】无可用回复策略: xyGoodsId={}", accountId, xyGoodsId);
                return;
            }
            
            // 4. 构建触发上下文
            AutoReplyTriggerContext triggerContext = new AutoReplyTriggerContext();
            List<AutoReplyTriggerContext.TriggerMessage> triggerMessages = new ArrayList<>();
            for (ChatMessageData msg : messageList) {
                AutoReplyTriggerContext.TriggerMessage tm = new AutoReplyTriggerContext.TriggerMessage();
                tm.setPnmId(msg.getPnmId());
                tm.setSenderUserId(msg.getSenderUserId());
                tm.setSenderUserName(msg.getSenderUserName());
                tm.setMsgContent(msg.getMsgContent());
                tm.setMessageTime(msg.getMessageTime());
                triggerMessages.add(tm);
            }
            triggerContext.setTriggerMessages(triggerMessages);
            
            // 5. 创建回复记录（状态=0，待回复）
            XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
            record.setXianyuAccountId(accountId);
            record.setXianyuGoodsId(goodsInfo.getId());
            record.setXyGoodsId(xyGoodsId);
            record.setSId(sId);
            record.setPnmId(pnmId);
            record.setBuyerUserId(lastMessage.getSenderUserId());
            record.setBuyerUserName(lastMessage.getSenderUserName());
            record.setBuyerMessage(buyerMessage);
            record.setState(0);
            
            int insertResult;
            try {
                insertResult = autoReplyRecordMapper.insert(record);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    log.info("【账号{}】该消息已处理过，跳过自动回复: sId={}, pnmId={}", accountId, sId, pnmId);
                    return;
                }
                throw e;
            }
            
            if (insertResult <= 0) {
                log.error("【账号{}】创建回复记录失败", accountId);
                return;
            }
            
            // 6. 构建会话历史上下文（仅需要上下文的策略才查询，且受“携带上下文”开关控制）
            String contextMessages = null;
            if (!strategy.needsContext()) {
                log.debug("【账号{}】当前策略无需上下文，跳过历史构建", accountId);
            } else if (!isContextEnabled(goodsConfig)) {
                log.info("【账号{}】携带上下文开关已关闭，不携带历史对话: xyGoodsId={}", accountId, xyGoodsId);
            } else {
                contextMessages = buildContextMessages(accountId, sId, messageList);
                if (contextMessages != null) {
                    triggerContext.setContextMessages(contextMessages);
                    log.info("【账号{}】已携带会话上下文: sId={}, 长度={}", accountId, sId, contextMessages.length());
                } else {
                    log.info("【账号{}】无可用会话上下文（首次对话或历史消息均属于本次触发）: sId={}", accountId, sId);
                }
            }

            // 7. 执行回复策略（携带上下文）
            ReplyStrategy.ReplyResult replyResult = strategy.execute(messageList, contextMessages);
            
            if (!replyResult.isSuccess() || replyResult.getItems() == null || replyResult.getItems().isEmpty()) {
                log.warn("【账号{}】回复策略未生成有效内容", accountId);
                updateRecordState(record.getId(), -1, null);
                return;
            }
            
            if (replyResult.getMatchedKeyword() != null) {
                record.setMatchedKeyword(replyResult.getMatchedKeyword());
            }
            
            String allReplyText = replyResult.getItems().stream()
                    .map(ReplyStrategy.ReplyResult.ReplyItem::getTextContent)
                    .filter(t -> t != null && !t.trim().isEmpty())
                    .collect(java.util.stream.Collectors.joining("\n"));
            record.setReplyType(replyResult.getItems().get(0).getReplyType());
            
            log.info("【账号{}】回复策略生成内容: type={}, keyword={}, itemCount={}", 
                    accountId, replyResult.getItems().get(0).getReplyType(), replyResult.getMatchedKeyword(),
                    replyResult.getItems().size());
            
            // 8. 保存触发上下文（复用步骤1/2已加载的配置与商品信息，不再重复查询）
            if (goodsConfig.getFixedMaterial() != null && !goodsConfig.getFixedMaterial().isEmpty()) {
                triggerContext.setFixedMaterial(goodsConfig.getFixedMaterial());
            }
            if (goodsInfo.getDetailInfo() != null && !goodsInfo.getDetailInfo().isEmpty()) {
                triggerContext.setGoodsDetail(goodsInfo.getDetailInfo());
            }
            
            try {
                String triggerContextJson = objectMapper.writeValueAsString(triggerContext);
                record.setTriggerContext(triggerContextJson);
                autoReplyRecordMapper.updateTriggerContext(record.getId(), triggerContextJson);
            } catch (Exception e) {
                log.warn("【账号{}】序列化触发上下文失败，跳过保存: {}", accountId, e.getMessage());
            }
            
            // 9. 发送回复消息
            boolean sendSuccess = false;
            String cid = sId.replace("@goofish", "");
            String toId = cid;
            
            for (ReplyStrategy.ReplyResult.ReplyItem item : replyResult.getItems()) {
                if (item.getImageUrl() != null && !item.getImageUrl().isEmpty()) {
                    boolean imageSent = webSocketService.sendImageMessageWithResult(accountId, cid, toId, item.getImageUrl(), 0, 0);
                    if (!imageSent) {
                        log.warn("【账号{}】发送回复图片失败: {}", accountId, item.getImageUrl());
                    } else {
                        sendSuccess = true;
                        sentMessageSaveService.saveAiImageReply(accountId, cid, toId, item.getImageUrl(), xyGoodsId);
                    }
                }
                if (item.getTextContent() != null && !item.getTextContent().trim().isEmpty()) {
                    boolean textSent = webSocketService.sendMessage(accountId, cid, toId, item.getTextContent());
                    if (textSent) {
                        sendSuccess = true;
                    }
                }
            }
            
            // 10. 更新记录状态
            if (sendSuccess) {
                log.info("【账号{}】自动回复成功: xyGoodsId={}, sId={}", accountId, xyGoodsId, sId);
                updateRecordState(record.getId(), 1, allReplyText);
                
                if (allReplyText != null && !allReplyText.trim().isEmpty()) {
                    sentMessageSaveService.saveAiAssistantReply(accountId, cid, toId, allReplyText, xyGoodsId);
                }
            } else {
                log.error("【账号{}】自动回复发送失败: xyGoodsId={}, sId={}", accountId, xyGoodsId, sId);
                updateRecordState(record.getId(), -1, allReplyText);
            }
            
        } catch (Exception e) {
            log.error("【账号{}】执行自动回复异常: xyGoodsId={}, sId={}", accountId, xyGoodsId, sId, e);
        }
    }
    
    /**
     * 检查商品是否开启“携带上下文”
     *
     * <p>字段 xianyu_auto_reply_context_on，1=开启，0=关闭；未配置时按开启处理（跟随自动回复开关的默认行为）。</p>
     */
    boolean isContextEnabled(XianyuGoodsConfig goodsConfig) {
        if (goodsConfig == null) {
            return false;
        }
        Integer contextOn = goodsConfig.getXianyuAutoReplyContextOn();
        // 未配置时默认开启，与建表默认值（DEFAULT 1）保持一致
        return contextOn == null || contextOn == 1;
    }

    /**
     * 根据会话ID构建历史上下文
     *
     * <p>从 xianyu_chat_message 取该会话最近的消息，按 role: content 逐行拼接，
     * 供 AI 回复时保持多轮对话的一致性。角色判定：</p>
     * <ul>
     *   <li>contentType=888/887：AI 自动回复 → assistant</li>
     *   <li>contentType=999/997：卖家手动回复 → seller（人工已答，AI 不应推翻或重复）</li>
     *   <li>contentType=1 且发送者是自己 → seller；否则 → user</li>
     * </ul>
     *
     * <p>会排除本次触发回复的消息（按 pnmId 去重，避免同一条消息既是“历史”又是“当前问题”），
     * 并跳过图片占位消息、超过总长度预算后从最早的消息开始丢弃。</p>
     *
     * @param accountId       闲鱼账号ID
     * @param sId             会话ID
     * @param triggerMessages 本次触发回复的消息列表
     * @return 形如 {@code "user: 在吗\nassistant: 在的"} 的上下文，无可用的返回 null
     */
    String buildContextMessages(Long accountId, String sId, List<ChatMessageData> triggerMessages) {
        if (accountId == null || sId == null) {
            return null;
        }
        try {
            Set<String> triggerPnmIds = new HashSet<>();
            if (triggerMessages != null) {
                for (ChatMessageData msg : triggerMessages) {
                    if (msg.getPnmId() != null) {
                        triggerPnmIds.add(msg.getPnmId());
                    }
                }
            }

            // 按 message_time DESC 取最近的若干条（多取一些，去掉图片/触发消息后再截断）
            List<XianyuChatMessage> history = chatMessageMapper.findRecentBySId(sId, MAX_CONTEXT_MESSAGES * 3, 0);
            if (history == null || history.isEmpty()) {
                return null;
            }

            String ownUserId = accountService.getXianyuUserId(accountId);
            if (ownUserId == null || ownUserId.isEmpty()) {
                // 无法区分“买家说的”和“卖家自己发的”，宁可这次不带历史，也不要让AI把卖家的话当成买家提问
                log.warn("【账号{}】未获取到闲鱼用户ID，本次跳过上下文构建: sId={}", accountId, sId);
                return null;
            }

            List<String> lines = new ArrayList<>();
            long newerMessageTime = -1L;
            int count = 0;

            for (XianyuChatMessage msg : history) {
                String content = msg.getMsgContent();
                if (content == null || content.trim().isEmpty()) {
                    continue;
                }
                // 图片消息以 [图片] 开头，对文本对话没有价值
                if (content.startsWith("[图片]")) {
                    continue;
                }
                // 本次触发的消息会出现在“用户问题：”里，不再重复放进历史
                if (msg.getPnmId() != null && triggerPnmIds.contains(msg.getPnmId())) {
                    continue;
                }

                long messageTime = msg.getMessageTime() != null ? msg.getMessageTime() : 0L;
                if (newerMessageTime > 0 && messageTime > 0 && newerMessageTime - messageTime > MAX_CONTEXT_GAP_MS) {
                    // 与上一条消息间隔超过24小时，视为上一段对话，不再往前取
                    break;
                }
                if (messageTime > 0) {
                    newerMessageTime = messageTime;
                }

                String role = resolveContextRole(msg, ownUserId);
                if (role == null) {
                    continue;
                }

                String text = truncateContextText(content.replace("\r", " ").replace("\n", " ").trim());
                lines.add(role + ": " + text);

                if (++count >= MAX_CONTEXT_MESSAGES) {
                    break;
                }
            }

            if (lines.isEmpty()) {
                return null;
            }

            // 上面是按时间倒序收集的，翻转成正常对话顺序
            Collections.reverse(lines);

            // 控制总长度：超预算时丢弃最早的消息
            while (lines.size() > 1 && totalContextLength(lines) > MAX_CONTEXT_CHARS) {
                lines.remove(0);
            }

            return String.join("\n", lines);
        } catch (Exception e) {
            log.warn("【账号{}】构建会话上下文失败，本次不携带历史: sId={}, error={}", accountId, sId, e.getMessage());
            return null;
        }
    }

    /**
     * 判定历史消息在上下文中的角色
     *
     * @return user / seller / assistant，无法识别返回 null
     */
    private String resolveContextRole(XianyuChatMessage msg, String ownUserId) {
        Integer contentType = msg.getContentType();
        if (contentType == null) {
            return null;
        }
        switch (contentType) {
            case ChatMessageConstants.ContentType.AI_ASSISTANT_REPLY:
            case ChatMessageConstants.ContentType.AI_IMAGE_REPLY:
                return ChatMessageConstants.ContextRole.ASSISTANT;
            case ChatMessageConstants.ContentType.MANUAL_REPLY:
            case ChatMessageConstants.ContentType.IMAGE_REPLY:
                return ChatMessageConstants.ContextRole.SELLER;
            case ChatMessageConstants.ContentType.USER_MESSAGE:
                if (ownUserId.equals(msg.getSenderUserId())) {
                    return ChatMessageConstants.ContextRole.SELLER;
                }
                return ChatMessageConstants.ContextRole.USER;
            default:
                return null;
        }
    }

    /**
     * 截断单条历史消息，避免切断 UTF-16 代理对（emoji 等）产生乱码
     */
    private String truncateContextText(String text) {
        if (text.length() <= MAX_CONTEXT_MSG_CHARS) {
            return text;
        }
        int end = MAX_CONTEXT_MSG_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "...";
    }

    /**
     * 计算上下文总字符数
     */
    private int totalContextLength(List<String> lines) {
        int total = 0;
        for (String line : lines) {
            total += line.length() + 1;
        }
        return total;
    }

    @Override
    public boolean isAutoReplyEnabled(Long accountId, String xyGoodsId) {
        if (accountId == null || xyGoodsId == null) {
            return false;
        }
        try {
            return isAnyReplyEnabled(goodsConfigMapper.selectByAccountAndGoodsId(accountId, xyGoodsId));
        } catch (Exception e) {
            log.error("【账号{}】检查回复开关异常: xyGoodsId={}", accountId, xyGoodsId, e);
            return false;
        }
    }

    /**
     * 判断商品是否开启了任一回复开关（AI 回复 / 关键词回复）
     *
     * @param goodsConfig 商品配置，可为 null
     * @return true 表示至少有一个开关开启
     */
    private boolean isAnyReplyEnabled(XianyuGoodsConfig goodsConfig) {
        if (goodsConfig == null) {
            return false;
        }
        boolean aiOn = goodsConfig.getXianyuAutoReplyOn() != null && goodsConfig.getXianyuAutoReplyOn() == 1;
        boolean keywordOn = goodsConfig.getXianyuKeywordReplyOn() != null && goodsConfig.getXianyuKeywordReplyOn() == 1;
        return aiOn || keywordOn;
    }
    
    private void updateRecordState(Long recordId, Integer state, String replyContent) {
        try {
            autoReplyRecordMapper.updateStateAndContent(recordId, state, replyContent);
        } catch (Exception e) {
            log.error("更新回复记录状态失败: recordId={}, state={}", recordId, state, e);
        }
    }
}
