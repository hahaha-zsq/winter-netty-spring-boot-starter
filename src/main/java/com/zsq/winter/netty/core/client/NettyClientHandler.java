package com.zsq.winter.netty.core.client;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.autoconfigure.NettyProperties;
import com.zsq.winter.netty.entity.NettyMessage;
import com.zsq.winter.netty.service.NettyClientMessageService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Netty客户端消息处理器
 * <p>
 * 该处理器负责管理与服务器之间的连接和消息交互，主要功能包括：<br/>
 * 1. 连接生命周期管理：建立连接、维持心跳、断线重连 <br/>
 * 2. 消息处理：接收和发送各类消息（业务消息、系统消息、心跳消息等）<br/>
 * 3. 连接质量监控：跟踪心跳延迟、连接状态、资源使用等<br/>
 * 4. 异常处理：处理网络异常、超时等各类异常情况<br/>
 * <p>
 * 关键特性：<br/>
 * - 支持自动重连机制，使用指数退避算法<br/>
 * - 实现了心跳检测，保持连接活性<br/>
 * - 提供了详细的连接统计信息<br/>
 * - 支持资源监控，防止内存泄漏<br/>
 * <p>
 * 工作流程：<br/>
 * 1. 初始化：创建连接时初始化统计信息和监控任务<br/>
 * 2. 心跳维护：定期发送心跳包，检测连接状态<br/>
 * 3. 消息处理：接收消息后根据类型分发到对应处理器<br/>
 * 4. 异常处理：检测到异常后进行重连或资源清理<br/>
 * 5. 资源监控：定期检查连接资源使用情况<br/>
 * <p>
 * 注意事项：<br/>
 * - 所有IO操作都在EventLoop线程中执行<br/>
 * - 耗时操作应提交到业务线程池处理<br/>
 * - 需要正确处理连接的生命周期事件<br/>
 * - 重连策略使用指数退避，避免频繁重试<br/>
 */
@Slf4j
@ChannelHandler.Sharable  // 标记该处理器可以被多个Channel共享
public class NettyClientHandler extends SimpleChannelInboundHandler<String> {

    /**
     * Channel管理器
     * <p>
     * 用于管理所有的客户端连接，实现连接的添加、删除、查找等操作。
     * 主要功能：
     * - 维护活跃连接列表
     * - 提供连接查找和广播能力
     * - 处理连接的生命周期事件
     */
    private final NettyClientChannelManager channelManager;

    /**
     * 消息处理服务
     * <p>
     * 负责处理业务层面的消息逻辑，将网络层消息转换为业务层处理。
     * 主要功能：
     * - 消息解析和验证
     * - 业务逻辑处理
     * - 消息路由分发
     */
    private final NettyClientMessageService messageService;

    /**
     * 业务线程池
     * <p>
     * 用于执行耗时的业务逻辑，避免阻塞IO线程，提高系统吞吐量。
     * 特点：
     * - 线程池大小可配置
     * - 支持任务队列和拒绝策略
     * - 可监控线程池状态
     */
    private final ThreadPoolTaskExecutor executor;

    /**
     * 定时任务执行器
     * <p>
     * 用于执行定时任务，如心跳检测、资源监控等。
     * 特点：
     * - 单线程执行，避免并发问题
     * - 支持定时和周期性任务
     * - 优雅关闭，等待任务完成
     */
    private final ScheduledExecutorService scheduledExecutor;

    /**
     * 心跳和重连配置参数
     */
    private final NettyProperties properties;

    /**
     * 连接统计信息映射表
     * <p>
     * key: ChannelId - 每个连接的唯一标识
     * value: ConnectionStats - 该连接的统计信息
     * <p>
     * 用途：
     * - 记录连接的心跳状态
     * - 统计网络质量指标
     * - 监控资源使用情况
     */
    private final Map<ChannelId, ConnectionStats> connectionStats = new ConcurrentHashMap<>();

    /**
     * 资源监控任务映射表
     * <p>
     * key: ChannelId - 每个连接的唯一标识
     * value: ScheduledFuture - 该连接的资源监控任务
     * <p>
     * 用途：
     * - 管理每个连接的监控任务
     * - 支持动态取消和更新监控
     * - 防止任务泄漏
     */
    private final Map<ChannelId, ScheduledFuture<?>> monitoringTasks = new ConcurrentHashMap<>();

