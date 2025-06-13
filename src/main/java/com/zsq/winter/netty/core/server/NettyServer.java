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
import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket服务器启动器（集成Spring生命周期）
 */
@Slf4j
public class NettyServer {

    // WebSocket属性配置类（端口、线程数、路径等）
    private final NettyProperties properties;
    // Channel初始化器，注入WebSocket相关的业务处理器
    private final NettyServerChannelInitializer channelInitializer;
    // 异步线程池（用于启动Netty，避免阻塞主线程）
    private final ThreadPoolTaskExecutor winterNettyServerTaskExecutor;

    // Boss线程组（用于处理客户端连接请求）
    private EventLoopGroup bossGroup;
    // Worker线程组（用于处理连接的I/O事件）
    private EventLoopGroup workerGroup;
    // Channel绑定结果的异步控制对象
    private ChannelFuture channelFuture;

    public NettyServer(NettyProperties properties,
                       NettyServerChannelInitializer channelInitializer,
                       ThreadPoolTaskExecutor winterNettyServerTaskExecutor) {
        this.properties = properties;
        this.channelInitializer = channelInitializer;
        this.winterNettyServerTaskExecutor = winterNettyServerTaskExecutor;
    }
    /**
     * Spring容器启动后自动调用，用于异步启动 Netty WebSocket 服务。
     * <p>
     * 使用线程池 {@link ThreadPoolTaskExecutor} 异步执行 Netty 启动逻辑的原因如下：
     * <ul>
     *   <li><b>避免阻塞 Spring 启动流程：</b> {@code doStart()} 方法内部使用 {@code channelFuture.channel().closeFuture().sync()} 会阻塞当前线程，
     *       若在主线程执行将导致 Spring Boot 启动流程被“卡住”，无法正常完成应用初始化。</li>
     *   <li><b>Netty 是阻塞模型：</b> Netty Server 需要通过 {@code sync()} 保持服务常驻运行，若不在新线程中启动，会直接阻塞 Spring 启动线程。</li>
     *   <li><b>线程隔离与资源控制：</b> 将 Netty 启动逻辑交由自定义线程池执行，可灵活配置核心线程数、异常处理策略、队列大小等，增强系统资源管理能力。</li>
     *   <li><b>提高服务健壮性：</b> Netty 运行过程中若发生异常，可在线程池中捕获并处理，避免影响整个 Spring Boot 主线程，提高容错性和可维护性。</li>
     * </ul>
     */
    @PostConstruct
    public void start() {
        winterNettyServerTaskExecutor.execute(this::doStart);
    }


    /**
     * 启动Netty服务
     */
    private void doStart() {
        int bossThreads = properties.getServer().getBossThreads();
        int workerThreads = properties.getServer().getWorkerThreads() == 0 ?
                Runtime.getRuntime().availableProcessors() * 2 : properties.getServer().getWorkerThreads();

        // 初始化线程组
        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);
        // 创建并启动ServerBootstrap服务端
        try {
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

            // 异步绑定端口并返回一个 ChannelFuture 对象，并不意味着服务会一直运行下去。一旦 bind() 完成，主线程如果没有其他操作，就会继续往下执行，最终退出整个方法，甚至 JVM 进程
            channelFuture = bootstrap.bind(properties.getServer().getPort()).sync();

            log.info("Netty WebSocket服务器启动成功");
            log.info("服务地址: {}://localhost:{}{}",
                    properties.getServer().isSslEnabled() ? "wss" : "ws",
                    properties.getServer().getPort(),
                    properties.getServer().getPath());
            log.info("Boss线程数: {}, Worker线程数: {}", bossThreads, workerThreads);

            /*
             * bootstrap.bind(properties.getPort()).sync();异步绑定端口并返回一个 ChannelFuture 对象，并不意味着服务会一直运行下去。一旦 bind() 完成，主线程如果没有其他操作，就会继续往下执行，最终退出整个方法，甚至 JVM 进程
             * 所以你需要一种方式让线程“卡住”，防止程序提前退出 阻塞调用,所以需要使用channelFuture.channel().closeFuture().sync();
             * 保持Netty服务一直运行，不让启动方法结束，保证主线程或启动线程不退出，服务持续对外提供连接和处理能力。
             * */
            channelFuture.channel().closeFuture().sync();

        } catch (InterruptedException e) {
            log.error("Netty WebSocket服务器启动失败", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Netty服务器发生异常", e);
        } finally {
            shutdown();
        }
    }

    /**
     * 优雅关闭Netty服务，释放资源
     * 在Spring容器关闭前调用，确保正确释放所有Netty相关资源
     */
    @PreDestroy
    public void shutdown() {
        log.info("正在关闭 Netty WebSocket 服务器...");

        // 关闭 ServerChannel（如果已激活）
        try {
            if (!ObjectUtils.isEmpty(channelFuture) && channelFuture.channel().isActive()) {
                channelFuture.channel().close().sync();
                log.debug("ServerChannel 已关闭");
            }
        } catch (Exception e) {
            log.warn("关闭 ServerChannel 时发生异常", e);
        }

        // 关闭 EventLoopGroup 线程组
        try {
            if (!ObjectUtils.isEmpty(workerGroup)) {
                workerGroup.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
                log.debug("WorkerGroup 线程组已关闭");
            }
        } catch (InterruptedException e) {
            log.warn("WorkerGroup 关闭被中断", e);
            Thread.currentThread().interrupt();
        }

        try {
            if (!ObjectUtils.isEmpty(bossGroup)) {
                bossGroup.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
                log.debug("BossGroup 线程组已关闭");
            }
        } catch (InterruptedException e) {
            log.warn("BossGroup 关闭被中断", e);
            Thread.currentThread().interrupt();
        }

        log.info("Netty WebSocket 服务器已关闭");
    }


    /**
     * 判断服务器是否仍在运行
     */
    public boolean isRunning() {
        return channelFuture != null && channelFuture.channel().isActive();
    }

    /**
     * 获取服务器端口
     */
    public int getPort() {
        return properties.getServer().getPort();
    }
}
