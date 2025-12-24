package com.zsq.winter.netty.core.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * WebSocket 握手认证处理器
 * 
 * 这个处理器在 WebSocket 握手阶段进行用户认证，确保只有通过认证的用户才能建立 WebSocket 连接。
 * 相比于连接建立后再认证的方式，握手阶段认证提供了更强的安全保障：
 * 
 * 安全优势：
 * 1. 防止恶意连接：未认证的请求无法建立 WebSocket 连接
 * 2. 资源保护：避免恶意连接占用服务器资源
 * 3. 协议层安全：在 WebSocket 协议层面就进行了安全验证
 * 
 * 工作原理：
 * 1. 拦截所有 HTTP 请求，识别 WebSocket 握手请求
 * 2. 从握手请求中提取认证 Token（支持多种方式）
 * 3. 调用业务层认证器验证 Token 有效性
 * 4. 认证成功则允许握手继续，失败则直接拒绝连接
 * 
 * 使用场景：
 * - 需要用户认证的 WebSocket 应用
 * - 防止恶意连接攻击的场景
 * - 对安全性要求较高的实时通信系统
 * 
 * @author Winter Netty Team
 * @since 1.0.0
 */
@Slf4j
public class WebSocketHandshakeAuthHandler extends ChannelInboundHandlerAdapter {

    /**
     * Channel 属性键：用户ID
     * 用于在 Channel 上存储认证成功后的用户标识
     * 后续的业务处理器可以通过这个键获取当前连接对应的用户ID
     */
    private static final AttributeKey<String> USER_ID_KEY = AttributeKey.valueOf("userId");
    
    /**
     * Token 认证器
     * 由业务层提供具体的认证实现逻辑
     */
    private final TokenAuthenticator tokenAuthenticator;
    
    /**
     * WebSocket 会话管理器
     * 用于检查当前连接数等信息
     */
    private final WebSocketSessionManager sessionManager;
    
    /**
     * 最大连接数限制
     * 防止服务器过载，当连接数达到此限制时拒绝新连接
     */
    private final int maxConnections;

    /**
     * 构造函数
     * 
     * @param tokenAuthenticator Token 认证器，不能为 null
     * @param sessionManager 会话管理器，用于获取当前连接数
     * @param maxConnections 最大连接数限制
     */
    public WebSocketHandshakeAuthHandler(TokenAuthenticator tokenAuthenticator, 
                                       WebSocketSessionManager sessionManager,
                                       int maxConnections) {
        this.tokenAuthenticator = tokenAuthenticator;
        this.sessionManager = sessionManager;
        this.maxConnections = maxConnections;
    }

    /**
     * 处理入站消息
     * 
     * 这是 Netty 入站处理器的核心方法，所有进入的消息都会经过这里
     * 我们在这里拦截 WebSocket 握手请求并进行认证
     * 
     * @param ctx 通道处理上下文
     * @param msg 接收到的消息对象
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 只处理 HTTP 请求消息
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            
            // 检查是否为 WebSocket 握手请求
            if (isWebSocketHandshake(request)) {
                // 执行认证逻辑
                if (!handleWebSocketAuth(ctx, request)) {
                    // 认证失败，不继续传递请求到后续处理器
                    // 连接已在 handleWebSocketAuth 中关闭
                    return;
                }
                // 认证成功，继续传递请求到下一个处理器
            }
        }
        
        // 认证成功或非 WebSocket 请求，继续传递给下一个处理器
        super.channelRead(ctx, msg);
    }

    /**
     * 检查是否为 WebSocket 握手请求
     * 
     * 根据 HTTP 头部信息判断是否为 WebSocket 升级请求
     * WebSocket 握手请求的特征：
     * - Upgrade: websocket
     * - Connection: upgrade
     * 
     * @param request HTTP 请求对象
     * @return true 如果是 WebSocket 握手请求，false 否则
     */
    private boolean isWebSocketHandshake(FullHttpRequest request) {
        // 获取 Upgrade 头部，WebSocket 握手时值为 "websocket"
        String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
        // 获取 Connection 头部，WebSocket 握手时包含 "upgrade"
        String connection = request.headers().get(HttpHeaderNames.CONNECTION);
        
        // 检查是否符合 WebSocket 握手的特征
        return "websocket".equalsIgnoreCase(upgrade) && 
               connection != null && connection.toLowerCase().contains("upgrade");
    }

