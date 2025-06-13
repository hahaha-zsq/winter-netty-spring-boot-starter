package com.zsq.winter.netty.core.server;

import cn.hutool.json.JSONUtil;
import com.zsq.winter.netty.entity.NettyMessage;
import com.zsq.winter.netty.service.NettyMessageService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * <table style="border: 1px solid black; border-collapse: collapse;">
 *   <thead>
 *     <tr>
 *       <th style="border: 1px solid black;">方法名</th>
 *       <th style="border: 1px solid black;">触发时机</th>
 *       <th style="border: 1px solid black;">适合操作</th>
 *       <th style="border: 1px solid black;">注意事项</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td style="border: 1px solid black;">channelRegistered()</td>
 *       <td style="border: 1px solid black;">Channel 注册到 Selector</td>
 *       <td style="border: 1px solid black;">记录通道注册日志<br/>准备线程相关资源</td>
 *       <td style="border: 1px solid black;">无</td>
 *     </tr>
 *     <tr>
 *       <td style="border: 1px solid black;">channelActive()</td>
 *       <td style="border: 1px solid black;">连接建立成功后</td>
 *       <td style="border: 1px solid black;">
 *         发送欢迎/握手消息<br/>
 *         初始化用户会话<br/>
 *         通知业务系统“用户上线”
 *       </td>
 *       <td style="border: 1px solid black;">主业务起点，可调用 ctx.writeAndFlush()</td>
 *     </tr>
 *     <tr>
 *       <td style="border: 1px solid black;">channelRead()/channelRead0()</td>
 *       <td style="border: 1px solid black;">收到数据</td>
 *       <td style="border: 1px solid black;">
 *         解码、协议解析<br/>
 *         处理业务逻辑、转发数据
 *       </td>
 *       <td style="border: 1px solid black;">避免在 I/O 线程中执行耗时逻辑</td>
 *     </tr>
 *     <tr>
 *       <td style="border: 1px solid black;">channelReadComplete()</td>
 *       <td style="border: 1px solid black;">一次读取操作完成</td>
 *       <td style="border: 1px solid black;">
 *         ctx.flush() 刷新缓冲区<br/>
 *         执行后置处理，比如聚合分析
 *       </td>
 *       <td style="border: 1px solid black;">如果用 ctx.write()，记得 flush()</td>
 *     </tr>
 *     <tr>
 *       <td style="border: 1px solid black;">channelInactive()</td>
 *       <td style="border: 1px solid black;">连接断开</td>
 *       <td style="border: 1px solid black;">
 *         清理用户状态<br/>
 *         通知服务端“下线”<br/>
 *         关闭数据库或会话缓存
 *       </td>
 *       <td style="border: 1px solid black;">资源不清理会导致内存泄漏</td>
 *     </tr>
 *     <tr>
 *       <td style="border: 1px solid black;">channelUnregistered()</td>
 *       <td style="border: 1px solid black;">Channel 从 Selector 注销</td>
 *       <td style="border: 1px solid black;">
 *         删除底层引用<br/>
 *         打印生命周期日志
 *       </td>
 *       <td style="border: 1px solid black;">不常用，资源敏感项目会用</td>
 *     </tr>
 *     <tr>
 *       <td style="border: 1px solid black;">exceptionCaught()</td>
 *       <td style="border: 1px solid black;">发生异常</td>
 *       <td style="border: 1px solid black;">
 *         打印错误日志<br/>
 *         关闭连接 ctx.close()<br/>
 *         发送错误响应
 *       </td>
 *       <td style="border: 1px solid black;">不重写会导致异常传播导致崩溃</td>
 *     </tr>
 *     <tr>
 *       <td style="border: 1px solid black;">userEventTriggered()</td>
 *       <td style="border: 1px solid black;">用户自定义事件</td>
 *       <td style="border: 1px solid black;">
 *         IdleStateHandler 心跳检测<br/>
 *         WebSocket 握手完成事件<br/>
 *         自定义消息控制事件
 *       </td>
 *       <td style="border: 1px solid black;">适合处理非数据流的逻辑</td>
 *     </tr>
 *   </tbody>
 * </table>
 */