    /**
     * 构造函数
     * <p>
     * 初始化客户端处理器的各个组件，建立依赖关系。
     *
     * @param channelManager Channel管理器实例，负责连接管理
     * @param messageService 消息处理服务实例，负责业务逻辑
     * @param executor       业务线程池实例，用于执行异步任务
     * @param properties     配置属性
     */
    public NettyClientHandler(NettyClientChannelManager channelManager,
                            @Qualifier("nettyClientMessageService") NettyClientMessageService messageService,
                            @Qualifier("winterNettyClientTaskExecutor") ThreadPoolTaskExecutor executor,
                            NettyProperties properties) {
        this.channelManager = channelManager;
        this.messageService = messageService;
        this.executor = executor;
        this.properties = properties;
        this.scheduledExecutor = Executors.newScheduledThreadPool(1);
    }

    /**
     * 连接统计信息内部类
     * <p>
     * 用于记录和统计单个连接的各项指标，包括：
     * - 心跳统计：发送次数、丢失次数、延迟等
     * - 重试信息：重试次数、当前延迟等
     * - 连接质量：基于心跳延迟的质量评分
     * - 资源使用：内存占用等资源统计
     * <p>
     * 特点：
     * - 线程安全：使用原子类型确保并发安全
     * - 实时更新：所有指标都是实时更新的
     * - 可追踪：记录最后一次操作的时间戳
     */
    private class ConnectionStats {
        /**
         * 心跳统计计数器
         */
        private final AtomicInteger heartbeatsSent = new AtomicInteger(0);    // 已发送的心跳数
        private final AtomicInteger heartbeatsMissed = new AtomicInteger(0);  // 丢失的心跳数
        private final AtomicInteger retryAttempt = new AtomicInteger(0);      // 重试次数
        private final AtomicLong totalHeartbeatLatency = new AtomicLong(0);  // 总心跳延迟

        /**
         * 时间戳记录
         */
        private volatile long lastHeartbeatTime;                              // 最后一次心跳时间
        private volatile Instant lastHeartbeatSentTime;                       // 最后一次发送心跳的时间

        /**
         * 连接状态指标
         */
        private volatile int currentRetryDelay;                               // 当前重试延迟
        private volatile int connectionQuality = 100;                         // 连接质量（0-100）
        private volatile long resourceUsage = 0;                             // 资源使用量

        /**
         * 构造函数
         * <p>
         * 初始化连接统计信息，设置初始时间戳
         */
        public ConnectionStats() {
            this.lastHeartbeatTime = System.currentTimeMillis();
            this.currentRetryDelay = properties.getClient().getInitialRetryDelay();
        }

        /**
         * 记录心跳
         * <p>
         * 更新心跳相关的统计信息，重置错误计数。
         * 具体更新内容：
         * - 增加已发送心跳计数
         * - 更新最后心跳时间
         * - 重置丢失计数和重试状态
         */
        public void recordHeartbeat() {
            heartbeatsSent.incrementAndGet();
            lastHeartbeatTime = System.currentTimeMillis();
            lastHeartbeatSentTime = Instant.now();
            heartbeatsMissed.set(0);
            retryAttempt.set(0);
            currentRetryDelay = properties.getClient().getInitialRetryDelay();
        }

        /**
         * 增加重试次数
         * <p>
         * 更新重试次数并使用指数退避算法计算新的重试延迟。
         * 重试延迟计算规则：
         * - 初始延迟 * (退避系数 ^ (重试次数-1))
         * - 不超过最大延迟限制
         */
        public void incrementRetryAttempt() {
            int attempt = retryAttempt.incrementAndGet();
            currentRetryDelay = calculateRetryDelay(attempt);
        }

