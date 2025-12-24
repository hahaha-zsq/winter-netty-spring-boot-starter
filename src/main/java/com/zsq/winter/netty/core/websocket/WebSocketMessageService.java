package com.zsq.winter.netty.core.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zsq.winter.netty.entity.NettyMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * WebSocket 消息服务（安全增强版）
 * 
 * 为第三方提供 WebSocket 消息推送能力，支持：
 * 1. 推送消息给指定用户
 * 2. 广播消息给所有在线用户（可选是否排除发送者）
 * 3. 发送系统消息
 * 4. 发送私聊消息
 * 5. 批量推送消息
 * 6. 异步消息发送
 * 7. 消息发送状态回调
 * 8. 消息发送统计
 * 
 * 安全特性：
 * - 发送方身份验证：确保消息发送者身份合法
 * - 权限控制：验证发送方是否有权限发送消息给接收方
 * - 系统消息权限：只有管理员可以发送系统消息
 * - 广播权限：只有授权用户可以发送广播消息
 * - 防止恶意攻击：限制消息发送频率和内容长度
 * 
 * 优化特性：
 * - 统一的参数验证和错误处理
 * - 异步消息发送支持
 * - 消息发送状态跟踪
 * - 内存优化的批量操作
 * - 详细的发送统计信息
 * - 消息序列化缓存优化
 * 
 * 使用示例：
 * <pre>
 * {@code
 * @Service
 * public class NotificationService {
 *     
 *     @Autowired
 *     private WebSocketMessageService messageService;
 *     
 *     // 系统消息发送（需要管理员权限）
 *     public void notifyUser(String adminToken, String userId, String content) {
 *         SendResult result = messageService.sendSystemMessage(adminToken, userId, content);
 *         if (!result.isSuccess()) {
 *             log.error("发送失败: {}", result.getErrorMessage());
 *         }
 *     }
 *     
 *     // 私聊消息发送（需要发送方认证）
 *     public void sendPrivateMessage(String senderToken, String toUserId, String content) {
 *         SendResult result = messageService.sendPrivateMessage(senderToken, toUserId, content);
 *         if (!result.isSuccess()) {
 *             log.error("发送失败: {}", result.getErrorMessage());
 *         }
 *     }
 *     
 *     // 异步发送
 *     public void notifyUserAsync(String token, String userId, String content) {
 *         messageService.sendSystemMessageAsync(token, userId, content)
 *             .thenAccept(result -> {
 *                 if (result.isSuccess()) {
 *                     log.info("消息发送成功");
 *                 } else {
 *                     log.warn("消息发送失败: {}", result.getErrorMessage());
 *                 }
 *             });
 *     }
 * }
 * }
 * </pre>
 */
@Slf4j
public class WebSocketMessageService {

    private final WebSocketSessionManager sessionManager;
    private final TokenAuthenticator tokenAuthenticator;
    private final MessagePermissionValidator permissionValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 消息发送统计
     */
    private final AtomicInteger totalSentMessages = new AtomicInteger(0);
    private final AtomicInteger totalFailedMessages = new AtomicInteger(0);
    private final AtomicInteger totalBlockedMessages = new AtomicInteger(0);
    
    /**
     * 消息缓存，用于优化重复消息的序列化
     */
    private final Map<String, String> messageCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 1000;
    
    /**
     * 消息内容最大长度限制
     */
    private static final int MAX_MESSAGE_LENGTH = 30000;
    
    /**
     * 发送频率限制（每个用户每秒最多发送的消息数）
     */
    private final Map<String, MessagePermissionValidator.RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private static final int MAX_MESSAGES_PER_SECOND = 10;

    public WebSocketMessageService(WebSocketSessionManager sessionManager, 
                                 TokenAuthenticator tokenAuthenticator,
                                 MessagePermissionValidator permissionValidator) {

        this.sessionManager = sessionManager;
        this.tokenAuthenticator = tokenAuthenticator;
        this.permissionValidator = permissionValidator;
        
        log.info("WebSocketMessageService 初始化完成，使用 TokenAuthenticator: {}, MessagePermissionValidator: {}", 
            this.tokenAuthenticator.getClass().getSimpleName(),
            this.permissionValidator.getClass().getSimpleName());
    }

    // ==================== 安全验证方法 ====================

