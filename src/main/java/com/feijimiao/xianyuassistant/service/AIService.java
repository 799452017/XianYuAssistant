package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.service.bo.RAGDataRespBO;
import com.feijimiao.xianyuassistant.service.bo.RAGReplyResult;
import org.antlr.v4.runtime.TokenStream;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @author IAMLZY
 * @date 2026/4/10 22:26
 * @description
 */

public interface AIService {

    Flux<String> chatByRAG(String msg,String goodsId);

    /**
     * RAG聊天并返回命中资料详情
     */
    RAGReplyResult chatByRAGWithDetails(String msg, String goodsId);

    /**
     * RAG聊天并返回命中资料详情（携带会话上下文）
     */
    RAGReplyResult chatByRAGWithDetails(String msg, String goodsId, String contextMessages);

    /**
     * RAG聊天并返回命中资料详情（携带固定资料和商品详情）
     */
    RAGReplyResult chatByRAGWithFixedMaterial(String msg, String goodsId, String fixedMaterial, String goodsDetail);

    /**
     * RAG聊天（流式）- 携带固定资料和商品详情
     */
    reactor.core.publisher.Flux<String> chatByRAGWithFixedMaterialStream(String msg, String goodsId, String fixedMaterial, String goodsDetail);

    /**
     * 流式对话（叠加商品级系统提示词），用于自动回复测试页
     */
    reactor.core.publisher.Flux<String> chatByRAGWithFixedMaterialStream(String msg, String goodsId, String goodsSystemPrompt,
                                                                        String fixedMaterial, String goodsDetail);

    /**
     * 流式对话（携带会话历史 + 商品级系统提示词），用于自动回复测试页
     */
    reactor.core.publisher.Flux<String> chatByRAGWithFixedMaterialStream(String msg, String goodsId, String contextMessages,
                                                                        String goodsSystemPrompt, String fixedMaterial, String goodsDetail);

    /**
     * RAG聊天并返回命中资料详情（携带会话上下文、固定资料和商品详情）
     */
    RAGReplyResult chatByRAGWithFixedMaterial(String msg, String goodsId, String contextMessages, String fixedMaterial, String goodsDetail);

    /**
     * 生成回复（叠加商品级系统提示词）
     *
     * <p>最终系统提示词 = 全局系统提示词 + 商品级系统提示词 + 固定资料，后两者可为空。</p>
     *
     * @param msg              买家消息
     * @param goodsId          闲鱼商品ID（用于 RAG 过滤）
     * @param contextMessages  会话历史（role: content），可为 null
     * @param goodsSystemPrompt 商品级系统提示词，可为 null
     * @param fixedMaterial    固定资料，可为 null
     * @param goodsDetail      商品详情，可为 null
     * @return 回复结果
     */
    RAGReplyResult chatByRAGWithFixedMaterial(String msg, String goodsId, String contextMessages,
                                              String goodsSystemPrompt, String fixedMaterial, String goodsDetail);

    void putDataToRAG(String content,String goodsId);

    List<RAGDataRespBO> queryRAGDataBygoodsId(String goodsId);

    void deleteRAGDataByDocumentId(String documentId);

    String simpleChat(String message);
}