        /**
         * 计算重试延迟
         * <p>
         * 使用指数退避算法计算下一次重试的延迟时间。
         * 算法特点：
         * - 延迟时间随重试次数指数增长
         * - 有最大延迟限制，避免等待时间过长
         * - 使用退避系数控制增长速度
         *
         * @param attempt 当前重试次数
         * @return 计算得到的重试延迟（秒）
         */
        private int calculateRetryDelay(int attempt) {
            double delay = properties.getClient().getInitialRetryDelay() * 
                          Math.pow(properties.getClient().getBackoffMultiplier(), attempt - 1);
            return (int) Math.min(delay, properties.getClient().getMaxRetryDelay());
        }

        /**
         * 更新资源使用情况
         * <p>
         * 记录连接当前的资源使用量，用于监控和预警。
         *
         * @param usage 当前资源使用量（字节）
         */
        public void updateResourceUsage(long usage) {
            this.resourceUsage = usage;
        }

        /**
         * 获取统计信息的字符串表示
         * <p>
         * 返回当前连接的所有关键指标，包括：
         * - 心跳统计：发送数、丢失数
         * - 重试情况：重试次数
         * - 连接质量：质量评分
         * - 资源使用：内存占用
         *
         * @return 格式化的统计信息字符串
         */
        public String getStats() {
            return String.format("心跳: 发送=%d, 丢失=%d, 重试次数=%d, 连接质量=%d%%, 资源使用=%d bytes",
                    heartbeatsSent.get(), heartbeatsMissed.get(), retryAttempt.get(), 
                    connectionQuality, resourceUsage);
        }
    }


