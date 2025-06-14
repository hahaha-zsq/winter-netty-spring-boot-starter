package com.zsq.winter.netty.core.server;

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
import java.util.Collection;

/**
 * WebSocket连接管理器
 * 
 * 该类负责管理所有WebSocket连接，提供以下核心功能：
 * 1. 连接生命周期管理（添加、移除连接）
 * 2. 用户会话管理（用户与连接的绑定关系）
 * 3. 消息发送（单播、广播）
 * 4. 在线状态管理
 * 
 * 线程安全说明：
 * - 使用ConcurrentHashMap保证并发安全
 * - 使用DefaultChannelGroup进行连接的线程安全管理
 * - 所有操作都是原子的，支持高并发场景
 */
@Slf4j
public class NettyServerChannelManager {

    /**
     * 全局连接组
     * 用于存储和管理所有活动的WebSocket连接
     * 使用DefaultChannelGroup实现自动化的连接生命周期管理
     */
    private static final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 用户ID到Channel的映射
     * 用于根据用户ID快速找到对应的连接
     * key: 用户ID
     * value: 用户的WebSocket连接
     */
    private static final ConcurrentMap<String, Channel> userChannelMap = new ConcurrentHashMap<>();

    /**
     * Channel到用户ID的映射
     * 用于根据连接快速找到对应的用户ID
     * key: 连接的唯一标识
     * value: 用户ID
     */
    private static final ConcurrentMap<ChannelId, String> channelUserMap = new ConcurrentHashMap<>();

    /**
     * 添加新的WebSocket连接
     * 将连接添加到全局连接组中进行统一管理
     *
     * @param channel 新建立的WebSocket连接
     */
    public void addChannel(Channel channel) {
        channels.add(channel);
        log.info("新连接加入: {}, 当前连接数: {}", channel.id(), channels.size());
    }

    /**
     * 移除WebSocket连接
     * 清理连接资源和用户映射关系
     *
     * @param channel 要移除的WebSocket连接
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
     * 发送消息到指定通道
     *
     * @param channel 目标通道
     * @param message 要发送的消息
     * @return 是否发送成功
     */
    public boolean sendToChannel(Channel channel, String message) {
        try {
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(message));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("发送消息到通道失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 根据用户ID获取对应的Channel
     *
     * @param userId 用户ID
     * @return 对应的Channel，如果不存在则返回null
     */
    public Channel getChannel(String userId) {
        return userChannelMap.get(userId);
    }

    /**
     * 获取所有活动的Channel
     *
     * @return 所有活动的Channel集合
     */
    public Collection<Channel> getAllChannels() {
        return channels;
    }

    /**
     * 绑定用户ID和Channel的关系
     *
     * @param userId 用户ID
     * @param channel Channel对象
     */
    public void bindUser(String userId, Channel channel) {
        userChannelMap.put(userId, channel);
        channelUserMap.put(channel.id(), userId);
        log.info("用户 {} 绑定到连接: {}", userId, channel.id());
    }

    /**
     * 解绑用户ID和Channel的关系
     *
     * @param userId 用户ID
     */
    public void unbindUser(String userId) {
        Channel channel = userChannelMap.remove(userId);
        if (channel != null) {
            channelUserMap.remove(channel.id());
            log.info("用户 {} 解绑连接: {}", userId, channel.id());
        }
    }

    /**
     * 获取WebSocket连接对应的用户ID
     *
     * @param channelId 连接ID
     * @return 用户ID，如果连接未绑定用户则返回null
     */
    public String getChannelUser(ChannelId channelId) {
        return channelUserMap.get(channelId);
    }

    /**
     * 向指定用户发送消息
     * 
     * @param userId 目标用户ID
     * @param message 要发送的消息内容
     * @return 发送是否成功
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
     * 广播消息给所有在线连接
     *
     * @param message 要广播的消息内容
     * @return 是否广播成功
     */
    public boolean broadcast(String message) {
        try {
            channels.writeAndFlush(new TextWebSocketFrame(message));
            log.info("广播消息发送给 {} 个连接: {}", channels.size(), message);
            return true;
        } catch (Exception e) {
            log.error("广播消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 广播消息给除指定用户外的所有在线用户
     *
     * @param excludeUserId 需要排除的用户ID
     * @param message 要广播的消息内容
     * @return 是否广播成功
     */
    public boolean broadcastExclude(String excludeUserId, String message) {
        try {
            // 获取需要排除的用户连接
            Channel excludeChannel = userChannelMap.get(excludeUserId);

            // 向其他所有活动连接发送消息
            channels.stream()
                    .filter(channel -> channel != excludeChannel && channel.isActive())
                    .forEach(channel -> channel.writeAndFlush(new TextWebSocketFrame(message)));

            // 记录广播消息的日志
            log.info("广播消息发送（排除用户 {}）: {}", excludeUserId, message);
            return true;
        } catch (Exception e) {
            log.error("广播消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取当前在线用户数量
     *
     * @return 在线用户数量
     */
    public int getOnlineUserCount() {
        return userChannelMap.size();
    }

    /**
     * 获取当前总连接数
     *
     * @return 总连接数（包括未绑定用户的连接）
     */
    public int getTotalChannelCount() {
        return channels.size();
    }

    /**
     * 检查用户是否在线
     *
     * @param userId 用户ID
     * @return true表示用户在线且连接活动，false表示用户离线
     */
    public boolean isUserOnline(String userId) {
        Channel channel = userChannelMap.get(userId);
        return !ObjectUtils.isEmpty(channel) && channel.isActive();
    }

    /**
     * 关闭所有WebSocket连接
     * 用于服务器关闭时清理资源
     */
    public void closeAll() {
        channels.close();
        userChannelMap.clear();
        channelUserMap.clear();
        log.info("所有WebSocket连接已关闭");
    }
}
