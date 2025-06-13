package com.zsq.winter.netty.core.client;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 客户端连接管理器
 */
@Slf4j
public class NettyClientChannelManager {

    private static final AtomicInteger connectionCounter = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, Channel> clientChannels = new ConcurrentHashMap<>();

    public void addClient(String clientId, Channel channel) {
        clientChannels.put(clientId, channel);
        connectionCounter.incrementAndGet();
        log.info("客户端连接添加成功: {}", clientId);
    }

    public void removeClient(String clientId) {
        clientChannels.remove(clientId);
        connectionCounter.decrementAndGet();
        log.info("客户端连接移除: {}", clientId);
    }

    public Channel getClient(String clientId) {
        return clientChannels.get(clientId);
    }

    public int getTotalConnections() {
        return connectionCounter.get();
    }

    public void closeAll() {
        clientChannels.forEach((id, channel) -> channel.close());
        clientChannels.clear();
        connectionCounter.set(0);
        log.info("所有客户端连接已关闭");
    }
}