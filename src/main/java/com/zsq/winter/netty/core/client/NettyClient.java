package com.zsq.winter.netty.core.client;

import com.zsq.winter.netty.autoconfigure.NettyProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ObjectUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NettyClient {

    private final NettyProperties properties;
    private final NettyClientChannelInitializer initializer;
    private final ThreadPoolTaskExecutor winterNettyClientTaskExecutor;

    private EventLoopGroup group;
    private ChannelFuture channelFuture;

    public NettyClient(NettyProperties properties,
                       NettyClientChannelInitializer initializer,
                       ThreadPoolTaskExecutor winterNettyClientTaskExecutor) {
        this.properties = properties;
        this.initializer = initializer;
        this.winterNettyClientTaskExecutor = winterNettyClientTaskExecutor;
    }

    @PostConstruct
    public void start() {
        winterNettyClientTaskExecutor.execute(this::doStart);
    }

    private void doStart() {
        String host = properties.getClient().getHost();
        int port = properties.getClient().getPort();

        group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();

        try {
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(initializer);

            log.info("正在连接 TCP 服务端：{}:{}", host, port);

            channelFuture = bootstrap.connect(host, port).sync();
            channelFuture.channel().closeFuture().sync();

        } catch (InterruptedException e) {
            log.error("Netty TCP 客户端启动失败", e);
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
    }


    public void sendMessage(String message) {
        if (channelFuture != null && channelFuture.channel().isActive()) {
            channelFuture.channel().writeAndFlush(message);
        } else {
            log.warn("TCP 连接未建立，无法发送消息");
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭 Netty TCP 客户端...");

        if (!ObjectUtils.isEmpty(channelFuture) && channelFuture.channel().isActive()) {
            try {
                channelFuture.channel().close().sync();
                log.debug("ClientChannel 已关闭");
            } catch (Exception e) {
                log.warn("关闭 ClientChannel 异常", e);
            }
        }

        if (!ObjectUtils.isEmpty(group)) {
            try {
                group.shutdownGracefully(5, 10, TimeUnit.SECONDS).sync();
                log.debug("Client EventLoopGroup 线程组已关闭");
            } catch (InterruptedException e) {
                log.warn("线程组关闭被中断", e);
                Thread.currentThread().interrupt();
            }
        }

        log.info("Netty TCP 客户端已关闭");
    }

    public boolean isRunning() {
        return channelFuture != null && channelFuture.channel().isActive();
    }

    public String getRemoteAddress() {
        return properties.getClient().getHost() + ":" + properties.getClient().getPort();
    }
}
