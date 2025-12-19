package com.zsq.winter.netty.core.server;

import io.netty.channel.ChannelPipeline;

/**
 * 允许用户扩展服务端 Pipeline
 */
@FunctionalInterface
public interface NettyServerPipelineCustomizer {
    void customize(ChannelPipeline pipeline);
}