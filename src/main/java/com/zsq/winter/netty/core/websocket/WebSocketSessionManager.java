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
 * 这是 WebSocket 连接会话的核心管理组件，负责维护所有活跃连接的状态和映射关系。
 * 采用线程安全的设计，支持高并发场景下的会话管理。
 * 
 * 核心功能：
 * 1. 会话生命周期管理：添加、移除会话
 * 2. 用户连接映射：维护 userId 与 Channel 的双向映射
 * 3. 在线状态查询：检查用户是否在线，获取用户连接
 * 4. 统计信息：在线用户数量统计
 * 5. 批量操作：获取所有连接，清空所有会话
 * 
 * 设计特点：
 * - 线程安全：所有修改操作都使用同步锁保护
 * - 双向映射：支持通过 userId 查找 Channel，也支持通过 Channel 查找 userId
 * - 自动清理：当用户重复连接时，自动关闭旧连接
 * - 高效查询：使用 ConcurrentHashMap 提供高性能的并发访问
 * 
 * 使用场景：
 * - 用户上线/下线管理
 * - 私聊消息路由（根据 userId 找到目标 Channel）
 * - 广播消息发送（获取所有活跃连接）
 * - 在线用户统计和监控
 * 
 * 线程安全说明：
 * - 读操作（如 getChannel、isOnline）无需同步，性能较高
 * - 写操作（如 addSession、removeSession）使用 synchronized 保护
 * - 使用 ConcurrentHashMap 作为底层存储，支持高并发读取
 * 
 * @author Winter Netty Team
 * @since 1.0.0
 */
@Slf4j
public class WebSocketSessionManager {

    /**
     * 存储所有已认证的 Channel
     * 
     * 使用 Netty 的 ChannelGroup 来管理多个 Channel：
     * - 自动处理 Channel 的生命周期
     * - 支持批量操作（如广播消息）
     * - 线程安全的集合操作
     * - 自动清理已关闭的 Channel
     */
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 用户ID与Channel的映射关系
     * 
     * 这是核心的映射表，用于根据用户ID快速找到对应的连接：
     * - key: 用户ID（String类型，业务系统中的用户唯一标识）
     * - value: 用户对应的 Netty Channel 对象
     * 
     * 使用场景：
     * - 私聊消息发送：根据接收者ID找到其连接
     * - 用户状态查询：检查特定用户是否在线
     * - 单用户操作：如踢出特定用户
     */
    private final Map<String, Channel> userChannelMap = new ConcurrentHashMap<>();

    /**
     * Channel与用户ID的映射关系
     * 
     * 这是反向映射表，用于根据 Channel 快速找到对应的用户ID：
     * - key: Channel的唯一ID（使用 channel.id().asLongText() 获取）
     * - value: 用户ID
     * 
     * 使用场景：
     * - 连接断开时清理：根据 Channel 找到用户ID进行清理
     * - 消息发送时验证：确认消息发送者的身份
     * - 日志记录：在日志中关联 Channel 和用户信息
     */
    private final Map<String, String> channelUserMap = new ConcurrentHashMap<>();

    /**
     * 添加用户会话
     * 
     * 将新的用户连接添加到会话管理器中，建立用户ID与Channel的映射关系。
     * 如果用户已存在连接，会自动关闭旧连接，确保每个用户只有一个活跃连接。
     * 
     * 操作步骤：
     * 1. 检查用户是否已存在连接
     * 2. 如果存在，关闭旧连接并清理相关映射
     * 3. 添加新连接到各个映射表中
     * 4. 记录连接建立日志
     * 
     * 线程安全：使用 synchronized 确保操作的原子性
     * 
     * @param userId 用户唯一标识，不能为 null 或空字符串
     * @param channel 用户的 Netty Channel 连接，不能为 null
     */
    public void addSession(String userId, Channel channel) {
        synchronized (this) {
            // 检查该用户是否已经存在连接
            if (userChannelMap.containsKey(userId)) {
                Channel oldChannel = userChannelMap.get(userId);
                // 在同步块内移除旧连接，避免竞态条件
                // 这确保了每个用户只能有一个活跃连接
                removeSessionInternal(oldChannel);
                log.warn("用户 {} 已存在连接，关闭旧连接", userId);
            }

            // 添加新连接到所有映射表
            channels.add(channel);                                    // 添加到 Channel 集合
            userChannelMap.put(userId, channel);                     // 建立 userId -> Channel 映射
            channelUserMap.put(channel.id().asLongText(), userId);   // 建立 Channel -> userId 映射
        }
        
        log.info("用户 {} 建立连接，当前在线人数: {}", userId, getOnlineCount());
    }

    /**
     * 移除用户会话（外部接口）
     * 
     * 这是移除会话的公共接口，提供线程安全的会话移除操作。
     * 通常在连接断开时调用，清理相关的映射关系。
     * 
     * @param channel 要移除的 Channel 连接
     */
    public void removeSession(Channel channel) {
        synchronized (this) {
            removeSessionInternal(channel);
        }
    }

