package com.zsq.winter.netty.core.websocket;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话管理器
 * 
 * 负责管理所有的 WebSocket 连接会话，提供以下功能：
 * 1. 添加和移除会话
 * 2. 根据用户ID查找会话
 * 3. 获取所有会话
 * 4. 统计在线用户数
 */
@Slf4j
public class WebSocketSessionManager {

    /**
     * 存储所有在线的 Channel
     * 使用 ChannelGroup 可以方便地进行批量操作（如广播消息）
     */
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 用户ID与Channel的映射关系
     * key: 用户ID
     * value: 用户对应的Channel
     */
    private final Map<String, Channel> userChannelMap = new ConcurrentHashMap<>();

    /**
     * Channel与用户ID的映射关系
     * key: Channel的ID
     * value: 用户ID
     */
    private final Map<String, String> channelUserMap = new ConcurrentHashMap<>();

    /**
     * 添加会话
     * 
     * @param userId 用户ID
     * @param channel 用户的Channel
     */
    public void addSession(String userId, Channel channel) {
        // 如果该用户已经存在连接，先移除旧连接
        if (userChannelMap.containsKey(userId)) {
            Channel oldChannel = userChannelMap.get(userId);
            removeSession(oldChannel);
            log.warn("用户 {} 已存在连接，关闭旧连接", userId);
        }

        channels.add(channel);
        userChannelMap.put(userId, channel);
        channelUserMap.put(channel.id().asLongText(), userId);
        
        log.info("用户 {} 建立连接，当前在线人数: {}", userId, getOnlineCount());
    }

    /**
     * 移除会话
     * 
     * @param channel 要移除的Channel
     */
    public void removeSession(Channel channel) {
        String channelId = channel.id().asLongText();
        String userId = channelUserMap.remove(channelId);
        
        if (userId != null) {
            userChannelMap.remove(userId);
            channels.remove(channel);
            log.info("用户 {} 断开连接，当前在线人数: {}", userId, getOnlineCount());
        }
    }

    /**
     * 根据用户ID获取Channel
     * 
     * @param userId 用户ID
     * @return 用户的Channel，如果不存在则返回null
     */
    public Channel getChannel(String userId) {
        return userChannelMap.get(userId);
    }

    /**
     * 根据Channel获取用户ID
     * 
     * @param channel Channel
     * @return 用户ID，如果不存在则返回null
     */
    public String getUserId(Channel channel) {
        return channelUserMap.get(channel.id().asLongText());
    }

    /**
     * 获取所有在线的Channel
     * 
     * @return ChannelGroup
     */
    public ChannelGroup getAllChannels() {
        return channels;
    }

    /**
     * 获取在线用户数
     * 
     * @return 在线用户数
     */
    public int getOnlineCount() {
        return channels.size();
    }

    /**
     * 判断用户是否在线
     * 
     * @param userId 用户ID
     * @return true表示在线，false表示离线
     */
    public boolean isOnline(String userId) {
        Channel channel = userChannelMap.get(userId);
        return channel != null && channel.isActive();
    }

    /**
     * 清空所有会话
     */
    public void clear() {
        channels.clear();
        userChannelMap.clear();
        channelUserMap.clear();
        log.info("清空所有会话");
    }
}
