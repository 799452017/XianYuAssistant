package com.feijimiao.xianyuassistant.service.reply;

import com.feijimiao.xianyuassistant.entity.bo.KeywordReplyRuleBO;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;

import java.util.ArrayList;
import java.util.List;

public interface ReplyStrategy {

    ReplyResult execute(List<ChatMessageData> messageList);

    /**
     * 是否需要会话历史上下文
     *
     * <p>只有会调用大模型的策略（AI 回复、关键词 + AI 润色）才需要；纯关键词策略不需要，
     * 返回 false 可以省掉查询与拼接历史的消息开销。</p>
     *
     * @return true 表示调用方应准备上下文并传入 {@link #execute(List, String)}
     */
    default boolean needsContext() {
        return false;
    }

    /**
     * 执行回复策略（携带会话历史上下文）
     *
     * <p>默认忽略上下文；只有需要历史的策略（AI回复、关键词+AI润色）才重写本方法。</p>
     *
     * @param messageList     本次触发回复的买家消息（延时窗口内收集的消息）
     * @param contextMessages 会话历史，格式 role: content（role 取值 user/seller/assistant），可为 null
     * @return 回复结果
     */
    default ReplyResult execute(List<ChatMessageData> messageList, String contextMessages) {
        return execute(messageList);
    }

    @lombok.Data
    class ReplyResult {
        private boolean success;
        private List<ReplyItem> items = new ArrayList<>();
        private String matchedKeyword;
        private KeywordReplyRuleBO matchedRule;
        private List<KeywordReplyRuleBO> matchedRules = new ArrayList<>();

        @lombok.Data
        public static class ReplyItem {
            private String textContent;
            private String imageUrl;
            private int replyType;

            public static ReplyItem text(String text, int replyType) {
                ReplyItem item = new ReplyItem();
                item.setTextContent(text);
                item.setReplyType(replyType);
                return item;
            }

            public static ReplyItem image(String imageUrl, int replyType) {
                ReplyItem item = new ReplyItem();
                item.setImageUrl(imageUrl);
                item.setReplyType(replyType);
                return item;
            }

            public static ReplyItem textAndImage(String text, String imageUrl, int replyType) {
                ReplyItem item = new ReplyItem();
                item.setTextContent(text);
                item.setImageUrl(imageUrl);
                item.setReplyType(replyType);
                return item;
            }
        }

        public static ReplyResult of(List<ReplyItem> items) {
            ReplyResult r = new ReplyResult();
            r.setSuccess(true);
            r.setItems(items);
            return r;
        }

        public static ReplyResult fail() {
            ReplyResult r = new ReplyResult();
            r.setSuccess(false);
            return r;
        }
    }
}
