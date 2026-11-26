package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.controller.dto.QRLoginResponse;
import com.feijimiao.xianyuassistant.controller.dto.QRLoginSession;
import com.feijimiao.xianyuassistant.controller.dto.QRStatusResponse;
import com.feijimiao.xianyuassistant.service.QRLoginService;
import com.feijimiao.xianyuassistant.utils.CookieUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 二维码登录服务实现
 */
@Service
@Slf4j
public class QRLoginServiceImpl implements QRLoginService {
    
    private final Map<String, QRLoginSession> sessions = new ConcurrentHashMap<>();
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.AccountService accountService;
    
    private static final String HOST = "https://passport.goofish.com";
    private static final String API_MINI_LOGIN = HOST + "/mini_login.htm";
    private static final String API_GENERATE_QR = HOST + "/newlogin/qrcode/generate.do";
    private static final String API_SCAN_STATUS = HOST + "/newlogin/qrcode/query.do";
    private static final String API_H5_TK = "https://h5api.m.goofish.com/h5/mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get/1.0/";
    
    public QRLoginServiceImpl() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }
    
    private Headers generateHeaders() {
        // 注意：不要手动设置Accept-Encoding，让OkHttp自动处理gzip
        return new Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Upgrade-Insecure-Requests", "1")
                .build();
    }
    
    private Headers generateApiHeaders() {
        // 注意：不要手动设置Accept-Encoding，让OkHttp自动处理gzip
        return new Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Referer", "https://passport.goofish.com/")
                .add("Origin", "https://passport.goofish.com")
                .build();
    }

    
    /**
     * 获取_m_h5_tk token
     * 这个token是闲鱼API调用必需的，用于签名验证
     */
    private void getMh5tk(QRLoginSession session) throws IOException {
        log.info("开始获取_m_h5_tk token...");
        
        Map<String, Object> data = new HashMap<>();
        data.put("bizScene", "home");
        String dataStr = gson.toJson(data);
        long t = System.currentTimeMillis();
        String appKey = "34839810";
        
        // 第一次请求获取cookie
        Request request = new Request.Builder()
                .url(API_H5_TK)
                .headers(generateApiHeaders())
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                // 提取cookie（注意：Cookie名称是 _m_h5_tk，带下划线前缀）
                List<String> cookieHeaders = response.headers("Set-Cookie");
                for (String cookie : cookieHeaders) {
                    String[] parts = cookie.split(";")[0].split("=", 2);
                    if (parts.length == 2) {
                        session.getCookies().put(parts[0], parts[1]);
                        log.debug("提取到Cookie: {} = {}", parts[0], parts[1].substring(0, Math.min(20, parts[1].length())));
                    }
                }
                
                // 获取 _m_h5_tk（注意下划线前缀）
                String mh5tk = session.getCookies().get("_m_h5_tk");
                String token = "";
                if (mh5tk != null && mh5tk.contains("_")) {
                    token = mh5tk.split("_")[0];
                    log.info("提取到_m_h5_tk token: {}", token.substring(0, Math.min(10, token.length())));
                } else {
                    log.warn("未找到_m_h5_tk，当前cookies: {}", session.getCookies().keySet());
                }
                
                // 生成签名
                String signInput = token + "&" + t + "&" + appKey + "&" + dataStr;
                String sign = md5(signInput);
                
                // 构造请求参数
                HttpUrl url = HttpUrl.parse(API_H5_TK).newBuilder()
                        .addQueryParameter("jsv", "2.7.2")
                        .addQueryParameter("appKey", appKey)
                        .addQueryParameter("t", String.valueOf(t))
                        .addQueryParameter("sign", sign)
                        .addQueryParameter("v", "1.0")
                        .addQueryParameter("type", "originaljson")
                        .addQueryParameter("dataType", "json")
                        .addQueryParameter("timeout", "20000")
                        .addQueryParameter("api", "mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get")
                        .addQueryParameter("data", dataStr)
                        .build();
                
                // 第二次请求，刷新token
                Request request2 = new Request.Builder()
                        .url(url)
                        .headers(generateApiHeaders())
                        .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                        .post(RequestBody.create(new byte[0]))
                        .build();
                
                try (Response response2 = httpClient.newCall(request2).execute()) {
                    if (response2.isSuccessful()) {
                        // 第二次请求可能会更新_m_h5_tk
                        List<String> cookieHeaders2 = response2.headers("Set-Cookie");
                        for (String cookie : cookieHeaders2) {
                            String[] parts = cookie.split(";")[0].split("=", 2);
                            if (parts.length == 2) {
                                session.getCookies().put(parts[0], parts[1]);
                            }
                        }
                        log.info("_m_h5_tk获取成功: sessionId={}, cookies包含: {}", 
                                session.getSessionId(), session.getCookies().keySet());
                    } else {
                        log.warn("第二次请求失败，状态码: {}", response2.code());
                    }
                }
            } else {
                log.error("获取_m_h5_tk失败，状态码: {}", response.code());
            }
        }
    }
    
    /**
     * 获取登录参数
     */
    private Map<String, String> getLoginParams(QRLoginSession session) throws IOException {
        HttpUrl url = HttpUrl.parse(API_MINI_LOGIN).newBuilder()
                .addQueryParameter("lang", "zh_cn")
                .addQueryParameter("appName", "xianyu")
                .addQueryParameter("appEntrance", "web")
                .addQueryParameter("styleType", "vertical")
                .addQueryParameter("bizParams", "")
                .addQueryParameter("notLoadSsoView", "false")
                .addQueryParameter("notKeepLogin", "false")
                .addQueryParameter("isMobile", "false")
                .addQueryParameter("qrCodeFirst", "false")
                .addQueryParameter("stie", "77")
                .addQueryParameter("rnd", String.valueOf(Math.random()))
                .build();
        
        Request request = new Request.Builder()
                .url(url)
                .headers(generateHeaders())
                .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String html = response.body().string();
                log.debug("获取登录页面HTML长度: {}", html.length());
                
                // 正则匹配需要的json数据
                Pattern pattern = Pattern.compile("window\\.viewData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(html);
                
                if (matcher.find()) {
                    String jsonString = matcher.group(1);
                    log.debug("提取到的viewData: {}", jsonString.substring(0, Math.min(200, jsonString.length())));
                    
                    JsonObject viewData = gson.fromJson(jsonString, JsonObject.class);
                    JsonObject loginFormData = viewData.getAsJsonObject("loginFormData");
                    
                    if (loginFormData != null) {
                        Map<String, String> params = new HashMap<>();
                        loginFormData.entrySet().forEach(entry -> {
                            if (entry.getValue().isJsonPrimitive()) {
                                params.put(entry.getKey(), entry.getValue().getAsString());
                            } else {
                                params.put(entry.getKey(), entry.getValue().toString());
                            }
                        });
                        params.put("umidTag", "SERVER");
                        session.getParams().putAll(params);
                        log.info("获取登录参数成功: {}, 参数数量: {}", session.getSessionId(), params.size());
                        return params;
                    } else {
                        log.error("viewData中没有loginFormData字段，viewData keys: {}", viewData.keySet());
                    }
                } else {
                    log.error("未匹配到window.viewData，尝试查找其他模式");
                    // 尝试其他可能的模式
                    Pattern pattern2 = Pattern.compile("var\\s+viewData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
                    Matcher matcher2 = pattern2.matcher(html);
                    if (matcher2.find()) {
                        String jsonString = matcher2.group(1);
                        log.debug("使用备用模式提取到viewData");
                        JsonObject viewData = gson.fromJson(jsonString, JsonObject.class);
                        JsonObject loginFormData = viewData.getAsJsonObject("loginFormData");
                        if (loginFormData != null) {
                            Map<String, String> params = new HashMap<>();
                            loginFormData.entrySet().forEach(entry -> {
                                if (entry.getValue().isJsonPrimitive()) {
                                    params.put(entry.getKey(), entry.getValue().getAsString());
                                } else {
                                    params.put(entry.getKey(), entry.getValue().toString());
                                }
                            });
                            params.put("umidTag", "SERVER");
                            session.getParams().putAll(params);
                            log.info("获取登录参数成功(备用模式): {}", session.getSessionId());
                            return params;
                        }
                    }
                }
                
                // 如果都失败了，保存HTML用于调试
                log.error("无法提取登录参数，HTML内容前1000字符: {}", html.substring(0, Math.min(1000, html.length())));
                
                // 尝试直接查找所有可能的参数
                Map<String, String> params = extractParamsFromHtml(html);
                if (!params.isEmpty()) {
                    params.put("umidTag", "SERVER");
                    session.getParams().putAll(params);
                    log.info("使用备用方法提取到参数: {}", params.keySet());
                    return params;
                }
                
                throw new RuntimeException("未找到loginFormData");
            }
            throw new RuntimeException("获取登录参数失败，HTTP状态码: " + response.code());
        }
    }

    
    @Override
    public QRLoginResponse generateQRCode() {
        try {
            // 创建新会话
            String sessionId = UUID.randomUUID().toString();
            QRLoginSession session = new QRLoginSession(sessionId);
            
            // 1. 获取m_h5_tk
            getMh5tk(session);
            
            // 2. 获取登录参数
            Map<String, String> loginParams = getLoginParams(session);
            
            // 3. 生成二维码
            HttpUrl.Builder urlBuilder = HttpUrl.parse(API_GENERATE_QR).newBuilder();
            loginParams.forEach(urlBuilder::addQueryParameter);
            
            Request request = new Request.Builder()
                    .url(urlBuilder.build())
                    .headers(generateApiHeaders())
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    log.debug("获取二维码接口原始响应: {}", responseBody);
                    
                    JsonObject results = gson.fromJson(responseBody, JsonObject.class);
                    JsonObject content = results.getAsJsonObject("content");
                    
                    if (content != null && content.get("success").getAsBoolean()) {
                        JsonObject data = content.getAsJsonObject("data");
                        
                        // 更新会话参数
                        session.getParams().put("t", data.get("t").getAsString());
                        session.getParams().put("ck", data.get("ck").getAsString());
                        
                        // 获取二维码内容
                        String qrContent = data.get("codeContent").getAsString();
                        session.setQrContent(qrContent);
                        
                        // 生成二维码图片（base64格式）
                        String qrDataUrl = generateQRCodeImage(qrContent);
                        session.setQrCodeUrl(qrDataUrl);
                        session.setStatus("waiting");
                        
                        // 保存会话
                        sessions.put(sessionId, session);
                        
                        // 启动状态监控
                        new Thread(() -> monitorQRStatus(sessionId)).start();
                        
                        log.info("二维码生成成功: {}", sessionId);
                        return new QRLoginResponse(true, sessionId, qrDataUrl, null);
                    } else {
                        return new QRLoginResponse(false, "获取登录二维码失败");
                    }
                }
            }
            
            return new QRLoginResponse(false, "生成二维码失败");
            
        } catch (Exception e) {
            log.error("二维码生成过程中发生异常", e);
            return new QRLoginResponse(false, "生成二维码失败: " + e.getMessage());
        }
    }
    
    /**
     * 从HTML中提取参数（备用方法）
     */
    private Map<String, String> extractParamsFromHtml(String html) {
        Map<String, String> params = new HashMap<>();
        
        // 尝试提取常见的参数
        String[] paramNames = {"appName", "appEntrance", "hsiz", "rnd", "bizParams", 
                               "isMobile", "lang", "returnUrl", "fromSite", "umidToken"};
        
        for (String paramName : paramNames) {
            Pattern pattern = Pattern.compile("\"" + paramName + "\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                params.put(paramName, matcher.group(1));
                log.debug("提取到参数 {}: {}", paramName, matcher.group(1));
            }
        }
        
        return params;
    }
    
    /**
     * 生成二维码图片（Base64格式）
     */
    private String generateQRCodeImage(String content) throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);
        
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, 300, 300, hints);
        BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(qrImage, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        
        return "data:image/png;base64," + base64;
    }

    
    /**
     * 监控二维码状态
     */
    private void monitorQRStatus(String sessionId) {
        try {
            QRLoginSession session = sessions.get(sessionId);
            if (session == null) {
                return;
            }
            
            log.info("开始监控二维码状态: {}", sessionId);
            
            long maxWaitTime = 300000; // 5分钟
            long startTime = System.currentTimeMillis();
            
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                try {
                    // 检查会话是否还存在
                    if (!sessions.containsKey(sessionId)) {
                        break;
                    }
                    
                    // 轮询二维码状态
                    String qrCodeStatus = pollQRCodeStatus(session);
                    
                    if ("CONFIRMED".equals(qrCodeStatus)) {
                        // 登录确认
                        log.info("扫码登录成功: {}, UNB: {}", sessionId, session.getUnb());
                        break;
                    } else if ("NEW".equals(qrCodeStatus)) {
                        // 二维码未被扫描，继续轮询
                    } else if ("EXPIRED".equals(qrCodeStatus)) {
                        // 二维码已过期
                        session.setStatus("expired");
                        log.info("二维码已过期: {}", sessionId);
                        break;
                    } else if ("SCANED".equals(qrCodeStatus)) {
                        // 二维码已被扫描，等待确认
                        if ("waiting".equals(session.getStatus())) {
                            session.setStatus("scanned");
                            log.info("二维码已扫描，等待确认: {}", sessionId);
                        }
                    } else {
                        // 用户取消确认
                        session.setStatus("cancelled");
                        log.info("用户取消登录: {}", sessionId);
                        break;
                    }
                    
                    Thread.sleep(800); // 每0.8秒检查一次
                    
                } catch (Exception e) {
                    log.error("监控二维码状态异常", e);
                    Thread.sleep(2000);
                }
            }
            
            // 超时处理
            if (session != null && !Arrays.asList("success", "expired", "cancelled", "verification_required", "error").contains(session.getStatus())) {
                session.setStatus("expired");
                log.info("二维码监控超时，标记为过期: {}", sessionId);
            }
            
        } catch (Exception e) {
            log.error("监控二维码状态失败", e);
            QRLoginSession session = sessions.get(sessionId);
            if (session != null && !Arrays.asList("success", "error").contains(session.getStatus())) {
                session.setStatus("expired");
            }
        }
    }
    
    /**
     * 轮询二维码状态
     */
    private String pollQRCodeStatus(QRLoginSession session) throws IOException {
        FormBody.Builder formBuilder = new FormBody.Builder();
        session.getParams().forEach(formBuilder::add);
        
        Request request = new Request.Builder()
                .url(API_SCAN_STATUS)
                .headers(generateApiHeaders())
                .header("Cookie", CookieUtils.formatCookies(session.getCookies()))
                .post(formBuilder.build())
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JsonObject results = gson.fromJson(responseBody, JsonObject.class);
                JsonObject content = results.getAsJsonObject("content");
                
                if (content != null) {
                    JsonObject data = content.getAsJsonObject("data");
                    if (data != null) {
                        String qrCodeStatus = data.get("qrCodeStatus").getAsString();
                        
                        if ("CONFIRMED".equals(qrCodeStatus)) {
                            // 检查是否需要风控验证
                            if (data.has("iframeRedirect") && data.get("iframeRedirect").getAsBoolean()) {
                                session.setStatus("verification_required");
                                String iframeUrl = data.get("iframeRedirectUrl").getAsString();
                                session.setVerificationUrl(iframeUrl);
                                log.warn("⚠️ 账号被风控，需要手机验证");
                                log.warn("   - 会话ID: {}", session.getSessionId());
                                log.warn("   - 验证URL: {}", iframeUrl);
                            } else {
                                // 登录确认成功：必须先完整提取Cookie、同步SSO并刷新登录态_m_h5_tk，最后再标记 status=success
                                // 防止前端轮询在Cookie未提取完之前提前拉取到残缺Cookie！
                                log.info("🎉 扫码确认成功！开始提取并完善登录态Cookie...");

                                List<String> cookieHeaders = response.headers("Set-Cookie");
                                for (String cookie : cookieHeaders) {
                                    String[] parts = cookie.split(";")[0].split("=", 2);
                                    if (parts.length == 2) {
                                        String ckName = parts[0].trim();
                                        String ckVal = parts[1].trim();
                                        if (!ckVal.isEmpty()) {
                                            session.getCookies().put(ckName, ckVal);
                                            if ("unb".equals(ckName)) {
                                                session.setUnb(ckVal);
                                                log.info("✅ 获取到UNB: {}", ckVal);
                                            }
                                        }
                                    }
                                }

                                // 处理返回数据中的 asyncUrls 跨域SSO种Cookie（对齐淘宝/闲鱼mini_login协议）
                                if (data.has("asyncUrls") && data.get("asyncUrls").isJsonArray()) {
                                    com.feijimiao.xianyuassistant.utils.SessionCookieJar ssoJar =
                                            new com.feijimiao.xianyuassistant.utils.SessionCookieJar(session.getCookies());
                                    OkHttpClient ssoClient = ssoJar.createHttpClient();
                                    data.getAsJsonArray("asyncUrls").forEach(elem -> {
                                        try {
                                            String ssoUrl = elem.getAsString();
                                            if (ssoUrl != null && ssoUrl.startsWith("http")) {
                                                Request ssoReq = new Request.Builder()
                                                        .url(ssoUrl)
                                                        .headers(generateHeaders())
                                                        .get()
                                                        .build();
                                                try (Response ignored = ssoClient.newCall(ssoReq).execute()) {
                                                }
                                            }
                                        } catch (Exception ssoEx) {
                                            log.debug("执行SSO asyncUrl异常: {}", ssoEx.getMessage());
                                        }
                                    });
                                    session.getCookies().putAll(ssoJar.getCookieMap());
                                    if (session.getUnb() == null && session.getCookies().containsKey("unb")) {
                                        session.setUnb(session.getCookies().get("unb"));
                                    }
                                }

                                // 关键：使用扫码后的新登录态Cookie重新请求 h5api 换取匹配的 _m_h5_tk（替代扫码前的匿名 _m_h5_tk）
                                try {
                                    refreshLoggedInMh5tk(session);
                                } catch (Exception tkEx) {
                                    log.warn("扫码后刷新登录态_m_h5_tk异常: {}", tkEx.getMessage());
                                }

                                if (session.getUnb() == null || session.getUnb().isEmpty()) {
                                    log.error("❌ 扫码响应未包含有效UNB: sessionId={}", session.getSessionId());
                                    session.setStatus("error");
                                } else {
                                    // 不在此处私自写库覆盖已有账号备注或造成串号，统一由前端对应入口（添加账号 / 更新指定账号Cookie）落库
                                    log.info("✅ 扫码会话Cookie准备就绪: sessionId={}, unb={}, 字段数={}, 包含_m_h5_tk={}",
                                            session.getSessionId(), session.getUnb(),
                                            session.getCookies().size(), session.getCookies().containsKey("_m_h5_tk"));
                                    session.setStatus("success");
                                }
                            }
                        }
                        
                        return qrCodeStatus;
                    }
                }
            }
        }
        
        return "NEW";
    }

    
    @Override
    public QRStatusResponse getSessionStatus(String sessionId) {
        QRStatusResponse response = new QRStatusResponse();
        QRLoginSession session = sessions.get(sessionId);
        
        if (session == null) {
            response.setStatus("not_found");
            response.setMessage("会话不存在或已过期");
            return response;
        }
        
        if (session.isExpired() && !"success".equals(session.getStatus())) {
            session.setStatus("expired");
        }
        
        // 转换后端状态为前端期望的状态
        String frontendStatus = convertToFrontendStatus(session.getStatus());
        response.setStatus(frontendStatus);
        response.setSessionId(sessionId);
        
        // 根据状态设置详细的消息
        switch (session.getStatus()) {
            case "waiting":
                response.setMessage("等待扫码...");
                break;
            case "scanned":
                response.setMessage("已扫码，等待确认...");
                break;
            case "success":
                response.setMessage("登录成功！账号已添加");
                // 如果登录成功，返回Cookie信息
                if (!session.getCookies().isEmpty() && session.getUnb() != null) {
                    response.setCookies(CookieUtils.formatCookies(session.getCookies()));
                    response.setUnb(session.getUnb());
                }
                break;
            case "expired":
                response.setMessage("二维码已过期，请重新生成");
                break;
            case "cancelled":
                response.setMessage("用户取消登录");
                break;
            case "verification_required":
                response.setMessage("账号触发安全验证，请完成验证或使用网页版手动更新Cookie");
                if (session.getVerificationUrl() != null) {
                    response.setVerificationUrl(session.getVerificationUrl());
                }
                break;
            case "error":
                response.setMessage("登录凭证提取失败，请重新生成二维码再试");
                break;
            default:
                response.setMessage("未知状态");
                break;
        }
        
        return response;
    }
    
    /**
     * 转换后端状态为前端期望的状态
     * 后端: waiting, scanned, success, expired, cancelled, verification_required, error
     * 前端: pending, scanned, confirmed, expired, cancelled, verification_required, error
     */
    private String convertToFrontendStatus(String backendStatus) {
        switch (backendStatus) {
            case "waiting":
                return "pending";
            case "success":
                return "confirmed";
            case "scanned":
            case "expired":
            case "cancelled":
            case "verification_required":
            case "error":
                return backendStatus;
            default:
                return "pending";
        }
    }

    /**
     * 扫码登录确认后，携带已登录Cookie请求 h5api 刷新与登录态绑定的 _m_h5_tk
     */
    private void refreshLoggedInMh5tk(QRLoginSession session) throws IOException {
        com.feijimiao.xianyuassistant.utils.SessionCookieJar jar =
                new com.feijimiao.xianyuassistant.utils.SessionCookieJar(session.getCookies());
        OkHttpClient client = jar.createHttpClient();
        Map<String, Object> data = new HashMap<>();
        data.put("bizScene", "home");
        String dataStr = gson.toJson(data);
        long t = System.currentTimeMillis();
        String appKey = "34839810";
        String token = jar.getMh5tkToken();
        String sign = md5(token + "&" + t + "&" + appKey + "&" + dataStr);

        HttpUrl url = HttpUrl.parse(API_H5_TK).newBuilder()
                .addQueryParameter("jsv", "2.7.2")
                .addQueryParameter("appKey", appKey)
                .addQueryParameter("t", String.valueOf(t))
                .addQueryParameter("sign", sign)
                .addQueryParameter("v", "1.0")
                .addQueryParameter("type", "originaljson")
                .addQueryParameter("dataType", "json")
                .addQueryParameter("timeout", "20000")
                .addQueryParameter("api", "mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get")
                .addQueryParameter("data", dataStr)
                .build();

        Request req = new Request.Builder()
                .url(url)
                .headers(generateApiHeaders())
                .header("Referer", "https://www.goofish.com/")
                .header("Origin", "https://www.goofish.com")
                .post(RequestBody.create(new byte[0]))
                .build();

        try (Response ignored = client.newCall(req).execute()) {
            session.getCookies().putAll(jar.getCookieMap());
            log.info("扫码后刷新登录态_m_h5_tk完成: 包含_m_h5_tk={}", session.getCookies().containsKey("_m_h5_tk"));
        }
    }
    
    @Override
    public Map<String, String> getSessionCookies(String sessionId) {
        QRLoginSession session = sessions.get(sessionId);
        if (session != null && "success".equals(session.getStatus())) {
            Map<String, String> result = new HashMap<>();
            result.put("cookies", CookieUtils.formatCookies(session.getCookies()));
            result.put("unb", session.getUnb());
            return result;
        }
        return null;
    }
    
    @Override
    public void cleanupExpiredSessions() {
        List<String> expiredSessions = new ArrayList<>();
        sessions.forEach((sessionId, session) -> {
            if (session.isExpired()) {
                expiredSessions.add(sessionId);
            }
        });
        
        expiredSessions.forEach(sessionId -> {
            sessions.remove(sessionId);
            log.info("清理过期会话: {}", sessionId);
        });
    }
    
    /**
     * 保存Cookie到数据库
     */
    private void saveCookieToDatabase(QRLoginSession session) {
        try {
            String unb = session.getUnb();
            if (unb == null || unb.isEmpty()) {
                log.error("❌ UNB为空，无法保存Cookie: sessionId={}", session.getSessionId());
                session.setStatus("error");
                return;
            }
            
            // 检查关键Cookie字段
            Map<String, String> cookies = session.getCookies();
            log.info("📝 准备保存Cookie到数据库，当前Cookie包含字段: {}", cookies.keySet());
            
            // 提取 _m_h5_tk
            String mH5Tk = cookies.get("_m_h5_tk");
            if (mH5Tk == null || mH5Tk.isEmpty()) {
                log.warn("⚠️ Cookie中缺少_m_h5_tk字段！这可能导致后续API调用失败");
            } else {
                log.info("✅ _m_h5_tk已包含: {}", mH5Tk.substring(0, Math.min(20, mH5Tk.length())));
            }
            
            // 格式化Cookie字符串
            String cookieText = CookieUtils.formatCookies(cookies);
            log.info("📦 格式化后的Cookie长度: {}", cookieText.length());
            
            // 使用UNB作为账号备注（可以后续优化为用户自定义）
            String accountNote = "账号_" + unb.substring(0, Math.min(8, unb.length()));
            
            // 保存到数据库（包含 m_h5_tk）
            Long accountId = accountService.saveAccountAndCookie(accountNote, unb, cookieText, mH5Tk);
            
            if (accountId != null && accountId > 0) {
                log.info("✅ 扫码登录成功！Cookie已保存到数据库");
                log.info("   - 会话ID: {}", session.getSessionId());
                log.info("   - 账号ID: {}", accountId);
                log.info("   - UNB: {}", unb);
                log.info("   - Cookie字段数: {}", cookies.size());
                log.info("   - m_h5_tk: {}", mH5Tk != null ? "已保存" : "未提供");
                log.info("   - 账号备注: {}", accountNote);
            } else {
                log.error("❌ 保存Cookie失败：accountId为空");
                session.setStatus("error");
            }
            
        } catch (Exception e) {
            log.error("❌ 保存Cookie到数据库失败: sessionId={}", session.getSessionId(), e);
            session.setStatus("error");
        }
    }
    
    /**
     * MD5加密
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (Exception e) {
            log.error("MD5加密失败", e);
            return "";
        }
    }
}