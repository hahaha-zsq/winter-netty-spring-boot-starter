package com.zsq.winter.netty.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * WebSocket消息实体
 */
@Data
public class NettyMessage {

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        TEXT,           // 文本消息
        HEARTBEAT,      // 心跳消息
        SYSTEM,         // 系统消息
        BROADCAST,      // 广播消息
        PRIVATE         // 私聊消息
    }

    /**
     * 消息ID
     */
    private String messageId;

    /**
     * 消息类型
     */
    private MessageType type;

    /**
     * 发送者ID
     */
    private String fromUserId;

    /**
     * 接收者ID（私聊时使用）
     */
    private String toUserId;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 扩展数据
     */
    private Map<String, Object> extra;

    /**
     * 发送时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;

    /**
     * 默认构造函数
     * 初始化消息的时间为当前时间
     */
    public NettyMessage() {
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 构造函数
     *
     * @param type   消息类型
     * @param content 消息内容
     */
    public NettyMessage(MessageType type, String content) {
        this();
        this.type = type;
        this.content = content;
    }

    /**
     * 构造函数
     *
     * @param type       消息类型
     * @param fromUserId 发送者ID
     * @param content    消息内容
     */
    public NettyMessage(MessageType type, String fromUserId, String content) {
        this(type, content);
        this.fromUserId = fromUserId;
    }

    /**
     * 创建文本消息
     *
     * @param content 消息内容
     * @return WebSocketMessage实例
     */
    public static NettyMessage text(String content) {
        return new NettyMessage(MessageType.TEXT, content);
    }

    /**
     * 创建心跳消息
     *
     * @return WebSocketMessage实例
     */
    public static NettyMessage heartbeat() {
        return new NettyMessage(MessageType.HEARTBEAT, "ping");
    }

    /**
     * 创建系统消息
     *
     * @param content 消息内容
     * @return WebSocketMessage实例
     */
    public static NettyMessage system(String content) {
        return new NettyMessage(MessageType.SYSTEM, content);
    }

    /**
     * 创建广播消息
     *
     * @param fromUserId 发送者ID
     * @param content    消息内容
     * @return WebSocketMessage实例
     */
    public static NettyMessage broadcast(String fromUserId, String content) {
        return new NettyMessage(MessageType.BROADCAST, fromUserId, content);
    }

    /**
     * 创建私聊消息
     *
     * @param fromUserId 发送者ID
     * @param toUserId   接收者ID
     * @param content    消息内容
     * @return WebSocketMessage实例
     */
    public static NettyMessage privateMessage(String fromUserId, String toUserId, String content) {
        NettyMessage message = new NettyMessage(MessageType.PRIVATE, fromUserId, content);
        message.setToUserId(toUserId);
        return message;
    }
}
