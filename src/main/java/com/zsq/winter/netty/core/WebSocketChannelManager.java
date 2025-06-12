package com.zsq.winter.netty.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ObjectUtils;

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
        if (!ObjectUtils.isEmpty(userId)) {
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
        if (!ObjectUtils.isEmpty(oldChannel) && oldChannel.isActive()) {
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
        if (!ObjectUtils.isEmpty(channel) && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        return false;
    }

    /**
     * 向指定Channel发送消息
     */
    public boolean sendToChannel(Channel channel, String message) {
        if (!ObjectUtils.isEmpty(channel) && channel.isActive()) {
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
     * 向所有活跃用户广播消息，但排除指定用户
     *
     * @param excludeUserId 需要排除的用户ID
     * @param message       要广播的消息内容
     */
    public void broadcastExclude(String excludeUserId, String message) {
        // 获取需要排除的用户的通道
        Channel excludeChannel = userChannelMap.get(excludeUserId);

        // 使用 Stream 流过滤并发送消息
        channels.stream()
                .filter(channel -> channel != excludeChannel && channel.isActive())
                .forEach(channel -> channel.writeAndFlush(new TextWebSocketFrame(message)));

        // 记录广播消息的日志
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
        return !ObjectUtils.isEmpty(channel) && channel.isActive();
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
