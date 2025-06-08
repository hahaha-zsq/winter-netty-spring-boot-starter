package com.zsq.winter.netty.core;

import com.zsq.winter.netty.autoconfigure.WebSocketProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket服务器启动器（集成Spring生命周期）
 */
@Slf4j
public class NettyWebSocketServer {

    // WebSocket属性配置类（端口、线程数、路径等）
    private final WebSocketProperties properties;
    // Channel初始化器，注入WebSocket相关的业务处理器
    private final WebSocketChannelInitializer channelInitializer;
    // 异步线程池（用于启动Netty，避免阻塞主线程）
    private final ThreadPoolTaskExecutor winterNettyTaskExecutor;

    // Boss线程组（用于处理客户端连接请求）
    private EventLoopGroup bossGroup;
    // Worker线程组（用于处理连接的I/O事件）
    private EventLoopGroup workerGroup;
    // Channel绑定结果的异步控制对象
    private ChannelFuture channelFuture;

    public NettyWebSocketServer(WebSocketProperties properties,
                                WebSocketChannelInitializer channelInitializer,
                                ThreadPoolTaskExecutor winterNettyTaskExecutor) {
        this.properties = properties;
        this.channelInitializer = channelInitializer;
        this.winterNettyTaskExecutor = winterNettyTaskExecutor;
    }

    /**
     * Spring容器启动后，自动调用，异步启动Netty服务
     */
    @PostConstruct
    public void start() {
        winterNettyTaskExecutor.execute(this::doStart);
    }

    /**
     * 启动Netty服务
     */
    private void doStart() {
        int bossThreads = properties.getBossThreads();
        int workerThreads = properties.getWorkerThreads() == 0 ?
                Runtime.getRuntime().availableProcessors() * 2 : properties.getWorkerThreads();

        // 初始化线程组
        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(channelInitializer) // 设置子通道处理器（核心业务逻辑配置）
                    .option(ChannelOption.SO_BACKLOG, 1024) // 连接队列长度
                    .option(ChannelOption.SO_REUSEADDR, true) // 允许地址重用
                    .childOption(ChannelOption.SO_KEEPALIVE, true) // 保持长连接
                    .childOption(ChannelOption.TCP_NODELAY, true) // 禁用Nagle算法，提升实时性
                    .childOption(ChannelOption.SO_RCVBUF, 32 * 1024) // 接收缓冲区
                    .childOption(ChannelOption.SO_SNDBUF, 32 * 1024); // 发送缓冲区

            // 绑定端口并启动
            channelFuture = bootstrap.bind(properties.getPort()).sync();

            log.info("Netty WebSocket服务器启动成功");
            log.info("服务地址: {}://localhost:{}{}",
                    properties.isSslEnabled() ? "wss" : "ws",
                    properties.getPort(),
                    properties.getPath());
            log.info("Boss线程数: {}, Worker线程数: {}", bossThreads, workerThreads);

            // 阻塞等待服务关闭
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
     */
    @PreDestroy
    public void shutdown() {
        log.info("正在关闭Netty WebSocket服务器...");

        try {
            if (channelFuture != null && channelFuture.channel().isOpen()) {
                channelFuture.channel().close();
            }
        } catch (Exception e) {
            log.warn("关闭channel时发生异常", e);
        }

        try {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
            }
        } catch (InterruptedException e) {
            log.warn("线程池关闭被中断", e);
            Thread.currentThread().interrupt();
        }

        log.info("Netty WebSocket服务器已关闭");
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
        return properties.getPort();
    }
}
