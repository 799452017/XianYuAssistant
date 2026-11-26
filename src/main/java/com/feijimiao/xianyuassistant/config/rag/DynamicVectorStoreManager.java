package com.feijimiao.xianyuassistant.config.rag;

import com.feijimiao.xianyuassistant.service.SysSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 动态 VectorStore 管理器
 * 支持延迟初始化和热更新
 * 首次使用时才创建 VectorStore，配置变更后可重建
 *
 * @author IAMLZY
 * @date 2026/4/24
 */
@Slf4j
@Component
public class DynamicVectorStoreManager {

    // AI 对话配置（默认共用）
    private static final String AI_API_KEY_SETTING = "ai_api_key";
    private static final String AI_BASE_URL_SETTING = "ai_base_url";

    // Embedding 模型独立配置（可选，默认使用AI对话配置）
    private static final String EMBEDDING_API_KEY_SETTING = "ai_embedding_api_key";
    private static final String EMBEDDING_BASE_URL_SETTING = "ai_embedding_base_url";
    private static final String EMBEDDING_MODEL_SETTING = "ai_embedding_model";

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";

    @Value("${ai.vectorstore.simple.file-path:dbdata/vectorstore.json}")
    private String vectorStoreFilePath;

    @Autowired
    @Lazy
    private SysSettingService sysSettingService;

    /** 当前 VectorStore 实例 */
    private volatile VectorStore vectorStore;

    /** 当前缓存的 API Key，用于判断是否需要重建 */
    private volatile String cachedApiKey;

    /** 当前缓存的 Base URL */
    private volatile String cachedBaseUrl;

    /** 当前缓存的模型 */
    private volatile String cachedModel;

