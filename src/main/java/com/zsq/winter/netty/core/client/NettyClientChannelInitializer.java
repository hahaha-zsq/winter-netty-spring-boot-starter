package com.zsq.winter.netty.core.client;

import cn.hutool.core.util.ObjectUtil;
import com.zsq.winter.netty.autoconfigure.NettyProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Netty客户端Channel初始化器
 * 负责配置Channel的处理器链（Pipeline），心跳检测、编解码器等
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
     * 、心跳间隔等参数
     */
    private final NettyProperties properties;

    private final List<NettyClientPipelineCustomizer> customizers;

    public NettyClientChannelInitializer(NettyClientHandler nettyClientHandler,
                                         NettyProperties properties,
                                         List<NettyClientPipelineCustomizer> customizers) {
        this.nettyClientHandler = nettyClientHandler;
        this.properties = properties;
        this.customizers = customizers;
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

        // 插入用户自定义处理器
        if (ObjectUtil.isNotEmpty(customizers)) {
            customizers.forEach(c -> c.customize(pipeline));
        }
        // 自定义业务处理器
        // 处理实际的业务逻辑，如接收消息、发送心跳等
        pipeline.addLast(nettyClientHandler);
    }
}