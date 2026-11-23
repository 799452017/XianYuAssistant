package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.config.rag.DynamicVectorStoreManager;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import com.feijimiao.xianyuassistant.service.bo.RAGReplyResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 提示词拼装测试
 *
 * <p>验证携带历史对话时：系统提示词追加了连贯性要求、用户消息里包含历史对话，
 * 且卖家人工回复渲染为“卖家本人”。</p>
 */
@ExtendWith(MockitoExtension.class)
class AutoReplyPromptTest {

    @Mock
    private DynamicAIChatClientManager chatClientManager;

    @Mock
    private DynamicVectorStoreManager vectorStoreManager;

    @Mock
    private SysSettingService sysSettingService;

    @InjectMocks
    private AIServiceImpl aiService;

    @Test
    @DisplayName("带历史时系统提示追加连贯性要求，用户消息包含历史与卖家本人标记")
    void promptContainsHistoryAndConsistencyRule() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientManager.getChatClient()).thenReturn(chatClient);
        when(vectorStoreManager.getVectorStore()).thenReturn(null);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("还是 850，包邮的");

        String history = "user: 128G还有货吗\nseller: 有货，现货\nassistant: 850包邮";
        RAGReplyResult result = aiService.chatByRAGWithFixedMaterial(
                "刚才说多少来着？", "goods-1", history, "固定资料X", "商品详情Y");

        assertEquals("还是 850，包邮的", result.getReplyContent());

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        verify(requestSpec).user(userCaptor.capture());

        String systemPrompt = systemCaptor.getValue();
        String userMessage = userCaptor.getValue();

        assertTrue(systemPrompt.contains("【对话连贯性要求】"), "系统提示词应包含连贯性要求");
        assertTrue(userMessage.contains("历史对话："), "用户消息应包含历史对话段落");
        assertTrue(userMessage.contains("卖家本人：有货，现货"), "人工回复应渲染为卖家本人");
        assertTrue(userMessage.contains("用户：128G还有货吗"), "买家消息应渲染为用户");
        assertTrue(userMessage.contains("助手：850包邮"), "AI历史回复应渲染为助手");
        assertTrue(userMessage.contains("用户问题：刚才说多少来着？"), "应包含本轮问题");
    }

    @Test
    @DisplayName("商品级系统提示词叠加在全局之后、固定资料之前")
    void goodsPromptStacksBetweenGlobalAndFixedMaterial() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientManager.getChatClient()).thenReturn(chatClient);
        when(vectorStoreManager.getVectorStore()).thenReturn(null);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("好的");

        aiService.chatByRAGWithFixedMaterial("在吗", "goods-1", null,
                "本商品话术：只推年卡，不推周卡", "固定资料X", "商品详情Y");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        String systemPrompt = systemCaptor.getValue();

        int globalIdx = systemPrompt.indexOf("你是一个闲鱼卖家");
        int goodsIdx = systemPrompt.indexOf("本商品话术：只推年卡，不推周卡");
        int fixedIdx = systemPrompt.indexOf("固定资料：");

        assertTrue(globalIdx >= 0, "应包含全局系统提示词");
        assertTrue(goodsIdx > globalIdx, "商品级提示词应排在全局之后");
        assertTrue(fixedIdx > goodsIdx, "固定资料应排在商品级提示词之后");
    }

    @Test
    @DisplayName("不带历史时不追加连贯性要求（保持原有行为）")
    void promptWithoutHistoryKeepsPlainInstruction() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientManager.getChatClient()).thenReturn(chatClient);
        when(vectorStoreManager.getVectorStore()).thenReturn(null);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("在的");

        aiService.chatByRAGWithFixedMaterial("在吗", "goods-1", null, null, null);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        verify(requestSpec).user(userCaptor.capture());

        assertFalse(systemCaptor.getValue().contains("【对话连贯性要求】"));
        assertFalse(userCaptor.getValue().contains("历史对话："));
    }
}
