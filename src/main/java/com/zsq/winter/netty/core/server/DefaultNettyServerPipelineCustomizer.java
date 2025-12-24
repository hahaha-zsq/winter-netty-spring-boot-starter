package com.zsq.winter.netty.core.server;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认的Netty服务端管道定制器
 * <p>
 * 当禁用WebSocket功能时使用的默认管道配置，提供基础的TCP服务器功能：
 * - 日志处理器：记录网络事件
 * - 字符串编解码器：处理字符串消息
 * <p>
 * 这是一个基础实现，用户可以通过实现NettyServerPipelineCustomizer接口
 * 来提供自定义的管道配置。
 * 
 * @author zsq
 * @since 1.0.0
 */
@Slf4j
public class DefaultNettyServerPipelineCustomizer implements NettyServerPipelineCustomizer {

    /**
     * 自定义管道配置
     * <p>
     * 提供基础的TCP服务器管道配置：
     * 1. LoggingHandler - 记录网络事件，便于调试
     * 2. StringDecoder - 将接收到的字节数据解码为字符串
     * 3. StringEncoder - 将字符串编码为字节数据发送
     * <p>
     * 注意：这是一个基础配置，实际项目中通常需要根据具体协议需求进行定制
     *
     * @param pipeline Netty的ChannelPipeline对象
     */
    @Override
    public void customize(ChannelPipeline pipeline) {
        log.info("配置默认的TCP服务器管道");
        
        // 添加日志处理器 - 记录网络事件
        pipeline.addLast("logging", new LoggingHandler(LogLevel.DEBUG));
        
        // 添加字符串解码器 - 将接收到的字节数据解码为字符串
        pipeline.addLast("string-decoder", new StringDecoder());
        
        // 添加字符串编码器 - 将字符串编码为字节数据发送
        pipeline.addLast("string-encoder", new StringEncoder());
        
        // 注意：这里没有添加业务处理器，用户需要根据实际需求添加
        // 例如：pipeline.addLast("business-handler", new CustomBusinessHandler());
        
        log.debug("默认TCP服务器管道配置完成");
    }
}