    /**
     * 验证发送方身份和权限
     */
    private MessagePermissionValidator.SecurityValidationResult validateSecurity(String token, MessagePermissionValidator.Operation operation, String targetUserId) {
        // 1. Token 验证
        if (!StringUtils.hasText(token)) {
            log.warn("Token 不能为空");
            return MessagePermissionValidator.SecurityValidationResult.failure("Token 不能为空");
        }
        
        TokenAuthenticator.AuthResult authResult = tokenAuthenticator.authenticate(token);
        if (!authResult.isSuccess()) {
            log.warn("Token 验证失败: {}", authResult.getErrorMessage());
            totalBlockedMessages.incrementAndGet();
            return MessagePermissionValidator.SecurityValidationResult.failure("身份验证失败: " + authResult.getErrorMessage());
        }

        String senderId = authResult.getUserId();

        // 2. 发送方在线验证
        if (!sessionManager.isOnline(senderId)) {
            log.warn("发送方 {} 不在线", senderId);
            return MessagePermissionValidator.SecurityValidationResult.failure("发送方不在线");
        }

        // 3. 频率限制验证
        if (!checkRateLimit(senderId)) {
            log.warn("用户 {} 发送消息过于频繁", senderId);
            totalBlockedMessages.incrementAndGet();
            return MessagePermissionValidator.SecurityValidationResult.failure("发送消息过于频繁，请稍后再试");
        }

        // 4. 权限验证
        if (!permissionValidator.hasPermission(senderId, operation, targetUserId)) {
            log.warn("用户 {} 没有权限执行操作: {} -> {}", senderId, operation, targetUserId);
            totalBlockedMessages.incrementAndGet();
            return MessagePermissionValidator.SecurityValidationResult.failure("没有权限执行此操作");
        }

        return MessagePermissionValidator.SecurityValidationResult.success(senderId);
    }

    /**
     * 检查发送频率限制
     */
    private boolean checkRateLimit(String userId) {
        MessagePermissionValidator.RateLimiter rateLimiter = rateLimiters.computeIfAbsent(userId, 
            k -> new MessagePermissionValidator.RateLimiter(MAX_MESSAGES_PER_SECOND));
        return rateLimiter.tryAcquire();
    }

    // ==================== 核心发送方法 ====================

    /**
     * 推送消息给指定用户（核心方法 - 安全版本）
     * 
     * @param senderId 发送者ID（已验证）
     * @param userId 用户ID
     * @param message 消息对象
     * @param callback 发送结果回调（可选）
     * @return 发送结果
     */
    private MessagePermissionValidator.SendResult sendToUser(String senderId, String userId, NettyMessage message, Consumer<MessagePermissionValidator.SendResult> callback) {
        // 参数验证
        if (!validateUserId(userId) || !validateMessage(message)) {
            MessagePermissionValidator.SendResult result = MessagePermissionValidator.SendResult.failure("参数验证失败");
            if (callback != null) callback.accept(result);
            return result;
        }

        // 内容长度验证
        if (message.getContent() != null && message.getContent().length() > MAX_MESSAGE_LENGTH) {
            log.warn("消息内容过长，发送者: {}, 长度: {}", senderId, message.getContent().length());
            MessagePermissionValidator.SendResult result = MessagePermissionValidator.SendResult.failure("消息内容过长");
            if (callback != null) callback.accept(result);
            return result;
        }

        Channel channel = sessionManager.getChannel(userId);
        if (channel == null || !channel.isActive()) {
            log.warn("用户 {} 不在线，无法发送消息", userId);
            MessagePermissionValidator.SendResult result = MessagePermissionValidator.SendResult.failure("接收方不在线");
            if (callback != null) callback.accept(result);
            return result;
        }

        // 确保消息有时间戳和发送者信息
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }
        if (message.getFromUserId() == null) {
            message.setFromUserId(senderId);
        }

