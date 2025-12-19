package com.zsq.winter.netty.core.client;

import com.zsq.winter.netty.autoconfigure.NettyProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty客户端类
 * 负责管理与服务器的TCP连接、消息发送、断线重连等功能
 */
@Slf4j
public class NettyClient {

    /**
     * Netty客户端配置属性
     */
    private final NettyProperties properties;

    /**
     * 客户端Channel初始化器，用于设置编解码器和处理器
     */
    private final NettyClientChannelInitializer initializer;

    /**
     * 客户端专用线程池，用于执行异步任务
     */
    private final ThreadPoolTaskExecutor winterNettyClientTaskExecutor;

    /**
     * Netty的事件循环组，用于处理I/O操作
     */
    private EventLoopGroup group;

    /**
     * Netty的客户端启动引导类
     */
    private Bootstrap bootstrap;

    /**
     * 与服务器建立的channel连接
     */
    private Channel channel;

    /**
     * 客户端运行状态标识
     * 使用AtomicBoolean保证多线程下的可见性和原子性操作
     * true 表示客户端正在运行且连接有效
     * false 表示客户端未启动或连接已断开
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * 客户端关闭状态标识
     * true 表示客户端正在进行关闭操作
     * false 表示客户端正常运行中
     */
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);


    /**
     * 构造函数
     *
     * @param properties                    Netty配置属性
     * @param initializer                   通道初始化器
     * @param winterNettyClientTaskExecutor 线程池执行器
     */
    public NettyClient(NettyProperties properties,
                       NettyClientChannelInitializer initializer,
                       ThreadPoolTaskExecutor winterNettyClientTaskExecutor) {
        this.properties = properties;
        this.initializer = initializer;
        this.winterNettyClientTaskExecutor = winterNettyClientTaskExecutor;
    }

    /**
     * 启动Netty客户端
     * Spring容器启动时自动调用
     */
    @PostConstruct
    public void start() {
        if (isRunning.get()) {
            log.warn("客户端：已经在运行中");
            return;
        }
        winterNettyClientTaskExecutor.execute(this::doStart);
    }

    /**
     * 执行客户端启动的具体逻辑
     */
    private void doStart() {
        try {
            initializeBootstrap();
            connect();
        } catch (Exception e) {
            log.error("客户端：启动Netty失败", e);
            shutdown();
        }
    }

    /**
     * 初始化Netty的Bootstrap配置
     */
    private void initializeBootstrap() {
        group = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)  // 启用TCP心跳机制
                .option(ChannelOption.TCP_NODELAY, true)   // 禁用Nagle算法，减少延迟
                .handler(initializer);
    }

    /**
     * 连接服务器
     */
    private void connect() {
        // 检查系统是否正在关闭如果是，则不执行连接操作
        if (isShuttingDown.get()) {
            return;
        }

        try {
            // 使用bootstrap尝试连接服务器
            ChannelFuture future = bootstrap.connect(
                    properties.getClient().getHost(),
                    properties.getClient().getPort()
            );

            // 添加监听器以处理连接完成后的操作
            future.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    // 如果连接成功，记录日志并更新通道状态
                    log.info("客户端：连接服务器成功: {}:{}",
                            properties.getClient().getHost(),
                            properties.getClient().getPort());
                    channel = f.channel();
                    isRunning.set(true);
                } else {
                    // 如果连接失败，记录警告日志
                    log.warn("客户端：连接服务器失败");
                }
            });

        } catch (Exception e) {
            // 捕获并记录连接过程中发生的异常
            log.error("客户端：连接服务器时发生异常", e);
        }
    }
    /**
     * 关闭Netty客户端
     * Spring容器销毁时自动调用
     */
    @PreDestroy
    public void shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            log.info("客户端：正在关闭...");
            isRunning.set(false);

            // 关闭当前channel
            if (channel != null && channel.isActive()) {
                try {
                    channel.close().sync();
                    log.debug("客户端：Channel已关闭");
                } catch (InterruptedException e) {
                    log.warn("客户端：关闭channel时被中断", e);
                    Thread.currentThread().interrupt();
                }
            }

            // 关闭线程组
            if (group != null && !group.isShuttingDown()) {
                try {
                    group.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
                    log.debug("客户端：EventLoopGroup已关闭");
                } catch (InterruptedException e) {
                    log.warn("客户端：关闭EventLoopGroup时被中断", e);
                    Thread.currentThread().interrupt();
                }
            }

            log.info("客户端：Netty已关闭");
        }
    }

    /**
     * 获取客户端运行状态
     *
     * @return true表示客户端正在运行且连接有效，false表示客户端未运行或连接已断开
     */
    public boolean isRunning() {
        return isRunning.get() && isChannelActive();
    }

    /**
     * 检查channel是否处于活动状态
     *
     * @return true表示channel存在且活动，false表示channel不存在或已断开
     */
    private boolean isChannelActive() {
        return channel != null && channel.isActive();
    }

    /**
     * 获取远程服务器地址
     *
     * @return 格式为"host:port"的服务器地址字符串
     */
    public String getRemoteAddress() {
        return String.format("%s:%d",
                properties.getClient().getHost(),
                properties.getClient().getPort());
    }
}
