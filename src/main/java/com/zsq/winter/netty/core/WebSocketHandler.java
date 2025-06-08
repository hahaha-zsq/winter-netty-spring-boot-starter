package com.zsq.winter.netty.core;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.entity.WebSocketMessage;
import com.zsq.winter.netty.service.WebSocketMessageService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket消息处理器
 */
@Slf4j
@ChannelHandler.Sharable
public class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    // 通道管理器，用于管理WebSocket通道
    private final WebSocketChannelManager channelManager;

    // 消息服务，用于处理接收到的消息
    private final WebSocketMessageService messageService;

    // 构造函数，初始化WebSocketHandler
    public WebSocketHandler(WebSocketChannelManager channelManager,
                            WebSocketMessageService messageService) {
        this.channelManager = channelManager;
        this.messageService = messageService;
    }

    // 当通道激活时调用，表示WebSocket连接建立
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        channelManager.addChannel(ctx.channel());
        log.info("WebSocket连接建立: {}", ctx.channel().id());
    }

    // 当通道非激活时调用，表示WebSocket连接断开
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelManager.removeChannel(ctx.channel());
        log.info("WebSocket连接断开: {}", ctx.channel().id());
        ctx.close(); // 显式关闭
    }

    // 处理接收到的WebSocket帧
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            String text = ((TextWebSocketFrame) frame).text();
            log.debug("收到WebSocket消息: {}", text);

            try {
                // 尝试解析JSON消息
                WebSocketMessage message = JSONUtil.toBean(text, WebSocketMessage.class);
                handleMessage(ctx, message);
            } catch (Exception e) {
                // 如果不是JSON格式，当作普通文本消息处理
                WebSocketMessage message = WebSocketMessage.text(text);
                handleMessage(ctx, message);
            }
        }
    }

    /**
     * 处理WebSocket消息
     */
    private void handleMessage(ChannelHandlerContext ctx, WebSocketMessage message) {
        try {
            switch (message.getType()) {
                case HEARTBEAT:
                    handleHeartbeat(ctx);
                    break;
                case TEXT:
                    handleTextMessage(ctx, message);
                    break;
                case BROADCAST:
                    handleBroadcastMessage(ctx, message);
                    break;
                case PRIVATE:
                    handlePrivateMessage(ctx, message);
                    break;
                case SYSTEM:
                    handleSystemMessage(ctx, message);
                    break;
                default:
                    log.warn("未知消息类型: {}", message.getType());
                    sendErrorMessage(ctx, "未知的消息类型");
                    break;
            }

            // 调用业务处理服务
            messageService.handleMessage(ctx.channel(), message);

        } catch (Exception e) {
            log.error("处理WebSocket消息异常: {}", e.getMessage(), e);
            sendErrorMessage(ctx, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(ChannelHandlerContext ctx) {
        WebSocketMessage pong = WebSocketMessage.heartbeat();
        pong.setContent("pong");
        sendMessage(ctx, pong);
    }

    /**
     * 处理文本消息
     */
    private void handleTextMessage(ChannelHandlerContext ctx, WebSocketMessage message) {
        log.info("处理文本消息: {}", message.getContent());

        // 如果消息包含用户绑定信息
        if (message.getFromUserId() != null) {
            channelManager.bindUser(message.getFromUserId(), ctx.channel());
        }
    }

    /**
     * 处理广播消息
     */
    private void handleBroadcastMessage(ChannelHandlerContext ctx, WebSocketMessage message) {
        log.info("处理广播消息: {}", message.getContent());

        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            channelManager.broadcast(jsonMessage);
        } catch (Exception e) {
            log.error("广播消息序列化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理私聊消息
     */
    private void handlePrivateMessage(ChannelHandlerContext ctx, WebSocketMessage message) {
        log.info("处理私聊消息: {} -> {}", message.getFromUserId(), message.getToUserId());

        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            boolean sent = channelManager.sendToUser(message.getToUserId(), jsonMessage);

            if (!sent) {
                sendErrorMessage(ctx, "用户 " + message.getToUserId() + " 不在线");
            }
        } catch (Exception e) {
            log.error("私聊消息序列化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理系统消息
     */
    private void handleSystemMessage(ChannelHandlerContext ctx, WebSocketMessage message) {
        log.info("处理系统消息: {}", message.getContent());
        // 系统消息可以用于用户认证、状态同步等
    }

    /**
     * 发送消息到客户端
     */
    private void sendMessage(ChannelHandlerContext ctx, WebSocketMessage message) {
        if (!ctx.channel().isActive()) return;

        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            ctx.writeAndFlush(new TextWebSocketFrame(jsonMessage));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(ChannelHandlerContext ctx, String errorMsg) {
        WebSocketMessage errorMessage = WebSocketMessage.system(errorMsg);
        sendMessage(ctx, errorMessage);
    }

    // 当发生异常时调用
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("WebSocket连接异常: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
