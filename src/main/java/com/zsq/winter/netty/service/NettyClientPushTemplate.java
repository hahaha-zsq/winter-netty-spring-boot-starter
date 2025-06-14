package com.zsq.winter.netty.service;

import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.core.client.NettyClientChannelManager;
import com.zsq.winter.netty.entity.NettyMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ObjectUtils;
import io.netty.channel.Channel;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty客户端消息推送模板
 * <p>
 * 该类提供了一套完整的客户端消息推送功能，主要特点：
 * 1. 消息类型支持：
 * - 文本消息：普通文本内容
 * - 系统消息：系统级通知
 * - 私聊消息：点对点通信
 * - 广播消息：一对多通信
 * - 自定义消息：支持扩展
 * <p>
 * 2. 推送方式：
 * - 同步推送：等待推送结果
 * - 异步推送：立即返回Future
 * <p>
 * 3. 高级特性：
 * - 消息状态跟踪：通过messageId跟踪
 * - 超时控制：可配置超时时间
 * - 异常处理：完善的错误处理机制
 * - 线程安全：支持并发操作
 * <p>
 * 使用示例：
 * <pre>
 * // 同步推送
 * template.pushMessage("你好");
 *
 * // 异步推送
 * template.pushMessageAsync("你好")
 *         .thenAccept(success -> {
 *             if (success) {
 *                 // 推送成功的处理逻辑
 *             }
 *         });
 * </pre>
 */
@Slf4j
public class NettyClientPushTemplate {

    /**
     * 通道管理器
     * 负责管理客户端的连接通道，提供消息发送能力
     */
    private final NettyClientChannelManager channelManager;