    /** 读写锁，保护 VectorStore 的线程安全 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 获取 VectorStore 实例
     * 如果配置未变更，返回缓存的实例
     * 如果配置变更，自动重建
     *
     * @return VectorStore 实例，未配置 API Key 时返回 null
     */
    public VectorStore getVectorStore() {
        // 读取当前配置
        String embeddingApiKey = getSettingValue(EMBEDDING_API_KEY_SETTING);
        String embeddingBaseUrl = getSettingValue(EMBEDDING_BASE_URL_SETTING);
        String embeddingModel = getSettingValue(EMBEDDING_MODEL_SETTING);

        // 如果 Embedding 专用 API Key 未配置，使用 AI 对话的 API Key
        if (embeddingApiKey == null || embeddingApiKey.trim().isEmpty()) {
            embeddingApiKey = getSettingValue(AI_API_KEY_SETTING);
        }

        // 如果 Embedding 专用 Base URL 未配置，使用 AI 对话的 Base URL
        if (embeddingBaseUrl == null || embeddingBaseUrl.trim().isEmpty()) {
            embeddingBaseUrl = getSettingValue(AI_BASE_URL_SETTING);
        }

        // API Key 未配置
        if (embeddingApiKey == null || embeddingApiKey.trim().isEmpty()) {
            if (vectorStore == null) {
                log.debug("[VectorStore] API Key 未配置，VectorStore 不可用");
            }
            return null;
        }

        String effectiveBaseUrl = DynamicAIChatClientManager.normalizeBaseUrl(embeddingBaseUrl);
        String effectiveModel = (embeddingModel != null && !embeddingModel.trim().isEmpty())
                ? embeddingModel.trim() : DEFAULT_EMBEDDING_MODEL;

        // 检查配置是否变化，需要重建
        boolean needRebuild = vectorStore == null
                || !embeddingApiKey.equals(cachedApiKey)
                || !safeEquals(effectiveBaseUrl, cachedBaseUrl)
                || !safeEquals(effectiveModel, cachedModel);

        if (needRebuild) {
            lock.writeLock().lock();
            try {
                // 双重检查
                boolean stillNeedRebuild = vectorStore == null
                        || !embeddingApiKey.equals(cachedApiKey)
                        || !safeEquals(effectiveBaseUrl, cachedBaseUrl)
                        || !safeEquals(effectiveModel, cachedModel);

                if (stillNeedRebuild) {
                    log.info("[VectorStore] 检测到配置变化，重建 VectorStore: baseUrl={}, model={}",
                            effectiveBaseUrl, effectiveModel);

                    vectorStore = buildVectorStore(embeddingApiKey, effectiveBaseUrl, effectiveModel);
                    cachedApiKey = embeddingApiKey;
                    cachedBaseUrl = effectiveBaseUrl;
                    cachedModel = effectiveModel;

                    if (vectorStore != null) {
                        log.info("[VectorStore] VectorStore 重建成功");
                    }
                }
            } catch (Exception e) {
                log.error("[VectorStore] VectorStore 重建失败", e);
                vectorStore = null;
                cachedApiKey = null;
            } finally {
                lock.writeLock().unlock();
            }
        }

        lock.readLock().lock();
        try {
            return vectorStore;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取当前实际生效的 Embedding Base URL（用于错误诊断提示）
     */
    public String getEffectiveBaseUrl() {
        String embeddingBaseUrl = getSettingValue(EMBEDDING_BASE_URL_SETTING);
        if (embeddingBaseUrl == null || embeddingBaseUrl.trim().isEmpty()) {
            embeddingBaseUrl = getSettingValue(AI_BASE_URL_SETTING);
        }
        return DynamicAIChatClientManager.normalizeBaseUrl(embeddingBaseUrl);
    }

    /**
     * 强制重建 VectorStore（配置变更时调用）
     */
    public void forceRebuild() {
        log.info("[VectorStore] 收到强制重建信号，清除缓存");
        lock.writeLock().lock();
        try {
            cachedApiKey = null;
            cachedBaseUrl = null;
            cachedModel = null;
            vectorStore = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 保存向量数据到文件（兼容无参调用）
     */
    public void saveToFile() {
        saveToFile(this.vectorStore);
    }

    /**
     * 线程安全地将指定 VectorStore 实例持久化到文件（加写锁并采用临时文件原子替换，防止并发写坏或热重建丢数据）
     */
    public void saveToFile(VectorStore targetStore) {
        if (!(targetStore instanceof SimpleVectorStore simpleStore)) {
            return;
        }
        lock.writeLock().lock();
        try {
            Path targetPath = Paths.get(vectorStoreFilePath);
            if (targetPath.getParent() != null && !Files.exists(targetPath.getParent())) {
                Files.createDirectories(targetPath.getParent());
            }
            Path tempPath = targetPath.resolveSibling(targetPath.getFileName().toString() + ".tmp");
            simpleStore.save(tempPath.toFile());
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[VectorStore] 向量数据已安全保存到: {}", vectorStoreFilePath);
        } catch (Exception e) {
            log.warn("[VectorStore] 保存向量数据失败: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 构建 VectorStore 实例
     */
    private VectorStore buildVectorStore(String apiKey, String baseUrl, String model) {
        try {
            // 创建 OpenAiApi 实例
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .apiKey(new SimpleApiKey(apiKey.trim()))
                    .baseUrl(baseUrl)
                    .build();

            // 创建 EmbeddingOptions（指定模型）
            OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                    .model(model)
                    .build();

            // 创建 EmbeddingModel
            OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(
                    openAiApi, MetadataMode.EMBED, embeddingOptions);

            // 创建 SimpleVectorStore
            SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();

            // 尝试从文件加载已有数据
            Path path = Paths.get(vectorStoreFilePath);
            if (Files.exists(path)) {
                try {
                    simpleVectorStore.load(new File(vectorStoreFilePath));
                    log.info("[VectorStore] 成功从文件加载向量数据: {}", vectorStoreFilePath);
                } catch (Exception e) {
                    log.warn("[VectorStore] 加载向量数据失败，将创建新的向量存储: {}", e.getMessage());
                }
            } else {
                // 确保父目录存在
                try {
                    Files.createDirectories(path.getParent());
                    log.info("[VectorStore] 创建向量存储目录: {}", path.getParent());
                } catch (Exception e) {
                    log.warn("[VectorStore] 创建目录失败: {}", e.getMessage());
                }
            }

            return simpleVectorStore;

        } catch (Exception e) {
            log.error("[VectorStore] 创建 VectorStore 失败", e);
            return null;
        }
    }

    private String getSettingValue(String key) {
        try {
            return sysSettingService.getSettingValue(key);
        } catch (Exception e) {
            log.warn("[VectorStore] 读取配置失败: key={}", key, e);
            return null;
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
