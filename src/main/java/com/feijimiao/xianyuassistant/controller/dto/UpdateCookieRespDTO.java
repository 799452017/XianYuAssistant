package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

/**
 * 更新Cookie响应DTO
 */
@Data
public class UpdateCookieRespDTO {
    
    /**
     * 提示信息
     */
    private String message;

    /**
     * WebSocket是否已自动重连成功
     */
    private Boolean wsConnected;

    /**
     * 是否触发滑块验证
     */
    private Boolean needCaptcha;

    /**
     * 滑块验证URL
     */
    private String captchaUrl;
}
