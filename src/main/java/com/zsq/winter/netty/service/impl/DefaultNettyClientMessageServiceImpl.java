package com.zsq.winter.netty.service.impl;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.core.client.NettyClientChannelManager;
import com.zsq.winter.netty.entity.NettyMessage;
import com.zsq.winter.netty.service.NettyClientMessageService;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认的客户端消息处理服务实现
 */
@Slf4j
public class DefaultNettyClientMessageServiceImpl implements NettyClientMessageService {

    private final NettyClientChannelManager channelManager;

    public DefaultNettyClientMessageServiceImpl(NettyClientChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public void handleMessage(Channel channel, NettyMessage message) {
        log.info("客户端：处理服务器消息 - 通道: {}, 消息类型: {}, 内容: {}",
                channel.id(), message.getType(), message.getContent());

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
                    log.warn("客户端：未知消息类型: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("客户端：处理服务器消息失败", e);
        }
    }

    @Override
    public void onConnect(Channel channel) {
        log.info("客户端：连接建立 - 通道: {}", channel.id());
        NettyMessage connectMsg = NettyMessage.system("Connected to server");
        channel.writeAndFlush(JSONUtil.toJsonStr(connectMsg));
    }

    @Override
    public void onDisconnect(Channel channel) {
        log.info("客户端：连接断开 - 通道: {}", channel.id());
    }


    /**
     * 处理文本消息
     */
    private void handleTextMessage(Channel channel, NettyMessage message) {
        sendMessage(channel, message);
        log.debug("客户端：处理文本消息，通道: {}", channel.id());
    }


    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(Channel channel, NettyMessage message) {
        // 回复心跳
        NettyMessage pong = NettyMessage.heartbeat();
        pong.setContent("pong");
        sendMessage(channel, pong);

        log.debug("客户端：处理心跳消息，通道: {}", channel.id());
    }

    /**
     * 处理系统消息
     */
    private void handleSystemMessage(Channel channel, NettyMessage message) {
        log.info("客户端：收到系统消息: {}", message.getContent());
    }

    /**
     * 处理广播消息
     */
    private void handleBroadcastMessage(Channel channel, NettyMessage message) {
        log.info("客户端：处理广播消息: {}", message.getContent());
    }

    /**
     * 处理私聊消息
     */
    private void handlePrivateMessage(Channel channel, NettyMessage message) {
        log.info("客户端：处理私聊消息: {}", message.getContent());
    }

    /**
     * 发送消息到指定通道
     */
    private void sendMessage(Channel channel, NettyMessage message) {
        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            channelManager.sendToChannel(channel, jsonMessage);
        } catch (Exception e) {
            log.error("客户端：发送消息到通道失败", e);
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(Channel channel, String errorMsg) {
        NettyMessage errorMessage = NettyMessage.system("客户端：错误: " + errorMsg);
        sendMessage(channel, errorMessage);
    }
} 