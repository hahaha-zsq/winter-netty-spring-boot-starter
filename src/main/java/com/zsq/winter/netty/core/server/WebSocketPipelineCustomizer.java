package com.zsq.winter.netty.core.server;

import com.zsq.winter.netty.autoconfigure.NettyProperties;
import com.zsq.winter.netty.core.websocket.WebSocketHandshakeAuthHandler;
import com.zsq.winter.netty.core.websocket.WebSocketServerHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket Pipeline 定制器
 * 
 * 负责配置 WebSocket 服务的 Netty 处理器链（Pipeline）
 * Pipeline 是 Netty 中处理网络事件的核心机制，类似于过滤器链
 * 
 * 处理器链的执行顺序：
 * 1. IdleStateHandler - 心跳检测和连接空闲管理
 * 2. HttpServerCodec - HTTP 协议编解码
 * 3. HttpObjectAggregator - HTTP 消息聚合
 * 4. ChunkedWriteHandler - 大文件传输支持
 * 5. CorsHandler - 跨域请求处理
 * 6. WebSocketHandshakeAuthHandler - 握手阶段认证（自定义）
 * 7. WebSocketServerProtocolHandler - WebSocket 协议处理
 * 8. WebSocketServerHandler - 业务逻辑处理（自定义）
 * 
 * 设计特点：
 * - 握手认证：在协议升级前进行用户认证
 * - 安全防护：支持 CORS 跨域和连接数限制
 * - 性能优化：启用 WebSocket 压缩和大文件传输
 * 
 * @author Winter Netty Team
 * @since 1.0.0
 */
@Slf4j
public class WebSocketPipelineCustomizer implements NettyServerPipelineCustomizer {

    /**
     * Netty 配置属性
     */
    private final NettyProperties properties;
    
    /**
     * WebSocket 业务处理器
     */
    private final WebSocketServerHandler webSocketServerHandler;

    /**
     * 认证管理器（用于用户认证）
     */
    private final WebSocketHandshakeAuthHandler  webSocketHandshakeAuthHandler;


    /**
     * 构造函数
     * 
     * @param properties Netty 配置属性
     * @param webSocketServerHandler WebSocket 业务处理器
     */
    public WebSocketPipelineCustomizer(NettyProperties properties, 
                                       WebSocketServerHandler webSocketServerHandler,
                                       WebSocketHandshakeAuthHandler  webSocketHandshakeAuthHandler) {
        this.properties = properties;
        this.webSocketServerHandler = webSocketServerHandler;
        this.webSocketHandshakeAuthHandler = webSocketHandshakeAuthHandler;
    }

    /**
     * 自定义 Pipeline 配置
     * 
     * 按照特定顺序添加处理器，每个处理器负责特定的功能。
     * 处理器的顺序很重要，因为数据会按顺序流经每个处理器。
     * 
     * <p><strong>Netty 事件驱动机制：</strong>
     * Netty 是"按事件驱动"，不是"按 Handler 驱动"。
     * 不是「一个 Handler 里的所有事件都执行完，再执行下一个 Handler」，
     * 而是「一次事件，沿着 Pipeline，逐个 Handler 传播」。
     * 
     * <p><strong>执行单位：</strong>事件（Event）
     * 
     * <p><strong>常见事件包括：</strong>
     * <ul>
     *   <li>{@code channelActive} - 通道激活事件</li>
     *   <li>{@code channelRead} - 通道读取事件</li>
     *   <li>{@code write} - 写入事件</li>
     *   <li>{@code flush} - 刷新事件</li>
     *   <li>{@code exceptionCaught} - 异常捕获事件</li>
     *   <li>{@code userEventTriggered} - 用户事件触发</li>
     * </ul>
     * 
     * <p>每一个事件，都会单独沿 Pipeline 传播一次。userEventTriggered 没有固定顺序，它不属于 TCP 生命周期，只要有人 fireUserEventTriggerer，就会立刻传播,👉 可能发生在 任何阶段
     * 
     * @param pipeline Netty 的 ChannelPipeline 对象
     */
    @Override
    public void customize(ChannelPipeline pipeline) {
        // 获取 WebSocket 配置
        NettyProperties.WebSocketProperties wsConfig = properties.getServer().getWebsocket();
        
        // 创建 CORS 配置 - 允许跨域访问
        // forAnyOrigin(): 允许任何域名访问
        // allowNullOrigin(): 允许本地文件访问
        // allowCredentials(): 允许携带认证信息
        CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin()
                .allowNullOrigin()
                .allowCredentials()
                .build();

        // ==================== 处理器链配置 ====================
        
        // 1. 心跳检测处理器（可选）
        // 用于检测客户端是否还活着，防止僵尸连接占用资源
        if (wsConfig.isHeartbeatEnabled()) {
            pipeline.addLast(new IdleStateHandler(
                    wsConfig.getMaxIdleTime(),  // 读空闲时间：超过此时间未收到数据则触发事件
                    0,                          // 写空闲时间：0 表示不检测写空闲
                    0,                          // 读写空闲时间：0 表示不检测读写空闲
                    TimeUnit.SECONDS
            ));
        }

        // 2. HTTP 编解码器
        // 将字节流解码成 HTTP 请求对象，将 HTTP 响应对象编码成字节流
        // HTTP 协议有明确的消息边界，能自然避免 TCP 粘包问题
        pipeline.addLast(new HttpServerCodec());
        
        // 3. HTTP 消息聚合器
        // 将可能分片的 HTTP 消息聚合成完整的 FullHttpRequest 或 FullHttpResponse
        // 65536 字节 = 64KB，这是单个 HTTP 消息的最大大小
        pipeline.addLast(new HttpObjectAggregator(65536));
        
        // 4. 分块写入处理器
        // 用于处理大文件传输，支持 HTTP 分块传输编码
        // 虽然 WebSocket 基于帧协议不存在粘包问题，但握手阶段仍是 HTTP
        pipeline.addLast(new ChunkedWriteHandler());
        
        // 5. CORS 跨域处理器
        // 处理浏览器的跨域请求，添加必要的 CORS 响应头
        pipeline.addLast(new CorsHandler(corsConfig));
        
        // 6. WebSocket 握手认证处理器（自定义）
        // 在 WebSocket 握手阶段进行用户认证，这是安全的关键环节
        // 只有通过认证的用户才能建立 WebSocket 连接
        pipeline.addLast(webSocketHandshakeAuthHandler);
        
        // 7. WebSocket 协议处理器（Netty 内置）
        // 负责处理 WebSocket 协议的各种控制帧：
        // - 握手升级：将 HTTP 连接升级为 WebSocket 连接
        // - Close 帧：处理连接关闭
        // - Ping/Pong 帧：处理心跳检测
        // - 数据帧：处理文本和二进制数据
        pipeline.addLast(new WebSocketServerProtocolHandler(
            wsConfig.getPath(),  // WebSocket 访问路径，如 "/ws"
            null,                // 子协议：null 表示不使用子协议
            true,                // 启用 WebSocket 压缩（permessage-deflate）
            65536                // 最大帧大小：64KB
        ));

        // 8. WebSocket 业务处理器（自定义）
        // 处理具体的业务逻辑：心跳响应、私聊、广播等
        // 此时连接已经完成认证和协议升级，可以安全地处理业务消息
        pipeline.addLast(webSocketServerHandler);
        
        log.debug("WebSocket Pipeline 配置完成，路径: {}，已启用握手认证", wsConfig.getPath());
    }
}
