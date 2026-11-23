package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

/**
 * 登录请求DTO
 * @author IAMLZY
 * @date 2026/4/22
 */
@Data
public class LoginReqDTO {
    private String username;
    private String password;

    /** 图形验证码票据（GET /api/login/captcha 返回的 id）。 */
    private String captchaId;

    /** 用户输入的验证码，大小写不敏感。 */
    private String captchaCode;
}
