package com.zsq.winter.netty.core.websocket;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * WebSocket 握手认证处理器 (修复版)
 * * 修复说明：
 * 1. 增加了 URI 清理逻辑：在提取 Token 后，将 request.uri() 重置为无参数路径。
 * 解决因 URL 携带参数导致 WebSocketServerProtocolHandler 路径匹配失败的问题。
 */
@Slf4j
@ChannelHandler.Sharable
public class WebSocketHandshakeAuthHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<String> USER_ID_KEY = AttributeKey.valueOf("userId");

    private final TokenAuthenticator tokenAuthenticator;
    private final WebSocketSessionManager sessionManager;
    private final int maxConnections;

    public WebSocketHandshakeAuthHandler(TokenAuthenticator tokenAuthenticator,
                                         WebSocketSessionManager sessionManager,
                                         int maxConnections) {
        this.tokenAuthenticator = tokenAuthenticator;
        this.sessionManager = sessionManager;
        this.maxConnections = maxConnections;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;

            if (isWebSocketHandshake(request)) {
                if (!handleWebSocketAuth(ctx, request)) {
                    return; // 认证失败，连接已关闭
                }
                // 认证成功，继续传递给后续处理器
            }
        }
        super.channelRead(ctx, msg);
    }

    private boolean isWebSocketHandshake(FullHttpRequest request) {
        String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
        String connection = request.headers().get(HttpHeaderNames.CONNECTION);
        return "websocket".equalsIgnoreCase(upgrade) &&
               connection != null && connection.toLowerCase().contains("upgrade");
    }

    private boolean handleWebSocketAuth(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            // 1. 检查连接数
            if (sessionManager.getOnlineCount() >= maxConnections) {
                log.warn("连接数满，拒绝: {}", ctx.channel().remoteAddress());
                sendAuthError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "服务器满载");
                return false;
            }

            if (tokenAuthenticator == null) {
                sendAuthError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "配置错误");
                return false;
            }

            // 2. 提取 Token
            String token = extractToken(request);
            if (token == null || token.trim().isEmpty()) {
                log.warn("缺少 Token: {}", ctx.channel().remoteAddress());
                sendAuthError(ctx, HttpResponseStatus.UNAUTHORIZED, "缺少 Token");
                return false;
            }

            // 3. 【核心修复】清理 URI 参数
            // WebSocketServerProtocolHandler 默认进行精确路径匹配。
            // 客户端请求 "/ws?token=xxx" 会导致匹配失败。
            // 这里将 URI 重置为纯路径（如 "/ws"），去除查询参数。
            String uri = request.uri();
            int queryIndex = uri.indexOf('?');
            if (queryIndex != -1) {
                request.setUri(uri.substring(0, queryIndex));
            }

            // 4. 执行认证
            TokenAuthenticator.AuthResult authResult = tokenAuthenticator.authenticate(token);
            if (!authResult.isSuccess()) {
                log.warn("认证失败: {}", authResult.getErrorMessage());
                sendAuthError(ctx, HttpResponseStatus.UNAUTHORIZED, authResult.getErrorMessage());
                return false;
            }

            // 5. 保存用户ID
            String userId = authResult.getUserId();
            ctx.channel().attr(USER_ID_KEY).set(userId);

            log.info("握手认证成功，用户: {}", userId);
            return true;

        } catch (Exception e) {
            log.error("认证异常", e);
            sendAuthError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "认证异常");
            return false;
        }
    }

    private String extractToken(FullHttpRequest request) {
        // 1. URL 参数
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> tokenParams = decoder.parameters().get("token");
        if (tokenParams != null && !tokenParams.isEmpty()) {
            return tokenParams.get(0);
        }

        // 2. Authorization Header
        String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }

        // 3. Custom Header
        String tokenHeader = request.headers().get("Token");
        if (tokenHeader != null && !tokenHeader.trim().isEmpty()) {
            return tokenHeader.trim();
        }

        return null;
    }

    private void sendAuthError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.content().writeBytes(message.getBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}