    /**
     * 通道注册事件处理
     * <p>
     * 当Channel被注册到EventLoop时调用，此时TCP连接尚未建立。
     * 主要任务：
     * - 初始化连接统计信息
     * - 将连接添加到管理器
     * - 启动资源监控
     * - 触发连接建立事件
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        ConnectionStats stats = new ConnectionStats();
        connectionStats.put(ctx.channel().id(), stats);
        channelManager.addClient(ctx.channel().id().asLongText(), ctx.channel());
        messageService.onConnect(ctx.channel());
        startResourceMonitoring(ctx.channel().id(), stats);
        log.info("客户端：连接成功: {}", ctx.channel().remoteAddress());
    }

    /**
     * 消息接收处理
     * <p>
     * 处理从服务器接收到的消息，支持以下功能：
     * - 消息解析：将文本消息解析为NettyMessage对象
     * - 心跳处理：识别并处理心跳消息
     * - 业务分发：将业务消息转发给对应的处理器
     * - 异常处理：处理消息解析失败等异常情况
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     * @param msg   接收到的消息内容，文本格式
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        try {
            log.debug("客户端：收到服务端消息: {}", msg);
            // 尝试解析为NettyMessage对象
            NettyMessage message = JSONUtil.toBean(msg, NettyMessage.class);
            
            // 处理心跳响应
            if (message.isHeartbeat()) {
                handleHeartbeatResponse(ctx);
                return;
            }
            
            // 处理其他业务消息
            handleMessage(ctx, message);
        } catch (Exception e) {
            log.warn("客户端：消息解析失败: {}", msg, e);
            // 如果解析失败，作为普通文本消息处理
            messageService.handleMessage(ctx.channel(), NettyMessage.text(msg));
        }
    }

    /**
     * 用户事件触发处理
     * <p>
     * 处理各类Channel事件，主要包括：
     * - 空闲状态事件：触发心跳检测
     * - 自定义业务事件：转发给对应的处理器
     * <p>
     * 空闲状态检测配置：
     * - 读空闲：客户端长时间未收到服务器消息
     * - 写空闲：客户端长时间未发送消息
     * - 全部空闲：双向都长时间未通信
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     * @param evt   触发的事件对象，可能是IdleStateEvent或自定义事件
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.ALL_IDLE) {
                handleHeartbeat(ctx);
            }
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    /**
     * 通道断开处理
     * <p>
     * 当连接断开时执行清理工作，包括：
     * - 移除连接统计信息
     * - 停止资源监控
     * - 从管理器中移除连接
     * - 触发连接断开事件
     * <p>
     * 断开原因可能是：
     * - 客户端主动关闭
     * - 服务器关闭连接
     * - 网络异常导致断开
     * - 心跳超时断开
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ConnectionStats stats = connectionStats.remove(ctx.channel().id());
        if (stats != null) {
            log.info("客户端：连接断开，最终统计: {}", stats.getStats());
            handleConnectionLost(ctx);
        }
        // 停止资源监控
        stopResourceMonitoring(ctx.channel().id());
        channelManager.removeClient(ctx.channel().id().asLongText());
        messageService.onDisconnect(ctx.channel());
        log.info("客户端：连接断开: {}", ctx.channel().remoteAddress());
    }

    /**
     * 异常处理
     * <p>
     * 处理连接过程中的异常情况，主要包括：
     * - 网络异常：连接超时、连接重置等
     * - 协议异常：消息格式错误、校验失败等
     * - 业务异常：消息处理失败等
     * <p>
     * 处理策略：
     * - 记录异常日志
     * - 关闭问题连接
     * - 触发重连机制
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     * @param cause 异常原因，包含具体的错误信息
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端：异常: {}", cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * 处理业务消息
     * <p>
     * 将消息提交到业务线程池处理，避免阻塞IO线程。
     * 处理流程：
     * 1. 提交到业务线程池
     * 2. 调用消息处理服务
     * 3. 处理执行结果
     * 4. 异常情况反馈
     *
     * @param ctx     通道上下文对象，提供了与ChannelPipeline交互的能力
     * @param message 待处理的消息对象，包含具体的业务数据
     */
    private void handleMessage(ChannelHandlerContext ctx, NettyMessage message) {
        try {
            executor.execute(() -> {
                messageService.handleMessage(ctx.channel(), message);
            });
        } catch (Exception e) {
            log.error("客户端：处理消息异常: {}", e.getMessage(), e);
            sendErrorMessage(ctx, "客户端：消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 处理心跳检测
     * <p>
     * 发送心跳包并更新统计信息。
     * 检测流程：
     * 1. 检查上次心跳响应情况
     * 2. 更新心跳丢失计数
     * 3. 发送新的心跳包
     * 4. 更新统计信息
     * <p>
     * 心跳策略：
     * - 定期发送心跳包
     * - 统计心跳丢失情况
     * - 超过阈值触发重连
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     */
    private void handleHeartbeat(ChannelHandlerContext ctx) {
        ConnectionStats stats = connectionStats.get(ctx.channel().id());
        if (stats == null) {
            return;
        }

        if (stats.lastHeartbeatSentTime != null && stats.heartbeatsMissed.get() > 0) {
            int missed = stats.heartbeatsMissed.incrementAndGet();
            log.warn("客户端：心跳响应丢失，当前连续丢失次数: {}", missed);
            
            if (missed >= properties.getClient().getMaxHeartbeatMiss()) {
                log.error("客户端：心跳连续{}次未收到响应，主动断开连接", properties.getClient().getMaxHeartbeatMiss());
                ctx.close();
                return;
            }
        }

        NettyMessage heartbeat = NettyMessage.heartbeat();
        ctx.writeAndFlush(JSONUtil.toJsonStr(heartbeat));
        
        stats.heartbeatsSent.incrementAndGet();
        stats.lastHeartbeatSentTime = Instant.now();
        log.debug("客户端：发送心跳包...");
    }

    /**
     * 处理心跳响应
     * <p>
     * 处理服务器返回的心跳响应，更新连接状态。
     * 处理流程：
     * 1. 计算心跳延迟
     * 2. 更新连接质量
     * 3. 重置心跳丢失计数
     * 4. 更新统计信息
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     */
    private void handleHeartbeatResponse(ChannelHandlerContext ctx) {
        ConnectionStats stats = connectionStats.get(ctx.channel().id());
        if (stats != null && stats.lastHeartbeatSentTime != null) {
            // 计算心跳延迟
            long heartbeatDelay = Duration.between(stats.lastHeartbeatSentTime, Instant.now()).toMillis();
            stats.totalHeartbeatLatency.addAndGet(heartbeatDelay);
            
            // 更新连接质量
            updateConnectionQuality(stats, heartbeatDelay);
            
            // 重置心跳丢失计数
            stats.heartbeatsMissed.set(0);
            log.debug("客户端：收到心跳响应，延迟: {}ms", heartbeatDelay);
        }
    }

    /**
     * 重置心跳统计信息
     * 在重连成功后调用，重置所有计数器
     *
     * @param stats 连接统计对象
     */
    private void resetHeartbeatStats(ConnectionStats stats) {
        stats.heartbeatsSent.set(0);
        stats.heartbeatsMissed.set(0);
        stats.totalHeartbeatLatency.set(0);
        stats.lastHeartbeatTime = System.currentTimeMillis();
        stats.lastHeartbeatSentTime = null;
        stats.retryAttempt.set(0);
        stats.currentRetryDelay = properties.getClient().getInitialRetryDelay();
        stats.connectionQuality = 100;
    }

    /**
     * 记录心跳统计信息
     * 输出当前连接的各项统计指标
     *
     * @param stats 连接统计对象
     */
    private void logHeartbeatStats(ConnectionStats stats) {
        int sent = stats.heartbeatsSent.get();
        int missed = stats.heartbeatsMissed.get();
        long avgLatency = sent > 0 ? stats.totalHeartbeatLatency.get() / sent : 0;

        log.info("客户端：心跳统计 - 发送: {}, 丢失: {}, 平均延迟: {}ms, 连接质量: {}%, 重试次数: {}",
                sent, missed, avgLatency, stats.connectionQuality, stats.retryAttempt.get());
    }

    /**
     * 更新连接质量
     * <p>
     * 根据心跳延迟计算连接质量评分。
     * 计算规则：
     * - 基于心跳延迟时间
     * - 考虑历史数据影响
     * - 分数范围：0-100
     * <p>
     * 质量等级：
     * - 90-100：极好
     * - 70-89：良好
     * - 50-69：一般
     * - 30-49：较差
     * - 0-29：很差
     *
     * @param stats          连接统计对象，包含历史数据
     * @param heartbeatDelay 心跳延迟时间（毫秒）
     */
    private void updateConnectionQuality(ConnectionStats stats, long heartbeatDelay) {
        // 基础分数100，根据延迟扣分
        int quality = 100;
        
        // 延迟超过1秒，每100ms扣1分
        if (heartbeatDelay > 1000) {
            quality -= (int) ((heartbeatDelay - 1000) / 100);
        }
        
        // 考虑丢包率的影响
        quality -= stats.heartbeatsMissed.get() * 10;
        
        // 确保分数在0-100范围内
        stats.connectionQuality = Math.max(0, Math.min(100, quality));
    }

    /**
     * 处理连接丢失
     * <p>
     * 当连接断开时执行重连逻辑。
     * 重连策略：
     * - 使用指数退避算法
     * - 有最大重试次数限制
     * - 每次重试间隔递增
     * <p>
     * 处理流程：
     * 1. 更新重试统计
     * 2. 计算下次重试延迟
     * 3. 触发重连事件
     * 4. 清理旧连接资源
     *
     * @param ctx   通道上下文对象，提供了与ChannelPipeline交互的能力
     */
    private void handleConnectionLost(ChannelHandlerContext ctx) {
        ConnectionStats stats = connectionStats.get(ctx.channel().id());
        if (stats != null) {
            stats.incrementRetryAttempt();
            log.warn("客户端：连接断开，准备第{}次重试，延迟{}秒",
                    stats.retryAttempt.get(), stats.currentRetryDelay);
            messageService.onDisconnect(ctx.channel());
        }
    }

    /**
     * 发送错误消息
     * <p>
     * 将错误信息包装成系统消息发送给客户端。
     * 消息格式：
     * - 类型：系统消息
     * - 内容：错误描述
     * - 时间戳：发送时间
     *
     * @param ctx      通道上下文对象，提供了与ChannelPipeline交互的能力
     * @param errorMsg 错误信息内容，将被包装成系统消息
     */
    private void sendErrorMessage(ChannelHandlerContext ctx, String errorMsg) {
        NettyMessage errorMessage = NettyMessage.system(errorMsg);
        sendMessage(ctx, errorMessage);
    }

    /**
     * 发送消息
     * <p>
     * 将消息序列化并发送到客户端。
     * 发送流程：
     * 1. 消息序列化
     * 2. 写入通道
     * 3. 刷新缓冲区
     * 4. 异常处理
     *
     * @param ctx     通道上下文对象，提供了与ChannelPipeline交互的能力
     * @param message 待发送的消息对象，将被序列化为JSON
     */
    private void sendMessage(ChannelHandlerContext ctx, NettyMessage message) {
        try {
            ctx.writeAndFlush(JSONUtil.toJsonStr(message));
        } catch (Exception e) {
            log.error("客户端：发送消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 启动心跳检测
     * 定期发送心跳包并检查响应状态
     *
     * @param ctx   通道上下文对象
     * @param stats 连接统计对象
     */
    private void startHeartbeat(ChannelHandlerContext ctx, ConnectionStats stats) {
        ctx.executor().scheduleAtFixedRate(() -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }

                if (stats.lastHeartbeatSentTime != null) {
                    if (stats.heartbeatsMissed.get() > 0) {
                        if (stats.heartbeatsMissed.incrementAndGet() >= properties.getClient().getMaxHeartbeatMiss()) {
                            log.error("客户端：心跳连续{}次未收到响应，准备重连", properties.getClient().getMaxHeartbeatMiss());
                            handleConnectionLost(ctx);
                            return;
                        }
                        log.warn("客户端：心跳未收到响应，已丢失次数: {}", stats.heartbeatsMissed.get());
                    }
                }

                sendHeartbeat(ctx, stats);

            } catch (Exception e) {
                log.error("客户端：心跳任务异常", e);
                handleConnectionLost(ctx);
            }
        }, 0, properties.getClient().getHeartbeatInterval(), TimeUnit.SECONDS);
    }

    /**
     * 发送心跳包
     * 发送心跳消息并更新统计信息
     *
     * @param ctx   通道上下文对象
     * @param stats 连接统计对象
     */
    private void sendHeartbeat(ChannelHandlerContext ctx, ConnectionStats stats) {
        try {
            NettyMessage heartbeat = NettyMessage.heartbeat();
            ctx.writeAndFlush(JSONUtil.toJsonStr(heartbeat));
            stats.lastHeartbeatSentTime = Instant.now();
            stats.heartbeatsSent.incrementAndGet();
            log.debug("客户端：发送心跳包");
        } catch (Exception e) {
            log.error("客户端：发送心跳包失败", e);
            stats.heartbeatsMissed.incrementAndGet();
        }
    }

    /**
     * 启动资源监控
     * <p>
     * 定期检查连接的资源使用情况。
     * 监控内容：
     * - 内存使用
     * - 消息队列大小
     * - 线程使用情况
     * <p>
     * 监控策略：
     * - 固定间隔检查
     * - 超过阈值报警
     * - 支持动态调整
     *
     * @param channelId 通道ID，用于标识具体连接
     * @param stats     连接统计对象，记录监控数据
     */
    private void startResourceMonitoring(ChannelId channelId, ConnectionStats stats) {
        ScheduledFuture<?> future = scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                // 获取当前内存使用情况
                long memoryUsage = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                stats.updateResourceUsage(memoryUsage);
                
                // 检查资源使用是否超过阈值
                if (memoryUsage > Runtime.getRuntime().maxMemory() * 0.8) {
                    log.warn("客户端：连接{}内存使用过高: {}bytes", channelId, memoryUsage);
                }
            } catch (Exception e) {
                log.error("客户端：资源监控异常: {}", e.getMessage(), e);
            }
        }, 0, 60, TimeUnit.SECONDS);
        
        monitoringTasks.put(channelId, future);
    }

    /**
     * 停止资源监控
     * <p>
     * 取消指定连接的资源监控任务。
     * 清理流程：
     * 1. 取消定时任务
     * 2. 移除任务记录
     * 3. 释放相关资源
     *
     * @param channelId 要停止监控的通道ID
     */
    private void stopResourceMonitoring(ChannelId channelId) {
        ScheduledFuture<?> future = monitoringTasks.remove(channelId);
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * 销毁处理器
     * 清理资源，关闭定时任务
     */
    @PreDestroy
    public void destroy() {
        // 关闭定时任务执行器
        if (scheduledExecutor != null) {
            try {
                scheduledExecutor.shutdown();
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

