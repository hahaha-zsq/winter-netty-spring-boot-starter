package com.zsq.winter.netty.core.server;

import com.zsq.winter.netty.autoconfigure.NettyProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ObjectUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty WebSocket服务器
 * <p>
 * 该类是整个WebSocket服务器的核心类，负责以下功能：
 * 1. 服务器的启动和关闭：包括优雅启动、自动重试和优雅关闭
 * 2. 管理服务器线程组：包括Boss线程组（接收连接）和Worker线程组（处理IO）
 * 3. 配置服务器启动参数：包括端口、线程数、TCP参数等
 * 4. 集成Spring生命周期：通过@PostConstruct和@PreDestroy注解
 * 5. 服务器异常重试机制：支持可配置的重试策略
 * <p>
 * 核心组件说明：
 * - Boss线程组：负责接收客户端的连接请求，类似于传统BIO中的接收线程
 * - Worker线程组：负责处理已连接客户端的数据读写，用于处理IO事件
 * - ChannelFuture：Netty中的异步操作结果占位符，用于异步操作的结果获取
 * - ServerBootstrap：服务器启动引导类，用于配置服务器参数并启动服务
 * <p>
 * 重试机制说明：
 * - 支持配置最大重试次数
 * - 支持配置重试间隔时间
 * - 支持配置重试时间的指数增长
 * - 支持配置触发重试的异常类型
 */
@Slf4j
public class NettyServer {

    /**
     * WebSocket服务器配置属性
     * 包含以下核心配置：
     * - 服务器端口
     * - Boss和Worker线程数
     * - 重试策略配置
     * - SSL配置
     * - WebSocket路径配置
     */
    private final NettyProperties properties;

    /**
     * 通道初始化器
     * 用于配置新建立的连接的处理器链（Pipeline），包括：
     * - 编解码器
     * - 心跳检测
     * - 业务处理器
     * - 异常处理器
     */
    private final NettyServerChannelInitializer channelInitializer;

    /**
     * 异步任务执行器
     * 用于在独立线程中启动Netty服务器，避免阻塞Spring主线程
     * 同时也可用于处理其他异步任务
     */
    private final ThreadPoolTaskExecutor winterNettyServerTaskExecutor;

    /**
     * Boss线程组
     * 用于接收客户端连接请求的线程组
     * 线程数通过配置指定，默认为1
     */
    private EventLoopGroup bossGroup;

    /**
     * Worker线程组
     * 用于处理IO事件的线程组
     * 线程数通过配置指定，默认为CPU核心数*2
     */
    private EventLoopGroup workerGroup;

    /**
     * 服务器Channel的异步操作结果
     * 用于：
     * 1. 等待服务器关闭
     * 2. 获取服务器运行状态
     * 3. 主动关闭服务器
     */
    private ChannelFuture channelFuture;

    /**
     * 服务器关闭标志
     * 使用AtomicBoolean保证线程安全
     * true表示服务器正在关闭，用于：
     * 1. 防止服务关闭过程中继续尝试重试
     * 2. 确保优雅关闭
     */
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    /**
     * 服务器启动Future
     * 用于异步等待服务器启动完成
     * 可以通过该Future：
     * 1. 判断服务器是否启动成功
     * 2. 获取启动过程中的异常
     * 3. 注册启动完成的回调函数
     */
    private final CompletableFuture<Void> startupFuture;

    /**
     * 构造函数
     *
     * @param properties                    WebSocket服务器配置属性
     * @param channelInitializer            通道初始化器
     * @param winterNettyServerTaskExecutor 异步任务执行器
     */
    public NettyServer(NettyProperties properties,
                       NettyServerChannelInitializer channelInitializer,
                       ThreadPoolTaskExecutor winterNettyServerTaskExecutor) {
        this.properties = properties;
        this.channelInitializer = channelInitializer;
        this.winterNettyServerTaskExecutor = winterNettyServerTaskExecutor;
        this.startupFuture = new CompletableFuture<>();
    }

