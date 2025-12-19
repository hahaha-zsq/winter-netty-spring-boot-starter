package com.zsq.winter.netty.core.server;

import cn.hutool.core.util.ObjectUtil;
import com.zsq.winter.netty.autoconfigure.NettyProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * WebSocket通道初始化器
 * 
 * 该类负责为每个新的WebSocket连接配置处理器链（Pipeline），主要功能包括：
 * 2. HTTP协议编解码
 * 3. WebSocket协议支持
 * 4. 心跳检测
 * 5. 消息压缩
 * 6. 业务逻辑处理
 * 
 * 处理器链配置顺序（从前到后）：
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


    // 注入可选的自定义器
    private final List<NettyServerPipelineCustomizer> customizers;


    /**
     * 构造函数
     *
     * @param customizers 可选的用户自定义的处理器
     */
    public NettyServerChannelInitializer(
                                         List<NettyServerPipelineCustomizer> customizers) {
        this.customizers = customizers;
    }


    /**
     * 初始化WebSocket通道
     * 为每个新的客户端连接配置处理器链
     *
     * @param ch 新建立的客户端连接通道
     * @throws Exception 初始化过程中发生异常
     */
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        // 插入用户自定义处理器 (在业务逻辑处理器之前)
        if (ObjectUtil.isNotEmpty(customizers)) {
            customizers.forEach(c -> c.customize(pipeline));
        }
        log.debug("服务端：WebSocket通道初始化完成: {}", ch.id());
    }
}