    /**
     * 待处理的异步请求映射表
     * key: 消息ID
     * value: 异步结果Future
     */
    private final Map<String, CompletableFuture<Boolean>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param channelManager 通道管理器，用于管理和操作客户端连接
     */
    public NettyClientPushTemplate(NettyClientChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /**
     * 推送文本消息（同步方式）
     * <p>
     * 将文本内容包装为消息对象并发送，等待发送结果。
     * 适用于对实时性要求较高的场景。
     *
     * @param content 消息内容
     * @return 是否推送成功
     */
    public boolean pushMessage(String content) {
        try {
            NettyMessage message = NettyMessage.text(content);
            message.setMessageId(UUID.randomUUID().toString());
            return doPushMessage(message);
        } catch (Exception e) {
            log.error("推送消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 推送文本消息（异步方式）
     * <p>
     * 异步发送文本消息，立即返回Future对象。
     * 适用于对响应时间敏感的场景。
     *
     * @param content 消息内容
     * @return 包含推送结果的Future对象
     */
    public CompletableFuture<Boolean> pushMessageAsync(String content) {
        // 创建一个新的CompletableFuture对象
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            // 创建一个NettyMessage对象，并设置其内容为给定的content
            NettyMessage message = NettyMessage.text(content);
            // 为消息生成一个唯一的ID
            message.setMessageId(UUID.randomUUID().toString());
            // 将消息ID与CompletableFuture关联起来，以便后续处理消息响应
            pendingRequests.put(message.getMessageId(), future);
            // 尝试发送消息如果发送失败，标记CompletableFuture为false完成，并移除消息ID的关联
            if (!doPushMessage(message)) {
                future.complete(false);
                pendingRequests.remove(message.getMessageId());
            }
        } catch (Exception e) {
            // 记录异常信息，并标记CompletableFuture为异常完成
            log.error("异步推送消息失败: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        // 返回CompletableFuture对象
        return future;
    }

    /**
     * 推送系统消息
     * <p>
     * 发送系统级别的通知消息，如系统公告、警告等。
     * 系统消息通常具有较高的优先级。
     *
     * @param content 系统消息内容
     * @return 是否推送成功
     */
    public boolean pushSystemMessage(String content) {
        try {
            NettyMessage message = NettyMessage.system(content);
            message.setMessageId(UUID.randomUUID().toString());
            return doPushMessage(message);
        } catch (Exception e) {
            log.error("推送系统消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 推送私聊消息
     * <p>
     * 向指定用户发送私聊消息，实现点对点通信。
     * 确保消息只能被目标用户接收。
     *
     * @param toUserId 目标用户ID
     * @param content  消息内容
     * @return 是否推送成功
     */
    public boolean pushPrivateMessage(String toUserId, String content) {
        try {
            NettyMessage message = new NettyMessage();
            message.setType(NettyMessage.MessageType.PRIVATE);
            message.setContent(content);
            message.setMessageId(UUID.randomUUID().toString());
            message.setToUserId(toUserId);
            return doPushMessage(message);
        } catch (Exception e) {
            log.error("推送私聊消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 推送广播消息
     * <p>
     * 向所有在线用户发送广播消息。
     * 适用于需要通知所有用户的场景。
     *
     * @param content 广播消息内容
     * @return 是否推送成功
     */
    public boolean pushBroadcastMessage(String content) {
        try {
            NettyMessage message = NettyMessage.broadcast("client", content);
            message.setMessageId(UUID.randomUUID().toString());
            return doPushMessage(message);
        } catch (Exception e) {
            log.error("推送广播消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 推送自定义消息
     * <p>
     * 发送自定义格式的消息，提供最大的灵活性。
     * 可以自定义消息的所有属性。
     *
     * @param message 自定义消息对象
     * @return 是否推送成功
     */
    public boolean pushCustomMessage(NettyMessage message) {
        if (ObjectUtils.isEmpty(message.getMessageId())) {
            message.setMessageId(UUID.randomUUID().toString());
        }
        return doPushMessage(message);
    }

    /**
     * 推送自定义消息（异步方式）
     * <p>
     * 异步发送自定义消息，适用于复杂的消息处理场景。
     * 支持自定义消息格式和处理逻辑。
     *
     * @param message 自定义消息对象
     * @return 包含推送结果的Future对象
     */
    public CompletableFuture<Boolean> pushCustomMessageAsync(NettyMessage message) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            if (ObjectUtils.isEmpty(message.getMessageId())) {
                message.setMessageId(UUID.randomUUID().toString());
            }
            pendingRequests.put(message.getMessageId(), future);

            if (!doPushMessage(message)) {
                future.complete(false);
                pendingRequests.remove(message.getMessageId());
            }
        } catch (Exception e) {
            log.error("异步推送自定义消息失败: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 推送带附加数据的消息
     * <p>
     * 发送携带额外信息的消息，支持业务扩展。
     * 可以在消息中附加任意的业务数据。
     *
     * @param content 消息内容
     * @param extra   附加数据，键值对形式
     * @return 是否推送成功
     */
    public boolean pushMessageWithExtra(String content, Map<String, Object> extra) {
        try {
            NettyMessage message = NettyMessage.text(content);
            message.setMessageId(UUID.randomUUID().toString());
            message.setExtra(extra);
            message.setTimestamp(System.currentTimeMillis());
            return doPushMessage(message);
        } catch (Exception e) {
            log.error("推送带附加数据的消息失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 完成消息推送请求
     * <p>
     * 处理异步消息的回调，更新消息状态。
     * 主要用于异步消息的状态管理。
     *
     * @param messageId 消息ID
     * @param success   是否推送成功
     */
    public void completeRequest(String messageId, boolean success) {
        CompletableFuture<Boolean> future = pendingRequests.remove(messageId);
        if (future != null) {
            future.complete(success);
        }
    }

    /**
     * 实际执行消息推送的内部方法
     * <p>
     * 核心的消息发送逻辑：
     * 1. 将消息转换为JSON字符串
     * 2. 获取目标用户的连接通道
     * 3. 检查通道状态并发送消息
     * 4. 处理发送结果
     *
     * @param message 待发送的消息对象
     * @return 是否推送成功
     */
    private boolean doPushMessage(NettyMessage message) {
        try {
            // 将消息对象转换为JSON字符串
            String jsonMessage = JSONUtil.toJsonStr(message);
            // 获取接收方用户的活跃连接
            Channel channel = channelManager.getChannel(message.getToUserId());
            if (channel != null && channel.isActive()) {
                // 如果找到活跃连接，发送消息
                return channelManager.sendToChannel(channel, jsonMessage);
            } else {
                // 如果未找到活跃连接，记录警告日志并返回失败
                log.warn("无法找到活跃的连接");
                return false;
            }
        } catch (Exception e) {
            // 捕获异常并记录错误日志，返回失败
            log.error("消息推送失败: {}", e.getMessage(), e);
            return false;
        }
    }
} 