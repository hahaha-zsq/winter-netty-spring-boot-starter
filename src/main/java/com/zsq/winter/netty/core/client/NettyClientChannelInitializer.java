package com.zsq.winter.netty.core.client;

import com.zsq.winter.netty.autoconfigure.NettyProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * Netty客户端Channel初始化器
 * 负责配置Channel的处理器链（Pipeline），包括SSL加密、心跳检测、编解码器等
 * 每个新的Channel连接建立时都会调用initChannel方法进行初始化
 */
@Slf4j
public class NettyClientChannelInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * 客户端消息处理器
     * 处理业务逻辑，如接收服务器消息、处理心跳等
     */
    private final NettyClientHandler nettyClientHandler;

    /**
     * Netty配置属性
     * 包含SSL配置、心跳间隔等参数
     */
    private final NettyProperties properties;

    /**
     * SSL上下文
     * 用于配置SSL加密通信
     */
    private SslContext sslContext;

    /**
     * 构造函数
     *
     * @param nettyClientHandler 客户端消息处理器
     * @param properties         Netty配置属性
     */
    public NettyClientChannelInitializer(NettyClientHandler nettyClientHandler, NettyProperties properties) {
        this.nettyClientHandler = nettyClientHandler;
        this.properties = properties;
        initSslContext();
    }

    /**
     * 初始化SSL上下文
     * 根据配置的证书文件创建SSL上下文
     * 支持以下两种模式：
     * 1. 使用信任证书验证服务器（单向认证）
     * 2. 使用客户端证书和私钥进行双向认证
     */
    private void initSslContext() {
        if (properties.getClient().isSslEnabled()) {
            try {
                SslContextBuilder builder = SslContextBuilder.forClient();
                
                // 配置信任证书（用于验证服务器证书）
                if (StringUtils.hasText(properties.getClient().getSslTrustCertPath())) {
                    File trustCertFile = new File(properties.getClient().getSslTrustCertPath());
                    if (trustCertFile.exists()) {
                        // 创建X.509证书工厂
                        CertificateFactory cf = CertificateFactory.getInstance("X.509");
                        // 加载信任证书
                        try (FileInputStream fis = new FileInputStream(trustCertFile)) {
                            X509Certificate trustCert = (X509Certificate) cf.generateCertificate(fis);
                            // 创建信任管理器
                            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                            trustStore.load(null, null);
                            trustStore.setCertificateEntry("trust-cert", trustCert);
                            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                            trustManagerFactory.init(trustStore);
                            builder.trustManager(trustManagerFactory);
                        }
                    } else {
                        log.warn("客户端：信任证书文件不存在: {}", trustCertFile.getAbsolutePath());
                    }
                }

                // 配置客户端证书和私钥（用于双向认证）
                String certPath = properties.getClient().getSslCertPath();
                String keyPath = properties.getClient().getSslKeyPath();
                if (StringUtils.hasText(certPath) && StringUtils.hasText(keyPath)) {
                    File certFile = new File(certPath);
                    File keyFile = new File(keyPath);
                    if (certFile.exists() && keyFile.exists()) {
                        builder.keyManager(certFile, keyFile);
                    } else {
                        log.warn("客户端：证书或私钥文件不存在: cert={}, key={}",
                                certFile.getAbsolutePath(), keyFile.getAbsolutePath());
                    }
                }

                sslContext = builder.build();
                log.info("客户端：SSL上下文初始化成功");
            } catch (Exception e) {
                log.error("客户端：SSL上下文初始化失败", e);
                throw new RuntimeException("Failed to initialize SSL context", e);
            }
        }
    }

    /**
     * 初始化Channel
     * 当新的Channel连接建立时，Netty会调用此方法
     * 在这里配置Channel的处理器链（Pipeline）
     *
     * @param ch 新建立的SocketChannel
     */
    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // 配置SSL（如果启用）
        if (properties.getClient().isSslEnabled() && sslContext != null) {
            pipeline.addLast(sslContext.newHandler(ch.alloc()));
            log.debug("客户端：已添加SSL处理器到管道");
        }

        // 心跳检测处理器，用于检测连接是否存活，防止连接假死
        // 当指定时间内没有读写操作时，会触发IdleStateEvent事件
        pipeline.addLast(new IdleStateHandler(
                properties.getClient().getHeartbeatInterval(), // 读空闲时间
                properties.getClient().getHeartbeatInterval(), // 写空闲时间
                properties.getClient().getHeartbeatInterval(), // 读写空闲时间
                TimeUnit.SECONDS
        ));

        // 字符串编解码器
        // 将ByteBuf转换为String，或将String转换为ByteBuf
        pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));  // 入站消息解码器，网络数据（ByteBuf）→ StringDecoder → String类型消息
        pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));  // 出站消息编码器，String类型消息 → StringEncoder → 网络数据（ByteBuf）

        // 自定义业务处理器
        // 处理实际的业务逻辑，如接收消息、发送心跳等
        pipeline.addLast(nettyClientHandler);
    }
}