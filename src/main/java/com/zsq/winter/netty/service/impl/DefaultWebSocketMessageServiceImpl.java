package com.zsq.winter.netty.service.impl;

import com.zsq.winter.netty.entity.WebSocketMessage;
import com.zsq.winter.netty.service.WebSocketMessageService;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultWebSocketMessageServiceImpl implements WebSocketMessageService {
    @Override
    public void handleMessage(Channel channel, WebSocketMessage message) {
        log.info("处理WebSocket消息 - 通道: {}, 消息类型: {}, 内容: {}",
                channel.id(), message.getType(), message.getContent());

        // 默认实现：简单记录日志
        // 用户可以通过实现WebSocketMessageService接口来自定义消息处理逻辑
    }

    @Override
    public void onConnect(Channel channel) {
        log.info("WebSocket连接建立 - 通道: {}", channel.id());

        // 默认实现：发送欢迎消息
        // 用户可以在此处添加连接建立时的业务逻辑
    }

    @Override
    public void onDisconnect(Channel channel) {
        log.info("WebSocket连接断开 - 通道: {}", channel.id());

        // 默认实现：清理资源
        // 用户可以在此处添加连接断开时的业务逻辑
    }
}