    /**
     * Spring容器启动后自动调用，用于异步启动 Netty WebSocket 服务。
     * <p>
     * 使用线程池 {@link ThreadPoolTaskExecutor} 异步执行 Netty 启动逻辑的原因如下：
     * <ul>
     *   <li><b>避免阻塞 Spring 启动流程：</b> {@code doStart()} 方法内部使用 {@code channelFuture.channel().closeFuture().sync()} 会阻塞当前线程，
     *       若在主线程执行将导致 Spring Boot 启动流程被"卡住"，无法正常完成应用初始化。</li>
     *   <li><b>Netty 是阻塞模型：</b> Netty Server 需要通过 {@code sync()} 保持服务常驻运行，若不在新线程中启动，会直接阻塞 Spring 启动线程。</li>
     *   <li><b>线程隔离与资源控制：</b> 将 Netty 启动逻辑交由自定义线程池执行，可灵活配置核心线程数、异常处理策略、队列大小等，增强系统资源管理能力。</li>
     *   <li><b>提高服务健壮性：</b> Netty 运行过程中若发生异常，可在线程池中捕获并处理，避免影响整个 Spring Boot 主线程，提高容错性和可维护性。</li>
     * </ul>
     */
    @PostConstruct
    public void start() {
        winterNettyServerTaskExecutor.execute(() -> {
            try {
                startServer();
            } catch (Exception e) {
                handleStartupFailure(e);
            }
        });
    }

    /**
     * 服务器启动的入口方法
     * 负责调用具体的启动逻辑，并处理启动过程中的异常
     */
    private void startServer() throws InterruptedException {
        try {
            if (isShuttingDown.get()) {
                log.info("服务端：服务器正在关闭，取消启动尝试");
                return;
            }

            initializeServer();
            // 手动表示任务成功完成
            startupFuture.complete(null);
            // 等待服务器关闭
            channelFuture.channel().closeFuture().sync();

        } catch (Exception e) {
            log.error("服务端：服务器启动失败", e);
            startupFuture.completeExceptionally(e);
            throw e;
        } finally {
            shutdownResources();
        }
    }

    /**
     * 初始化服务器
     * 包括：
     * 1. 创建并配置线程组
     * 2. 配置ServerBootstrap
     * 3. 绑定端口
     * 4. 输出启动日志
     *
     * @throws InterruptedException 当线程被中断时抛出
     */
    private void initializeServer() throws InterruptedException {
        // 配置线程组大小
        int bossThreads = properties.getServer().getBossThreads();
        int workerThreads = properties.getServer().getWorkerThreads() == 0 ?
                Runtime.getRuntime().availableProcessors() * 2 : properties.getServer().getWorkerThreads();

        // 初始化线程组
        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);

        // 创建服务器启动引导类
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                /*
                    Bootstrap.handler(...)	设置客户端连接的业务处理器链（如编码、解码、业务逻辑）
                    ServerBootstrap.handler(...)  设置服务端监听端口的处理器，如日志、全局钩子等
                    ServerBootstrap.childHandler(...) 是 ServerBootstrap 特有的方法，用于设置每一个 新连接（子 Channel） 的处理器链。每当有新的客户端连接进来，Netty 都会创建一个新的子 Channel，并应用这个 childHandler。
                 */
                .childHandler(channelInitializer) // 	作用于 SocketChannel（每个客户端连接） 设置子通道处理器（核心业务逻辑配置）
                /*
                 * option()用于设置 服务端 ServerChannel（即监听端口的主通道） 的参数。它影响的是服务端的主监听通道
                 * childOption()用于设置 子通道 Channel（即每一个客户端连接建立后的SocketChannel） 的参数。它影响的是和客户端之间通信的具体连接通道。
                 * */
                .option(ChannelOption.SO_BACKLOG, 1024) // TCP连接请求队列长度，内核维护的等待完成三次握手的连接队列大小。队列满后新连接会被拒绝。设置1024提高连接并发能力。
                .option(ChannelOption.SO_REUSEADDR, true) // 允许地址复用，保证重启服务时，端口能够立即被绑定，避免"端口被占用"错误。
                .childOption(ChannelOption.SO_KEEPALIVE, true) // 启用TCP的Keep-Alive机制，保持连接长时间不活动时，检测连接状态，防止死连接。
                .childOption(ChannelOption.TCP_NODELAY, true) // 禁用Nagle算法，提升小包实时性。Nagle算法是合并小包发送，禁用后减少延迟，适合对延迟敏感的应用。
                .childOption(ChannelOption.SO_RCVBUF, 32 * 1024) // 设置接收缓冲区大小，提升接收效率，防止数据丢失，32KB大小是比较常见的调整。
                .childOption(ChannelOption.SO_SNDBUF, 32 * 1024); // 设置发送缓冲区大小，提升发送效率。

