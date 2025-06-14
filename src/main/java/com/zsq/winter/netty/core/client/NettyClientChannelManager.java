package com.zsq.winter.netty.core.client;

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
 * TCP 客户端连接管理器
 * 
 * 该类负责管理所有的 Netty 客户端 Channel 连接，提供以下核心功能：
 * 1. 连接生命周期管理（添加、移除连接）
 * 2. 客户端会话管理（客户端ID与连接的绑定关系）
 * 3. 消息发送（单播、广播）
 * 4. 在线状态管理
 * 
 * 线程安全说明：
 * - 使用ConcurrentHashMap保证并发安全
 * - 使用DefaultChannelGroup进行连接的线程安全管理
 * - 所有操作都是原子的，支持高并发场景
 */
@Slf4j
public class NettyClientChannelManager {

    /**
     * 全局连接组
     * 用于存储和管理所有活动的客户端连接
     * 使用DefaultChannelGroup实现自动化的连接生命周期管理
     */
    private static final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 客户端ID到Channel的映射
     * 用于根据客户端ID快速找到对应的连接
     * key: 客户端ID
     * value: 客户端的连接
     */
    private static final ConcurrentMap<String, Channel> clientChannelMap = new ConcurrentHashMap<>();

    /**
     * Channel到客户端ID的映射
     * 用于根据连接快速找到对应的客户端ID
     * key: 连接的唯一标识
     * value: 客户端ID
     */
    private static final ConcurrentMap<ChannelId, String> channelClientMap = new ConcurrentHashMap<>();

    /**
     * 添加新的客户端连接
     * 将连接添加到全局连接组中进行统一管理
     *
     * @param clientId 客户端唯一标识符
     * @param channel 客户端的连接
     */
    public void addClient(String clientId, Channel channel) {
        channels.add(channel);
        clientChannelMap.put(clientId, channel);
        channelClientMap.put(channel.id(), clientId);
        log.info("客户端连接添加成功 - ID: {}, Channel: {}, 当前连接数: {}", 
                clientId, channel.id(), channels.size());
    }

    /**
     * 移除客户端连接
     * 清理连接资源和客户端映射关系
     *
     * @param clientId 要移除的客户端标识符
     */
    public void removeClient(String clientId) {
        Channel channel = clientChannelMap.remove(clientId);
        if (channel != null) {
            channels.remove(channel);
            channelClientMap.remove(channel.id());
            log.info("客户端连接移除 - ID: {}, Channel: {}, 当前连接数: {}", 
                    clientId, channel.id(), channels.size());
        }
    }

    /**
     * 获取指定客户端的连接
     *
     * @param clientId 客户端标识符
     * @return 对应的连接，如果不存在则返回null
     */
    public Channel getChannel(String clientId) {
        return clientChannelMap.get(clientId);
    }

    /**
     * 获取连接对应的客户端ID
     *
     * @param channelId 连接ID
     * @return 客户端ID，如果连接未绑定则返回null
     */
    public String getChannelClient(ChannelId channelId) {
        return channelClientMap.get(channelId);
    }

    /**
     * 向指定客户端发送消息
     *
     * @param clientId 目标客户端ID
     * @param message 要发送的消息内容
     * @return 发送是否成功
     */
    public boolean sendToClient(String clientId, String message) {
        Channel channel = clientChannelMap.get(clientId);
        if (!ObjectUtils.isEmpty(channel) && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        return false;
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
        log.info("广播消息发送给 {} 个连接: {}", channels.size(), message);
    }

    /**
     * 广播消息给除指定客户端外的所有在线客户端
     *
     * @param excludeClientId 需要排除的客户端ID
     * @param message 要广播的消息内容
     */
    public void broadcastExclude(String excludeClientId, String message) {
        Channel excludeChannel = clientChannelMap.get(excludeClientId);
        channels.stream()
                .filter(channel -> channel != excludeChannel && channel.isActive())
                .forEach(channel -> channel.writeAndFlush(new TextWebSocketFrame(message)));
        log.info("广播消息发送（排除客户端 {}）: {}", excludeClientId, message);
    }

    /**
     * 获取所有活跃的客户端连接
     * @return 所有客户端连接的集合
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
     * 检查指定客户端是否已连接
     * @param clientId 客户端标识符
     * @return true 如果客户端已连接且通道活跃，否则返回 false
     */
    public boolean isClientConnected(String clientId) {
        Channel channel = clientChannelMap.get(clientId);
        return channel != null && channel.isActive();
    }

    /**
     * 关闭并清理所有连接
     */
    public void closeAll() {
        channels.close();
        clientChannelMap.clear();
        channelClientMap.clear();
        log.info("所有客户端连接已关闭");
    }
}