    /**
     * 移除用户会话（内部实现）
     * 
     * 实际执行会话移除的内部方法，清理所有相关的映射关系：
     * 1. 根据 Channel 找到对应的用户ID
     * 2. 从所有映射表中移除相关记录
     * 3. 记录断开连接日志
     * 
     * 注意：此方法假设调用者已经获取了同步锁
     * 
     * @param channel 要移除的 Channel 连接
     */
    private void removeSessionInternal(Channel channel) {
        // 获取 Channel 的唯一标识
        String channelId = channel.id().asLongText();
        // 根据 Channel ID 移除并获取对应的用户ID,
        String userId = channelUserMap.remove(channelId);
        
        if (userId != null) {
            // 找到了对应的用户，清理所有相关映射
            userChannelMap.remove(userId);           // 移除 userId -> Channel 映射
            channels.remove(channel);                // 从 Channel 集合中移除
            log.info("用户 {} 断开连接，当前在线人数: {}", userId, getOnlineCount());
        }
        // 如果 userId 为 null，说明这个 Channel 可能已经被清理过了，或者从未正确添加
    }

    /**
     * 根据用户ID获取对应的Channel连接
     * 
     * 这是一个高频使用的查询方法，主要用于：
     * - 私聊消息发送：根据接收者ID找到其连接
     * - 单用户操作：如向特定用户推送消息
     * - 用户状态检查：配合 isOnline 方法使用
     * 
     * 性能特点：
     * - 无需同步锁，读取性能高
     * - 基于 ConcurrentHashMap，支持高并发访问
     * - 时间复杂度 O(1)
     * 
     * @param userId 用户唯一标识
     * @return 用户对应的 Channel 连接，如果用户不在线则返回 null
     */
    public Channel getChannel(String userId) {
        return userChannelMap.get(userId);
    }

    /**
     * 根据Channel获取对应的用户ID
     * 
     * 这是反向查询方法，主要用于：
     * - 连接断开时的清理工作
     * - 消息处理时确认发送者身份
     * - 日志记录和监控
     * 
     * @param channel Channel 连接对象
     * @return 对应的用户ID，如果找不到则返回 null
     */
    public String getUserId(Channel channel) {
        return channelUserMap.get(channel.id().asLongText());
    }

    /**
     * 获取所有在线用户的Channel集合
     * 
     * 返回包含所有活跃连接的 ChannelGroup，主要用于：
     * - 广播消息：向所有在线用户发送消息
     * - 批量操作：如服务器关闭时断开所有连接
     * - 监控统计：获取详细的连接信息
     * 
     * 注意：返回的是内部集合的引用，请谨慎使用
     * 
     * @return 包含所有活跃连接的 ChannelGroup
     */
    public ChannelGroup getAllChannels() {
        return channels;
    }

    /**
     * 获取当前在线用户数量
     * 
     * 这是一个轻量级的统计方法，用于：
     * - 系统监控：实时监控在线用户数
     * - 负载评估：评估服务器负载情况
     * - 业务统计：用户活跃度统计
     * 
     * @return 当前在线用户数量
     */
    public int getOnlineCount() {
        return channels.size();
    }

    /**
     * 检查指定用户是否在线
     * 
     * 不仅检查用户是否存在映射关系，还会验证连接是否仍然活跃。
     * 这确保了返回结果的准确性，避免返回已断开的连接。
     * 
     * 检查逻辑：
     * 1. 查找用户对应的 Channel
     * 2. 验证 Channel 是否不为 null
     * 3. 验证 Channel 是否仍然活跃（isActive）
     * 
     * @param userId 用户唯一标识
     * @return true 表示用户在线且连接活跃，false 表示用户离线或连接已断开
     */
    public boolean isOnline(String userId) {
        Channel channel = userChannelMap.get(userId);
        return channel != null && channel.isActive();
    }

    /**
     * 清空所有会话
     * 
     * 这是一个危险操作，会清理所有的连接和映射关系。
     * 通常在以下场景使用：
     * - 服务器关闭时的清理工作
     * - 系统重置或维护
     * - 测试环境的数据清理
     * 
     * 操作步骤：
     * 1. 清空 Channel 集合（会自动关闭所有连接）
     * 2. 清空用户ID到Channel的映射
     * 3. 清空Channel到用户ID的映射
     * 4. 记录清理日志
     * 
     * 注意：此操作不可逆，请谨慎使用
     */
    public void clear() {
        channels.clear();        // 清空并关闭所有 Channel
        userChannelMap.clear();  // 清空 userId -> Channel 映射
        channelUserMap.clear();  // 清空 Channel -> userId 映射
        log.info("清空所有会话");
    }
}
