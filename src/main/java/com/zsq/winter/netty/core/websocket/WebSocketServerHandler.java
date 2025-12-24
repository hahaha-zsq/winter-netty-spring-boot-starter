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
 * WebSocket 服务端业务处理器 (修复版)
 * * 修复说明：
 * 1. 移除了 channelActive 中的 Session 注册逻辑，防止连接建立初期因 userId 为空被误关。
 * 2. 新增 userEventTriggered 监听 HandshakeComplete 事件，确保握手成功后再注册 Session。
 */
@Slf4j
@ChannelHandler.Sharable
public class WebSocketServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final AttributeKey<String> USER_ID_KEY = AttributeKey.valueOf("userId");
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSocketServerHandler(WebSocketSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 连接建立
     * 注意：此时 WebSocket 握手尚未完成，不能在这里获取 userId 或注册 Session
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.debug("TCP 连接建立: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    /**
     * 连接断开
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String userId = sessionManager.getUserId(ctx.channel());
        if (userId != null) {
            sessionManager.removeSession(ctx.channel());
            log.info("用户 {} 已下线", userId);
        }
        log.info("WebSocket 连接断开，ChannelId={}", ctx.channel().id().asShortText());
        super.channelInactive(ctx);
    }

    /**
     * 事件触发处理
     * 核心修复点：在这里处理握手完成事件
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 1. 处理 WebSocket 握手完成事件
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            String userId = ctx.channel().attr(USER_ID_KEY).get();

            // 握手成功说明认证已通过，userId 理论上不为空
            if (userId != null) {
                sessionManager.addSession(userId, ctx.channel());
                log.info("WebSocket 握手完成，用户: {} 上线，ChannelId={}",
                        userId, ctx.channel().id().asShortText());
            } else {
                // 防御性关闭：如果认证了但没 userId，说明逻辑异常
                log.error("握手完成但未找到用户ID，关闭连接");
                ctx.close();
            }
        }
        // 2. 处理心跳超时事件
        else if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleEvent = (IdleStateEvent) evt;
            if (idleEvent.state() == IdleState.READER_IDLE) {
                String userId = sessionManager.getUserId(ctx.channel());
                log.warn("用户 {} 心跳超时，关闭连接", userId != null ? userId : "匿名");
                ctx.close();
            }
        }
        // 3. 其他事件透传
        else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            handleTextMessage(ctx, (TextWebSocketFrame) frame);
        } else {
            log.warn("不支持的 WebSocket 消息类型：{}", frame.getClass().getName());
        }
    }

    private void handleTextMessage(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            NettyMessage message = objectMapper.readValue(text, NettyMessage.class);
            switch (message.getType()) {
                case HEARTBEAT:
                    handleHeartbeatMessage(ctx);
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
            log.error("消息处理失败", e);
        }
    }

    private void handleHeartbeatMessage(ChannelHandlerContext ctx) {
        NettyMessage response = NettyMessage.heartbeat();
        response.setContent("PONG");
        sendMessage(ctx.channel(), response);
    }

    private void handleBroadcastMessage(ChannelHandlerContext ctx, NettyMessage message) {
        String userId = sessionManager.getUserId(ctx.channel());
        if (userId == null) return;

        message.setFromUserId(userId);
        message.setTimestamp(System.currentTimeMillis());

        try {
            String json = objectMapper.writeValueAsString(message);
            TextWebSocketFrame frame = new TextWebSocketFrame(json);

            // 广播给所有其他用户
            Collection<Channel> channels = sessionManager.getAllChannels();
            for (Channel channel : channels) {
                if (channel.isActive() && !channel.id().equals(ctx.channel().id())) {
                    channel.writeAndFlush(frame.retainedDuplicate());
                }
            }
            frame.release();
        } catch (Exception e) {
            log.error("广播失败", e);
        }
    }

    private void handlePrivateMessage(ChannelHandlerContext ctx, NettyMessage message) {
        String fromUserId = sessionManager.getUserId(ctx.channel());
        if (fromUserId == null) return;

        String toUserId = message.getToUserId();
        Channel targetChannel = sessionManager.getChannel(toUserId);

        if (targetChannel != null && targetChannel.isActive()) {
            message.setFromUserId(fromUserId);
            message.setTimestamp(System.currentTimeMillis());
            sendMessage(targetChannel, message);
        } else {
            // 可选：通知发送者对方不在线
            sendMessage(ctx.channel(), NettyMessage.system("用户 " + toUserId + " 不在线"));
        }
    }

    private void sendMessage(Channel channel, NettyMessage message) {
        try {
            channel.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.error("发送失败", e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("WebSocket 异常", cause);
        ctx.close();
    }
}