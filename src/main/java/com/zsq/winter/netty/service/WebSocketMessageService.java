package com.zsq.winter.netty.service;

import com.zsq.winter.netty.entity.WebSocketMessage;
import io.netty.channel.Channel;

/**
 * WebSocket消息业务处理服务接口
 */
public interface WebSocketMessageService {

    /**
     * 处理WebSocket消息
     * @param channel 连接通道
     * @param message 消息对象
     */
    void handleMessage(Channel channel, WebSocketMessage message);

    /**
     * 用户连接时的处理
     * @param channel 连接通道
     */
    void onConnect(Channel channel);

    /**
     * 用户断开连接时的处理
     * @param channel 连接通道
     */
    void onDisconnect(Channel channel);
}
