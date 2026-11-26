package com.feijimiao.xianyuassistant.exception;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常拦截器
 * 捕获所有异常并返回统一格式的错误信息
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusinessException(BusinessException e) {
        Map<String, Object> result = new HashMap<>();
        String msg = e.getMessage() != null ? e.getMessage() : "业务处理失败";
        result.put("code", e.getCode());
        result.put("msg", msg);
        result.put("message", msg);
        return result;
    }
    
    /**
     * 处理验证码异常（使用 1001 业务码，避免 401 触发前端退出系统登录）
     */
    @ExceptionHandler(CaptchaRequiredException.class)
    public Map<String, Object> handleCaptchaRequiredException(CaptchaRequiredException e) {
        Map<String, Object> result = new HashMap<>();
        String msg = e.getMessage() != null ? e.getMessage() : "需要完成滑块验证";
        result.put("code", 1001);
        result.put("msg", msg);
        result.put("message", msg);
        result.put("captchaUrl", e.getCaptchaUrl());
        return result;
    }
    
    /**
     * 处理所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleException(Exception e) {
        Map<String, Object> result = new HashMap<>();
        String msg = e.getMessage() != null ? e.getMessage() : "系统异常";
        result.put("code", 500);
        result.put("msg", msg);
        result.put("message", msg);
        return result;
    }
}
