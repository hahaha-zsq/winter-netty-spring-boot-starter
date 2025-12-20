package com.zsq.winter.netty.core.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zsq.winter.netty.entity.NettyMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 服务端消息处理器
 *
 * 核心职责：
 * 1. 接收并解析客户端发送的 WebSocket 消息
 * 2. 根据消息类型（认证 / 心跳 / 聊天 / 广播 / 私聊）分发处理
 * 3. 维护 Channel 与用户 ID 的绑定关系
 * 4. 通过 SessionManager 管理在线用户会话
 *
 * 说明：
 * - 该 Handler 是 Netty Pipeline 中 WebSocket 帧的核心业务处理器
 * - 仅处理 WebSocketFrame（主要是 TextWebSocketFrame）
 */
@Slf4j
@ChannelHandler.Sharable // 标记为可共享，允许多个 Channel 共用同一个 Handler 实例
public class WebSocketServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    /**
     * Channel 属性 Key：
     * 用于在 Channel 上绑定当前连接对应的用户 ID
     *
     * 示例：
     * ctx.channel().attr(USER_ID_KEY).set("user123");
     */
    private static final AttributeKey<String> USER_ID_KEY =
            AttributeKey.valueOf("userId");

    /**
     * WebSocket 会话管理器
     * 负责：
     * - userId -> Channel 的映射
     * - 在线用户的统一管理
     */
    private final WebSocketSessionManager sessionManager;

    /**
     * Token 认证器（可选）
     * 如果使用者提供了实现，则使用 Token 认证
     * 否则认证功能将被禁用
     */
    private final TokenAuthenticator tokenAuthenticator;

    /**
     * JSON 序列化/反序列化工具
     * 用于：
     * - 接收客户端 JSON 消息并反序列化为 NettyMessage
     * - 将 NettyMessage 序列化后发送给客户端
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSocketServerHandler(WebSocketSessionManager sessionManager,
                                   TokenAuthenticator tokenAuthenticator) {
        this.sessionManager = sessionManager;
        this.tokenAuthenticator = tokenAuthenticator;
    }

    /**
     * 当 WebSocket 连接建立成功时触发
     *
     * 注意：
     * - 此时连接已建立，但用户尚未认证
     * - USER_ID_KEY 尚未设置
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("WebSocket 连接建立，ChannelId={}",
                ctx.channel().id().asShortText());
        super.channelActive(ctx);
    }

    /**
     * 当 WebSocket 连接断开时触发
     *
     * 处理逻辑：
     * 1. 从 Channel 中获取绑定的 userId
     * 2. 如果已认证，则从 SessionManager 中移除该会话
     * 3. 释放相关资源
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 获取当前 Channel 绑定的用户 ID
        String userId = ctx.channel().attr(USER_ID_KEY).get();

        // 如果用户已认证，则移除其会话
        if (userId != null) {
            sessionManager.removeSession(ctx.channel());
            log.info("用户 {} 已下线", userId);
        }

        log.info("WebSocket 连接断开，ChannelId={}",
                ctx.channel().id().asShortText());
        super.channelInactive(ctx);
    }

    /**
     * 读取 WebSocket 数据帧
     *
     * 说明：
     * - WebSocketFrame 是所有 WebSocket 帧的父类
     * - 本服务只处理 TextWebSocketFrame（文本消息）
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx,
                                WebSocketFrame frame) throws Exception {

        // 仅支持文本消息
        if (frame instanceof TextWebSocketFrame) {
            handleTextMessage(ctx, (TextWebSocketFrame) frame);
        } else {
            // 如：BinaryWebSocketFrame、PingWebSocketFrame 等
            log.warn("不支持的 WebSocket 消息类型：{}",
                    frame.getClass().getName());
        }
    }

    /**
     * 处理文本类型 WebSocket 消息
     *
     * 处理流程：
     * 1. 获取文本内容
     * 2. 反序列化为 NettyMessage
     * 3. 根据消息类型分发到不同处理方法
     */
    private void handleTextMessage(ChannelHandlerContext ctx,
                                   TextWebSocketFrame frame) {
        String text = frame.text();
        log.debug("收到原始消息内容：{}", text);

        try {
            // JSON -> NettyMessage
            NettyMessage message =
                    objectMapper.readValue(text, NettyMessage.class);

            // 根据消息类型进行分发
            switch (message.getType()) {
                case AUTH:
                    handleAuthMessage(ctx, message);
                    break;
                case HEARTBEAT:
                    handleHeartbeatMessage(ctx, message);
                    break;
                case BROADCAST:
                    handleBroadcastMessage(ctx, message);
                    break;
                case PRIVATE:
                    handlePrivateMessage(ctx, message);
                    break;
                default:
                    log.warn("未知的消息类型：{}", message.getType());
            }
        } catch (Exception e) {
            log.error("消息处理失败，原始内容：{}", text, e);
            sendErrorMessage(ctx, "消息格式错误");
        }
    }

    /**
     * 处理用户认证消息（基于 Token）
     *
     * 认证逻辑：
     * 1. 检查是否已配置 TokenAuthenticator
     * 2. 校验 Token 是否存在
     * 3. 调用 TokenAuthenticator 验证 Token
     * 4. 验证成功后将 userId 绑定到 Channel
     * 5. 将用户会话加入 SessionManager
     * 6. 返回认证成功响应
     */
    private void handleAuthMessage(ChannelHandlerContext ctx,
                                   NettyMessage message) {

        // 检查是否配置了认证器
        if (tokenAuthenticator == null) {
            log.error("未配置 TokenAuthenticator，无法进行认证");
            sendErrorMessage(ctx, "服务端未配置认证功能");
            ctx.close();
            return;
        }

        String token = message.getToken();

        // 校验 Token
        if (token == null || token.trim().isEmpty()) {
            sendErrorMessage(ctx, "Token 不能为空");
            ctx.close();
            return;
        }

        // 调用使用者提供的认证逻辑
        TokenAuthenticator.AuthResult authResult = tokenAuthenticator.authenticate(token);

        if (!authResult.isSuccess()) {
            // 认证失败
            String errorMsg = authResult.getErrorMessage();
            log.warn("Token 认证失败: {}", errorMsg);
            sendErrorMessage(ctx, errorMsg != null ? errorMsg : "认证失败");
            ctx.close();
            return;
        }

        // 认证成功，获取用户 ID
        String userId = authResult.getUserId();

        // 将 userId 存入当前 Channel 的属性容器 中
        // ctx.channel()：当前连接的 Channel（一个客户端连接）
        ctx.channel().attr(USER_ID_KEY).set(userId);

        // 将用户会话加入会话管理器
        sessionManager.addSession(userId, ctx.channel());

        // 发送系统认证成功消息
        NettyMessage response = NettyMessage.system("认证成功");
        sendMessage(ctx, response);

        log.info("用户 {} 认证成功（Token: {}***）", userId, 
                token.length() > 10 ? token.substring(0, 10) : token);
    }

    /**
     * 处理心跳消息
     *
     * 用途：
     * - 保持 WebSocket 连接存活
     * - 让客户端感知服务器状态
     */
    private void handleHeartbeatMessage(ChannelHandlerContext ctx,
                                        NettyMessage message) {

        // 构造心跳响应
        NettyMessage response = NettyMessage.heartbeat();
        response.setContent("PONG");

        // 发送给客户端
        sendMessage(ctx, response);

        log.debug("心跳响应已发送，ChannelId={}",
                ctx.channel().id().asShortText());
    }



    /**
     * 处理广播消息
     *
     * 广播规则：
     * - 向所有在线用户发送
     * - 不包含消息发送者本人
     */
    private void handleBroadcastMessage(ChannelHandlerContext ctx,
                                        NettyMessage message) {

        // 获取用户 ID
        String userId = ctx.channel().attr(USER_ID_KEY).get();

        if (userId == null) {
            sendErrorMessage(ctx, "请先进行认证");
            return;
        }

        message.setFromUserId(userId);
        message.setTimestamp(System.currentTimeMillis());

        try {
            // 消息序列化
            String json = objectMapper.writeValueAsString(message);
            TextWebSocketFrame frame = new TextWebSocketFrame(json);

            // 遍历所有在线 Channel
            for (io.netty.channel.Channel channel :
                    sessionManager.getAllChannels()) {

                // 管道在线且管道编号不等于当前管道编号的话就发生数据
                if (channel.isActive()
                    && !channel.id().equals(ctx.channel().id())) {
                    channel.writeAndFlush(frame.retainedDuplicate());
                }
            }

            // 释放引用
            frame.release();

            log.info("用户 {} 发送广播消息", userId);
        } catch (Exception e) {
            log.error("广播消息失败", e);
            sendErrorMessage(ctx, "广播消息失败");
        }
    }

    /**
     * 处理私聊消息
     *
     * 私聊规则：
     * - 只发送给指定用户
     * - 若对方不在线，返回错误提示
     */
    private void handlePrivateMessage(ChannelHandlerContext ctx,
                                      NettyMessage message) {
        // 获取当前管道认证成功时设置的用户编号
        String fromUserId = ctx.channel().attr(USER_ID_KEY).get();

        if (fromUserId == null) {
            sendErrorMessage(ctx, "请先进行认证");
            return;
        }

        String toUserId = message.getToUserId();

        if (toUserId == null || toUserId.trim().isEmpty()) {
            sendErrorMessage(ctx, "接收者ID不能为空");
            return;
        }

        message.setFromUserId(fromUserId);
        message.setTimestamp(System.currentTimeMillis());

        // 获取目标用户 Channel
        io.netty.channel.Channel targetChannel =
                sessionManager.getChannel(toUserId);

        if (targetChannel == null || !targetChannel.isActive()) {
            sendErrorMessage(ctx, "对方不在线");
            return;
        }

        // 发送私聊消息
        sendMessage(targetChannel, message);

        log.info("用户 {} 私聊用户 {}", fromUserId, toUserId);
    }

    /**
     * 向当前 Channel 发送消息
     */
    private void sendMessage(ChannelHandlerContext ctx,
                             NettyMessage message) {
        sendMessage(ctx.channel(), message);
    }

    /**
     * 向指定 Channel 发送消息
     */
    private void sendMessage(Channel channel,
                             NettyMessage message) {
        try {
            String json =
                    objectMapper.writeValueAsString(message);
            channel.writeAndFlush(
                    new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.error("消息发送失败", e);
        }
    }

    /**
     * 向客户端发送系统错误消息
     */
    private void sendErrorMessage(ChannelHandlerContext ctx,
                                  String errorMsg) {
        NettyMessage message =
                NettyMessage.system(errorMsg);
        sendMessage(ctx, message);
    }

    /**
     * 异常捕获处理
     *
     * 发生异常时：
     * - 记录日志
     * - 关闭当前连接
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx,
                                Throwable cause) throws Exception {
        log.error("WebSocket 处理异常，ChannelId={}",
                ctx.channel().id().asShortText(), cause);
        ctx.close();
    }
}