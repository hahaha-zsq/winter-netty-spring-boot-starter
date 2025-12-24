package com.zsq.winter.netty.core.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zsq.winter.netty.entity.NettyMessage;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

/**
 * WebSocket 服务端业务处理器
 * 
 * 这是 WebSocket 连接的核心业务处理器，负责处理所有 WebSocket 相关的业务逻辑。
 * 由于采用了握手阶段认证，到达这个处理器的连接都已经通过了认证。
 * 
 * 主要功能：
 * 1. 连接生命周期管理：连接建立、断开的处理
 * 2. 消息路由分发：根据消息类型分发到不同的处理方法
 * 3. 业务消息处理：心跳、私聊、广播等具体业务逻辑
 * 4. 异常处理：连接异常和消息处理异常的统一处理
 * 5. 空闲检测：处理连接空闲超时事件
 * 
 * 支持的消息类型：
 * - HEARTBEAT：心跳消息，用于保持连接活跃
 * - PRIVATE：私聊消息，点对点通信
 * - BROADCAST：广播消息，一对多通信
 * - SYSTEM：系统消息，服务器主动推送
 * 
 * 安全特性：
 * - 所有连接都已在握手阶段完成认证
 * - 每个消息都会验证发送者身份
 * - 自动处理连接异常和超时
 * 
 * @author Winter Netty Team
 * @since 1.0.0
 */
