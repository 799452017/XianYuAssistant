package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.annotation.NoAuth;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.service.CaptchaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 登录图形验证码。放在 /api/login/** 下，沿用 WebMvcConfig 对该前缀的免鉴权与
 * {@link NoAuth} 注解，前端不需要带 token。
 */
@RestController
@RequestMapping("/api/login")
@CrossOrigin(origins = "*")
@NoAuth
public class CaptchaController {

    @Autowired
    private CaptchaService captchaService;

    /** 返回 { id, image }：id 原样带回登录请求，image 是 data URI。 */
    @GetMapping("/captcha")
    public ResultObject<Map<String, String>> captcha() {
        try {
            CaptchaService.Issued issued = captchaService.issue();
            Map<String, String> data = new HashMap<>();
            data.put("id", issued.id());
            data.put("image", issued.image());
            return ResultObject.success(data);
        } catch (Exception e) {
            return ResultObject.failed("生成验证码失败: " + e.getMessage());
        }
    }
}
