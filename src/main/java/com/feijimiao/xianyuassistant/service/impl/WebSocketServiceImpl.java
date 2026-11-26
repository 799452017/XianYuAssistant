package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.config.WebSocketConfig;
import com.feijimiao.xianyuassistant.constants.OperationConstants;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.OperationLogService;

import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import com.feijimiao.xianyuassistant.utils.XianyuSignUtils;
import com.feijimiao.xianyuassistant.websocket.WebSocketInitializer;
import com.feijimiao.xianyuassistant.websocket.WebSocketMessageHandler;
import com.feijimiao.xianyuassistant.websocket.XianyuWebSocketClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket服务实现类
 * 参考Python代码的XianyuAutoAsync类
 * 增强功能：
 * 1. Token自动刷新机制
 * 2. 心跳超时检测
 * 3. 连接重连机制
 */
@Slf4j
@Service
public class WebSocketServiceImpl implements WebSocketService {

    @Autowired
    private AccountService accountService;
    
    @Autowired
    private WebSocketMessageHandler messageHandler;
    
    @Autowired
    private WebSocketTokenService tokenService;
    
    @Autowired
    private WebSocketInitializer initializer;
    
    @Autowired
    private WebSocketConfig config;
    
    @Autowired
    private com.feijimiao.xianyuassistant.utils.AccountDisplayNameUtils displayNameUtils;
    
    @Autowired
    private OperationLogService operationLogService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.CookieRefreshService cookieRefreshService;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.service.EmailNotifyService emailNotifyService;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper xianyuAccountMapper;


    // 存储WebSocket客户端
    private final Map<Long, XianyuWebSocketClient> webSocketClients = new ConcurrentHashMap<>();
    
    // 心跳定时器
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(2);
    
    // 心跳任务
    private final Map<Long, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    
    // Token刷新定时器
    private final ScheduledExecutorService tokenRefreshScheduler = Executors.newScheduledThreadPool(1);
    
    // Token刷新任务
    private final Map<Long, ScheduledFuture<?>> tokenRefreshTasks = new ConcurrentHashMap<>();

    // Token静默刷新失败后的重试任务（受控管理，stop时一并取消）
    private final Map<Long, ScheduledFuture<?>> tokenRefreshRetryTasks = new ConcurrentHashMap<>();

    // 下次Token刷新目标时间（秒级时间戳，10-14小时随机）
    private final Map<Long, Long> nextTokenRefreshDeadlines = new ConcurrentHashMap<>();

    // 账号级连接操作锁，防止手动连接与后台重连并发互踢
    private final Map<Long, Object> connectionLocks = new ConcurrentHashMap<>();
    
    // 心跳响应时间记录
    private final Map<Long, Long> lastHeartbeatResponseTimes = new ConcurrentHashMap<>();
    
    // 心跳发送时间记录（参考Python的last_heartbeat_time）
    private final Map<Long, Long> lastHeartbeatSendTimes = new ConcurrentHashMap<>();
    
    // Token刷新时间记录
    private final Map<Long, Long> lastTokenRefreshTimes = new ConcurrentHashMap<>();
    
    // 连接重启标志
    private final Map<Long, Boolean> connectionRestartFlags = new ConcurrentHashMap<>();
    
    // 重连任务（防止重复重连）
    private final Map<Long, Future<?>> reconnectTasks = new ConcurrentHashMap<>();
    
    // 重连执行器（参考Python的异步重连）
    private final ScheduledExecutorService reconnectExecutor = Executors.newScheduledThreadPool(2);
    
    // 重连次数记录（参考Python的无限重连但有退避）
    private final Map<Long, AtomicInteger> reconnectAttemptCounts = new ConcurrentHashMap<>();

    // 邮件通知防抖记录（避免频繁发送）
    private final Map<Long, Long> lastDisconnectNotifyTimes = new ConcurrentHashMap<>();

    // 重连失败达到此次数后触发邮件通知
    private static final int RECONNECT_NOTIFY_THRESHOLD = 3;

    // 邮件通知最小间隔（10分钟）
    private static final long NOTIFY_INTERVAL_MS = 10 * 60 * 1000;

    /**
     * 闲鱼WebSocket URL
     * 参考Python代码：wss://wss-goofish.dingtalk.com/
     */
    private static final String WEBSOCKET_URL = "wss://wss-goofish.dingtalk.com/";

    private Object getConnectionLock(Long accountId) {
        return connectionLocks.computeIfAbsent(accountId, k -> new Object());
    }

    @Override
    public boolean startWebSocket(Long accountId) {
        synchronized (getConnectionLock(accountId)) {
            return startWebSocketInternal(accountId, false);
        }
    }