        // 绑定端口并启动服务器
        channelFuture = bootstrap.bind(properties.getServer().getPort()).sync();

        // 输出启动成功日志
        logServerStartup(bossThreads, workerThreads);
    }

    /**
     * 输出服务器启动成功的日志信息
     * 包括：
     * 1. 服务器地址（ws/wss）
     * 2. 端口号
     * 3. WebSocket路径
     * 4. 线程配置信息
     *
     * @param bossThreads   Boss线程组线程数
     * @param workerThreads Worker线程组线程数
     */
    private void logServerStartup(int bossThreads, int workerThreads) {
        log.info("服务端：服务器启动成功");
        log.info("服务端：服务地址: {}://localhost:{}{}",
                properties.getServer().isSslEnabled() ? "wss" : "ws",
                properties.getServer().getPort(),
                properties.getServer().getPath());
        log.info("服务端：Boss线程数: {}, Worker线程数: {}", bossThreads, workerThreads);
    }

    /**
     * 处理启动失败的情况
     * 包括：
     * 1. 记录错误日志
     * 2. 完成startupFuture（异常完成）
     * 3. 清理资源
     *
     * @param e 启动过程中的异常
     */
    private void handleStartupFailure(Exception e) {
        log.error("服务端：服务器启动过程中发生致命错误", e);
        startupFuture.completeExceptionally(e);
        shutdownResources();
    }

    /**
     * 清理服务器资源
     * 包括：
     * 1. 关闭服务器Channel
     * 2. 关闭Worker线程组
     * 3. 关闭Boss线程组
     * <p>
     * 每个步骤都有优雅关闭的超时时间，避免资源清理过程阻塞太久
     */
    private void shutdownResources() {
        if (channelFuture != null && channelFuture.channel().isActive()) {
            try {
                channelFuture.channel().close().sync();
                log.debug("服务端：ServerChannel 已关闭");
            } catch (Exception e) {
                log.warn("服务端：关闭 ServerChannel 时发生异常", e);
            }
        }

        if (workerGroup != null) {
            try {
                workerGroup.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
                log.debug("服务端：WorkerGroup 线程组已关闭");
            } catch (InterruptedException e) {
                log.warn("服务端：WorkerGroup 关闭被中断", e);
                Thread.currentThread().interrupt();
            }
        }

        if (bossGroup != null) {
            try {
                bossGroup.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
                log.debug("服务端：BossGroup 线程组已关闭");
            } catch (InterruptedException e) {
                log.warn("服务端：BossGroup 关闭被中断", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Spring容器关闭时自动调用，用于优雅关闭服务器
     * 设置关闭标志并清理资源
     */
    @PreDestroy
    public void shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            log.info("服务端：正在关闭服务器...");
            shutdownResources();
            log.info("服务端：服务器已关闭");
        }
    }

    /**
     * 判断服务器是否正在运行
     *
     * @return true表示服务器正在运行，false表示服务器已停止
     */
    public boolean isRunning() {
        return channelFuture != null && channelFuture.channel().isActive();
    }

    /**
     * 获取服务器监听的端口号
     *
     * @return 服务器端口号
     */
    public int getPort() {
        return properties.getServer().getPort();
    }

    /**
     * 获取服务器启动Future
     * 可用于：
     * 1. 等待服务器启动完成
     * 2. 添加启动完成后的回调
     * 3. 获取启动过程中的异常
     *
     * @return 启动完成的Future
     */
    public CompletableFuture<Void> getStartupFuture() {
        return startupFuture;
    }
}
