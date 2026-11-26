package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.config.rag.DynamicVectorStoreManager;
import com.feijimiao.xianyuassistant.controller.dto.ChatWithAIReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeleteRAGDataReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.PutNewDataToRAGReqDTO;
import com.feijimiao.xianyuassistant.service.AIService;
import com.feijimiao.xianyuassistant.service.GoodsInfoService;
import com.feijimiao.xianyuassistant.service.bo.RAGDataRespBO;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI对话控制器
 * 始终加载，AI功能未配置时自动降级
 *
 * @author IAMLZY
 * @date 2026/4/12 00:16
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AIChatController {
    @Autowired
    private AIService aiService;

    @Autowired
    private DynamicAIChatClientManager dynamicAIChatClientManager;

    @Autowired
    private DynamicVectorStoreManager dynamicVectorStoreManager;
    
    @Autowired
    private GoodsInfoService goodsInfoService;
    
    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    /**
     * AI对话（流式返回）
     * 未配置API Key时返回降级提示
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithAi(@RequestBody ChatWithAIReqDTO chatWithAIReqDTO) {
        return aiService.chatByRAG(chatWithAIReqDTO.getMsg(), chatWithAIReqDTO.getGoodsId());
    }

    /**
     * AI对话测试接口（流式）- 与自动回复流程一致
     * 携带固定资料和商品详情，用于测试提示词与资料效果
     */
    @PostMapping(path = "/chatTest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatTestWithAi(@RequestBody ChatTestReqDTO req) {
        String fixedMaterial = null;
        String goodsDetail = null;
        String systemPrompt = null;
        String contextMessages = buildHistoryContext(req.getHistory());
        
        if (req.getAccountId() != null && req.getGoodsId() != null) {
            XianyuGoodsConfig config = goodsConfigMapper.selectByAccountAndGoodsId(req.getAccountId(), req.getGoodsId());
            if (config != null) {
                fixedMaterial = config.getFixedMaterial();
                systemPrompt = config.getSystemPrompt();
            }
            
            String detailInfo = goodsInfoService.getDetailInfoByGoodsId(req.getGoodsId());
            if (detailInfo != null && !detailInfo.isEmpty()) {
                goodsDetail = detailInfo;
            }
        }
        
        return aiService.chatByRAGWithFixedMaterialStream(req.getMsg(), req.getGoodsId(), contextMessages,
                systemPrompt, fixedMaterial, goodsDetail);
    }

    /**
     * 把测试页传来的历史消息拼成 {@code role: content} 文本（最多 20 条、2000 字）
     *
     * <p>让"AI回答测试"与真实自动回复一致：都能带上多轮对话。</p>
     */
    private String buildHistoryContext(List<HistoryItem> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        int start = Math.max(0, history.size() - 20);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = start; i < history.size(); i++) {
            HistoryItem item = history.get(i);
            if (item == null || item.getContent() == null || item.getContent().trim().isEmpty()) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(item.getRole()) ? "assistant" : "user";
            sb.append(role).append(": ").append(item.getContent().replace("\n", " ").trim()).append("\n");
            count++;
        }
        if (count == 0) {
            return null;
        }
        String text = sb.toString();
        if (text.length() > 2000) {
            text = text.substring(text.length() - 2000);
            int nl = text.indexOf('\n');
            if (nl >= 0) {
                text = text.substring(nl + 1);
            }
        }
        return text.trim();
    }

    /**
     * AI状态检测接口
     * 返回AI服务是否可用、配置状态等信息
     */
    @PostMapping("/status")
    public ResultObject<AIStatusRespDTO> getAIStatus() {
        DynamicAIChatClientManager.AIStatusInfo statusInfo = dynamicAIChatClientManager.getStatusInfo();

        AIStatusRespDTO respDTO = new AIStatusRespDTO();
        respDTO.setEnabled(statusInfo.isEnabled());
        respDTO.setAvailable(statusInfo.isAvailable());
        respDTO.setApiKeyConfigured(statusInfo.isApiKeyConfigured());
        respDTO.setMessage(statusInfo.getMessage());
        respDTO.setBaseUrl(statusInfo.getBaseUrl());
        respDTO.setModel(statusInfo.getModel());

        return ResultObject.success(respDTO);
    }

    @PostMapping("/putNewData")
    public ResultObject<?> putNewData(@RequestBody PutNewDataToRAGReqDTO putNewDataToRAGReqDTO) {
        try {
            aiService.putDataToRAG(putNewDataToRAGReqDTO.getContent(), putNewDataToRAGReqDTO.getGoodsId());
            return ResultObject.success(null);
        } catch (Exception e) {
            return handleRAGException(e, "上传知识库资料");
        }
    }

    @PostMapping("/queryRAGData")
    public ResultObject<List<RAGDataRespBO>> queryRAGData(@RequestBody PutNewDataToRAGReqDTO req) {
        try {
            List<RAGDataRespBO> data = aiService.queryRAGDataBygoodsId(req.getGoodsId());
            return ResultObject.success(data);
        } catch (Exception e) {
            return handleRAGException(e, "查询知识库资料");
        }
    }

    @PostMapping("/deleteRAGData")
    public ResultObject<?> deleteRAGData(@RequestBody DeleteRAGDataReqDTO req) {
        try {
            aiService.deleteRAGDataByDocumentId(req.getDocumentId());
            return ResultObject.success(null);
        } catch (Exception e) {
            return handleRAGException(e, "删除知识库资料");
        }
    }

    /**
     * 统一把 Spring AI / Embedding 调用异常转换为可读提示，避免前端直接收到 500 + HTML 404
     */
    private <T> ResultObject<T> handleRAGException(Exception e, String actionName) {
        String fullMsg = extractFullErrorMessage(e);
        Integer httpStatus = extractHttpStatusCode(e);
        log.warn("[AIChatController] {}异常(httpStatus={}): {}", actionName, httpStatus, fullMsg);

        if (fullMsg.contains("向量库未初始化")) {
            return ResultObject.failed(1001, "请完成AI配置再上传资料");
        }

        boolean is404 = (httpStatus != null && httpStatus == 404)
                || (fullMsg.matches("(?s).*(?<!\\d)404(?!\\d).*")
                    && (fullMsg.contains("Not Found") || fullMsg.contains("404 -") || fullMsg.contains("404 ")));
        if (is404) {
            String baseUrl = dynamicVectorStoreManager.getEffectiveBaseUrl();
            return ResultObject.failed("当前AI服务商（" + baseUrl + "）不支持 Embedding 向量知识库接口(404)。"
                    + "请前往【系统设置 -> AI服务配置 -> Embedding模型配置】单独填写支持向量化的服务（如阿里云百炼、硅基流动），"
                    + "或直接使用上方无需向量库的【固定资料】功能");
        }

        boolean isAuthError = (httpStatus != null && (httpStatus == 401 || httpStatus == 403))
                || fullMsg.contains("Unauthorized") || fullMsg.contains("InvalidApiKey")
                || fullMsg.matches("(?s).*(?<!\\d)(401|403)\\s*-.*");
        if (isAuthError) {
            return ResultObject.failed("Embedding 向量接口鉴权失败，请检查【系统设置 -> AI服务配置】中的 API Key 是否正确");
        }

        // 剥离 HTML 标签并截断过长响应体
        String cleanMsg = fullMsg.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        if (cleanMsg.length() > 160) {
            cleanMsg = cleanMsg.substring(0, 160) + "...";
        }
        return ResultObject.failed(actionName + "失败：" + (cleanMsg.isEmpty() ? "服务异常" : cleanMsg));
    }

    private Integer extractHttpStatusCode(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 6) {
            if (cur instanceof org.springframework.web.client.RestClientResponseException restEx) {
                return restEx.getStatusCode().value();
            }
            if (cur instanceof org.springframework.web.reactive.function.client.WebClientResponseException webEx) {
                return webEx.getStatusCode().value();
            }
            cur = cur.getCause();
            depth++;
        }
        return null;
    }

    private String extractFullErrorMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    @PostMapping("/saveFixedMaterial")
    public ResultObject<?> saveFixedMaterial(@RequestBody FixedMaterialReqDTO req) {
        if (req.getAccountId() == null || req.getGoodsId() == null || req.getGoodsId().isBlank()) {
            return ResultObject.validateFailed("账号ID和商品ID不能为空");
        }
        int updated = goodsConfigMapper.updateFixedMaterial(req.getAccountId(), req.getGoodsId(), req.getFixedMaterial());
        if (updated == 0) {
            XianyuGoodsConfig config = createDefaultGoodsConfig(req.getAccountId(), req.getGoodsId());
            config.setFixedMaterial(req.getFixedMaterial());
            goodsConfigMapper.insert(config);
        }
        return ResultObject.success(null);
    }

    @PostMapping("/saveSystemPrompt")
    public ResultObject<?> saveSystemPrompt(@RequestBody SystemPromptReqDTO req) {
        if (req.getAccountId() == null || req.getGoodsId() == null || req.getGoodsId().isBlank()) {
            return ResultObject.validateFailed("账号ID和商品ID不能为空");
        }
        int updated = goodsConfigMapper.updateSystemPrompt(req.getAccountId(), req.getGoodsId(), req.getSystemPrompt());
        if (updated == 0) {
            XianyuGoodsConfig config = createDefaultGoodsConfig(req.getAccountId(), req.getGoodsId());
            config.setSystemPrompt(req.getSystemPrompt());
            goodsConfigMapper.insert(config);
        }
        return ResultObject.success(null);
    }

    @PostMapping("/getSystemPrompt")
    public ResultObject<SystemPromptRespDTO> getSystemPrompt(@RequestBody SystemPromptReqDTO req) {
        XianyuGoodsConfig config = goodsConfigMapper.selectByAccountAndGoodsId(req.getAccountId(), req.getGoodsId());
        SystemPromptRespDTO resp = new SystemPromptRespDTO();
        if (config != null) {
            resp.setSystemPrompt(config.getSystemPrompt());
        }
        return ResultObject.success(resp);
    }

    @PostMapping("/getFixedMaterial")
    public ResultObject<FixedMaterialRespDTO> getFixedMaterial(@RequestBody FixedMaterialReqDTO req) {
        XianyuGoodsConfig config = goodsConfigMapper.selectByAccountAndGoodsId(req.getAccountId(), req.getGoodsId());
        FixedMaterialRespDTO resp = new FixedMaterialRespDTO();
        if (config != null) {
            resp.setFixedMaterial(config.getFixedMaterial());
        }
        return ResultObject.success(resp);
    }

    @PostMapping("/syncDetailToFixedMaterial")
    public ResultObject<?> syncDetailToFixedMaterial(@RequestBody FixedMaterialReqDTO req) {
        if (req.getAccountId() == null || req.getGoodsId() == null || req.getGoodsId().isBlank()) {
            return ResultObject.validateFailed("账号ID和商品ID不能为空");
        }
        String detailInfo = goodsInfoService.getDetailInfoByGoodsId(req.getGoodsId());
        if (detailInfo != null && !detailInfo.isEmpty()) {
            int updated = goodsConfigMapper.updateFixedMaterial(req.getAccountId(), req.getGoodsId(), detailInfo);
            if (updated == 0) {
                XianyuGoodsConfig config = createDefaultGoodsConfig(req.getAccountId(), req.getGoodsId());
                config.setFixedMaterial(detailInfo);
                goodsConfigMapper.insert(config);
            }
            return ResultObject.success(null);
        } else {
            return ResultObject.failed("商品详情为空，无法同步");
        }
    }

    private XianyuGoodsConfig createDefaultGoodsConfig(Long accountId, String goodsId) {
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAccountId(accountId);
        config.setXyGoodsId(goodsId);
        config.setXianyuAutoDeliveryOn(0);
        config.setXianyuAutoReplyOn(0);
        config.setXianyuAutoReplyContextOn(1);
        config.setXianyuKeywordReplyOn(0);
        config.setHumanInterventionOn(0);
        config.setHumanInterventionMinutes(10);
        return config;
    }

    public static class FixedMaterialReqDTO {
        private Long accountId;
        private String goodsId;
        private String fixedMaterial;

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public String getGoodsId() { return goodsId; }
        public void setGoodsId(String goodsId) { this.goodsId = goodsId; }
        public String getFixedMaterial() { return fixedMaterial; }
        public void setFixedMaterial(String fixedMaterial) { this.fixedMaterial = fixedMaterial; }
    }

    public static class SystemPromptReqDTO {
        private Long accountId;
        private String goodsId;
        private String systemPrompt;

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public String getGoodsId() { return goodsId; }
        public void setGoodsId(String goodsId) { this.goodsId = goodsId; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    }

    public static class SystemPromptRespDTO {
        private String systemPrompt;

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    }

    public static class FixedMaterialRespDTO {
        private String fixedMaterial;

        public String getFixedMaterial() { return fixedMaterial; }
        public void setFixedMaterial(String fixedMaterial) { this.fixedMaterial = fixedMaterial; }
    }

    public static class HistoryItem {
        private String role;
        private String content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class ChatTestReqDTO {
        private Long accountId;
        private String goodsId;
        private String msg;
        /** 测试页里的历史对话（role=user/assistant） */
        private List<HistoryItem> history;

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public String getGoodsId() { return goodsId; }
        public void setGoodsId(String goodsId) { this.goodsId = goodsId; }
        public String getMsg() { return msg; }
        public void setMsg(String msg) { this.msg = msg; }
        public List<HistoryItem> getHistory() { return history; }
        public void setHistory(List<HistoryItem> history) { this.history = history; }
    }

    /**
     * AI状态响应DTO
     */
    public static class AIStatusRespDTO {
        private boolean enabled;
        private boolean available;
        private boolean apiKeyConfigured;
        private String message;
        private String baseUrl;
        private String model;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public boolean isApiKeyConfigured() { return apiKeyConfigured; }
        public void setApiKeyConfigured(boolean apiKeyConfigured) { this.apiKeyConfigured = apiKeyConfigured; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