    private boolean startWebSocketInternal(Long accountId, boolean retriedAfter401) {
        try {
            log.info("启动WebSocket连接: accountId={}, retriedAfter401={}", accountId, retriedAfter401);

            // 主动启动连接时先取消挂起的退避重连任务，防止重连定时器稍后将新连接踢掉
            Future<?> pendingReconnect = reconnectTasks.remove(accountId);
            if (pendingReconnect != null && !pendingReconnect.isDone()) {
                pendingReconnect.cancel(false);
            }

            // 检查是否已经连接
            if (webSocketClients.containsKey(accountId)) {
                XianyuWebSocketClient existingClient = webSocketClients.get(accountId);
                if (existingClient != null && existingClient.isConnected()) {
                    log.info("WebSocket已连接: accountId={}", accountId);
                    return true;
                } else {
                    // 关闭旧连接
                    stopWebSocketInternal(accountId);
                }
            }

            // 获取Cookie
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("未找到账号Cookie: accountId={}", accountId);
                throw new com.feijimiao.xianyuassistant.exception.CookieNotFoundException("未找到账号Cookie，请先配置Cookie");
            }

            // 解析Cookie
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookieStr);
            
            // 生成设备ID（参考Python的generate_device_id）
            String unb = cookies.get("unb");
            if (unb == null || unb.isEmpty()) {
                log.error("Cookie中缺少unb字段: accountId={}", accountId);
                throw new com.feijimiao.xianyuassistant.exception.CookieExpiredException("Cookie中缺少unb字段，Cookie可能已过期或无效");
            }
            // 使用持久化的设备ID（如果数据库中已有则使用，否则生成新的并保存）
            String deviceId = accountService.getOrGenerateDeviceId(accountId, unb);
            if (deviceId == null || deviceId.isEmpty()) {
                log.error("获取或生成设备ID失败: accountId={}", accountId);
                throw new RuntimeException("无法获取或生成设备ID");
            }
            log.info("使用设备ID: accountId={}, deviceId={}", accountId, deviceId);
            
            // 获取accessToken（参考Python的refresh_token）
            log.info("正在获取accessToken: accountId={}", accountId);
            String accessToken = tokenService.getAccessToken(accountId);
            if (accessToken == null || accessToken.isEmpty()) {
                log.error("获取accessToken失败: accountId={}", accountId);
                log.error("无法继续WebSocket连接，请检查Cookie是否有效");
                throw new com.feijimiao.xianyuassistant.exception.TokenInvalidException("无法获取WebSocket Token，请检查Cookie是否有效");
            }
            log.info("accessToken获取成功: accountId={}, token长度={}", accountId, accessToken.length());
            
