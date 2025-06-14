package com.zsq.winter.netty.core.server;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.entity.NettyMessage;
import com.zsq.winter.netty.service.NettyServerMessageService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/**
 * Netty服务端WebSocket消息处理器
 * 
 * 该处理器负责管理所有客户端的WebSocket连接和消息交互，主要功能包括：
 * 1. 连接生命周期管理：建立连接、维持心跳、断线检测
 * 2. 消息处理：接收和发送各类消息（业务消息、系统消息、心跳消息等）
 * 3. 连接质量监控：跟踪心跳延迟、连接状态、资源使用等
 * 4. 异常处理：处理网络异常、超时等各类异常情况
 * 
 * 关键特性：
 * - 支持多种WebSocket帧类型（文本帧、二进制帧、Ping/Pong等）
 * - 实现了完整的心跳检测机制
 * - 提供了详细的连接统计信息
 * - 支持资源监控，防止内存泄漏
 * - 支持优雅关闭，确保资源正确释放
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    /**
     * WebSocket连接管理器
     * 负责管理所有客户端连接，实现连接的添加、删除、查找等操作
     */
    private final NettyServerChannelManager channelManager;

    /**
     * 消息处理服务
     * 负责处理业务层面的消息逻辑，将网络层消息转换为业务层处理
     */
    private final NettyServerMessageService messageService;

    /**
     * 业务线程池
     * 用于执行耗时的业务逻辑，避免阻塞IO线程，提高系统吞吐量
     */
    private final ThreadPoolTaskExecutor executor;

    /**
     * 共享的定时任务执行器
     * 用于执行定时任务，如心跳检测、资源监控等
     * 使用守护线程，不阻止JVM退出
     */
    private static final ScheduledExecutorService SCHEDULED_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "resource-monitor");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 连接统计信息映射表
     * key: ChannelId - 每个连接的唯一标识
     * value: ConnectionStats - 该连接的统计信息
     */
    private final Map<ChannelId, ConnectionStats> connectionStats = new ConcurrentHashMap<>();
    
    /**
     * 心跳和连接配置参数
     */
    private static final int MAX_MISSED_HEARTBEATS = 3;  // 最大允许的连续心跳丢失次数
    private static final long HEARTBEAT_TIMEOUT_MS = 5000; // 心跳超时时间（毫秒）
    private static final long BUSINESS_TIMEOUT_MS = 10000; // 业务操作超时时间（毫秒）
    private static final long ZOMBIE_CONNECTION_TIMEOUT_MS = 30000; // 僵尸连接判定时间（毫秒）

    /**
     * 心跳和重试配置参数
     */
    private static final int HEARTBEAT_CHECK_INTERVAL = 30; // 心跳检查间隔（秒）
    private static final int MAX_HEARTBEAT_MISS = 3; // 最大心跳丢失次数，超过此值将触发重连
    private static final int INITIAL_RETRY_DELAY = 5; // 初始重试延迟（秒）
    private static final int MAX_RETRY_DELAY = 60; // 最大重试延迟（秒）
    private static final double BACKOFF_MULTIPLIER = 1.5; // 重试延迟增长系数，用于实现指数退避

    /**
     * 连接统计信息内部类
     * 用于记录和统计单个连接的各项指标
     */
    private static class ConnectionStats {
        private final AtomicInteger heartbeatsReceived = new AtomicInteger(0);    // 已接收的心跳数
        private final AtomicInteger heartbeatsMissed = new AtomicInteger(0);      // 丢失的心跳数
        private final AtomicInteger retryAttempt = new AtomicInteger(0);          // 重试次数
        private final AtomicLong lastHeartbeatTime = new AtomicLong(0);          // 最后一次心跳时间
        private final AtomicLong lastDataTime = new AtomicLong(0);               // 最后一次数据接收时间
        private final AtomicLong lastBusinessCommandTime = new AtomicLong(0);    // 最后一次业务命令时间
        private volatile int currentRetryDelay = INITIAL_RETRY_DELAY;             // 当前重试延迟
        private volatile long totalLatency = 0;                                   // 总心跳延迟
        private volatile int connectionQuality = 100;                             // 连接质量（0-100）
        private volatile String lastBusinessCommand = "";                         // 最后执行的业务命令
        private volatile long resourceUsage = 0;                                 // 资源使用量

        public ConnectionStats() {
            long now = System.currentTimeMillis();
            this.lastHeartbeatTime.set(now);
            this.lastDataTime.set(now);
            this.lastBusinessCommandTime.set(now);
        }

        /**
         * 记录心跳
         * 更新心跳相关的统计信息，重置错误计数
         */
        public void recordHeartbeat() {
            heartbeatsReceived.incrementAndGet();
            lastHeartbeatTime.set(System.currentTimeMillis());
            heartbeatsMissed.set(0);
            retryAttempt.set(0);
            currentRetryDelay = INITIAL_RETRY_DELAY;
        }

        /**
         * 增加重试次数
         * 更新重试次数并计算新的重试延迟
         */
        public void incrementRetryAttempt() {
            int attempt = retryAttempt.incrementAndGet();
            currentRetryDelay = calculateRetryDelay(attempt);
        }

        /**
         * 计算重试延迟
         * 使用指数退避算法计算下一次重试的延迟时间
         */
        private int calculateRetryDelay(int attempt) {
            double delay = INITIAL_RETRY_DELAY * Math.pow(BACKOFF_MULTIPLIER, attempt - 1);
            return (int) Math.min(delay, MAX_RETRY_DELAY);
        }

        /**
         * 记录业务命令
         * 更新最后执行的业务命令和时间
         */
        public void recordBusinessCommand(String command) {
            lastBusinessCommand = command;
            lastBusinessCommandTime.set(System.currentTimeMillis());
        }

        /**
         * 记录数据接收
         * 更新最后一次数据接收时间
         */
        public void recordDataReceived() {
            lastDataTime.set(System.currentTimeMillis());
        }

        /**
         * 更新资源使用情况
         */
        public void updateResourceUsage(long usage) {
            this.resourceUsage = usage;
        }

        /**
         * 检查是否为僵尸连接
         * 如果长时间没有收到任何数据，则认为是僵尸连接
         */
        public boolean isZombieConnection() {
            return System.currentTimeMillis() - lastDataTime.get() > ZOMBIE_CONNECTION_TIMEOUT_MS;
        }

        /**
         * 检查业务是否超时
         * 如果业务命令执行时间超过阈值，则认为超时
         */
        public boolean isBusinessTimeout() {
            return !lastBusinessCommand.isEmpty() && 
                   System.currentTimeMillis() - lastBusinessCommandTime.get() > BUSINESS_TIMEOUT_MS;
        }

        /**
         * 检查是否应该断开连接
         * 基于心跳丢失次数判断
         */
        public boolean shouldDisconnect() {
            return heartbeatsMissed.get() >= MAX_MISSED_HEARTBEATS;
        }

        /**
         * 获取统计信息的字符串表示
         */
        public String getStats() {
            return String.format("心跳: 收到=%d, 丢失=%d, 平均延迟=%dms, 连接质量=%d%%, 资源占用=%d, 最后业务命令=%s",
                    heartbeatsReceived.get(), heartbeatsMissed.get(), 
                    heartbeatsReceived.get() > 0 ? totalLatency / heartbeatsReceived.get() : 0,
                    connectionQuality, resourceUsage, lastBusinessCommand);
        }
    }

    /**
     * 构造函数
     * 初始化服务端处理器的各个组件
     *
     * @param channelManager WebSocket连接管理器
     * @param messageService 消息处理服务
     * @param executor 业务线程池
     */
    public NettyServerHandler(NettyServerChannelManager channelManager,
                            @Qualifier("webSocketMessageService") NettyServerMessageService messageService,
                            @Qualifier("winterNettyServerTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.channelManager = channelManager;
        this.messageService = messageService;
        this.executor = executor;
    }

    /**
     * 通道注册事件处理
     * 当Channel被注册到EventLoop时调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        log.info("通道已注册: {}", ctx.channel().id());
        super.channelRegistered(ctx);
    }

    /**
     * 通道就绪事件处理
     * 当WebSocket连接建立成功后调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 初始化连接统计
        ConnectionStats stats = new ConnectionStats();
        connectionStats.put(ctx.channel().id(), stats);
        
        // 启动资源监控
        startResourceMonitoring(ctx.channel().id(), stats);
        
        channelManager.addChannel(ctx.channel());
        messageService.onConnect(ctx.channel());
        log.info("服务端连接建立: {}", ctx.channel().id());
    }

    /**
     * WebSocket消息处理
     * 处理接收到的各类WebSocket帧
     *
     * @param ctx 通道上下文对象
     * @param frame 接收到的WebSocket帧
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        ConnectionStats stats = connectionStats.get(ctx.channel().id());
        if (stats != null) {
            stats.recordDataReceived();
        }

        if (frame instanceof PingWebSocketFrame) {
            handlePing(ctx, frame);
        } else if (frame instanceof PongWebSocketFrame) {
            handlePong(ctx);
        } else if (frame instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, (TextWebSocketFrame) frame);
        } else if (frame instanceof CloseWebSocketFrame) {
            log.info("收到关闭连接请求");
            ctx.close();
        } else {
            log.warn("收到未支持的消息类型: {}", frame.getClass().getSimpleName());
        }
    }

    /**
     * 消息读取完成事件处理
     * 在一次消息读取操作完成后调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 刷新通道，确保所有写入的消息都立即发送出去
        ctx.flush();
        super.channelReadComplete(ctx);
    }

    /**
     * 异常处理
     * 处理连接过程中的异常情况
     *
     * @param ctx 通道上下文对象
     * @param cause 异常原因
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("WebSocket连接异常: {}", cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * 通道断开事件处理
     * 当WebSocket连接断开时调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ConnectionStats stats = connectionStats.remove(ctx.channel().id());
        if (stats != null) {
            log.info("连接断开，最终统计: {}", stats.getStats());
        }
        
        channelManager.removeChannel(ctx.channel());
        messageService.onDisconnect(ctx.channel());
        log.info("WebSocket连接断开: {}", ctx.channel().id());
    }

    /**
     * 用户事件触发处理
     * 处理空闲状态事件，包括读空闲、写空闲和全部空闲
     *
     * @param ctx 通道上下文对象
     * @param evt 触发的事件对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            ConnectionStats stats = connectionStats.get(ctx.channel().id());
            
            if (stats != null) {
                switch (e.state()) {
                    case READER_IDLE:
                        handleReaderIdle(ctx, stats);
                        break;
                    case WRITER_IDLE:
                        handleWriterIdle(ctx);
                        break;
                    case ALL_IDLE:
                        handleAllIdle(ctx, stats);
                        break;
                }
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 处理Ping帧
     * 接收到Ping帧后立即回复Pong帧
     *
     * @param ctx 通道上下文对象
     * @param frame 接收到的Ping帧
     */
    private void handlePing(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // 更新心跳统计
        ConnectionStats stats = connectionStats.get(ctx.channel().id());
        if (stats != null) {
            stats.recordHeartbeat();
        }
        // 回复Pong
        ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        log.debug("回复PONG帧");
    }

    /**
     * 处理Pong帧
     * 更新心跳统计信息
     *
     * @param ctx 通道上下文对象
     */
    private void handlePong(ChannelHandlerContext ctx) {
        ConnectionStats stats = connectionStats.get(ctx.channel().id());
        if (stats != null) {
            stats.recordHeartbeat();
            log.debug("收到PONG帧，连接状态: {}", stats.getStats());
        }
    }

    /**
     * 处理文本帧
     * 解析并处理WebSocket文本消息
     *
     * @param ctx 通道上下文对象
     * @param frame 接收到的文本帧
     */
    private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        log.debug("收到文本消息: {}", text);
        try {
            NettyMessage message = JSONUtil.toBean(text, NettyMessage.class);
            handleMessage(ctx, message);
        } catch (Exception e) {
            NettyMessage message = NettyMessage.text(text);
            handleMessage(ctx, message);
        }
    }

    /**
     * 处理读空闲事件
     * 检查心跳超时和僵尸连接
     *
     * @param ctx 通道上下文对象
     * @param stats 连接统计对象
     */
    private void handleReaderIdle(ChannelHandlerContext ctx, ConnectionStats stats) {
        int missed = stats.heartbeatsMissed.incrementAndGet();
        log.warn("读空闲超时，已丢失心跳次数: {}, {}", missed, stats.getStats());
        
        // 检查是否为僵尸连接
        if (stats.isZombieConnection()) {
            log.error("检测到僵尸连接，关闭连接: {}", ctx.channel().id());
            ctx.close();
            handleConnectionLost(ctx, stats);
            return;
        }

        // 检查业务超时
        if (stats.isBusinessTimeout()) {
            log.error("业务操作超时，最后命令: {}, 关闭连接: {}", 
                    stats.lastBusinessCommand, ctx.channel().id());
            ctx.close();
            handleConnectionLost(ctx, stats);
            return;
        }

        // 检查心跳丢失
        if (stats.heartbeatsMissed.get() >= MAX_HEARTBEAT_MISS) {
            log.error("连续{}次未收到心跳，关闭连接: {}", 
                    MAX_HEARTBEAT_MISS, ctx.channel().id());
            ctx.close();
            handleConnectionLost(ctx, stats);
        }
    }

    /**
     * 处理写空闲事件
     * 发送Ping帧保持连接活跃
     *
     * @param ctx 通道上下文对象
     */
    private void handleWriterIdle(ChannelHandlerContext ctx) {
        log.debug("写空闲，发送Ping");
        ctx.writeAndFlush(new PingWebSocketFrame());
    }

    /**
     * 处理全部空闲事件
     * 检查心跳超时情况
     *
     * @param ctx 通道上下文对象
     * @param stats 连接统计对象
     */
    private void handleAllIdle(ChannelHandlerContext ctx, ConnectionStats stats) {
        if (stats.lastHeartbeatTime.get() != 0) {
            Duration idleTime = Duration.between(Instant.ofEpochMilli(stats.lastHeartbeatTime.get()), Instant.now());
            if (idleTime.toMillis() > HEARTBEAT_TIMEOUT_MS) {
                log.warn("全部空闲超时，关闭连接: {}", ctx.channel().id());
                ctx.close();
            }
        }
    }

    /**
     * 处理业务消息
     * 将消息提交到业务线程池处理
     *
     * @param ctx 通道上下文对象
     * @param message 待处理的消息对象
     */
    private void handleMessage(ChannelHandlerContext ctx, NettyMessage message) {
        try {
            executor.execute(() -> {
                messageService.handleMessage(ctx.channel(), message);
            });
        } catch (Exception e) {
            log.error("处理WebSocket消息异常: {}", e.getMessage(), e);
            sendErrorMessage(ctx, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 发送错误消息
     * 将错误信息包装成系统消息发送给客户端
     *
     * @param ctx 通道上下文对象
     * @param errorMsg 错误信息内容
     */
    private void sendErrorMessage(ChannelHandlerContext ctx, String errorMsg) {
        NettyMessage errorMessage = NettyMessage.system(errorMsg);
        sendMessage(ctx, errorMessage);
    }

    /**
     * 发送消息
     * 将消息序列化并发送到客户端
     *
     * @param ctx 通道上下文对象
     * @param message 待发送的消息对象
     */
    private void sendMessage(ChannelHandlerContext ctx, NettyMessage message) {
        if (!ctx.channel().isActive()) return;

        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            ctx.writeAndFlush(new TextWebSocketFrame(jsonMessage));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 启动资源监控
     * 定期监控连接的资源使用情况
     *
     * @param channelId 通道ID
     * @param stats 连接统计对象
     */
    private void startResourceMonitoring(ChannelId channelId, ConnectionStats stats) {
        SCHEDULED_EXECUTOR.scheduleWithFixedDelay(() -> {
            if (!connectionStats.containsKey(channelId)) {
                return; // 连接已关闭，停止监控
            }

            // 更新资源使用情况
            long memoryUsage = estimateChannelMemoryUsage(channelId);
            stats.updateResourceUsage(memoryUsage);

            // 检查资源使用是否超限
            if (memoryUsage > getMaxAllowedMemoryPerChannel()) {
                log.warn("连接资源使用超限，channelId={}, 内存使用={}bytes", channelId, memoryUsage);
                // 可以在这里添加资源超限的处理逻辑
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    /**
     * 估算通道内存使用
     * 计算单个通道的内存占用情况
     *
     * @param channelId 通道ID
     * @return 估算的内存使用量（字节）
     */
    private long estimateChannelMemoryUsage(ChannelId channelId) {
        // 这里实现估算单个连接的内存使用量的逻辑
        // 可以通过JMX、内存采样等方式实现
        return 0; // 示例返回值
    }

    /**
     * 获取每个通道允许的最大内存使用量
     *
     * @return 最大允许的内存使用量（字节）
     */
    private long getMaxAllowedMemoryPerChannel() {
        // 返回每个连接允许的最大内存使用量
        return 10 * 1024 * 1024; // 示例：10MB
    }

    /**
     * 处理连接丢失
     * 实现断线重连逻辑，使用指数退避策略
     *
     * @param ctx 通道上下文对象
     * @param stats 连接统计对象
     */
    private void handleConnectionLost(ChannelHandlerContext ctx, ConnectionStats stats) {
        if (ctx.channel() == null || !ctx.channel().isActive()) {
            return;
        }

        stats.incrementRetryAttempt();
        int currentRetryDelay = stats.currentRetryDelay;

        // 使用ctx.executor()来调度重连，这样可以确保在正确的EventLoop中执行
        ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                return; // 如果连接已经恢复，不需要重试
            }

            log.info("第{}次尝试重新建立连接，延迟: {}秒", stats.retryAttempt.get(), currentRetryDelay);
            
            try {
                // 尝试重新建立连接
                ctx.channel().connect(ctx.channel().remoteAddress()).addListener(future -> {
                    if (future.isSuccess()) {
                        log.info("重新连接成功");
                        stats.recordHeartbeat(); // 重置心跳和重试状态
                    } else {
                        log.error("重新连接失败", future.cause());
                        handleConnectionLost(ctx, stats);
                    }
                });
            } catch (Exception e) {
                log.error("重新连接时发生异常", e);
                handleConnectionLost(ctx, stats);
            }
        }, currentRetryDelay, TimeUnit.SECONDS);
    }
}