        try {
            String jsonMessage = serializeMessage(message);
            ChannelFuture future = channel.writeAndFlush(new TextWebSocketFrame(jsonMessage));
            
            // 异步处理发送结果
            future.addListener(f -> {
                MessagePermissionValidator.SendResult result;
                if (f.isSuccess()) {
                    totalSentMessages.incrementAndGet();
                    log.debug("发送消息给用户 {}: {}", userId, jsonMessage);
                    result = MessagePermissionValidator.SendResult.success();
                } else {
                    totalFailedMessages.incrementAndGet();
                    log.error("发送消息给用户 {} 失败: {}", userId, f.cause().getMessage());
                    result = MessagePermissionValidator.SendResult.failure("网络发送失败: " + f.cause().getMessage());
                }
                if (callback != null) callback.accept(result);
            });
            
            return MessagePermissionValidator.SendResult.success();
        } catch (Exception e) {
            log.error("发送消息给用户 {} 失败", userId, e);
            totalFailedMessages.incrementAndGet();
            MessagePermissionValidator.SendResult result = MessagePermissionValidator.SendResult.failure("序列化失败: " + e.getMessage());
            if (callback != null) callback.accept(result);
            return result;
        }
    }

    // ==================== 安全的公共API方法 ====================

    /**
     * 发送系统消息给指定用户（需要管理员权限）
     * 
     * @param adminToken 管理员Token
     * @param userId 接收用户ID
     * @param content 消息内容
     * @return 发送结果
     */
    public MessagePermissionValidator.SendResult sendSystemMessage(String adminToken, String userId, String content) {
        if (!validateContent(content)) {
            return MessagePermissionValidator.SendResult.failure("消息内容不能为空");
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(adminToken, MessagePermissionValidator.Operation.SEND_SYSTEM_MESSAGE, userId);
        if (!securityResult.isSuccess()) {
            return MessagePermissionValidator.SendResult.failure(securityResult.getErrorMessage());
        }

        NettyMessage message = NettyMessage.system(content);
        return sendToUser(securityResult.getSenderId(), userId, message, null);
    }

    /**
     * 发送系统消息给指定用户（带回调）
     */
    public void sendSystemMessage(String adminToken, String userId, String content, Consumer<MessagePermissionValidator.SendResult> callback) {
        if (!validateContent(content)) {
            if (callback != null) callback.accept(MessagePermissionValidator.SendResult.failure("消息内容不能为空"));
            return;
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(adminToken, MessagePermissionValidator.Operation.SEND_SYSTEM_MESSAGE, userId);
        if (!securityResult.isSuccess()) {
            if (callback != null) callback.accept(MessagePermissionValidator.SendResult.failure(securityResult.getErrorMessage()));
            return;
        }

        NettyMessage message = NettyMessage.system(content);
        sendToUser(securityResult.getSenderId(), userId, message, callback);
    }

    /**
     * 发送私聊消息
     * 
     * @param senderToken 发送者Token
     * @param toUserId 接收者ID
     * @param content 消息内容
     * @return 发送结果
     */
    public MessagePermissionValidator.SendResult sendPrivateMessage(String senderToken, String toUserId, String content) {
        if (!validateUserId(toUserId) || !validateContent(content)) {
            return MessagePermissionValidator.SendResult.failure("参数验证失败");
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(senderToken, MessagePermissionValidator.Operation.SEND_PRIVATE_MESSAGE, toUserId);
        if (!securityResult.isSuccess()) {
            return MessagePermissionValidator.SendResult.failure(securityResult.getErrorMessage());
        }

        NettyMessage message = NettyMessage.privateMessage(securityResult.getSenderId(), toUserId, content);
        MessagePermissionValidator.SendResult result = sendToUser(securityResult.getSenderId(), toUserId, message, null);
        
        if (result.isSuccess()) {
            log.info("用户 {} 私聊用户 {}", securityResult.getSenderId(), toUserId);
        }
        return result;
    }

    /**
     * 发送私聊消息（带回调）
     */
    public void sendPrivateMessage(String senderToken, String toUserId, String content, Consumer<MessagePermissionValidator.SendResult> callback) {
        if (!validateUserId(toUserId) || !validateContent(content)) {
            if (callback != null) callback.accept(MessagePermissionValidator.SendResult.failure("参数验证失败"));
            return;
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(senderToken, MessagePermissionValidator.Operation.SEND_PRIVATE_MESSAGE, toUserId);
        if (!securityResult.isSuccess()) {
            if (callback != null) callback.accept(MessagePermissionValidator.SendResult.failure(securityResult.getErrorMessage()));
            return;
        }

        NettyMessage message = NettyMessage.privateMessage(securityResult.getSenderId(), toUserId, content);
        sendToUser(securityResult.getSenderId(), toUserId, message, result -> {
            if (result.isSuccess()) {
                log.info("用户 {} 私聊用户 {}", securityResult.getSenderId(), toUserId);
            }
            if (callback != null) callback.accept(result);
        });
    }

    /**
     * 广播系统消息（需要管理员权限）
     * 
     * @param adminToken 管理员Token
     * @param content 消息内容
     * @return 成功接收消息的用户数
     */
    public MessagePermissionValidator.BroadcastResult broadcastSystemMessage(String adminToken, String content) {
        return broadcastSystemMessage(adminToken, content, null);
    }

    /**
     * 广播系统消息（可排除指定用户）
     * 
     * @param adminToken 管理员Token
     * @param content 消息内容
     * @param excludeUserId 需要排除的用户ID
     * @return 广播结果
     */
    public MessagePermissionValidator.BroadcastResult broadcastSystemMessage(String adminToken, String content, String excludeUserId) {
        if (!validateContent(content)) {
            return MessagePermissionValidator.BroadcastResult.failure("消息内容不能为空");
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(adminToken, MessagePermissionValidator.Operation.BROADCAST_SYSTEM_MESSAGE, null);
        if (!securityResult.isSuccess()) {
            return MessagePermissionValidator.BroadcastResult.failure(securityResult.getErrorMessage());
        }

        NettyMessage message = NettyMessage.system(content);
        int successCount = broadcastInternal(message, excludeUserId);
        return MessagePermissionValidator.BroadcastResult.success(successCount);
    }

    /**
     * 广播普通消息（由指定用户发起）
     * 
     * @param senderToken 发送者Token
     * @param content 消息内容
     * @return 广播结果
     */
    public MessagePermissionValidator.BroadcastResult broadcastMessage(String senderToken, String content) {
        if (!validateContent(content)) {
            return MessagePermissionValidator.BroadcastResult.failure("消息内容不能为空");
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(senderToken, MessagePermissionValidator.Operation.BROADCAST_MESSAGE, null);
        if (!securityResult.isSuccess()) {
            return MessagePermissionValidator.BroadcastResult.failure(securityResult.getErrorMessage());
        }

        NettyMessage message = NettyMessage.broadcast(securityResult.getSenderId(), content);
        // 广播时排除发送者本人
        int successCount = broadcastInternal(message, securityResult.getSenderId());
        return MessagePermissionValidator.BroadcastResult.success(successCount);
    }

    // ==================== 异步发送方法 ====================

    /**
     * 异步发送系统消息给指定用户
     */
    public CompletableFuture<MessagePermissionValidator.SendResult> sendSystemMessageAsync(String adminToken, String userId, String content) {
        CompletableFuture<MessagePermissionValidator.SendResult> future = new CompletableFuture<>();
        
        if (!validateContent(content)) {
            future.complete(MessagePermissionValidator.SendResult.failure("消息内容不能为空"));
            return future;
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(adminToken, MessagePermissionValidator.Operation.SEND_SYSTEM_MESSAGE, userId);
        if (!securityResult.isSuccess()) {
            future.complete(MessagePermissionValidator.SendResult.failure(securityResult.getErrorMessage()));
            return future;
        }

        NettyMessage message = NettyMessage.system(content);
        sendToUser(securityResult.getSenderId(), userId, message, future::complete);
        return future;
    }

    /**
     * 异步发送私聊消息
     */
    public CompletableFuture<MessagePermissionValidator.SendResult> sendPrivateMessageAsync(String senderToken, String toUserId, String content) {
        CompletableFuture<MessagePermissionValidator.SendResult> future = new CompletableFuture<>();
        
        if (!validateUserId(toUserId) || !validateContent(content)) {
            future.complete(MessagePermissionValidator.SendResult.failure("参数验证失败"));
            return future;
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(senderToken, MessagePermissionValidator.Operation.SEND_PRIVATE_MESSAGE, toUserId);
        if (!securityResult.isSuccess()) {
            future.complete(MessagePermissionValidator.SendResult.failure(securityResult.getErrorMessage()));
            return future;
        }

        NettyMessage message = NettyMessage.privateMessage(securityResult.getSenderId(), toUserId, content);
        sendToUser(securityResult.getSenderId(), toUserId, message, result -> {
            if (result.isSuccess()) {
                log.info("用户 {} 私聊用户 {}", securityResult.getSenderId(), toUserId);
            }
            future.complete(result);
        });
        return future;
    }

    /**
     * 异步广播系统消息
     */
    public CompletableFuture<MessagePermissionValidator.BroadcastResult> broadcastSystemMessageAsync(String adminToken, String content) {
        return broadcastSystemMessageAsync(adminToken, content, null);
    }

    /**
     * 异步广播系统消息（可排除指定用户）
     */
    public CompletableFuture<MessagePermissionValidator.BroadcastResult> broadcastSystemMessageAsync(String adminToken, String content, String excludeUserId) {
        CompletableFuture<MessagePermissionValidator.BroadcastResult> future = new CompletableFuture<>();
        
        if (!validateContent(content)) {
            future.complete(MessagePermissionValidator.BroadcastResult.failure("消息内容不能为空"));
            return future;
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(adminToken, MessagePermissionValidator.Operation.BROADCAST_SYSTEM_MESSAGE, null);
        if (!securityResult.isSuccess()) {
            future.complete(MessagePermissionValidator.BroadcastResult.failure(securityResult.getErrorMessage()));
            return future;
        }

        NettyMessage message = NettyMessage.system(content);
        int successCount = broadcastInternal(message, excludeUserId);
        future.complete(MessagePermissionValidator.BroadcastResult.success(successCount));
        return future;
    }

    // ==================== 批量发送方法 ====================

    /**
     * 批量发送系统消息给指定用户列表（需要管理员权限）
     * 
     * @param adminToken 管理员Token
     * @param userIds 用户ID列表
     * @param content 消息内容
     * @return 发送结果统计
     */
    public MessagePermissionValidator.BatchSendResult sendSystemMessageToUsers(String adminToken, Collection<String> userIds, String content) {
        if (userIds == null || userIds.isEmpty()) {
            return MessagePermissionValidator.BatchSendResult.failure("用户ID列表不能为空");
        }

        if (!validateContent(content)) {
            return MessagePermissionValidator.BatchSendResult.failure("消息内容不能为空");
        }

        MessagePermissionValidator.SecurityValidationResult securityResult = validateSecurity(adminToken, MessagePermissionValidator.Operation.SEND_SYSTEM_MESSAGE_BATCH, null);
        if (!securityResult.isSuccess()) {
            return MessagePermissionValidator.BatchSendResult.failure(securityResult.getErrorMessage());
        }

        NettyMessage message = NettyMessage.system(content);
        return sendToUsersInternal(securityResult.getSenderId(), userIds, message);
    }

    /**
     * 内部批量发送方法
     */
    private MessagePermissionValidator.BatchSendResult sendToUsersInternal(String senderId, Collection<String> userIds, NettyMessage message) {
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger offlineCount = new AtomicInteger(0);

        // 预先序列化消息以提高性能
        String jsonMessage;
        try {
            jsonMessage = serializeMessage(message);
        } catch (Exception e) {
            log.error("序列化消息失败", e);
            return MessagePermissionValidator.BatchSendResult.failure("序列化消息失败");
        }

        for (String userId : userIds) {
            if (!validateUserId(userId)) {
                failedCount.incrementAndGet();
                continue;
            }

            Channel channel = sessionManager.getChannel(userId);
            if (channel == null || !channel.isActive()) {
                offlineCount.incrementAndGet();
                continue;
            }

            ChannelFuture future = channel.writeAndFlush(new TextWebSocketFrame(jsonMessage));
            future.addListener(f -> {
                if (f.isSuccess()) {
                    successCount.incrementAndGet();
                    totalSentMessages.incrementAndGet();
                } else {
                    failedCount.incrementAndGet();
                    totalFailedMessages.incrementAndGet();
                    log.warn("批量发送消息到用户 {} 失败: {}", userId, f.cause().getMessage());
                }
            });
        }

        MessagePermissionValidator.BatchSendResult result = MessagePermissionValidator.BatchSendResult.success(successCount.get(), failedCount.get(), offlineCount.get());
        log.info("批量发送消息完成，目标用户数: {}，成功: {}，失败: {}，离线: {}", 
            userIds.size(), result.getSuccessCount(), result.getFailedCount(), result.getOfflineCount());
        
        return result;
    }

    // ==================== 内部广播方法 ====================

    /**
     * 内部广播方法
     */
    private int broadcastInternal(NettyMessage message, String excludeUserId) {
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }

        try {
            String jsonMessage = serializeMessage(message);
            TextWebSocketFrame frame = new TextWebSocketFrame(jsonMessage);
            
            Channel excludeChannel = null;
            if (StringUtils.hasText(excludeUserId)) {
                excludeChannel = sessionManager.getChannel(excludeUserId);
            }
            
            AtomicInteger successCount = new AtomicInteger(0);
            
            for (Channel channel : sessionManager.getAllChannels()) {
                if (channel.isActive() && 
                    (excludeChannel == null || !channel.id().equals(excludeChannel.id()))) {
                    
                    ChannelFuture future = channel.writeAndFlush(frame.retainedDuplicate());
                    future.addListener(f -> {
                        if (f.isSuccess()) {
                            successCount.incrementAndGet();
                            totalSentMessages.incrementAndGet();
                        } else {
                            totalFailedMessages.incrementAndGet();
                            log.warn("广播消息到 Channel {} 失败: {}", channel.id(), f.cause().getMessage());
                        }
                    });
                }
            }
            
            frame.release();
            
            int finalSuccessCount = successCount.get();
            if (StringUtils.hasText(excludeUserId)) {
                log.info("广播消息给 {} 个在线用户（排除用户 {}）", finalSuccessCount, excludeUserId);
            } else {
                log.info("广播消息给 {} 个在线用户", finalSuccessCount);
            }
            return finalSuccessCount;
        } catch (Exception e) {
            log.error("广播消息失败", e);
            return 0;
        }
    }

    // ==================== 参数验证和工具方法 ====================

    /**
     * 验证用户ID
     */
    private boolean validateUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            log.warn("用户ID不能为空");
            return false;
        }
        return true;
    }

    /**
     * 验证消息对象
     */
    private boolean validateMessage(NettyMessage message) {
        if (message == null) {
            log.warn("消息对象不能为空");
            return false;
        }
        return true;
    }

    /**
     * 验证消息内容
     */
    private boolean validateContent(String content) {
        if (content == null) {
            log.warn("消息内容不能为空");
            return false;
        }
        return true;
    }

    /**
     * 序列化消息（带缓存优化）
     */
    private String serializeMessage(NettyMessage message) throws Exception {
        // 对于系统消息和广播消息，使用缓存优化
        if (message.getType() == NettyMessage.MessageType.SYSTEM || 
            message.getType() == NettyMessage.MessageType.BROADCAST) {
            String cacheKey = message.getType() + ":" + message.getContent();
            return messageCache.computeIfAbsent(cacheKey, k -> {
                try {
                    if (messageCache.size() > MAX_CACHE_SIZE) {
                        messageCache.clear();
                    }
                    return objectMapper.writeValueAsString(message);
                } catch (Exception e) {
                    log.error("序列化消息失败", e);
                    return null;
                }
            });
        }
        
        return objectMapper.writeValueAsString(message);
    }

    // ==================== 统计和查询方法 ====================

    /**
     * 获取在线用户数
     */
    public int getOnlineCount() {
        return sessionManager.getOnlineCount();
    }

    /**
     * 判断用户是否在线
     */
    public boolean isUserOnline(String userId) {
        if (!StringUtils.hasText(userId)) {
            return false;
        }
        return sessionManager.isOnline(userId);
    }

    /**
     * 获取所有在线用户ID列表
     */
    public Collection<String> getOnlineUserIds() {
        return sessionManager.getAllChannels()
                .stream()
                .map(sessionManager::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 获取消息发送统计信息
     */
    public MessagePermissionValidator.MessageStatistics getMessageStatistics() {
        return new MessagePermissionValidator.MessageStatistics(
            totalSentMessages.get(),
            totalFailedMessages.get(),
            totalBlockedMessages.get(),
            messageCache.size()
        );
    }

    /**
     * 清空消息缓存
     */
    public void clearMessageCache() {
        messageCache.clear();
        log.info("消息缓存已清空");
    }

    /**
     * 重置统计信息
     */
    public void resetStatistics() {
        totalSentMessages.set(0);
        totalFailedMessages.set(0);
        totalBlockedMessages.set(0);
        log.info("统计信息已重置");
    }

    /**
     * 清理频率限制器
     */
    public void cleanupRateLimiters() {
        rateLimiters.entrySet().removeIf(entry -> {
            String userId = entry.getKey();
            return !sessionManager.isOnline(userId);
        });
        log.info("已清理离线用户的频率限制器");
    }
}