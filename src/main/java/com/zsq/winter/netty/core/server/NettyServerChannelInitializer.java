package com.zsq.winter.netty.core.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty服务端通道初始化器
 * 
 * 该类负责为每个新的服务端连接配置处理器链（Pipeline），主要功能包括：
 * 1. 协议编解码
 * 2. 消息处理
 * 3. 心跳检测
 * 4. 业务逻辑处理
 * 
 * 通过 NettyServerPipelineCustomizer 允许用户自定义 Pipeline 配置
 */
@Slf4j
public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    // 注入的 Pipeline 定制器
    private final NettyServerPipelineCustomizer customizer;

    /**
     * 构造函数
     *
     * @param customizer Pipeline 定制器
     */
    public NettyServerChannelInitializer(NettyServerPipelineCustomizer customizer) {
        this.customizer = customizer;
    }

    /**
     * 初始化服务端通道
     * 为每个新的客户端连接配置处理器链
     *
     * @param ch 新建立的客户端连接通道
     * @throws Exception 初始化过程中发生异常
     */
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        // 使用定制器配置 Pipeline
        if (customizer != null) {
            customizer.customize(pipeline);
            log.debug("Pipeline 配置完成，使用定制器: {}", customizer.getClass().getSimpleName());
        } else {
            log.warn("未配置 NettyServerPipelineCustomizer，Pipeline 为空");
        }
    }
}

