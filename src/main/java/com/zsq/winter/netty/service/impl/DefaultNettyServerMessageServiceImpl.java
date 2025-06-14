package com.zsq.winter.netty.service.impl;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.core.server.NettyServerChannelManager;
import com.zsq.winter.netty.entity.NettyMessage;
import com.zsq.winter.netty.service.NettyServerMessageService;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultNettyServerMessageServiceImpl implements NettyServerMessageService {

    private final NettyServerChannelManager channelManager;

    public DefaultNettyServerMessageServiceImpl(NettyServerChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public void handleMessage(Channel channel, NettyMessage message) {
        log.info("处理WebSocket消息 - 通道: {}, 消息类型: {}, 内容: {}",
                channel.id(), message.getType(), message.getContent());

        try {
            switch (message.getType()) {
                case AUTH:
                    handleAuth(channel, message);
                    break;
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
            }
        } catch (Exception e) {
            log.error("处理消息失败", e);
            sendErrorMessage(channel, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void onConnect(Channel channel) {
        log.info("用户连接 - 通道: {}", channel.id());
        channelManager.addChannel(channel);
        // 发送认证请求消息
        NettyMessage authRequest = NettyMessage.system("请进行身份认证");
        sendMessage(channel, authRequest);
    }

    @Override
    public void onDisconnect(Channel channel) {
        log.info("用户断开连接 - 通道: {}", channel.id());
        String userId = channelManager.getChannelUser(channel.id());
        if (userId != null) {
            channelManager.unbindUser(userId);
            log.info("用户 {} 解绑成功", userId);
        }
        channelManager.removeChannel(channel);
    }

    /**
     * 处理认证消息
     */
    private void handleAuth(Channel channel, NettyMessage message) {
        String userId = message.getFromUserId();
        if (userId == null || userId.trim().isEmpty()) {
            sendErrorMessage(channel, "认证失败：用户ID不能为空");
            return;
        }

        // 检查用户ID是否已经被其他连接使用
        if (channelManager.isUserOnline(userId)) {
            sendErrorMessage(channel, "认证失败：该用户ID已在其他设备登录");
            return;
        }

        // 绑定用户ID和Channel的关系
        channelManager.bindUser(userId, channel);
        
        // 发送认证成功消息
        NettyMessage authSuccess = NettyMessage.system("认证成功");
        sendMessage(channel, authSuccess);
        
        log.info("用户 {} 认证成功，通道: {}", userId, channel.id());
    }

    /**
     * 处理文本消息
     */
    private void handleTextMessage(Channel channel, NettyMessage message) {
        // 检查用户是否已认证
        String userId = channelManager.getChannelUser(channel.id());
        if (userId == null) {
            sendErrorMessage(channel, "请先进行身份认证");
            return;
        }

        // 处理文本消息
        log.info("处理文本消息 - 用户: {}, 内容: {}", userId, message.getContent());
        // 这里可以添加具体的业务逻辑
    }

    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(Channel channel, NettyMessage message) {
        // 回复心跳
        NettyMessage pong = NettyMessage.heartbeat();
        pong.setContent("pong");
        sendMessage(channel, pong);
    }

    /**
     * 处理系统消息
     */
    private void handleSystemMessage(Channel channel, NettyMessage message) {
        log.info("处理系统消息: {}", message.getContent());
    }

    /**
     * 处理广播消息
     */
    private void handleBroadcastMessage(Channel channel, NettyMessage message) {
        // 检查用户是否已认证
        String userId = channelManager.getChannelUser(channel.id());
        if (userId == null) {
            sendErrorMessage(channel, "请先进行身份认证");
            return;
        }

        log.info("处理广播消息 - 发送者: {}, 内容: {}", userId, message.getContent());
        channelManager.broadcast(JSONUtil.toJsonStr(message));
    }

    /**
     * 处理私聊消息
     */
    private void handlePrivateMessage(Channel channel, NettyMessage message) {
        // 检查发送者是否已认证
        String fromUserId = channelManager.getChannelUser(channel.id());
        if (fromUserId == null) {
            sendErrorMessage(channel, "请先进行身份认证");
            return;
        }

        // 检查接收者是否存在且在线
        String toUserId = message.getToUserId();
        if (toUserId == null || !channelManager.isUserOnline(toUserId)) {
            sendErrorMessage(channel, "接收用户不存在或已离线");
            return;
        }

        // 发送私聊消息
        message.setFromUserId(fromUserId);
        channelManager.sendToUser(toUserId, JSONUtil.toJsonStr(message));
        log.info("私聊消息发送成功 - 从 {} 到 {}", fromUserId, toUserId);
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(Channel channel, String errorMsg) {
        NettyMessage errorMessage = NettyMessage.system(errorMsg);
        sendMessage(channel, errorMessage);
    }

    /**
     * 发送消息到指定通道
     */
    private void sendMessage(Channel channel, NettyMessage message) {
        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            channelManager.sendToChannel(channel, jsonMessage);
        } catch (Exception e) {
            log.error("发送消息失败", e);
        }
    }
}