@Slf4j
@ChannelHandler.Sharable // 标记为可共享，允许多个 Channel 共用同一个 Handler 实例
public class WebSocketServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    /**
     * Channel 属性键：用户ID
     * 用于从 Channel 中获取已认证用户的唯一标识
     * 这个值在握手认证阶段由 WebSocketHandshakeAuthHandler 设置
     */
    private static final AttributeKey<String> USER_ID_KEY =
            AttributeKey.valueOf("userId");

    /**
     * WebSocket 会话管理器
     * 负责管理所有活跃的 WebSocket 连接：
     * - 维护 userId 到 Channel 的映射关系
     * - 提供在线用户查询功能
     * - 统计在线用户数量
     */
    private final WebSocketSessionManager sessionManager;

    /**
     * JSON 序列化/反序列化工具
     * 用于处理客户端和服务器之间的 JSON 消息：
     * - 将接收到的 JSON 字符串反序列化为 NettyMessage 对象
     * - 将 NettyMessage 对象序列化为 JSON 字符串发送给客户端
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数
     * 
     * @param sessionManager WebSocket 会话管理器，不能为 null
     */
    public WebSocketServerHandler(WebSocketSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 当 WebSocket 连接建立成功时触发
     * 
     * 由于采用了握手阶段认证，当这个方法被调用时：
     * 1. WebSocket 握手已经完成
     * 2. 用户已经通过认证
     * 3. 用户ID已经存储在 Channel 属性中
     * 
     * 这个方法的主要任务是：
     * - 从 Channel 属性中获取已认证的用户ID
     * - 将用户会话注册到会话管理器
     * - 记录连接建立日志
     * 
     * @param ctx Netty 通道处理上下文
     * @throws Exception 处理过程中可能抛出的异常
     */
    /**
     * 【修改点 1】：channelActive 中不再进行认证检查
     * TCP 连接建立时，只做简单的日志记录，不要关闭连接
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 这里只记录 TCP 连接建立，不做认证检查，因为此时握手还没开始
        log.debug("TCP连接建立: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }


    /**
     * 当 WebSocket 连接断开时触发
     * 
     * 连接断开可能的原因：
     * 1. 客户端主动关闭连接
     * 2. 网络异常导致连接中断
     * 3. 服务器主动关闭连接（如认证失败、异常等）
     * 4. 心跳超时导致连接关闭
     * 
     * 这个方法的主要任务是：
     * - 从会话管理器中移除用户会话
     * - 清理相关资源
     * - 记录断开连接日志
     * 
     * @param ctx Netty 通道处理上下文
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 获取当前 Channel 绑定的用户 ID
        String userId = ctx.channel().attr(USER_ID_KEY).get();

        // 移除用户会话（因为握手阶段已认证，这里一定有userId）
        if (userId != null) {
            // 从会话管理器中移除用户会话
            // 这会清理 userId 到 Channel 的映射关系
            sessionManager.removeSession(ctx.channel());
            log.info("用户 {} 已下线", userId);
        } else {
            // 这种情况理论上不应该发生，因为握手阶段已经认证
            // 如果发生了，可能是 Pipeline 配置问题或异常情况
            log.warn("连接断开但未找到用户信息，ChannelId={}", 
                    ctx.channel().id().asShortText());
        }

        log.info("WebSocket 连接断开，ChannelId={}",
                ctx.channel().id().asShortText());
        
        // 调用父类方法，继续事件传播
        super.channelInactive(ctx);
    }


    /**
     * 处理接收到的 WebSocket 帧消息
     * 
     * 这是处理 WebSocket 数据帧的核心方法，所有客户端发送的消息都会经过这里
     * 
     * 支持的帧类型：
     * - TextWebSocketFrame：文本消息帧，包含 JSON 格式的业务数据
     * - BinaryWebSocketFrame：二进制消息帧（当前不支持）
     * - PingWebSocketFrame：Ping 帧（由 WebSocketServerProtocolHandler 处理）
     * - PongWebSocketFrame：Pong 帧（由 WebSocketServerProtocolHandler 处理）
     * - CloseWebSocketFrame：关闭帧（由 WebSocketServerProtocolHandler 处理）
     * 
     * @param ctx Netty 通道处理上下文
     * @param frame 接收到的 WebSocket 帧
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx,
                                WebSocketFrame frame) throws Exception {

        // 目前只支持文本消息帧
        if (frame instanceof TextWebSocketFrame) {
            // 处理文本消息，通常是 JSON 格式的业务数据
            handleTextMessage(ctx, (TextWebSocketFrame) frame);
        } else {
            // 不支持的消息类型，记录警告并发送错误消息
            // 如：BinaryWebSocketFrame、自定义帧等
            log.warn("不支持的 WebSocket 消息类型：{}",
                    frame.getClass().getName());
            sendErrorMessage(ctx, "不支持的消息类型");
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
                    sendErrorMessage(ctx, "不支持的消息类型: " + message.getType());
            }
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            log.error("JSON 解析失败，原始内容：{}", text, e);
            sendErrorMessage(ctx, "消息格式错误：JSON 解析失败");
        } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
            log.error("JSON 映射失败，原始内容：{}", text, e);
            sendErrorMessage(ctx, "消息格式错误：字段映射失败");
        } catch (Exception e) {
            log.error("消息处理失败，原始内容：{}", text, e);
            sendErrorMessage(ctx, "消息处理异常，请稍后重试");
            ctx.close();
        }
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

            // 获取所有在线 Channel
            Collection<Channel> channels = sessionManager.getAllChannels();
            int successCount = 0;
            int totalCount = 0;

            // 遍历所有在线 Channel
            for (Channel channel : channels) {
                totalCount++;
                // 管道在线且管道编号不等于当前管道编号的话就发生数据
                if (channel.isActive() && !channel.id().equals(ctx.channel().id())) {
                    try {
                        channel.writeAndFlush(frame.retainedDuplicate());
                        successCount++;
                    } catch (Exception e) {
                        log.warn("向 Channel {} 发送广播消息失败", 
                                channel.id().asShortText(), e);
                    }
                }
            }

            // 释放引用
            frame.release();

            log.info("用户 {} 发送广播消息，目标用户数: {}，成功数: {}", 
                    userId, totalCount - 1, successCount);
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
        Channel targetChannel =
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

    /**
     * 处理用户事件（如心跳超时）
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 监听 WebSocket 握手完成事件
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {

            // 获取握手阶段已认证的用户ID
            String userId = ctx.channel().attr(USER_ID_KEY).get();

            // 双重保险检查（理论上握手成功 handler 肯定通过了，但为了安全起见）
            if (userId == null) {
                log.error("握手完成但未找到认证信息，关闭连接: {}", ctx.channel().remoteAddress());
                ctx.close();
                return;
            }

            // 注册会话到管理器
            sessionManager.addSession(userId, ctx.channel());
            log.info("WebSocket 握手成功，用户: {}, ChannelId={}", userId, ctx.channel().id().asShortText());

        } else if (evt instanceof IdleStateEvent) {
            // 保持原有的心跳超时处理逻辑
            IdleStateEvent idleEvent = (IdleStateEvent) evt;
            if (idleEvent.state() == IdleState.READER_IDLE) {
                String userId = ctx.channel().attr(USER_ID_KEY).get();
                log.warn("用户 {} 心跳超时，关闭连接", userId != null ? userId : "未认证用户");
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}