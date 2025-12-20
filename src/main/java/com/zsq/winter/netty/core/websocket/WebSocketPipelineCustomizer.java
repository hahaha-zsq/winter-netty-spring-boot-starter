package com.zsq.winter.netty.core.websocket;

import com.zsq.winter.netty.autoconfigure.NettyProperties;
import com.zsq.winter.netty.core.server.NettyServerPipelineCustomizer;
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
 * 负责配置 WebSocket 的处理器链
 */
@Slf4j
public class WebSocketPipelineCustomizer implements NettyServerPipelineCustomizer {

    private final NettyProperties properties;
    private final WebSocketServerHandler webSocketServerHandler;

    public WebSocketPipelineCustomizer(NettyProperties properties, 
                                       WebSocketServerHandler webSocketServerHandler) {
        this.properties = properties;
        this.webSocketServerHandler = webSocketServerHandler;
    }

    @Override
    public void customize(ChannelPipeline pipeline) {
        // 创建CORS配置
        CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin().allowNullOrigin().allowCredentials().build();
        NettyProperties.WebSocketProperties wsConfig = properties.getServer().getWebsocket();
        
        // 1.HTTP编解码器 把字节流解码成 HTTP 请求对象（包括 headers + body）===>HTTP 是有消息边界的，能自然避免粘包
        pipeline.addLast("http-codec", new HttpServerCodec());
        
        // 2.HTTP 消息聚合器，将多个 HTTP 消息聚合成一个完整的 FullHttpRequest 或 FullHttpResponse ===>彻底消除了半包问题（最大帧可配置）
        pipeline.addLast("http-aggregator", new HttpObjectAggregator(65536));
        
        // 3. 用于处理大文件传输（如发送大图）  ===>WebSocket 基于帧（frame）协议，有边界，不存在粘包问题
        pipeline.addLast("http-chunked", new ChunkedWriteHandler());
        // 4. WebSocket 协议处理器
        // 负责处理 WebSocket 握手、Close、Ping、Pong 等控制帧
        pipeline.addLast("ws-protocol", new WebSocketServerProtocolHandler(
            wsConfig.getPath(),  // WebSocket 路径
            null,                // 子协议
            true,               // 👈 开启 WebSocket 压缩（permessage-deflate）
            65536                // 最大帧大小
        ));
        // CORS处理器
        pipeline.addLast(new CorsHandler(corsConfig));

        // 6. 心跳检测（如果启用）
        if (wsConfig.isHeartbeatEnabled()) {
            pipeline.addLast("idle-state", new IdleStateHandler(
                wsConfig.getMaxIdleTime(),  // 读空闲时间
                0,                          // 写空闲时间
                0,                          // 读写空闲时间
                TimeUnit.SECONDS
            ));
        }
        
        // 7. WebSocket 业务处理器
        pipeline.addLast("ws-handler", webSocketServerHandler);
        
        log.debug("WebSocket Pipeline 配置完成，路径: {}", wsConfig.getPath());
    }
}