@Slf4j
@ChannelHandler.Sharable
public class NettyServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    // 通道管理器，用于管理WebSocket通道
    private final NettyServerChannelManager channelManager;

    // 消息服务，用于处理接收到的消息
    private final NettyMessageService messageService;

    private final ThreadPoolTaskExecutor executor;

    // 构造函数，初始化WebSocketHandler
    public NettyServerHandler(NettyServerChannelManager channelManager,
                              @Qualifier("webSocketMessageService") NettyMessageService messageService,
                              @Qualifier("winterNettyServerTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.channelManager = channelManager;
        this.messageService = messageService;
        this.executor = executor;
    }

    /**
     * 当通道被注册到 Netty 的事件循环组（EventLoopGroup）时调用。
     *
     * <p>该方法在 Channel 被创建并注册到 Selector 之后触发，表示该连接已准备好进行 I/O 操作，
     * 但尚未建立实际的网络连接（如 TCP 握手尚未完成）。</p>
     *
     * <p>此方法通常用于执行一些初始化操作或记录通道注册日志。例如可以将通道 ID 记录下来，
     * 便于后续调试或跟踪连接生命周期。</p>
     *
     * @param ctx 当前通道的上下文对象，作用是将ChannelHandler与ChannelPipeline连接起来，提供了Handler与Channel、Pipeline交互的上下文环境，用于与 ChannelPipeline 进行交互
     * @throws Exception 如果在处理过程中发生异常
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        log.info("通道已注册: {}", ctx.channel().id());
        super.channelRegistered(ctx);
    }


    /**
     * 当客户端与服务端建立连接并处于活跃状态时调用。
     *
     * <p>该方法在客户端完成 WebSocket 握手并成功建立连接后触发，表示当前 Channel 已经可以进行读写操作。</p>
     *
     * <p>在此方法中通常执行以下操作：</p>
     * <ul>
     *     <li>将当前 Channel 添加到通道管理器中，用于后续消息广播或私聊推送</li>
     *     <li>记录连接建立日志，便于监控和调试</li>
     * </ul>
     *
     * @param ctx 当前通道的上下文对象，作用是将ChannelHandler与ChannelPipeline连接起来，提供了Handler与Channel、Pipeline交互的上下文环境，用于与 ChannelPipeline 进行交互
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        channelManager.addChannel(ctx.channel());
        messageService.onConnect(ctx.channel());
        log.info("WebSocket连接建立: {}", ctx.channel().id());
    }


    /**
     * 处理从客户端接收到的 WebSocket 文本帧。
     *
     * <p>当 Netty 从客户端接收到一个 WebSocket 消息帧时，该方法会被调用。
     * 此方法仅处理 {@link TextWebSocketFrame} 类型的消息帧，并尝试将其内容解析为
     * JSON 格式的 {@link NettyMessage} 对象。如果解析成功，则调用相应的业务逻辑进行处理；
     * 如果解析失败，则将消息作为普通文本消息处理。</p>
     *
     * <p>此方法内部使用了 Hutool 的 JSON 工具类进行 JSON 解析，若解析过程中出现异常，
     * 则构造一个类型为 TEXT 的默认消息进行处理。</p>
     *
     * @param ctx   当前通道的上下文对象，用于与 ChannelPipeline 进行交互
     * @param frame 接收到的 WebSocket 消息帧，必须是 {@link TextWebSocketFrame}
     * @throws Exception 如果在处理过程中发生任何异常
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            // 处理文本消息
            String text = ((TextWebSocketFrame) frame).text();
            log.debug("收到文本消息: {}", text);
            try {
                NettyMessage message = JSONUtil.toBean(text, NettyMessage.class);
                handleMessage(ctx, message);
            } catch (Exception e) {
                NettyMessage message = NettyMessage.text(text);
                handleMessage(ctx, message);
            }

        } else if (frame instanceof PingWebSocketFrame) {
            // 自动回复 PONG 响应
            log.debug("收到 PING 帧");
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));

        } else if (frame instanceof PongWebSocketFrame) {
            // 可忽略，或记录心跳响应
            log.debug("收到 PONG 帧");

        } else if (frame instanceof BinaryWebSocketFrame) {
            // 处理二进制消息（可选）
            log.warn("收到二进制消息，该框架目前无对应的处理");

        } else if (frame instanceof CloseWebSocketFrame) {
            // 客户端主动关闭连接
            log.info("客户端请求关闭 WebSocket 连接");
            ctx.close();

        } else if (frame instanceof ContinuationWebSocketFrame) {
            // 处理分片消息（建议配合 WebSocketFrameAggregator 使用）
            log.warn("收到分片消息，建议启用聚合器统一处理");

        } else {
            // 其他未知帧
            log.warn("收到未支持的 WebSocket 帧类型: {}", frame.getClass().getSimpleName());
        }
    }

    /**
     * 当通道的一次读取操作完成后调用。
     *
     * <p>该方法在 Netty 完成一次完整的读取操作后触发，通常用于执行后续的清理或刷新操作。</p>
     *
     * <p>在此实现中，主要作用是调用 {@link ChannelHandlerContext#flush()} 方法，确保之前写入的消息
     * 被立即发送出去，避免因缓冲区未满而导致消息延迟。</p>
     *
     * <p>典型使用场景包括：</p>
     * <ul>
     *     <li>在批量写入数据后主动刷新通道</li>
     *     <li>确保响应消息及时送达客户端</li>
     * </ul>
     *
     * @param ctx 当前通道的上下文对象，作用是将ChannelHandler与ChannelPipeline连接起来，提供了Handler与Channel、Pipeline交互的上下文环境，用于与 ChannelPipeline 进行交互     * @throws Exception 如果在刷新过程中发生异常
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 刷新通道，确保所有写入的消息都立即发送出去
        ctx.flush();
        super.channelReadComplete(ctx);
    }


    /**
     * 当通道处理过程中发生异常时调用。
     *
     * <p>该方法在 Netty 的 ChannelPipeline 中抛出异常时触发，用于捕获并处理网络通信中的错误，
     * 例如协议解析失败、连接中断等。</p>
     *
     * <p>在此实现中，主要执行以下操作：</p>
     * <ul>
     *     <li>记录详细的异常日志，包括异常信息和堆栈跟踪</li>
     *     <li>主动关闭当前连接，防止异常状态持续影响服务稳定性</li>
     * </ul>
     *
     * <p>建议始终重写此方法以避免未处理的异常导致连接无法正常关闭或服务崩溃。</p>
     *
     * @param ctx 当前通道的上下文对象，作用是将ChannelHandler与ChannelPipeline连接起来，提供了Handler与Channel、Pipeline交互的上下文环境，用于与 ChannelPipeline 进行交互     * @param cause 发生的异常原因，包含具体的错误信息
     * @throws Exception 如果希望继续向上传播异常，可选择抛出或调用 super.exceptionCaught()
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("WebSocket连接异常: {}", cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * 当 WebSocket 连接断开时调用。
     *
     * <p>该方法在客户端关闭连接或网络异常导致连接中断时触发，表示当前 Channel 已不再活跃。</p>
     *
     * <p>在此方法中通常执行以下操作：</p>
     * <ul>
     *     <li>从通道管理器中移除当前 Channel</li>
     *     <li>记录连接断开日志，便于监控和排查问题</li>
     *     <li>显式调用 `ctx.close()` 确保资源释放</li>
     * </ul>
     *
     * @param ctx 当前通道的上下文对象，用于获取并关闭 Channel
     * @throws Exception 如果在处理过程中发生异常
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelManager.removeChannel(ctx.channel());
        messageService.onDisconnect(ctx.channel());
        log.info("WebSocket连接断开: {}", ctx.channel().id());
        ctx.close(); // 显式关闭
    }

    /**
     * 当用户事件触发时，该方法被调用
     * 主要用于处理空闲状态事件，根据不同的空闲状态执行相应的操作
     *
     * @param ctx 通道处理上下文，提供了对通道进行操作的方法
     * @param evt 触发的事件对象，这里主要关注空闲状态事件
     * @throws Exception 如果处理事件时发生异常
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 判断触发的事件是否为 IdleStateEvent 类型
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            // 根据不同的空闲状态执行相应的操作
            switch (e.state()) {
                // 读空闲（客户端很久没发消息）
                case READER_IDLE:
                    // 当读操作空闲时，记录警告日志并关闭连接
                    log.warn("读空闲超时，关闭连接: {}", ctx.channel().id());
                    ctx.close(); // 关闭连接
                    break;
                // 写空闲（服务端很久没发消息)
                case WRITER_IDLE:
                    // 当写操作空闲时，记录信息日志并发送心跳包
                    log.info("写空闲，发送心跳 Ping");
                    ctx.writeAndFlush(new PingWebSocketFrame());
                    break;
                case ALL_IDLE:
                    // 当所有操作都空闲时，记录警告日志并关闭连接
                    log.warn("全部空闲，关闭连接: {}", ctx.channel().id());
                    ctx.close();
                    break;
                default:
                    // 当空闲状态不匹配上述情况时，调用父类方法处理
                    super.userEventTriggered(ctx, evt);
            }
        } else {
            // 当触发的事件不是 IdleStateEvent 类型时，调用父类方法处理
            super.userEventTriggered(ctx, evt);
        }
    }


    /**
     * 处理WebSocket消息
     */
    private void handleMessage(ChannelHandlerContext ctx, NettyMessage message) {
        try {
            // 调用业务处理服务
            executor.execute(() -> {
                // 处理WebSocket消息的核心业务
                messageService.handleMessage(ctx.channel(), message);
            });

        } catch (Exception e) {
            log.error("处理WebSocket消息异常: {}", e.getMessage(), e);
            sendErrorMessage(ctx, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(ChannelHandlerContext ctx, String errorMsg) {
        NettyMessage errorMessage = NettyMessage.system(errorMsg);
        sendMessage(ctx, errorMessage);
    }

    /**
     * 发送消息到客户端
     */
    private void sendMessage(ChannelHandlerContext ctx, NettyMessage message) {
        if (!ctx.channel().isActive()) return;

        try {
            String jsonMessage = JSONUtil.toJsonStr(message);
            ctx.writeAndFlush(new TextWebSocketFrame(jsonMessage));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage(), e);
        }
    }

}
