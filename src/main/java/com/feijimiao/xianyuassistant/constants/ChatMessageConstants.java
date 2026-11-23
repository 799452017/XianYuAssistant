package com.feijimiao.xianyuassistant.constants;

/**
 * 聊天消息相关常量
 *
 * <p>统一消息类型与会话上下文角色的取值，避免在各处硬编码魔法数字 / 字符串导致口径不一致。</p>
 */
public class ChatMessageConstants {

    private ChatMessageConstants() {
    }

    /**
     * 聊天消息类型（对应 xianyu_chat_message.content_type）
     */
    public static class ContentType {
        /** 买家消息（闲鱼推送的用户消息） */
        public static final int USER_MESSAGE = 1;
        /** AI 自动回复文本 */
        public static final int AI_ASSISTANT_REPLY = 888;
        /** AI 自动回复图片 */
        public static final int AI_IMAGE_REPLY = 887;
        /** 卖家手动回复文本 */
        public static final int MANUAL_REPLY = 999;
        /** 卖家手动回复图片 */
        public static final int IMAGE_REPLY = 997;
    }

    /**
     * 会话上下文中的角色
     *
     * <p>上下文按 {@code role: content} 逐行拼接，{@link #USER}/{@link #SELLER}/{@link #ASSISTANT}
     * 是传输格式，{@code *_LABEL} 是渲染给模型看的中文标签。</p>
     */
    public static class ContextRole {
        /** 买家 */
        public static final String USER = "user";
        /** 卖家本人（人工回复） */
        public static final String SELLER = "seller";
        /** AI 助手自己（自动回复） */
        public static final String ASSISTANT = "assistant";

        /** 渲染标签：买家 */
        public static final String USER_LABEL = "用户：";
        /** 渲染标签：卖家本人 */
        public static final String SELLER_LABEL = "卖家本人：";
        /** 渲染标签：AI 助手 */
        public static final String ASSISTANT_LABEL = "助手：";
    }
}
