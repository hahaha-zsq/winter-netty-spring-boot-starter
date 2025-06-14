package com.zsq.winter.netty.service;

import com.zsq.winter.netty.entity.NettyMessage;
import io.netty.channel.Channel;

/**
 * Netty客户端消息处理服务接口
 */
public interface NettyClientMessageService {

    /**
     * 处理从服务器接收到的消息
     * @param channel 连接通道
     * @param message 消息对象
     */
    void handleMessage(Channel channel, NettyMessage message);

    /**
     * 连接建立时的处理
     * @param channel 连接通道
     */
    void onConnect(Channel channel);

    /**
     * 连接断开时的处理
     * @param channel 连接通道
     */
    void onDisconnect(Channel channel);
} 