package com.zsq.winter.netty.service;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.core.WebSocketChannelManager;
import com.zsq.winter.netty.entity.WebSocketMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WebSocket消息推送服务
 */
@Slf4j
public class WebSocketPushTemplate {

    // 通道管理器，用于管理WebSocket通道
    private final WebSocketChannelManager channelManager;

    /**
     * 构造函数
     *
     * @param channelManager 通道管理器
     */
    public WebSocketPushTemplate(WebSocketChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /**
     * 向指定用户推送消息
     *
     * @param userId  用户ID
     * @param content 消息内容
     * @return 推送是否成功
     */
    public boolean pushToUser(String userId, String content) {
        return pushToUser(userId, content, null);
    }

    /**
     * 向指定用户推送消息（带扩展数据）
     *
     * @param userId  用户ID
     * @param content 消息内容
     * @param extra   扩展数据
     * @return 推送是否成功
     */
    public boolean pushToUser(String userId, String content, Map<String, Object> extra) {
        try {
            WebSocketMessage message = WebSocketMessage.system(content);
            message.setMessageId(UUID.randomUUID().toString());
            message.setToUserId(userId);
            message.setExtra(extra);
            String jsonMessage = JSONUtil.toJsonStr(message);
            boolean success = channelManager.sendToUser(userId, jsonMessage);

            if (success) {
                log.info("消息推送成功 - 用户: {}, 内容: {}", userId, content);
            } else {
                log.warn("消息推送失败 - 用户: {} 不在线", userId);
            }

            return success;
        } catch (Exception e) {
            log.error("推送消息到用户 {} 失败: {}", userId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 向多个用户推送消息
     *
     * @param userIds 用户ID列表
     * @param content 消息内容
     */
    public void pushToUsers(List<String> userIds, String content) {
        pushToUsers(userIds, content, null);
    }

    /**
     * 向多个用户推送消息（带扩展数据）
     *
     * @param userIds 用户ID列表
     * @param content 消息内容
     * @param extra   扩展数据
     */
    public void pushToUsers(List<String> userIds, String content, Map<String, Object> extra) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        for (String userId : userIds) {
            pushToUser(userId, content, extra);
        }
    }

    /**
     * 广播消息给所有在线用户
     *
     * @param content 消息内容
     */
    public void broadcast(String content) {
        broadcast(content, null);
    }

    /**
     * 广播消息给所有在线用户（带扩展数据）
     *
     * @param content 消息内容
     * @param extra   扩展数据
     */
    public void broadcast(String content, Map<String, Object> extra) {
        try {
            WebSocketMessage message = WebSocketMessage.broadcast("system", content);
            message.setMessageId(UUID.randomUUID().toString());
            message.setExtra(extra);
            String jsonMessage = JSONUtil.toJsonStr(message);
            channelManager.broadcast(jsonMessage);

            log.info("广播消息发送成功 - 内容: {}", content);
        } catch (Exception e) {
            log.error("广播消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 广播消息给所有在线用户（排除指定用户）
     *
     * @param excludeUserId 要排除的用户ID
     * @param content       消息内容
     */
    public void broadcastExclude(String excludeUserId, String content) {
        broadcastExclude(excludeUserId, content, null);
    }

    /**
     * 广播消息给所有在线用户（排除指定用户，带扩展数据）
     *
     * @param excludeUserId 要排除的用户ID
     * @param content       消息内容
     * @param extra         扩展数据
     */
    public void broadcastExclude(String excludeUserId, String content, Map<String, Object> extra) {
        try {
            WebSocketMessage message = WebSocketMessage.broadcast("system", content);
            message.setMessageId(UUID.randomUUID().toString());
            message.setExtra(extra);
            String jsonMessage = JSONUtil.toJsonStr(message);

            channelManager.broadcastExclude(excludeUserId, jsonMessage);

            log.info("广播消息发送成功（排除用户 {}） - 内容: {}", excludeUserId, content);
        } catch (Exception e) {
            log.error("广播消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送自定义消息
     *
     * @param userId  用户ID
     * @param message 自定义消息对象
     * @return 推送是否成功
     */
    public boolean pushCustomMessage(String userId, WebSocketMessage message) {
        try {
            if (message.getMessageId() == null) {
                message.setMessageId(UUID.randomUUID().toString());
            }
            String jsonMessage = JSONUtil.toJsonStr(message);
            boolean success = channelManager.sendToUser(userId, jsonMessage);

            if (success) {
                log.info("自定义消息推送成功 - 用户: {}, 消息类型: {}", userId, message.getType());
            } else {
                log.warn("自定义消息推送失败 - 用户: {} 不在线", userId);
            }

            return success;
        } catch (Exception e) {
            log.error("推送自定义消息到用户 {} 失败: {}", userId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取在线用户数量
     *
     * @return 在线用户数量
     */
    public int getOnlineUserCount() {
        return channelManager.getOnlineUserCount();
    }

    /**
     * 检查用户是否在线
     *
     * @param userId 用户ID
     * @return 用户是否在线
     */
    public boolean isUserOnline(String userId) {
        return channelManager.isUserOnline(userId);
    }

    /**
     * 向指定用户发送通知消息
     *
     * @param userId  用户ID
     * @param title   通知标题
     * @param content 通知内容
     * @return 推送是否成功
     */
    public boolean sendNotification(String userId, String title, String content) {
        WebSocketMessage message = WebSocketMessage.system(content);
        message.setMessageId(UUID.randomUUID().toString());
        message.setToUserId(userId);

        // 设置通知扩展信息
        Map<String, Object> extra = Map.of(
                "type", "notification",
                "title", title,
                "timestamp", System.currentTimeMillis()
        );
        message.setExtra(extra);

        return pushCustomMessage(userId, message);
    }

    /**
     * 向指定用户发送数据更新通知
     *
     * @param userId   用户ID
     * @param dataType 数据类型
     * @param data     更新的数据
     * @return 推送是否成功
     */
    public boolean sendDataUpdate(String userId, String dataType, Object data) {
        WebSocketMessage message = WebSocketMessage.system("数据更新");
        message.setMessageId(UUID.randomUUID().toString());
        message.setToUserId(userId);

        // 设置数据更新扩展信息
        Map<String, Object> extra = Map.of(
                "type", "dataUpdate",
                "dataType", dataType,
                "data", data,
                "timestamp", System.currentTimeMillis()
        );
        message.setExtra(extra);

        return pushCustomMessage(userId, message);
    }
}
