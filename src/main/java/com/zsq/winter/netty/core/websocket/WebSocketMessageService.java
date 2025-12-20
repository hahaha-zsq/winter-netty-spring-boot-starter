package com.zsq.winter.netty.core.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zsq.winter.netty.entity.NettyMessage;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * WebSocket 消息服务
 * 
 * 为第三方提供 WebSocket 消息推送能力，支持：
 * 1. 推送消息给指定用户
 * 2. 广播消息给所有在线用户（可选是否排除发送者）
 * 3. 发送系统消息
 * 4. 发送私聊消息
 * 5. 批量推送消息
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
 *     public void notifyUser(String userId, String content) {
 *         messageService.sendSystemMessage(userId, content);
 *     }
 *     
 *     public void notifyAll(String content) {
 *         messageService.broadcastSystemMessage(content);
 *     }
 * }
 * }
 * </pre>
 */
@Slf4j
public class WebSocketMessageService {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSocketMessageService(WebSocketSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 推送消息给指定用户
     * 
     * @param userId 用户ID
     * @param message 消息对象
     * @return true表示发送成功，false表示用户不在线或发送失败
     */
    private boolean sendToUser(String userId, NettyMessage message) {
        if (userId == null || userId.trim().isEmpty()) {
            log.warn("用户ID不能为空");
            return false;
        }

        if (message == null) {
            log.warn("消息对象不能为空");
            return false;
        }

        Channel channel = sessionManager.getChannel(userId);
        if (channel == null || !channel.isActive()) {
            log.warn("用户 {} 不在线，无法发送消息", userId);
            return false;
        }

        // 确保消息有时间戳
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }

        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            channel.writeAndFlush(new TextWebSocketFrame(jsonMessage));
            log.debug("发送消息给用户 {}: {}", userId, jsonMessage);
            return true;
        } catch (Exception e) {
            log.error("发送消息给用户 {} 失败", userId, e);
            return false;
        }
    }



    /**
     * 推送私聊消息
     * 
     * @param fromUserId 发送者ID
     * @param toUserId 接收者ID
     * @param content 消息内容
     * @return true表示发送成功，false表示接收者不在线或参数错误
     */
    public boolean sendPrivateMessage(String fromUserId, String toUserId, String content) {
        if (fromUserId == null || fromUserId.trim().isEmpty()) {
            log.warn("发送者ID不能为空");
            return false;
        }

        if (toUserId == null || toUserId.trim().isEmpty()) {
            log.warn("接收者ID不能为空");
            return false;
        }

        if (content == null) {
            log.warn("消息内容不能为空");
            return false;
        }

        NettyMessage message = NettyMessage.privateMessage(fromUserId, toUserId, content);
        message.setTimestamp(System.currentTimeMillis());
        
        boolean success = sendToUser(toUserId, message);
        if (success) {
            log.info("用户 {} 私聊用户 {}", fromUserId, toUserId);
        }
        return success;
    }

    /**
     * 广播消息给所有在线用户
     * 
     * @param message 消息对象
     * @return 成功接收消息的用户数
     */
    public int broadcast(NettyMessage message) {
        return broadcast(message, null);
    }

    /**
     * 广播消息给所有在线用户（可排除指定用户）
     * 
     * @param message 消息对象
     * @param excludeUserId 需要排除的用户ID，为null表示不排除任何人
     * @return 成功接收消息的用户数
     */
    public int broadcast(NettyMessage message, String excludeUserId) {
        if (message == null) {
            log.warn("广播消息不能为空");
            return 0;
        }

        // 确保消息有时间戳
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }

        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            TextWebSocketFrame frame = new TextWebSocketFrame(jsonMessage);
            
            // 获取需要排除的 Channel
            Channel excludeChannel = null;
            if (excludeUserId != null && !excludeUserId.trim().isEmpty()) {
                excludeChannel = sessionManager.getChannel(excludeUserId);
            }
            
            int successCount = 0;
            for (Channel channel : sessionManager.getAllChannels()) {
                // 跳过不活跃的 Channel 和需要排除的 Channel
                if (channel.isActive() && 
                    (excludeChannel == null || !channel.id().equals(excludeChannel.id()))) {
                    channel.writeAndFlush(frame.retainedDuplicate());
                    successCount++;
                }
            }
            
            // 释放最后一个引用
            frame.release();
            
            if (excludeUserId != null) {
                log.info("广播消息给 {} 个在线用户（排除用户 {}）", successCount, excludeUserId);
            } else {
                log.info("广播消息给 {} 个在线用户", successCount);
            }
            return successCount;
        } catch (Exception e) {
            log.error("广播消息失败", e);
            return 0;
        }
    }



    /**
     * 广播系统消息
     * 
     * @param content 消息内容
     * @return 成功接收消息的用户数
     */
    public int broadcastSystemMessage(String content) {
        return broadcastSystemMessage(content, null);
    }

    /**
     * 广播系统消息（可排除指定用户）
     * 
     * @param content 消息内容
     * @param excludeUserId 需要排除的用户ID，为null表示不排除任何人
     * @return 成功接收消息的用户数
     */
    public int broadcastSystemMessage(String content, String excludeUserId) {
        if (content == null) {
            log.warn("系统消息内容不能为空");
            return 0;
        }
        NettyMessage message = NettyMessage.system(content);
        return broadcast(message, excludeUserId);
    }

    /**
     * 广播普通消息（由指定用户发起，不包含发送者本人）
     * 
     * @param fromUserId 发送者ID
     * @param content 消息内容
     * @return 成功接收消息的用户数
     */
    public int broadcastMessage(String fromUserId, String content) {
        if (fromUserId == null || fromUserId.trim().isEmpty()) {
            log.warn("发送者ID不能为空");
            return 0;
        }

        if (content == null) {
            log.warn("消息内容不能为空");
            return 0;
        }

        NettyMessage message = NettyMessage.broadcast(fromUserId, content);
        // 广播时排除发送者本人
        return broadcast(message, fromUserId);
    }

    /**
     * 发送系统消息给指定用户
     * 
     * @param userId 用户ID
     * @param content 消息内容
     * @return true表示发送成功，false表示用户不在线或参数错误
     */
    public boolean sendSystemMessage(String userId, String content) {
        if (content == null) {
            log.warn("系统消息内容不能为空");
            return false;
        }
        NettyMessage message = NettyMessage.system(content);
        return sendToUser(userId, message);
    }

    /**
     * 批量发送消息给指定用户列表
     * 
     * @param userIds 用户ID列表
     * @param message 消息对象
     * @return 成功发送的用户数
     */
    public int sendToUsers(Collection<String> userIds, NettyMessage message) {
        if (userIds == null || userIds.isEmpty()) {
            log.warn("用户ID列表不能为空");
            return 0;
        }

        if (message == null) {
            log.warn("消息对象不能为空");
            return 0;
        }

        // 确保消息有时间戳
        if (message.getTimestamp() == null) {
            message.setTimestamp(System.currentTimeMillis());
        }

        int successCount = 0;
        for (String userId : userIds) {
            if (sendToUser(userId, message)) {
                successCount++;
            }
        }

        log.info("批量发送消息，目标用户数: {}，成功数: {}", userIds.size(), successCount);
        return successCount;
    }

    /**
     * 批量发送系统消息给指定用户列表
     * 
     * @param userIds 用户ID列表
     * @param content 消息内容
     * @return 成功发送的用户数
     */
    public int sendSystemMessageToUsers(Collection<String> userIds, String content) {
        if (content == null) {
            log.warn("系统消息内容不能为空");
            return 0;
        }
        NettyMessage message = NettyMessage.system(content);
        return sendToUsers(userIds, message);
    }

    /**
     * 获取在线用户数
     * 
     * @return 在线用户数
     */
    public int getOnlineCount() {
        return sessionManager.getOnlineCount();
    }

    /**
     * 判断用户是否在线
     * 
     * @param userId 用户ID
     * @return true表示在线，false表示离线或用户ID为空
     */
    public boolean isUserOnline(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return sessionManager.isOnline(userId);
    }

    /**
     * 获取所有在线用户ID列表
     * 
     * @return 在线用户ID集合
     */
    public Collection<String> getOnlineUserIds() {
        return sessionManager.getAllChannels()
                .stream()
                .map(sessionManager::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
