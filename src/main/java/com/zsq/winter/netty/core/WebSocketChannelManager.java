package com.zsq.winter.netty.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理所有 WebSocket 通信的连接通道（Channel）、用户映射、消息分发等，是整个 Netty WebSocket 服务中的“连接中心”。
 */
@Slf4j
public class WebSocketChannelManager {


    /**
     * 存储所有连接的Channel
     */
    private static final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 用户ID与Channel的映射关系
     */
    private static final ConcurrentMap<String, Channel> userChannelMap = new ConcurrentHashMap<>();

    /**
     * Channel与用户ID的映射关系
     */
    private static final ConcurrentMap<ChannelId, String> channelUserMap = new ConcurrentHashMap<>();

    /**
     * 添加连接
     */
    public void addChannel(Channel channel) {
        channels.add(channel);
        log.info("新连接加入: {}, 当前连接数: {}", channel.id(), channels.size());
    }

    /**
     * 移除连接
     */
    public void removeChannel(Channel channel) {
        channels.remove(channel);
        // 清理用户映射关系
        String userId = channelUserMap.remove(channel.id());
        if (userId != null) {
            userChannelMap.remove(userId);
            log.info("用户 {} 断开连接: {}", userId, channel.id());
        }
        log.info("连接移除: {}, 当前连接数: {}", channel.id(), channels.size());
    }

    /**
     * 绑定用户ID与Channel
     */
    public void bindUser(String userId, Channel channel) {
        // 如果用户已经有连接，先关闭旧连接
        Channel oldChannel = userChannelMap.get(userId);
        if (oldChannel != null && oldChannel.isActive()) {
            log.info("用户 {} 重复登录，关闭旧连接: {}", userId, oldChannel.id());
            oldChannel.close();
        }

        userChannelMap.put(userId, channel);
        channelUserMap.put(channel.id(), userId);
        log.info("用户 {} 绑定到连接: {}", userId, channel.id());
    }

    /**
     * 获取用户的Channel
     */
    public Channel getUserChannel(String userId) {
        return userChannelMap.get(userId);
    }

    /**
     * 获取Channel对应的用户ID
     */
    public String getChannelUser(ChannelId channelId) {
        return channelUserMap.get(channelId);
    }

    /**
     * 向指定用户发送消息
     */
    public boolean sendToUser(String userId, String message) {
        Channel channel = userChannelMap.get(userId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        return false;
    }

    /**
     * 向指定Channel发送消息
     */
    public boolean sendToChannel(Channel channel, String message) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        return false;
    }

    /**
     * 广播消息给所有连接
     */
    public void broadcast(String message) {
        channels.writeAndFlush(new TextWebSocketFrame(message));
        log.info("广播消息发送给 {} 个连接: {}", channels.size(), message);
    }

    /**
     * 广播消息给所有连接（排除指定用户）
     */
    public void broadcastExclude(String excludeUserId, String message) {
        Channel excludeChannel = userChannelMap.get(excludeUserId);
        for (Channel channel : channels) {
            if (channel != excludeChannel && channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(message));
            }
        }
        log.info("广播消息发送（排除用户 {}）: {}", excludeUserId, message);
    }

    /**
     * 获取在线用户数量
     */
    public int getOnlineUserCount() {
        return userChannelMap.size();
    }

    /**
     * 获取总连接数
     */
    public int getTotalChannelCount() {
        return channels.size();
    }

    /**
     * 检查用户是否在线
     */
    public boolean isUserOnline(String userId) {
        Channel channel = userChannelMap.get(userId);
        return channel != null && channel.isActive();
    }

    /**
     * 关闭所有连接
     */
    public void closeAll() {
        channels.close();
        userChannelMap.clear();
        channelUserMap.clear();
        log.info("所有WebSocket连接已关闭");
    }
}
