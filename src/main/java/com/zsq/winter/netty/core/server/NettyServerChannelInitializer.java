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
 * WebSocket通道初始化器
 * 
 * 该类负责为每个新的WebSocket连接配置处理器链（Pipeline），主要功能包括：
 * 1. SSL/TLS加密通信配置
 * 2. HTTP协议编解码
 * 3. WebSocket协议支持
 * 4. 心跳检测
 * 5. 消息压缩
 * 6. 业务逻辑处理
 * 
 * 处理器链配置顺序（从前到后）：
 * 1. SSL处理器（可选）
 * 2. HTTP编解码器
 * 3. HTTP消息聚合器
 * 4. 大文件传输处理器
 * 5. WebSocket压缩处理器
 * 6. WebSocket协议处理器
 * 7. 心跳检测处理器
 * 8. 业务逻辑处理器
 */
@Slf4j
public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * WebSocket配置属性
     * 包含SSL配置、路径配置、心跳配置等
     */
    private final NettyProperties properties;

    /**
     * WebSocket消息处理器
     * 处理WebSocket协议下的业务消息
     */
    private final NettyServerHandler nettyServerHandler;

    /**
     * SSL上下文
     * 用于配置HTTPS/WSS加密通信
     */
    private SslContext sslContext;

    /**
     * 构造函数
     *
     * @param properties WebSocket配置属性
     * @param nettyServerHandler WebSocket消息处理器
     */
    public NettyServerChannelInitializer(NettyProperties properties, NettyServerHandler nettyServerHandler) {
        this.properties = properties;
        this.nettyServerHandler = nettyServerHandler;
    }

    /**
     * 初始化SSL上下文
     * Spring容器启动时自动调用
     * 
     * 支持两种SSL证书配置方式：
     * 1. 自定义证书：通过配置文件指定证书和密钥文件路径
     * 2. 自签名证书：用于开发测试环境，自动生成临时证书
     *
     * @throws Exception SSL上下文初始化失败时抛出
     */
    @PostConstruct
    public void init() throws Exception {
        if (properties.getServer().isSslEnabled()) {
            initSslContext();
        }
    }

    /**
     * 初始化SSL上下文的具体实现
     * 
     * 证书选择逻辑：
     * 1. 如果配置了证书和密钥路径，使用自定义证书
     * 2. 如果未配置证书，自动生成自签名证书（仅用于开发测试）
     *
     * @throws Exception 证书加载或SSL上下文创建失败时抛出
     */
    private void initSslContext() throws Exception {
        // 检查是否配置了自定义证书
        if (!ObjectUtils.isEmpty(properties.getServer().getSslCertPath()) && 
            !ObjectUtils.isEmpty(properties.getServer().getSslKeyPath())) {
            // 加载自定义证书
            File certFile = new File(properties.getServer().getSslCertPath());
            File keyFile = new File(properties.getServer().getSslKeyPath());
            // 构建SSL上下文
            sslContext = SslContextBuilder.forServer(certFile, keyFile).build();
            // 记录日志信息
            log.info("使用自定义SSL证书: {}", properties.getServer().getSslCertPath());
        } else {
            // 生成自签名证书
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
            // 记录警告信息，自签名证书仅用于开发测试
            log.warn("使用自签名SSL证书，仅用于开发测试");
        }
    }

    /**
     * 初始化WebSocket通道
     * 为每个新的客户端连接配置处理器链
     * 
     * 处理器配置说明：
     * 1. SslHandler: SSL/TLS加密通信
     * 2. HttpServerCodec: HTTP请求解码和响应编码
     * 3. HttpObjectAggregator: 将HTTP消息的多个部分合并
     * 4. ChunkedWriteHandler: 支持大文件传输
     * 5. WebSocketServerCompressionHandler: WebSocket消息压缩
     * 6. WebSocketServerProtocolHandler: WebSocket协议处理
     * 7. IdleStateHandler: 连接空闲检测
     * 8. NettyServerHandler: 业务逻辑处理
     *
     * @param ch 新建立的客户端连接通道
     * @throws Exception 初始化过程中发生异常
     */
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        // 配置SSL加密通信（如果启用）
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

        // WebSocket消息压缩处理器
        pipeline.addLast(new WebSocketServerCompressionHandler());

        // WebSocket协议升级和帧处理器
        pipeline.addLast(new WebSocketServerProtocolHandler(
                properties.getServer().getPath(),  // WebSocket路径
                null,                  // 子协议
                true,                  // 允许扩展
                properties.getServer().getMaxFrameSize(), // 最大帧大小
                false,                 // 允许mask
                true,                  // 检查UTF8
                10000L                 // 握手超时时间
        ));

        // 空闲连接检测（心跳机制）
        pipeline.addLast(new IdleStateHandler(
                properties.getServer().getHeartbeatInterval(), // 读空闲时间
                0,                                             // 写空闲时间（不检测）
                0,                                             // 读写空闲时间（不检测）
                TimeUnit.SECONDS
        ));

        // 业务逻辑处理器
        pipeline.addLast(nettyServerHandler);

        log.debug("WebSocket通道初始化完成: {}", ch.id());
    }
}

