package com.zsq.winter.netty.service;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.core.server.NettyServerChannelManager;
import com.zsq.winter.netty.entity.NettyMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WebSocket 消息推送服务接口
 *
 * <p>支持面向用户、广播、自定义等多种推送方式。</p>
 *
 * <table style="border:1px solid black; border-collapse:collapse;" cellpadding="0" cellspacing="0">
 *   <caption>功能方法说明</caption>
 *   <thead>
 *     <tr>
 *       <th style="border:1px solid black;">功能</th>
 *       <th style="border:1px solid black;">方法</th>
 *       <th style="border:1px solid black;">描述</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td style="border:1px solid black;">向指定用户推送消息</td>
 *       <td style="border:1px solid black;"><code>pushToUser(...)</code></td>
 *       <td style="border:1px solid black;">推送系统消息或自定义消息给某个用户</td>
 *     </tr>
 *     <tr>
 *       <td style="border:1px solid black;">向多个用户批量推送消息</td>
 *       <td style="border:1px solid black;"><code>pushToUsers(...)</code></td>
 *       <td style="border:1px solid black;">遍历用户列表逐个发送</td>
 *     </tr>
 *     <tr>
 *       <td style="border:1px solid black;">广播消息给所有在线用户</td>
 *       <td style="border:1px solid black;"><code>broadcast(...)</code></td>
 *       <td style="border:1px solid black;">通知所有连接的客户端</td>
 *     </tr>
 *     <tr>
 *       <td style="border:1px solid black;">广播排除特定用户</td>
 *       <td style="border:1px solid black;"><code>broadcastExclude(...)</code></td>
 *       <td style="border:1px solid black;">如通知全体除自己外的用户</td>
 *     </tr>
 *     <tr>
 *       <td style="border:1px solid black;">自定义消息推送</td>
 *       <td style="border:1px solid black;"><code>pushCustomMessage(...)</code></td>
 *       <td style="border:1px solid black;">支持更灵活的消息结构和类型</td>
 *     </tr>
 *     <tr>
 *       <td style="border:1px solid black;">获取在线用户信息</td>
 *       <td style="border:1px solid black;"><code>getOnlineUserCount(), isUserOnline(...)</code></td>
 *       <td style="border:1px solid black;">查询当前连接状态</td>
 *     </tr>
 *     <tr>
 *       <td style="border:1px solid black;">特殊消息类型支持</td>
 *       <td style="border:1px solid black;"><code>sendNotification(...), sendDataUpdate(...)</code></td>
 *       <td style="border:1px solid black;">封装常用业务场景（如通知、数据更新）</td>
 *     </tr>
 *   </tbody>
 * </table>
 */

@Slf4j
public class NettyPushTemplate {

    // 通道管理器，用于管理WebSocket通道
    private final NettyServerChannelManager channelManager;

    /**
     * 构造函数
     *
     * @param channelManager 通道管理器
     */
    public NettyPushTemplate(NettyServerChannelManager channelManager) {
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
            NettyMessage message = NettyMessage.system(content);
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
            NettyMessage message = NettyMessage.broadcast("system", content);
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
            NettyMessage message = NettyMessage.broadcast("system", content);
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
    public boolean pushCustomMessage(String userId, NettyMessage message) {
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
        NettyMessage message = NettyMessage.system(content);
        message.setMessageId(UUID.randomUUID().toString());
        message.setToUserId(userId);

        // 设置通知扩展信息
        HashMap<String, Object> extra = new HashMap<>();
        extra.put("type", "notification");
        extra.put("title", title);
        extra.put("timestamp", System.currentTimeMillis());
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
        NettyMessage message = NettyMessage.system("数据更新");
        message.setMessageId(UUID.randomUUID().toString());
        message.setToUserId(userId);

        // 设置数据更新扩展信息
        HashMap<String, Object> extra = new HashMap<>();
        extra.put("type", "dataUpdate");
        extra.put("dataType", dataType);
        extra.put("data", data);
        extra.put("timestamp", System.currentTimeMillis());
        message.setExtra(extra);
        return pushCustomMessage(userId, message);
    }
}
