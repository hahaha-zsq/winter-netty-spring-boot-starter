package com.zsq.winter.netty.service;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.core.server.NettyServerChannelManager;
import com.zsq.winter.netty.entity.NettyMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 消息推送服务
 * 
 * 主要功能：
 * 1. 消息发送：
 *   - 同步发送：直接发送消息并等待结果
 *   - 异步发送：返回Future对象，支持回调处理
 *   - 广播：向所有连接发送消息
 * 2. 连接管理：
 *   - 查询所有连接信息
 *   - 监控连接状态
 */
@Slf4j
public class NettyServerPushTemplate {

    private final NettyServerChannelManager channelManager;
    
    /**
     * 待处理的异步请求映射表
     * key: 消息ID
     * value: 异步结果Future
     */
    private final Map<String, CompletableFuture<Boolean>> pendingRequests = new ConcurrentHashMap<>();

    public NettyServerPushTemplate(NettyServerChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /**
     * 向指定Channel发送消息（同步方式）
     *
     * @param channel 目标Channel
     * @param content 消息内容
     * @return 是否发送成功
     */
    public boolean pushMessage(Channel channel, String content) {
        try {
            NettyMessage message = NettyMessage.text(content);
            message.setMessageId(UUID.randomUUID().toString());
            return doPushMessage(channel, message);
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 向指定Channel发送消息（异步方式）
     *
     * @param channel 目标Channel
     * @param content 消息内容
     * @return 包含发送结果的Future对象
     */
    public CompletableFuture<Boolean> pushMessageAsync(Channel channel, String content) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            NettyMessage message = NettyMessage.text(content);
            message.setMessageId(UUID.randomUUID().toString());
            pendingRequests.put(message.getMessageId(), future);
            
            if (!doPushMessage(channel, message)) {
                future.complete(false);
                pendingRequests.remove(message.getMessageId());
            }
        } catch (Exception e) {
            log.error("异步发送消息失败: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 向指定Channel发送系统消息（同步方式）
     *
     * @param channel 目标Channel
     * @param content 消息内容
     * @return 是否发送成功
     */
    public boolean pushSystemMessage(Channel channel, String content) {
        try {
            NettyMessage message = NettyMessage.system(content);
            message.setMessageId(UUID.randomUUID().toString());
            return doPushMessage(channel, message);
        } catch (Exception e) {
            log.error("发送系统消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 向指定Channel发送系统消息（异步方式）
     *
     * @param channel 目标Channel
     * @param content 消息内容
     * @return 包含发送结果的Future对象
     */
    public CompletableFuture<Boolean> pushSystemMessageAsync(Channel channel, String content) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            NettyMessage message = NettyMessage.system(content);
            message.setMessageId(UUID.randomUUID().toString());
            pendingRequests.put(message.getMessageId(), future);
            
            if (!doPushMessage(channel, message)) {
                future.complete(false);
                pendingRequests.remove(message.getMessageId());
            }
        } catch (Exception e) {
            log.error("异步发送系统消息失败: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 广播消息（同步方式）
     *
     * @param content 消息内容
     * @return 是否发送成功
     */
    public boolean broadcast(String content) {
        try {
            NettyMessage message = NettyMessage.broadcast("system", content);
            message.setMessageId(UUID.randomUUID().toString());
            String jsonMessage = JSONUtil.toJsonStr(message);
            channelManager.broadcast(jsonMessage);
            log.info("广播消息发送成功 - 内容: {}", content);
            return true;
        } catch (Exception e) {
            log.error("广播消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 广播消息（异步方式）
     *
     * @param content 消息内容
     * @return 包含发送结果的Future对象
     */
    public CompletableFuture<Boolean> broadcastAsync(String content) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            NettyMessage message = NettyMessage.broadcast("system", content);
            message.setMessageId(UUID.randomUUID().toString());
            String jsonMessage = JSONUtil.toJsonStr(message);
            channelManager.broadcast(jsonMessage);
            future.complete(true);
            log.info("异步广播消息发送成功 - 内容: {}", content);
        } catch (Exception e) {
            log.error("异步广播消息失败: {}", e.getMessage(), e);
            future.complete(false);
        }
        return future;
    }

    /**
     * 获取所有Channel的信息
     *
     * @return Channel信息列表
     */
    public List<Map<String, Object>> getAllChannelInfo() {
        List<Map<String, Object>> channelInfoList = new ArrayList<>();
        Collection<Channel> channels = channelManager.getAllChannels();
        
        for (Channel channel : channels) {
            Map<String, Object> info = new HashMap<>();
            info.put("channelId", channel.id().asLongText());
            info.put("remoteAddress", channel.remoteAddress().toString());
            info.put("isActive", channel.isActive());
            info.put("isOpen", channel.isOpen());
            info.put("isWritable", channel.isWritable());
            
            channelInfoList.add(info);
        }
        
        return channelInfoList;
    }

    /**
     * 获取指定Channel的详细信息
     *
     * @param channelId Channel ID
     * @return Channel详细信息
     */
    public Map<String, Object> getChannelInfo(ChannelId channelId) {
        Collection<Channel> channels = channelManager.getAllChannels();
        for (Channel channel : channels) {
            if (channel.id().equals(channelId)) {
                Map<String, Object> info = new HashMap<>();
                info.put("channelId", channel.id().asLongText());
                info.put("remoteAddress", channel.remoteAddress().toString());
                info.put("isActive", channel.isActive());
                info.put("isOpen", channel.isOpen());
                info.put("isWritable", channel.isWritable());
                info.put("localAddress", channel.localAddress().toString());
                info.put("pipeline", channel.pipeline().toString());
                return info;
            }
        }
        return null;
    }

    /**
     * 完成消息发送请求
     *
     * @param messageId 消息ID
     * @param success 是否发送成功
     */
    public void completeRequest(String messageId, boolean success) {
        CompletableFuture<Boolean> future = pendingRequests.remove(messageId);
        if (future != null) {
            future.complete(success);
        }
    }

    /**
     * 实际执行消息发送的内部方法
     */
    private boolean doPushMessage(Channel channel, NettyMessage message) {
        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            return channelManager.sendToChannel(channel, jsonMessage);
        } catch (Exception e) {
            log.error("消息发送失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
