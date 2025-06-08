package com.zsq.winter.netty.core;


import com.zsq.winter.netty.autoconfigure.WebSocketProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket管道初始化器
 * 负责初始化SocketChannel的管道，配置SSL、HTTP和WebSocket处理器
 * 	•	用于初始化每个客户端连接的 SocketChannel
 * 	•	会被 Netty 的 ServerBootstrap.childHandler(...) 所引用
 * 	•	每个连接独立创建一个 ChannelPipeline
 */
@Slf4j
public class WebSocketChannelInitializer extends ChannelInitializer<SocketChannel> {


    /**
     * WebSocket属性配置
     */
    private final WebSocketProperties properties;

    /**
     * WebSocket消息处理器，你自定义的处理 WebSocket 消息的业务逻辑类（必须继承 ChannelInboundHandlerAdapter 或类似）
     */
    private final WebSocketHandler webSocketHandler;

    /**
     * 构造函数
     *
     * @param properties WebSocket属性配置
     * @param webSocketHandler WebSocket消息处理器
     */
    public WebSocketChannelInitializer(WebSocketProperties properties, WebSocketHandler webSocketHandler) {
        this.properties = properties;
        this.webSocketHandler = webSocketHandler;
    }


    /**
     * SSL上下文，用于配置SSL处理器
     */
    private SslContext sslContext;

    /**
     * 初始化方法，主要用于初始化SSL上下文
     * 在Spring容器中，该方法会在依赖注入完成后调用
     * 	•	启动时自动调用 init() 初始化 SSL 上下文
     * 	•	支持自定义证书或开发时使用自签名证书
     * 	•	如果配置启用 SSL，会在 pipeline 中加上 sslContext.newHandler(...)
     * @throws Exception 初始化SSL上下文时可能抛出的异常
     */
    @PostConstruct
    public void init() throws Exception {
        // 初始化SSL上下文
        if (properties.isSslEnabled()) {
            initSslContext();
        }
    }

    /**
     * 初始化SSL上下文
     * 如果配置了证书路径和密钥路径，则使用自定义证书
     * 否则，生成自签名证书（仅用于开发测试）
     *
     * @throws Exception 初始化SSL上下文时可能抛出的异常
     */
    private void initSslContext() throws Exception {
        if (properties.getSslCertPath() != null && properties.getSslKeyPath() != null) {
            // 使用自定义证书
            File certFile = new File(properties.getSslCertPath());
            File keyFile = new File(properties.getSslKeyPath());
            sslContext = SslContextBuilder.forServer(certFile, keyFile).build();
            log.info("使用自定义SSL证书: {}", properties.getSslCertPath());
        } else {
            // 使用自签名证书（仅用于开发测试）
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
            log.warn("使用自签名SSL证书，仅用于开发测试");
        }
    }

    /**
     * 初始化Channel管道
     * 根据配置添加SSL处理器、HTTP编解码器、WebSocket处理器等
     *
     * @param ch 要初始化的SocketChannel
     * @throws Exception 初始化过程中可能抛出的异常
     */
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        // SSL处理器（如果启用）
        if (sslContext != null) {
            pipeline.addLast(sslContext.newHandler(ch.alloc()));
        }

        // HTTP编解码器
        pipeline.addLast(new HttpServerCodec());

        // HTTP对象聚合器，将多个HTTP消息聚合成一个完整的HTTP消息
        pipeline.addLast(new HttpObjectAggregator(properties.getMaxFrameSize()));

        // 用于处理大文件传输（如发送大图）
        pipeline.addLast(new ChunkedWriteHandler());

        // WebSocket 数据压缩（支持 permessage-deflate）
        pipeline.addLast(new WebSocketServerCompressionHandler());

        // WebSocket 协议升级处理器（核心）
        pipeline.addLast(new WebSocketServerProtocolHandler(
                properties.getPath(),  // WebSocket路径
                null,                  // 子协议
                true,                  // 允许扩展
                properties.getMaxFrameSize(), // 最大帧大小
                false,                 // 允许mask
                true,                  // 检查UTF8
                10000L                 // 握手超时时间
        ));

        // 空闲状态处理器（心跳检测）
        pipeline.addLast(new IdleStateHandler(
                properties.getHeartbeatInterval(), // 读空闲时间
                0,                                 // 写空闲时间
                0,                                 // 读写空闲时间
                TimeUnit.SECONDS
        ));

        // 自定义WebSocket处理器
        pipeline.addLast(webSocketHandler);

        log.debug("WebSocket管道初始化完成");
    }
}

