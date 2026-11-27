package com.feijimiao.xianyuassistant.utils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP客户端工具类
 */
@Slf4j
public class HttpClientUtils {

    private static final RestTemplate restTemplate = new RestTemplate();

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * 发送POST请求
     *
     * @param url     请求URL
     * @param headers 请求头
     * @param body    请求体
     * @return 响应字符串
     */
    public static String post(String url, Map<String, String> headers, Map<String, String> body) {
        try {
            log.info("发送POST请求: {}", url);
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            if (headers != null) {
                headers.forEach(httpHeaders::add);
                log.debug("请求头数量: {}", headers.size());
            }

            // 构建表单数据（需要进行 URL 编码）
            StringBuilder formData = new StringBuilder();
            if (body != null) {
                body.forEach((key, value) -> {
                    try {
                        if (formData.length() > 0) {
                            formData.append("&");
                        }
                        formData.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        log.error("URL编码失败: key={}, value={}", key, value, e);
                    }
                });
                log.debug("表单数据长度: {}", formData.length());
            }

            HttpEntity<String> entity = new HttpEntity<>(formData.toString(), httpHeaders);
            log.info("开始执行HTTP请求...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("HTTP请求完成，状态码: {}", response.getStatusCode());
            return response.getBody();
        } catch (Exception e) {
            log.error("HTTP POST请求失败: url={}", url, e);
            return null;
        }
    }

    /**
     * 发送POST请求并返回包含响应头的结果
     * 用于提取响应中的Set-Cookie等信息
     *
     * @param url     请求URL
     * @param headers 请求头
     * @param body    请求体
     * @return HttpResponseResult 包含响应体和响应头
     */
    public static HttpResponseResult postWithHeaders(String url, Map<String, String> headers, Map<String, String> body) {
        try {
            log.info("发送POST请求(带响应头): {}", url);

            // 构建表单数据
            StringBuilder formData = new StringBuilder();
            if (body != null) {
                body.forEach((key, value) -> {
                    try {
                        if (formData.length() > 0) {
                            formData.append("&");
                        }
                        formData.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        log.error("URL编码失败: key={}, value={}", key, value, e);
                    }
                });
            }

            RequestBody requestBody = RequestBody.create(
                    formData.toString(),
                    okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8")
            );
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(requestBody);

            if (headers != null) {
                headers.forEach(requestBuilder::header);
            }

            log.info("开始执行HTTP请求...");
            try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";
                log.info("HTTP请求完成，状态码: {}", statusCode);

                HttpHeaders responseHeaders = new HttpHeaders();
                Headers okHeaders = response.headers();
                for (int i = 0; i < okHeaders.size(); i++) {
                    responseHeaders.add(okHeaders.name(i), okHeaders.value(i));
                }

                HttpResponseResult result = new HttpResponseResult();
                result.setBody(responseBody);
                result.setStatusCode(statusCode);
                result.setHeaders(responseHeaders);
                return result;
            }
        } catch (Exception e) {
            log.error("HTTP POST请求失败(带响应头): url={}", url, e);
            return null;
        }
    }

    /**
     * HTTP响应结果（包含响应头）
     */
    public static class HttpResponseResult {
        private String body;
        private int statusCode;
        private HttpHeaders headers;

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }

        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

        public HttpHeaders getHeaders() { return headers; }
        public void setHeaders(HttpHeaders headers) { this.headers = headers; }

        /**
         * 获取指定响应头的值列表
         */
        public List<String> getHeaderValues(String headerName) {
            if (headers == null) return List.of();
            return headers.get(headerName) != null ? headers.get(headerName) : List.of();
        }
    }

    /**
     * 发送GET请求
     *
     * @param url     请求URL
     * @param headers 请求头
     * @return 响应字符串
     */
    public static String get(String url, Map<String, String> headers) {
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            if (headers != null) {
                headers.forEach(httpHeaders::add);
            }

            HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("HTTP GET请求失败: url={}", url, e);
            return null;
        }
    }
}
