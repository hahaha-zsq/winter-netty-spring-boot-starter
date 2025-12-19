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

    private final List<NettyClientPipelineCustomizer> customizers;

    public NettyClientChannelInitializer(List<NettyClientPipelineCustomizer> customizers) {
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

        // 插入用户自定义处理器
        if (ObjectUtil.isNotEmpty(customizers)) {
            customizers.forEach(c -> c.customize(pipeline));
        }
    }
}