    /**
     * 处理 WebSocket 认证
     * 
     * 在 WebSocket 握手阶段进行认证验证，确保只有合法用户才能建立连接
     * 
     * 认证流程：
     * 1. 检查当前连接数是否超过限制
     * 2. 验证 TokenAuthenticator 是否已配置
     * 3. 从 HTTP 请求中提取认证 Token
     * 4. 调用认证器验证 Token 有效性
     * 5. 认证成功后将用户ID存储到 Channel 属性中
     * 
     * @param ctx Netty 通道处理上下文
     * @param request WebSocket 握手的 HTTP 请求
     * @return true 认证成功，false 认证失败
     */
    private boolean handleWebSocketAuth(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            // 1. 检查连接数限制 - 防止服务器过载
            if (sessionManager.getOnlineCount() >= maxConnections) {
                log.warn("连接数已达上限 {}，拒绝握手: {}", 
                        maxConnections, ctx.channel().remoteAddress());
                sendAuthError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, 
                            "服务器连接数已满，请稍后重试");
                return false;
            }

            // 2. 检查是否配置了认证器 - 确保认证功能可用
            if (tokenAuthenticator == null) {
                log.error("未配置 TokenAuthenticator，拒绝握手");
                sendAuthError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, 
                            "服务器配置错误");
                return false;
            }

            // 3. 从请求中提取 Token - 支持多种传递方式
            String token = extractToken(request);
            if (token == null || token.trim().isEmpty()) {
                log.warn("握手请求缺少认证 Token: {}", ctx.channel().remoteAddress());
                sendAuthError(ctx, HttpResponseStatus.UNAUTHORIZED, 
                            "缺少认证 Token");
                return false;
            }

            // 4. 执行认证 - 调用业务层认证逻辑
            TokenAuthenticator.AuthResult authResult = tokenAuthenticator.authenticate(token);
            if (!authResult.isSuccess()) {
                String errorMsg = authResult.getErrorMessage();
                log.warn("握手认证失败: {}, Token: {}***", errorMsg, 
                        token.length() > 10 ? token.substring(0, 10) : token);
                sendAuthError(ctx, HttpResponseStatus.UNAUTHORIZED, 
                            errorMsg != null ? errorMsg : "认证失败");
                return false;
            }

            // 5. 认证成功，将用户ID存储到 Channel 属性中
            // 这个用户ID将在后续的业务处理中使用
            String userId = authResult.getUserId();
            ctx.channel().attr(USER_ID_KEY).set(userId);
            
            log.info("握手认证成功，用户: {}, Token: {}***", userId, 
                    token.length() > 10 ? token.substring(0, 10) : token);
            
            return true;

        } catch (Exception e) {
            // 捕获所有异常，防止认证过程中的错误影响服务稳定性
            log.error("握手认证处理异常", e);
            sendAuthError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, 
                        "认证处理异常");
            return false;
        }
    }

    /**
     * 从 HTTP 请求中提取认证 Token
     * 
     * 支持三种 Token 传递方式，按优先级顺序尝试：
     * 1. URL 参数方式: ws://host/path?token=xxx （推荐，兼容性最好）
     * 2. Authorization Header: Authorization: Bearer xxx （标准HTTP认证方式）
     * 3. 自定义 Token Header: Token: xxx （简单直接）
     * 
     * @param request WebSocket 握手的 HTTP 请求对象
     * @return 提取到的 Token 字符串，如果未找到则返回 null
     */
    private String extractToken(FullHttpRequest request) {
        // 方式1: 尝试从 URL 参数获取 Token
        // 示例: ws://localhost:8888/ws?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> tokenParams = decoder.parameters().get("token");
        if (tokenParams != null && !tokenParams.isEmpty()) {
            return tokenParams.get(0);
        }

        // 方式2: 尝试从 Authorization Header 获取 Bearer Token
        // 示例: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
        String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7); // 移除 "Bearer " 前缀
        }

        // 方式3: 尝试从自定义 Token Header 获取
        // 示例: Token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
        String tokenHeader = request.headers().get("Token");
        if (tokenHeader != null && !tokenHeader.trim().isEmpty()) {
            return tokenHeader.trim();
        }

        // 所有方式都未找到 Token
        return null;
    }

    /**
     * 发送认证错误响应给客户端
     * 
     * 当认证失败时，向客户端发送 HTTP 错误响应并关闭连接
     * 这样可以让客户端明确知道认证失败的原因
     * 
     * @param ctx Netty 通道处理上下文
     * @param status HTTP 响应状态码（如 401 Unauthorized, 503 Service Unavailable）
     * @param message 错误消息内容，将显示给客户端
     */
    private void sendAuthError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        // 创建 HTTP 响应对象
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status);
        
        // 设置响应头 - 指定内容类型和编码
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, message.getBytes().length);
        
        // 设置响应体内容
        response.content().writeBytes(message.getBytes());
        
        // 发送响应并在完成后关闭连接
        // 使用 addListener 确保响应发送完成后立即关闭连接
        ctx.writeAndFlush(response).addListener(future -> ctx.close());
    }
}