            // 调用通用连接方法
            int connectResult = connectWebSocketWithCode(accountId, cookieStr, deviceId, accessToken, unb);
            if (connectResult == 401 && !retriedAfter401) {
                log.warn("【账号{}】旧Token注册返回401，已自动清除旧Token，立即使用最新Cookie重新获取Token并重连...", accountId);
                tokenService.clearToken(accountId);
                return startWebSocketInternal(accountId, true);
            }
            return connectResult == 200;

        } catch (com.feijimiao.xianyuassistant.exception.CaptchaRequiredException e) {
            log.warn("启动WebSocket需要滑块验证: accountId={}, url={}", accountId, e.getCaptchaUrl());
            throw e; // 重新抛出，让Controller处理
        } catch (com.feijimiao.xianyuassistant.exception.CookieNotFoundException
                 | com.feijimiao.xianyuassistant.exception.CookieExpiredException
                 | com.feijimiao.xianyuassistant.exception.TokenInvalidException e) {
            throw e;
        } catch (Exception e) {
            log.error("启动WebSocket失败: accountId={}", accountId, e);
            return false;
        }
    }

    @Override
    public boolean startWebSocketWithToken(Long accountId, String accessToken) {
        synchronized (getConnectionLock(accountId)) {
            return startWebSocketWithTokenInternal(accountId, accessToken);
        }
    }

    private boolean startWebSocketWithTokenInternal(Long accountId, String accessToken) {
        try {
            log.info("========== 使用指定Token启动WebSocket连接 ==========");
            log.info("【账号{}】accountId={}, accessToken长度={}", accountId, accountId, accessToken != null ? accessToken.length() : 0);

            Future<?> pendingReconnect = reconnectTasks.remove(accountId);
            if (pendingReconnect != null && !pendingReconnect.isDone()) {
                pendingReconnect.cancel(false);
            }

            // 调用方已显式传入新 accessToken，若存在旧连接则一律先平滑关闭再切换为新连接
            if (webSocketClients.containsKey(accountId)) {
                log.info("【账号{}】使用新Token重建连接，先平滑关闭旧WebSocket连接", accountId);
                stopWebSocketInternal(accountId);
            }

            // 获取Cookie
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到账号Cookie", accountId);
                throw new com.feijimiao.xianyuassistant.exception.CookieNotFoundException("未找到账号Cookie，请先配置Cookie");
            }

            // 解析Cookie
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookieStr);
            String unb = cookies.get("unb");
            if (unb == null || unb.isEmpty()) {
                log.error("【账号{}】Cookie中缺少unb字段", accountId);
                throw new com.feijimiao.xianyuassistant.exception.CookieExpiredException("Cookie中缺少unb字段，Cookie可能已过期或无效");
            }
            String deviceId = accountService.getOrGenerateDeviceId(accountId, unb);
            if (deviceId == null || deviceId.isEmpty()) {
                throw new RuntimeException("无法获取或生成设备ID");
            }

            int result = connectWebSocketWithCode(accountId, cookieStr, deviceId, accessToken, unb);
            log.info("【账号{}】连接结果code={}", accountId, result);
            return result == 200;

        } catch (Exception e) {
            log.error("【账号{}】使用指定Token启动WebSocket失败", accountId, e);
            return false;
        }
    }

    /**
     * 通用WebSocket连接方法（返回注册确认状态码：200=成功，401=Token失效，-1=连接失败）
     */
    private int connectWebSocketWithCode(Long accountId, String cookieStr, String deviceId, String accessToken, String unb) throws Exception {
        try {
            // 构建WebSocket请求头（参考Python的WEBSOCKET_HEADERS配置）
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", cookieStr);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
            headers.put("Origin", "https://www.goofish.com");
            headers.put("Host", "wss-goofish.dingtalk.com");
            headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            headers.put("Cache-Control", "no-cache");
            headers.put("Pragma", "no-cache");
            headers.put("Connection", "Upgrade");
            headers.put("Upgrade", "websocket");

            URI serverUri = new URI(WEBSOCKET_URL);
            XianyuWebSocketClient client = new XianyuWebSocketClient(serverUri, headers, String.valueOf(accountId), displayNameUtils);
            client.setMyUserId(unb);
            client.setMessageHandler(messageHandler);
            
            final String finalAccessToken = accessToken;
            client.setOnRegistrationSuccess(() -> {
                log.info("【账号{}】注册成功回调被触发，开始保存Token到数据库", accountId);
                tokenService.saveToken(accountId, finalAccessToken);
                log.info("【账号{}】✅ Token已成功保存到数据库", accountId);
            });
            
            // 运行期收到401时异步重连（投递到reconnectExecutor，避免在client自身messageExecutor线程内自锁关闭）
            client.setOnTokenExpired(() -> reconnectExecutor.execute(() -> {
                log.warn("【账号{}】运行期Token失效(401)，触发异步重连流程...", accountId);
                try {
                    connectionRestartFlags.put(accountId, true);
                    stopWebSocket(accountId);
                    tokenService.clearToken(accountId);
                    boolean success = startWebSocket(accountId);
                    if (success) {
                        log.info("【账号{}】✅ Token失效后自动重连成功", accountId);
                    } else {
                        log.error("【账号{}】❌ Token失效后自动重连失败，交由退避重连机制继续尝试", accountId);
                        scheduleReconnect(accountId, config.getReconnectDelay(), false);
                    }
                } catch (Exception e) {
                    log.error("【账号{}】Token失效自动重连异常", accountId, e);
                    scheduleReconnect(accountId, config.getReconnectDelay(), false);
                }
            }));
            
            client.setOnHeartbeatResponse(() -> updateHeartbeatResponseTime(accountId));
            
            client.setOnConnectionClosed(() -> {
                log.warn("【账号{}】WebSocket连接被关闭，触发自动重连...", accountId);
                Boolean restartFlag = connectionRestartFlags.get(accountId);
                boolean isManualRestart = restartFlag != null && restartFlag;
                int delay = isManualRestart ? 1 : config.getReconnectDelay();
                scheduleReconnect(accountId, delay, isManualRestart);
            });

            log.info("正在连接WebSocket: {}", WEBSOCKET_URL);
            boolean connected;
            try {
                connected = client.connectBlocking(10, TimeUnit.SECONDS);
            } catch (Exception connectEx) {
                client.shutdownResources();
                throw connectEx;
            }
            
            if (connected) {
                // 1. 发送 /reg 注册消息并等待确认（彻底杜绝握手101成功但/reg返回401或超时的“假连接”状态）
                log.info("开始WebSocket注册校验流程: accountId={}", accountId);
                initializer.sendRegistrationMessage(client, accessToken, deviceId, String.valueOf(accountId));
                int regCode = client.awaitRegistration(8000);

                if (regCode == 401) {
                    log.warn("【账号{}】WebSocket握手成功但 /reg 返回401(Token无效)，关闭本次连接", accountId);
                    client.setIntentionalClose(true);
                    client.close();
                    client.shutdownResources();
                    return 401;
                }
                if (regCode != 200 || !client.isConnected()) {
                    log.error("【账号{}】WebSocket /reg 注册未通过(regCode={})或连接已断开，关闭本次连接防假在线", accountId, regCode);
                    client.setIntentionalClose(true);
                    client.close();
                    client.shutdownResources();
                    return -1;
                }

                webSocketClients.put(accountId, client);

                // 2. 发送同步状态消息
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                initializer.sendSyncStatusMessage(client, String.valueOf(accountId));
                
                // 3. 启动心跳与静默续期任务
                startHeartbeat(accountId, client);

                AtomicInteger attemptCount = reconnectAttemptCounts.get(accountId);
                if (attemptCount != null) {
                    attemptCount.set(0);
                }
                
                log.info("WebSocket连接并注册成功: accountId={}, regCode={}", accountId, regCode);
                
                operationLogService.log(accountId, 
                    OperationConstants.Type.CONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接成功", 
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return 200;
            } else {
                client.shutdownResources();
                log.error("WebSocket连接失败: accountId={}", accountId);
                operationLogService.log(accountId, 
                    OperationConstants.Type.CONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接失败", 
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return -1;
            }
        } catch (Exception e) {
            log.error("连接WebSocket异常: accountId={}", accountId, e);
            operationLogService.log(accountId, 
                OperationConstants.Type.CONNECT, 
                OperationConstants.Module.WEBSOCKET,
                "WebSocket连接异常: " + e.getMessage(), 
                OperationConstants.Status.FAIL,
                OperationConstants.TargetType.WEBSOCKET, 
                String.valueOf(accountId),
                null, null, null, null);
            throw e;
        }
    }

    @Override
    public boolean stopWebSocket(Long accountId) {
        synchronized (getConnectionLock(accountId)) {
            return stopWebSocketInternal(accountId);
        }
    }

    private boolean stopWebSocketInternal(Long accountId) {
        try {
            log.info("停止WebSocket连接: accountId={}", accountId);

            // 停止心跳任务
            stopHeartbeat(accountId);

            // 关闭WebSocket连接
            XianyuWebSocketClient client = webSocketClients.remove(accountId);
            if (client != null) {
                // 标记为主动关闭，防止onClose回调触发自动重连
                // 重连由调用方自行决定是否需要
                client.setIntentionalClose(true);
                client.close();
                log.info("WebSocket连接已关闭: accountId={}", accountId);
                
                // 记录操作日志
                operationLogService.log(accountId, 
                    OperationConstants.Type.DISCONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接已关闭", 
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return true;
            } else {
                log.warn("WebSocket连接不存在: accountId={}", accountId);
                return false;
            }

        } catch (Exception e) {
            log.error("停止WebSocket失败: accountId={}", accountId, e);
            return false;
        }
    }

    @Override
    public boolean isConnected(Long accountId) {
        XianyuWebSocketClient client = webSocketClients.get(accountId);
        return client != null && client.isConnected();
    }

    @Override
    public void stopAllWebSockets() {
        log.info("停止所有WebSocket连接");
        
        for (Long accountId : webSocketClients.keySet()) {
            stopWebSocket(accountId);
        }
        
        // 关闭心跳调度器
        heartbeatScheduler.shutdown();
    }

    /**
     * 启动心跳任务
     * 增强功能：心跳超时检测（完全对齐Python逻辑）
     * 
     * Python心跳逻辑：
     * 1. 每隔heartbeat_interval秒发送一次心跳
     * 2. 检查上次心跳响应时间，如果超过(heartbeat_interval + heartbeat_timeout)则认为连接断开
     * 3. 心跳循环break后，外层while True循环会自动重连
     */
    private void startHeartbeat(Long accountId, XianyuWebSocketClient client) {
        // 初始化心跳响应时间（秒级时间戳，对齐Python）
        long currentTime = System.currentTimeMillis() / 1000;
        lastHeartbeatResponseTimes.put(accountId, currentTime);
        lastHeartbeatSendTimes.put(accountId, currentTime);
        
        // 心跳发送任务（参考Python的heartbeat_loop）
        // 立即发送第一次心跳,防止连接空闲被关闭
        try {
            if (client.isConnected()) {
                client.sendHeartbeat();
                log.info("【账号{}】已发送初始心跳", accountId);
            }
        } catch (Exception e) {
            log.error("发送初始心跳失败: accountId={}", accountId, e);
        }
        
        // 参考Python: heartbeat_loop 中每1秒检查一次
        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
            () -> {
                try {
                    long now = System.currentTimeMillis() / 1000;
                    
                    // 参考Python: 每隔heartbeat_interval秒发送一次心跳
                    Long lastSendTime = lastHeartbeatSendTimes.get(accountId);
                    if (lastSendTime == null || now - lastSendTime >= config.getHeartbeatInterval()) {
                        if (client.isConnected()) {
                            client.sendHeartbeat();
                            lastHeartbeatSendTimes.put(accountId, now);
                            log.debug("【账号{}】心跳已发送", accountId);
                        }
                    }
                    
                    // 参考Python: 检查上次心跳响应时间
                    // Python: if (current_time - self.last_heartbeat_response) > (self.heartbeat_interval + self.heartbeat_timeout):
                    Long lastResponseTime = lastHeartbeatResponseTimes.get(accountId);
                    if (lastResponseTime != null) {
                        long timeout = config.getHeartbeatInterval() + config.getHeartbeatTimeout();
                        
                        if (now - lastResponseTime > timeout) {
                            log.warn("【账号{}】心跳响应超时（{}秒无响应，超时阈值{}秒），连接可能已断开",
                                    accountId, now - lastResponseTime, timeout);
                            // 参考Python: heartbeat_loop break，触发外层重连
                            handleConnectionLost(accountId);
                        }
                    }
                } catch (Exception e) {
                    log.error("【账号{}】心跳任务异常", accountId, e);
                }
            },
            1, 1, TimeUnit.SECONDS  // 参考Python: 每秒检查一次
        );
        
        heartbeatTasks.put(accountId, heartbeatTask);
        log.info("心跳任务已启动: accountId={}, 心跳间隔{}秒, 超时阈值{}+{}秒", 
                accountId, config.getHeartbeatInterval(), config.getHeartbeatInterval(), config.getHeartbeatTimeout());
        
        // 启动Token自动刷新任务（参考Python的token_refresh_loop）
        startTokenRefresh(accountId);
    }
    
    /**
     * 启动Token自动刷新任务
     * 参考Python的token_refresh_loop方法
     * 
     * Python逻辑：
     * 1. 每分钟检查一次
     * 2. 当 current_time - last_token_refresh_time >= token_refresh_interval 时刷新Token
     * 3. Token刷新成功后，设置connection_restart_flag=True，关闭WebSocket触发重连
     * 4. Token刷新失败后，在token_retry_interval秒后重试
     * 
     * 关键修复：
     * - 记录Token获取时间（而非刷新时间），确保1小时后刷新
     * - Token有效期20小时，但每1小时主动刷新一次，保持连接活跃
     */
    private long computeNextTokenRefreshIntervalSeconds() {
        int baseSeconds = Math.max(1800, config.getTokenRefreshInterval());
        // 默认配置(43200s=12h)下，采用10~14小时随机间隔（对齐README防风控策略）
        int minSeconds = (int) (baseSeconds * 0.83);
        int maxSeconds = (int) (baseSeconds * 1.17);
        return ThreadLocalRandom.current().nextLong(minSeconds, maxSeconds + 1L);
    }

    private void startTokenRefresh(Long accountId) {
        long currentTime = System.currentTimeMillis() / 1000;
        long refreshInterval = computeNextTokenRefreshIntervalSeconds();
        lastTokenRefreshTimes.put(accountId, currentTime);
        nextTokenRefreshDeadlines.put(accountId, currentTime + refreshInterval);
        
        log.info("【账号{}】Token静默无损续期任务已启动: 下次刷新将在约{}分钟({}小时)后执行", 
                accountId, refreshInterval / 60, String.format("%.1f", refreshInterval / 3600.0));
        
        ScheduledFuture<?> tokenRefreshTask = tokenRefreshScheduler.scheduleAtFixedRate(
            () -> {
                try {
                    Long deadline = nextTokenRefreshDeadlines.get(accountId);
                    if (deadline == null) {
                        return;
                    }
                    
                    long now = System.currentTimeMillis() / 1000;
                    if (now >= deadline) {
                        log.info("【账号{}】已到达Token静默续期窗口，准备先获取新Token再平滑切换连接...", accountId);
                        // 推迟下次检查窗口，由 silentRefreshTokenAndReconnect 接管成功更新或失败重试
                        nextTokenRefreshDeadlines.put(accountId, now + computeNextTokenRefreshIntervalSeconds());
                        silentRefreshTokenAndReconnect(accountId);
                    }
                } catch (Exception e) {
                    log.error("【账号{}】Token刷新检查失败", accountId, e);
                }
            },
            60, 60, TimeUnit.SECONDS
        );
        
        tokenRefreshTasks.put(accountId, tokenRefreshTask);
    }
    
    /**
     * 静默刷新Token并平滑重连（对齐Python原版XianyuAutoAsync核心顺序）：
     * 1. 【关键】不断开当前正常工作的WebSocket连接，先在后台静默请求新的accessToken
     * 2. 只有拿到新Token成功后，才加锁关闭旧连接并用新Token无缝重建连接
     * 3. 若静默获取新Token失败（如临时风控/网络波动），保留当前正常连接继续收发消息，稍后重试
     */
    private void silentRefreshTokenAndReconnect(Long accountId) {
        try {
            if (!isConnected(accountId)) {
                log.debug("【账号{}】当前WebSocket未连接，跳过静默续期", accountId);
                return;
            }
            log.info("【账号{}】开始静默刷新Token（当前连接保持不断）...", accountId);
            
            try {
                if (cookieRefreshService != null) {
                    boolean cookieOk = cookieRefreshService.checkLoginStatusQuietly(accountId);
                    if (!cookieOk) {
                        log.warn("【账号{}】静默续期前检测到hasLogin未通过，尝试浏览器兜底刷新Cookie...", accountId);
                        cookieRefreshService.refreshCookie(accountId);
                    }
                }
            } catch (Exception e) {
                log.warn("【账号{}】静默续期前Cookie检查异常: {}", accountId, e.getMessage());
            }

            // 不断开旧连接，先静默获取新Token
            String newToken = tokenService.fetchNewAccessTokenQuietly(accountId);
            if (newToken != null && !newToken.isEmpty()) {
                log.info("【账号{}】✅ 新Token已就绪，开始平滑切换WebSocket连接...", accountId);
                boolean switched = startWebSocketWithToken(accountId, newToken);
                if (switched) {
                    long now = System.currentTimeMillis() / 1000;
                    lastTokenRefreshTimes.put(accountId, now);
                    nextTokenRefreshDeadlines.put(accountId, now + computeNextTokenRefreshIntervalSeconds());
                    ScheduledFuture<?> oldRetry = tokenRefreshRetryTasks.remove(accountId);
                    if (oldRetry != null) {
                        oldRetry.cancel(false);
                    }
                    log.info("【账号{}】✅ Token静默续期并平滑重连成功", accountId);
                    return;
                } else {
                    log.warn("【账号{}】新Token切换连接失败，交由退避重连恢复", accountId);
                    scheduleReconnect(accountId, config.getReconnectDelay(), false);
                    return;
                }
            }

            log.warn("【账号{}】⚠️ 静默获取新Token未成功，保留当前正常工作的WebSocket不断开，将在{}秒后重试",
                    accountId, config.getTokenRetryInterval());
            scheduleSilentTokenRetry(accountId);
        } catch (Exception e) {
            log.error("【账号{}】Token静默续期异常，保留当前连接，将在{}秒后重试",
                    accountId, config.getTokenRetryInterval(), e);
            scheduleSilentTokenRetry(accountId);
        }
    }

    private void scheduleSilentTokenRetry(Long accountId) {
        ScheduledFuture<?> prev = tokenRefreshRetryTasks.remove(accountId);
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
        ScheduledFuture<?> retryFuture = reconnectExecutor.schedule(() -> {
            tokenRefreshRetryTasks.remove(accountId);
            if (isConnected(accountId)) {
                log.info("【账号{}】执行Token静默续期重试...", accountId);
                silentRefreshTokenAndReconnect(accountId);
            }
        }, config.getTokenRetryInterval(), TimeUnit.SECONDS);
        tokenRefreshRetryTasks.put(accountId, retryFuture);
    }
    
    /**
     * 处理连接丢失
     * 参考Python的连接重连逻辑
     */
    private void handleConnectionLost(Long accountId) {
        log.warn("【账号{}】检测到连接丢失（心跳超时），准备重连...", accountId);
        scheduleReconnect(accountId, config.getReconnectDelay(), false);
    }
    
    /**
     * 调度重连任务
     * 参考Python的main()方法中while True无限重连循环
     * 
     * 关键改进（对齐Python逻辑）：
     * 1. 防止重复重连：同一账号同时只有一个重连任务
     * 2. 指数退避：重连失败后延迟逐渐增加
     * 3. 重连成功后重置计数
     * 
     * @param accountId 账号ID
     * @param delaySeconds 延迟秒数
     * @param isManualRestart 是否主动重启（Token刷新等）
     */
    private void scheduleReconnect(Long accountId, int delaySeconds, boolean isManualRestart) {
        // 取消已有的重连任务（防止重复）
        Future<?> existingTask = reconnectTasks.get(accountId);
        if (existingTask != null && !existingTask.isDone()) {
            log.debug("【账号{}】已有重连任务在执行，跳过", accountId);
            return;
        }
        
        // 重置重启标志
        if (isManualRestart) {
            connectionRestartFlags.put(accountId, false);
        }
        
        // 获取/初始化重连次数
        AtomicInteger attemptCount = reconnectAttemptCounts.computeIfAbsent(accountId, k -> new AtomicInteger(0));
        
        // 参考Python: 无限重连，但使用指数退避
        int currentAttempt = attemptCount.incrementAndGet();
        // 指数退避: 5s, 10s, 20s, 40s, 60s, 60s, ... 最大60秒
        int actualDelay = isManualRestart ? delaySeconds : 
                Math.min(delaySeconds * (int) Math.pow(2, Math.min(currentAttempt - 1, 4)), 60);
        
        log.info("【账号{}】计划{}秒后执行重连（第{}次尝试）...", accountId, actualDelay, currentAttempt);
        
        ScheduledFuture<?> reconnectTask = reconnectExecutor.schedule(() -> {
            try {
                reconnectTasks.remove(accountId);
                
                // 停止当前连接和心跳
                stopWebSocket(accountId);
                
                // 参考Python: 重连前先刷新Cookie（hasLogin保活）
                try {
                    if (cookieRefreshService != null) {
                        log.info("【账号{}】重连前先检查Cookie登录状态...", accountId);
                        // 使用静默检查，不记录操作日志（避免频繁记录）
                        boolean cookieOk = cookieRefreshService.checkLoginStatusQuietly(accountId);
                        if (!cookieOk) {
                            log.warn("【账号{}】Cookie已失效(hasLogin)，重连前触发浏览器兜底刷新Cookie（对齐Python）...", accountId);
                            boolean browserRefreshOk = cookieRefreshService.refreshCookie(accountId);
                            if (browserRefreshOk) {
                                log.info("【账号{}】浏览器兜底刷新Cookie成功，继续重连", accountId);
                            } else {
                                log.error("【账号{}】hasLogin和浏览器兜底刷新Cookie均失败，重连可能失败", accountId);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("【账号{}】重连前Cookie检查/兜底刷新异常，继续尝试重连: {}", accountId, e.getMessage());
                }
                
                // 重新启动连接
                boolean success = startWebSocket(accountId);
                
                if (success) {
                    // 重连成功，重置计数
                    attemptCount.set(0);
                    log.info("【账号{}】✅ 重连成功", accountId);
                    
                    operationLogService.log(accountId, 
                        OperationConstants.Type.RECONNECT, 
                        OperationConstants.Module.WEBSOCKET,
                        isManualRestart ? "主动重启连接成功" : "异常断开后重连成功", 
                        OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.WEBSOCKET, 
                        String.valueOf(accountId),
                        null, null, null, null);
                } else {
                    log.error("【账号{}】❌ 重连失败（第{}次），将继续尝试...", accountId, currentAttempt);
                    
                    operationLogService.log(accountId, 
                        OperationConstants.Type.RECONNECT, 
                        OperationConstants.Module.WEBSOCKET,
                        "重连失败（第" + currentAttempt + "次）", 
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.WEBSOCKET, 
                        String.valueOf(accountId),
                        null, null, null, null);
                    
                    // 重连失败达到阈值时触发邮件通知
                    if (currentAttempt >= RECONNECT_NOTIFY_THRESHOLD) {
                        triggerWsDisconnectNotify(accountId);
                    }
                    
                    // 参考Python: 重连失败后继续尝试（while True循环）
                    scheduleReconnect(accountId, config.getReconnectDelay(), false);
                }
            } catch (Exception e) {
                log.error("【账号{}】重连异常，将继续尝试...", accountId, e);
                scheduleReconnect(accountId, config.getReconnectDelay(), false);
            }
        }, actualDelay, TimeUnit.SECONDS);
        
        reconnectTasks.put(accountId, reconnectTask);
    }
    
    /**
     * 更新心跳响应时间
     * 由消息处理器调用
     */
    public void updateHeartbeatResponseTime(Long accountId) {
        lastHeartbeatResponseTimes.put(accountId, System.currentTimeMillis() / 1000);
    }

    /**
     * 停止心跳任务
     */
    private void stopHeartbeat(Long accountId) {
        // 停止心跳任务
        ScheduledFuture<?> heartbeatTask = heartbeatTasks.remove(accountId);
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            log.info("心跳任务已停止: accountId={}", accountId);
        }
        
        // 停止Token刷新任务
        ScheduledFuture<?> tokenRefreshTask = tokenRefreshTasks.remove(accountId);
        if (tokenRefreshTask != null) {
            tokenRefreshTask.cancel(false);
            log.info("Token刷新任务已停止: accountId={}", accountId);
        }

        // 停止Token静默刷新重试任务
        ScheduledFuture<?> tokenRetryTask = tokenRefreshRetryTasks.remove(accountId);
        if (tokenRetryTask != null) {
            tokenRetryTask.cancel(false);
        }
        nextTokenRefreshDeadlines.remove(accountId);
        
        // 取消重连任务
        Future<?> reconnectTask = reconnectTasks.remove(accountId);
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            log.info("重连任务已取消: accountId={}", accountId);
        }
        
        // 清理状态
        lastHeartbeatResponseTimes.remove(accountId);
        lastHeartbeatSendTimes.remove(accountId);
        lastTokenRefreshTimes.remove(accountId);
        connectionRestartFlags.remove(accountId);
    }

    @Override
    public boolean sendMessage(Long accountId, String cid, String toId, String text) {
        try {
            log.info("发送消息: accountId={}, cid={}, toId={}, text={}", accountId, cid, toId, text);
            
            // 获取WebSocket客户端
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }
            
            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }
            
            // 发送消息
            client.sendMessage(cid, toId, text);
            return true;
            
        } catch (Exception e) {
            log.error("发送消息失败: accountId={}, cid={}, toId={}", accountId, cid, toId, e);
            return false;
        }
    }

    @Override
    public boolean sendMessageWithResult(Long accountId, String cid, String toId, String text) {
        try {
            log.info("发送消息(等待结果): accountId={}, cid={}, toId={}, text={}", accountId, cid, toId, text);
            
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }
            
            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }
            
            return client.sendMessageWithResult(cid, toId, text);
            
        } catch (Exception e) {
            log.error("发送消息失败: accountId={}, cid={}, toId={}", accountId, cid, toId, e);
            return false;
        }
    }

    public void completePendingResponse(Long accountId, String mid, int code) {
        XianyuWebSocketClient client = webSocketClients.get(accountId);
        if (client != null) {
            client.completePendingResponse(mid, code);
        }
    }
    
    @Override
    public boolean sendImageMessage(Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        try {
            log.info("发送图片消息: accountId={}, cid={}, toId={}, url={}, size={}x{}", 
                    accountId, cid, toId, imageUrl, width, height);
            
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }
            
            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }
            
            client.sendImageMessage(cid, toId, imageUrl, width, height);
            return true;
            
        } catch (Exception e) {
            log.error("发送图片消息失败: accountId={}, cid={}, toId={}", accountId, cid, toId, e);
            return false;
        }
    }

    @Override
    public boolean sendImageMessageWithResult(Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        try {
            log.info("发送图片消息(等待结果): accountId={}, cid={}, toId={}, url={}, size={}x{}",
                    accountId, cid, toId, imageUrl, width, height);

            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }

            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }

            return client.sendImageMessageWithResult(cid, toId, imageUrl, width, height);

        } catch (Exception e) {
            log.error("发送图片消息失败: accountId={}, cid={}, toId={}", accountId, cid, toId, e);
            return false;
        }
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("应用关闭，清理WebSocket资源");
        stopAllWebSockets();
        
        // 关闭Token刷新调度器
        tokenRefreshScheduler.shutdown();
        
        // 关闭重连调度器
        reconnectExecutor.shutdown();
    }

    private void triggerWsDisconnectNotify(Long accountId) {
        try {
            if (emailNotifyService == null || !emailNotifyService.isWsDisconnectNotifyEnabled()) {
                return;
            }
            // 防抖：10分钟内只发送一次
            Long lastNotifyTime = lastDisconnectNotifyTimes.get(accountId);
            long now = System.currentTimeMillis();
            if (lastNotifyTime != null && (now - lastNotifyTime) < NOTIFY_INTERVAL_MS) {
                log.debug("【账号{}】邮件通知防抖中，跳过本次发送", accountId);
                return;
            }
            lastDisconnectNotifyTimes.put(accountId, now);

            String accountNote = "";
            try {
                com.feijimiao.xianyuassistant.entity.XianyuAccount account = xianyuAccountMapper.selectById(accountId);
                if (account != null) {
                    accountNote = account.getAccountNote() != null ? account.getAccountNote() : "";
                }
            } catch (Exception e) {
                log.debug("获取账号备注失败: {}", e.getMessage());
            }
            emailNotifyService.sendWsDisconnectNotifyEmail(accountId, accountNote);
        } catch (Exception e) {
            log.warn("触发WebSocket断开连接邮件通知异常: {}", e.getMessage());
        }
    }
}
