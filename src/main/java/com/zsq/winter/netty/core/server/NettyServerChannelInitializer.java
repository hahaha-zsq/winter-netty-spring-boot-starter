package com.zsq.winter.netty.core.server;


import com.zsq.winter.netty.autoconfigure.NettyProperties;
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
import org.springframework.util.ObjectUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket管道初始化器
 * ChannelInitializer 是 Netty 中用于初始化 Channel（连接）处理链的钩子点，它让你可以在 Channel 创建时注册需要的所有处理器
 * 负责初始化SocketChannel的管道，配置SSL、HTTP和WebSocket处理器
 * •	用于初始化每个客户端连接的 SocketChannel
 * •	会被 Netty 的 ServerBootstrap.childHandler(...) 所引用
 * •	每个连接独立创建一个 ChannelPipeline
 */
@Slf4j
public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {


    /**
     * WebSocket属性配置
     */
    private final NettyProperties properties;

    /**
     * WebSocket消息处理器，你自定义的处理 WebSocket 消息的业务逻辑类（必须继承 ChannelInboundHandlerAdapter 或类似）
     */
    private final NettyServerHandler nettyServerHandler;

    /**
     * 构造函数
     *
     * @param properties       WebSocket属性配置
     * @param nettyServerHandler WebSocket消息处理器
     */
    public NettyServerChannelInitializer(NettyProperties properties, NettyServerHandler nettyServerHandler) {
        this.properties = properties;
        this.nettyServerHandler = nettyServerHandler;
    }


    /**
     * SSL上下文，用于配置SSL处理器
     */
    private SslContext sslContext;

    /**
     * 初始化方法，主要用于初始化SSL上下文
     * 在Spring容器中，该方法会在依赖注入完成后调用
     * •	启动时自动调用 init() 初始化 SSL 上下文
     * •	支持自定义证书或开发时使用自签名证书
     * •	如果配置启用 SSL，会在 pipeline 中加上 sslContext.newHandler(...)
     *
     * @throws Exception 初始化SSL上下文时可能抛出的异常
     */
    @PostConstruct
    public void init() throws Exception {
        // 初始化SSL上下文
        if (properties.getServer().isSslEnabled()) {
            initSslContext();
        }
    }

    /**
     * 初始化SSL上下文
     * <p>
     * 此方法用于根据配置的证书路径和密钥路径初始化SSL上下文
     * 如果提供了自定义的证书和密钥路径，则使用这些文件来创建SSL上下文
     * 否则，将生成一个自签名证书用于开发测试环境
     *
     * @throws Exception 如果初始化SSL上下文过程中出现错误，则抛出异常
     */
    private void initSslContext() throws Exception {
        // 检查是否提供了自定义的证书和密钥路径
        if (!ObjectUtils.isEmpty(properties.getServer().getSslCertPath()) && !ObjectUtils.isEmpty(properties.getServer().getSslKeyPath())) {
            // 使用自定义证书
            File certFile = new File(properties.getServer().getSslCertPath());
            File keyFile = new File(properties.getServer().getSslKeyPath());
            // 构建SSL上下文
            sslContext = SslContextBuilder.forServer(certFile, keyFile).build();
            // 记录日志信息
            log.info("使用自定义SSL证书: {}", properties.getServer().getSslCertPath());
        } else {
            // 使用自签名证书（仅用于开发测试）
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            // 构建SSL上下文
            sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
            // 记录警告信息，自签名证书仅用于开发测试
            log.warn("使用自签名SSL证书，仅用于开发测试");
        }
    }


    /**
     * 在每次有新客户端连接都会触发
     * 初始化Channel管道
     * 根据配置添加SSL处理器、HTTP编解码器、WebSocket处理器等
     *
     * @param ch 要初始化的SocketChannel
     * @throws Exception 初始化过程中可能抛出的异常
     */
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        // SSL处理器（如果启用）  加密通信
        if (!ObjectUtils.isEmpty(sslContext)) {
            pipeline.addLast(sslContext.newHandler(ch.alloc()));
        }

       /*
        粘包就是多个数据混淆在一起了，而且多个数据包之间没有明确的分隔，导致无法对这些数据包进行正确的读取。
        半包就是一个大的数据包被拆分成了多个数据包发送，读取的时候没有把多个包合成一个原本的大包，导致读取的数据不完整。
        在纯 TCP 协议中容易出现，比如你自己写的 Netty + 二进制协议，就必须使用 LengthFieldBasedFrameDecoder 等解码器处理。
        */

        // HTTP编解码器 把字节流解码成 HTTP 请求对象（包括 headers + body）===>HTTP 是有消息边界的，能自然避免粘包
        pipeline.addLast(new HttpServerCodec());

        // HTTP对象聚合器，将多个HTTP消息聚合成一个完整的HTTP消息  ===>彻底消除了半包问题（最大帧可配置）
        pipeline.addLast(new HttpObjectAggregator(properties.getServer().getMaxFrameSize()));

        // 用于处理大文件传输（如发送大图）  ===>WebSocket 基于帧（frame）协议，有边界，不存在粘包问题
        pipeline.addLast(new ChunkedWriteHandler());

        // WebSocket 数据压缩（支持 permessage-deflate）
        pipeline.addLast(new WebSocketServerCompressionHandler());

        // WebSocket 协议升级处理器（核心）
        pipeline.addLast(new WebSocketServerProtocolHandler(
                properties.getServer().getPath(),  // WebSocket路径
                null,                  // 子协议
                true,                  // 允许扩展
                properties.getServer().getMaxFrameSize(), // 最大帧大小
                false,                 // 允许mask
                true,                  // 检查UTF8
                10000L                 // 握手超时时间
        ));

        // 空闲状态处理器（心跳检测）
        pipeline.addLast(new IdleStateHandler(
                properties.getServer().getHeartbeatInterval(), // 读空闲时间
                0,                                 // 写空闲时间
                0,                                 // 读写空闲时间
                TimeUnit.SECONDS
        ));

        // 自定义WebSocket处理器
        pipeline.addLast(nettyServerHandler);

        log.debug("WebSocket管道初始化完成");
    }
}

