package com.zsq.winter.netty.core.client;

import io.netty.channel.ChannelPipeline;

/**
 * 允许用户扩展客户端 Pipeline
 */
@FunctionalInterface
public interface NettyClientPipelineCustomizer {
    void customize(ChannelPipeline pipeline);
}