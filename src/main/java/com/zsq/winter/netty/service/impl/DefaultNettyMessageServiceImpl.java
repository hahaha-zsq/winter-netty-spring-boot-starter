package com.zsq.winter.netty.service.impl;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.core.server.NettyServerChannelManager;
import com.zsq.winter.netty.entity.NettyMessage;
import com.zsq.winter.netty.service.NettyMessageService;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultNettyMessageServiceImpl implements NettyMessageService {

    private final NettyServerChannelManager channelManager;

    public DefaultNettyMessageServiceImpl(NettyServerChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public void handleMessage(Channel channel, NettyMessage message) {
        log.info("实际业务：处理WebSocket消息 - 通道: {}, 消息类型: {}, 内容: {}",
                channel.id(), message.getType(), message.getContent());

        // 默认实现：简单记录日志
        // 用户可以通过实现WebSocketMessageService接口来自定义消息处理逻辑
        try {
            switch (message.getType()) {
                case TEXT:
                    handleTextMessage(channel, message);
                    break;
                case HEARTBEAT:
                    handleHeartbeat(channel, message);
                    break;
                case SYSTEM:
                    handleSystemMessage(channel, message);
                    break;
                case BROADCAST:
                    handleBroadcastMessage(channel, message);
                    break;
                case PRIVATE:
                    handlePrivateMessage(channel, message);
                    break;
                default:
                    log.warn("未知消息类型: {}", message.getType());
                    sendErrorMessage(channel, "不支持的消息类型: " + message.getType());
            }
        } catch (Exception e) {
            log.error("处理WebSocket消息失败", e);
            sendErrorMessage(channel, "消息处理失败");
        }
    }

    @Override
    public void onConnect(Channel channel) {
        log.info("实际业务：WebSocket连接建立 - 通道: {}", channel.id());

        // 默认实现：发送欢迎消息
        // 用户可以在此处添加连接建立时的业务逻辑
    }

    @Override
    public void onDisconnect(Channel channel) {
        log.info("实际业务：WebSocket连接断开 - 通道: {}", channel.id());

        // 默认实现：清理资源
        // 用户可以在此处添加连接断开时的业务逻辑
    }


    /**
     * 处理文本消息
     */
    private void handleTextMessage(Channel channel, NettyMessage message) {
        sendMessage(channel, message);
        log.debug("处理文本消息，通道: {}", channel.id());
    }


    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(Channel channel, NettyMessage message) {
        // 回复心跳
        NettyMessage pong = NettyMessage.heartbeat();
        pong.setContent("pong");
        sendMessage(channel, pong);

        log.debug("处理心跳消息，通道: {}", channel.id());
    }

    /**
     * 处理系统消息
     */
    private void handleSystemMessage(Channel channel, NettyMessage message) {
        log.info("收到系统消息: {}", message.getContent());
    }

    /**
     * 处理广播消息
     */
    private void handleBroadcastMessage(Channel channel, NettyMessage message) {
        log.info("处理广播消息: {}", message.getContent());
    }

    /**
     * 处理私聊消息
     */
    private void handlePrivateMessage(Channel channel, NettyMessage message) {
        log.info("处理私聊消息: {}", message.getContent());
    }

    /**
     * 发送消息到指定通道
     */
    private void sendMessage(Channel channel, NettyMessage message) {
        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            channelManager.sendToChannel(channel, jsonMessage);
        } catch (Exception e) {
            log.error("发送消息到通道失败", e);
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(Channel channel, String errorMsg) {
        NettyMessage errorMessage = NettyMessage.system("错误: " + errorMsg);
        sendMessage(channel, errorMessage);
    }
}
