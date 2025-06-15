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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

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
     * 用户ID到Channel集合的映射
     * 用于根据用户ID快速找到该用户的所有连接
     * key: 用户ID
     * value: 用户的WebSocket连接集合
     */
    private static final ConcurrentMap<String, Set<Channel>> userChannelsMap = new ConcurrentHashMap<>();

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
        log.info("服务端：新连接加入: {}, 当前连接数: {}", channel.id(), channels.size());
    }

    /**
     * 绑定用户ID到连接
     * 支持用户多端登录，一个用户可以有多个连接
     *
     * @param userId 用户ID
     * @param channel WebSocket连接
     */
    public void bindUser(String userId, Channel channel) {
        // 获取或创建用户的连接集合
        Set<Channel> userChannels = userChannelsMap.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>());
        userChannels.add(channel);
        channelUserMap.put(channel.id(), userId);
        log.info("服务端：用户 {} 绑定连接: {}, 当前连接数: {}", userId, channel.id(), userChannels.size());
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
            Set<Channel> userChannels = userChannelsMap.get(userId);
            if (userChannels != null) {
                userChannels.remove(channel);
                if (userChannels.isEmpty()) {
                    userChannelsMap.remove(userId);
                }
            }
            log.info("服务端：用户 {} 断开连接: {}", userId, channel.id());
        }
        log.info("服务端：连接移除: {}, 当前连接数: {}", channel.id(), channels.size());
    }

    /**
     * 获取用户的所有连接
     *
     * @param userId 用户ID
     * @return 用户的所有连接集合，如果用户不存在则返回空集合
     */
    public Set<Channel> getUserChannels(String userId) {
        return userChannelsMap.getOrDefault(userId, new CopyOnWriteArraySet<>());
    }

    /**
     * 获取连接对应的用户ID
     *
     * @param channelId 连接ID
     * @return 用户ID，如果连接未绑定则返回null
     */
    public String getChannelUser(ChannelId channelId) {
        return channelUserMap.get(channelId);
    }

    /**
     * 向指定用户的所有连接发送消息
     *
     * @param userId 目标用户ID
     * @param message 要发送的消息内容
     * @return 发送成功的连接数量
     */
    public int sendToUser(String userId, String message) {
        Set<Channel> userChannels = getUserChannels(userId);
        int successCount = 0;
        for (Channel channel : userChannels) {
            if (channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(message));
                successCount++;
            }
        }
        log.info("服务端：向用户 {} 发送消息，成功发送到 {} 个连接", userId, successCount);
        return successCount;
    }

    /**
     * 向指定的连接发送消息
     *
     * @param channel 目标连接
     * @param message 要发送的消息内容
     * @return 发送是否成功
     */
    public boolean sendToChannel(Channel channel, String message) {
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
     */
    public void broadcast(String message) {
        channels.writeAndFlush(new TextWebSocketFrame(message));
        log.info("服务端：广播消息发送给 {} 个连接", channels.size());
    }

    /**
     * 广播消息给除指定用户外的所有在线用户
     *
     * @param excludeUserId 需要排除的用户ID
     * @param message 要广播的消息内容
     */
    public void broadcastExclude(String excludeUserId, String message) {
        Set<Channel> excludeChannels = getUserChannels(excludeUserId);
        channels.stream()
                .filter(channel -> !excludeChannels.contains(channel) && channel.isActive())
                .forEach(channel -> channel.writeAndFlush(new TextWebSocketFrame(message)));
        log.info("服务端：广播消息发送（排除用户 {}）", excludeUserId);
    }

    /**
     * 获取所有活跃的连接
     * @return 所有连接的集合
     */
    public ChannelGroup getAllChannels() {
        return channels;
    }

    /**
     * 获取当前连接数量
     * @return 当前活跃的连接数量
     */
    public int getConnectionCount() {
        return channels.size();
    }

    /**
     * 检查指定用户是否在线
     * @param userId 用户ID
     * @return true 如果用户有任何活跃连接，否则返回 false
     */
    public boolean isUserOnline(String userId) {
        Set<Channel> userChannels = getUserChannels(userId);
        return userChannels.stream().anyMatch(Channel::isActive);
    }

    /**
     * 获取用户的所有连接数量
     * @param userId 用户ID
     * @return 用户的活跃连接数量
     */
    public int getUserConnectionCount(String userId) {
        Set<Channel> userChannels = getUserChannels(userId);
        return (int) userChannels.stream().filter(Channel::isActive).count();
    }

    /**
     * 关闭并清理所有连接
     */
    public void closeAll() {
        channels.close();
        userChannelsMap.clear();
        channelUserMap.clear();
        log.info("所有连接已关闭");
    }
}
