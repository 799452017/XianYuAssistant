package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 二维码登录会话
 */
@Data
public class QRLoginSession {
    
    private String sessionId;
    private volatile String status; // waiting, scanned, success, expired, cancelled, verification_required, error
    private String qrCodeUrl;
    private String qrContent;
    private Map<String, String> cookies = new ConcurrentHashMap<>();
    private volatile String unb;
    private long createdTime;
    private long expireTime = 300000; // 5分钟过期（毫秒）
    private Map<String, String> params = new ConcurrentHashMap<>();
    private volatile String verificationUrl;
    
    public QRLoginSession(String sessionId) {
        this.sessionId = sessionId;
        this.status = "waiting";
        this.createdTime = System.currentTimeMillis();
    }
    
    /**
     * 检查是否过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - createdTime > expireTime;
    }
}
