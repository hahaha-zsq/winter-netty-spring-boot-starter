package com.zsq.winter.netty.core.client;

import com.zsq.winter.netty.autoconfigure.NettyProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

public class NettyClientChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyClientHandler nettyClientHandler;
    private final NettyProperties properties;

    public NettyClientChannelInitializer(NettyClientHandler nettyClientHandler, NettyProperties properties) {
        this.nettyClientHandler = nettyClientHandler;
        this.properties = properties;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // 空闲检测（例如读写空闲 30 秒）
        pipeline.addLast(new IdleStateHandler(0, 0, 30, TimeUnit.SECONDS));

        // 判断是否启用 TCP over TLS
        if (properties.getClient().isSslEnabled()) {
            try {
                // 创建 SSL 上下文
                SslContext sslCtx = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();

                // 添加 SSL 处理器
                pipeline.addLast(sslCtx.newHandler(ch.alloc()));
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize SSL context", e);
            }
        }

        // 自定义业务处理
        pipeline.addLast(nettyClientHandler);
    }
}