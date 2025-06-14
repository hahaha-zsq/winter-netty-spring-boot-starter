服务端启动的流程图
```mermaid
graph TD
    A[Spring容器启动] -->|@PostConstruct| B[NettyServer.start]
    B -->|异步执行| C[NettyServer.doStart]
    C -->|调用| D[NettyServer.startServer]
    
    D -->|初始化| E[NettyServer.initializeServer]
    
    E -->|1| F[创建线程组]
    F -->|1.1| F1[创建BossGroup]
    F -->|1.2| F2[创建WorkerGroup]
    
    E -->|2| G[配置ServerBootstrap]
    G -->|2.1| G1[设置线程组]
    G -->|2.2| G2[设置Channel类型]
    G -->|2.3| G3[配置childHandler]
    G -->|2.4| G4[配置TCP参数]
    
    G3 -->|初始化Pipeline| H[NettyServerChannelInitializer]
    H -->|添加处理器| H1[SSL处理器]
    H -->|添加处理器| H2[HTTP编解码器]
    H -->|添加处理器| H3[HTTP消息聚合器]
    H -->|添加处理器| H4[大文件处理器]
    H -->|添加处理器| H5[WebSocket压缩处理器]
    H -->|添加处理器| H6[WebSocket协议处理器]
    H -->|添加处理器| H7[心跳检测处理器]
    H -->|添加处理器| H8[业务逻辑处理器]
    
    E -->|3| I[绑定端口]
    I -->|3.1| I1[创建ServerChannel]
    I -->|3.2| I2[等待绑定完成]
    
    E -->|4| J[启动完成处理]
    J -->|4.1| J1[重置重试计数]
    J -->|4.2| J2[完成startupFuture]
    J -->|4.3| J3[输出启动日志]
    
    K[异常处理机制]
    D -->|异常发生| K
    K -->|重试条件满足| L[重试处理]
    L -->|延迟等待| D
    K -->|重试条件不满足| M[启动失败处理]
    
    N[优雅关闭机制]
    N -->|1| N1[关闭ServerChannel]
    N -->|2| N2[关闭WorkerGroup]
    N -->|3| N3[关闭BossGroup]
    
    style A fill:#f9f,stroke:#333,stroke-width:2px
    style E fill:#bbf,stroke:#333,stroke-width:2px
    style H fill:#bfb,stroke:#333,stroke-width:2px
    style K fill:#fbb,stroke:#333,stroke-width:2px
    style N fill:#fbf,stroke:#333,stroke-width:2px
```


Pipeline 配置流程：
```mermaid
graph TD
    subgraph Pipeline配置流程
        A[NettyServerChannelInitializer] -->|初始化Pipeline| B[配置处理器链]
        
        subgraph 基础协议层
            B -->|1| C1[SSL处理器<br>SslHandler]
            B -->|2| C2[HTTP编解码器<br>HttpServerCodec]
            B -->|3| C3[HTTP消息聚合器<br>HttpObjectAggregator]
        end
        
        subgraph WebSocket协议层
            C3 -->|4| D1[大文件处理器<br>ChunkedWriteHandler]
            D1 -->|5| D2[WebSocket压缩处理器<br>WebSocketServerCompressionHandler]
            D2 -->|6| D3[WebSocket协议处理器<br>WebSocketServerProtocolHandler]
        end
        
        subgraph 应用层
            D3 -->|7| E1[心跳检测处理器<br>IdleStateHandler]
            E1 -->|8| E2[业务逻辑处理器<br>NettyServerHandler]
        end
        
        subgraph 处理器说明
            F1[基础协议层]---|处理HTTP升级请求|F2[WebSocket协议层]
            F2---|处理WebSocket帧|F3[应用层]
        end
    end

    classDef protocolLayer fill:#f0f0f0,stroke:#333,stroke-width:2px;
    class 基础协议层,WebSocket协议层,应用层 protocolLayer;
    
    style A fill:#f9f,stroke:#333,stroke-width:2px
    style E2 fill:#bfb,stroke:#333,stroke-width:2px
```

消息处理流程：

```mermaid
graph TD
    subgraph 消息处理流程
        A[接收WebSocket消息] -->|Pipeline处理| B{消息类型判断}
        
        B -->|文本消息| C[TextWebSocketFrame处理]
        C -->|1| C1[JSON解析]
        C1 -->|2| C2[转换为NettyMessage]
        C2 -->|3| C3[提交业务线程池]
        C3 -->|4| C4[messageService.handleMessage]
        
        B -->|Ping| D[PingWebSocketFrame处理]
        D -->|自动回复| D1[发送PongWebSocketFrame]
        
        B -->|Pong| E[PongWebSocketFrame处理]
        E -->|更新| E1[更新心跳状态]
        
        B -->|Close| F[CloseWebSocketFrame处理]
        F -->|关闭连接| F1[ctx.close]
        
        B -->|Binary| G[BinaryWebSocketFrame处理]
        G -->|当前| G1[不支持处理]
        
        subgraph 消息完成处理
            H[channelReadComplete]
            H -->|1| H1[处理完成回调]
            H -->|2| H2[刷新缓冲区]
        end
    end

    style A fill:#f9f,stroke:#333,stroke-width:2px
    style C4 fill:#bfb,stroke:#333,stroke-width:2px
    style H fill:#bbf,stroke:#333,stroke-width:2px
```
连接生命周期和异常处理：

```mermaid
graph TD
    subgraph 连接生命周期
        A[新连接到达] -->|注册| B[channelRegistered]
        B -->|就绪| C[channelActive]
        
        C -->|1| C1[添加到channelManager]
        C -->|2| C2[触发onConnect回调]
        
        subgraph 心跳检测
            D[IdleStateHandler触发]
            D -->|读空闲| D1[关闭连接]
            D -->|写空闲| D2[发送Ping]
            D -->|全空闲| D3[关闭连接]
        end
        
        subgraph 连接关闭
            E[连接断开] -->|触发| F[channelInactive]
            F -->|1| F1[从channelManager移除]
            F -->|2| F2[触发onDisconnect回调]
            F -->|3| F3[释放资源]
        end
    end
    
    subgraph 异常处理流程
        X[异常发生] -->|捕获| Y[exceptionCaught]
        Y -->|1| Y1[记录错误日志]
        Y -->|2| Y2[关闭连接]
        Y -->|3| Y3[资源清理]
        
        Z[重试机制]
        Z -->|条件判断| Z1{是否重试}
        Z1 -->|是| Z2[延迟重试]
        Z1 -->|否| Z3[启动失败处理]
    end

    style A fill:#f9f,stroke:#333,stroke-width:2px
    style D fill:#bbf,stroke:#333,stroke-width:2px
    style X fill:#fbb,stroke:#333,stroke-width:2px
    style Z fill:#bfb,stroke:#333,stroke-width:2px
```


客户端启动
```mermaid
graph TD
    %% 启动入口
    Start[Spring容器启动] --> PostConstruct["@PostConstruct<br/>NettyClient.start()"]
    PostConstruct --> CheckRunning{"isRunning.get()<br/>检查是否已运行"}
    CheckRunning -->|true| ReturnA[返回]
    CheckRunning -->|false| AsyncStart["winterNettyClientTaskExecutor<br/>异步执行doStart()"]
    
    %% 初始化基础组件
    AsyncStart --> InitBase["初始化基础组件<br/>initializeBootstrap()"]
    InitBase --> Group["创建NioEventLoopGroup<br/>处理I/O操作的线程组"]
    Group --> Bootstrap["创建Bootstrap实例<br/>客户端启动引导类"]
    
    %% Bootstrap配置
    Bootstrap --> BootConfig["配置Bootstrap参数"]
    BootConfig --> BC1["group(group)<br/>设置EventLoopGroup"]
    BootConfig --> BC2["channel(NioSocketChannel.class)<br/>设置Channel类型"]
    BootConfig --> BC3["option(SO_KEEPALIVE, true)<br/>启用TCP心跳"]
    BootConfig --> BC4["option(TCP_NODELAY, true)<br/>禁用Nagle算法"]
    BootConfig --> BC5["handler(initializer)<br/>设置Channel初始化器"]
    
    %% 执行连接
    BC1 & BC2 & BC3 & BC4 & BC5 --> Connect["执行连接操作<br/>connect()"]
    Connect --> CreateFuture["创建ChannelFuture<br/>bootstrap.connect(host, port)"]
    
    %% 连接结果处理
    CreateFuture --> AddListener["添加连接监听器<br/>addListener(ChannelFutureListener)"]
    AddListener --> ConnectResult{"连接结果判断<br/>future.isSuccess()"}
    
    %% 连接成功路径
    ConnectResult -->|成功| SuccessProcess["连接成功处理"]
    SuccessProcess --> SP1["保存Channel引用<br/>channel = f.channel()"]
    SP1 --> SP2["设置运行状态<br/>isRunning.set(true)"]
    SP2 --> SP3["重置重试参数<br/>currentRetryAttempt = 0"]
    SP3 --> SP4["重置重试延迟<br/>currentDelay = initialDelay"]
    
    %% Pipeline配置
    SP4 --> InitPipeline["初始化Pipeline"]
    InitPipeline --> P1["配置SSL Handler<br/>(如果启用)"]
    P1 --> P2["配置IdleStateHandler<br/>心跳检测"]
    P2 --> P3["配置StringDecoder<br/>消息解码"]
    P3 --> P4["配置StringEncoder<br/>消息编码"]
    P4 --> P5["配置NettyClientHandler<br/>业务处理"]
    
    %% 连接失败路径
    ConnectResult -->|失败| FailProcess["连接失败处理"]
    FailProcess --> RetryCheck["检查重试机制<br/>scheduleReconnect()"]
    
    %% 重试逻辑
    RetryCheck --> RC1{"检查是否正在关闭<br/>isShuttingDown.get()"}
    RC1 -->|true| ReturnB[返回]
    RC1 -->|false| RC2{"检查重试条件<br/>shouldRetry()"}
    
    RC2 --> RC3{"重试条件判断"}
    RC3 --> RC4["enabled = true"]
    RC4 --> RC5["未超过maxAttempts"]
    RC5 --> RC6["异常类型匹配"]
    
    RC3 -->|可以重试| RetryProcess["重试处理"]
    RetryProcess --> RP1["增加重试计数<br/>currentRetryAttempt++"]
    RP1 --> RP2["计算新延迟时间<br/>currentDelay * multiplier"]
    RP2 --> RP3["应用最大延迟限制<br/>Math.min(delay, maxDelay)"]
    RP3 --> RP4["安排重试任务<br/>group.schedule()"]
    RP4 --> Connect
    
    RC3 -->|不可重试| Shutdown["触发关闭流程<br/>shutdown()"]
    
    %% 样式设置
    style Start fill:#d0f4de
    style PostConstruct fill:#d0f4de
    style Connect fill:#f4ecd0
    style SuccessProcess fill:#98fb98
    style FailProcess fill:#f4d0d0
    style RetryProcess fill:#d0e8f4
    style Shutdown fill:#f4d0d0
```
Pipeline配置流程
```mermaid
graph TD
    subgraph Pipeline配置流程
        P_Start[ChannelInitializer.initChannel] --> SSL{是否启用SSL}
        SSL -->|是| P1["添加SslHandler<br/>SSL加密传输"]
        SSL -->|否| P2
        P1 --> P2["添加IdleStateHandler<br/>读空闲检测: heartbeatInterval<br/>写空闲检测: heartbeatInterval<br/>全局空闲: heartbeatInterval"]
        P2 --> P3["添加StringDecoder<br/>ByteBuf -> String<br/>UTF-8编码"]
        P3 --> P4["添加StringEncoder<br/>String -> ByteBuf<br/>UTF-8编码"]
        P4 --> P5["添加NettyClientHandler<br/>业务逻辑处理器"]
    end
    
    style P_Start fill:#d0f4de
    style P5 fill:#f4ecd0
```
消息处理流程：
```mermaid
graph TD
    subgraph 消息处理流程
        M_Start[收到消息] --> M_Parse["解析消息<br/>JSON -> NettyMessage"]
        M_Parse --> M_Type{消息类型判断}

        M_Type -->|心跳消息| M1["handleHeartbeatResponse<br/>1. 记录心跳响应<br/>2. 更新连接状态"]
        M_Type -->|系统消息| M2["handleSystemMessage<br/>1. 处理系统通知<br/>2. 记录系统消息"]
        M_Type -->|文本消息| M3["handleTextMessage<br/>1. 处理普通文本<br/>2. 业务逻辑处理"]
        M_Type -->|广播消息| M4["handleBroadcastMessage<br/>1. 处理广播内容<br/>2. 记录发送者信息"]
        M_Type -->|私聊消息| M5["handlePrivateMessage<br/>1. 处理私聊内容<br/>2. 记录发送接收者"]

        M1 & M2 & M3 & M4 & M5 --> M_Log[日志记录]
    end

    style M_Start fill:#d0f4de
    style M_Log fill:#f4ecd0
```
连接生命周期：

```mermaid
graph TD
    subgraph 连接生命周期
        L_Start[创建连接] --> L_Connect["bootstrap.connect<br/>连接服务器"]
        L_Connect --> L_Result{连接结果}
        
        L_Result -->|成功| L_Active["channelActive<br/>1. 记录连接成功<br/>2. 添加到连接管理器<br/>3. 发送连接成功消息"]
        L_Active --> L_Running["正常运行状态<br/>1. 消息收发<br/>2. 心跳维护"]
        
        L_Result -->|失败| L_Retry["重试机制<br/>1. 计算重试延迟<br/>2. 安排重试任务"]
        L_Retry -->|重试条件满足| L_Connect
        L_Retry -->|超出重试限制| L_Close1["触发关闭流程"]
        
        L_Running --> L_Idle["空闲检测<br/>IdleStateHandler"]
        L_Idle -->|触发空闲| L_Heart["发送心跳包"]
        L_Heart --> L_Running
        
        L_Running --> L_Inactive["channelInactive<br/>连接断开"]
        L_Inactive --> L_Clean["清理资源<br/>1. 从管理器移除<br/>2. 记录断开状态"]
        L_Clean --> L_Recon{是否重连}
        L_Recon -->|是| L_Connect
        L_Recon -->|否| L_Close2["关闭流程"]
    end
    
    style L_Start fill:#d0f4de
    style L_Running fill:#98fb98
    style L_Close1 fill:#f4d0d0
    style L_Close2 fill:#f4d0d0
```

异常处理流程：

```mermaid
graph TD
    subgraph 异常处理流程
        E_Start[异常发生] --> E_Type{异常类型判断}

        E_Type -->|连接异常| E1["ConnectException处理<br/>1. 记录连接失败<br/>2. 触发重试机制"]
        E_Type -->|通道异常| E2["ChannelException处理<br/>1. 关闭当前Channel<br/>2. 清理资源"]
        E_Type -->|SSL异常| E3["SslException处理<br/>1. 记录SSL错误<br/>2. 关闭连接"]
        E_Type -->|超时异常| E4["TimeoutException处理<br/>1. 记录超时信息<br/>2. 重试操作"]
        E_Type -->|其他异常| E5["通用异常处理<br/>1. 记录异常信息<br/>2. 评估是否重试"]

        E1 --> E_Retry["重试处理"]
        E_Retry --> E_Check{检查重试条件}
        E_Check -->|可以重试| E_Delay["计算重试延迟<br/>1. 指数退避算法<br/>2. 最大延迟限制"]
        E_Delay --> E_Schedule["安排重试任务<br/>group.schedule()"]
        E_Check -->|不可重试| E_Close["关闭处理<br/>1. 设置关闭标志<br/>2. 释放资源"]

        E2 & E3 & E4 & E5 --> E_Log["异常日志记录<br/>1. 错误信息<br/>2. 堆栈跟踪"]
        E_Log --> E_Notify["通知机制<br/>1. 更新状态<br/>2. 触发回调"]

        E_Schedule --> E_Monitor["监控重试状态<br/>1. 重试次数<br/>2. 成功率统计"]
        E_Close --> E_Final["最终清理<br/>1. 关闭连接<br/>2. 释放资源"]
    end

    style E_Start fill:#f4d0d0
    style E_Close fill:#f4d0d0
    style E_Final fill:#f4d0d0
    style E_Monitor fill:#d0e8f4
```
自定义WebSocket消息服务实现示例

```java
/**
 * 自定义WebSocket消息服务实现示例
 * 用户可以通过实现WebSocketMessageService接口来自定义消息处理逻辑
 */
@Service
public class CustomWebSocketMessageService implements WebSocketMessageService {
    
    private static final Logger logger = LoggerFactory.getLogger(CustomWebSocketMessageService.class);
    
    @Autowired
    private WebSocketChannelManager channelManager;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void handleMessage(Channel channel, WebSocketMessage message) {
        logger.info("自定义处理WebSocket消息 - 通道: {}, 消息: {}", channel.id(), message);
        
        try {
            switch (message.getType()) {
                case TEXT:
                    handleTextMessage(channel, message);
                    break;
                case HEARTBEAT:
                    handleHeartbeat(channel, message);
                    break;
                case SYSTEM:
                    handleSystemMessage(channel, message);
                    break;
                case BROADCAST:
                    handleBroadcastMessage(channel, message);
                    break;
                case PRIVATE:
                    handlePrivateMessage(channel, message);
                    break;
                default:
                    logger.warn("未知消息类型: {}", message.getType());
                    sendErrorMessage(channel, "不支持的消息类型: " + message.getType());
            }
        } catch (Exception e) {
            logger.error("处理WebSocket消息失败", e);
            sendErrorMessage(channel, "消息处理失败");
        }
    }
    
    @Override
    public void onConnect(Channel channel) {
        logger.info("用户连接 - 通道ID: {}", channel.id());
        
        // 记录连接信息
        channelManager.addChannel(channel);
        
        // 发送欢迎消息
        WebSocketMessage welcomeMessage = WebSocketMessage.system("欢迎连接到WebSocket服务");
        sendMessage(channel, welcomeMessage);
        
        // 可以在这里添加其他连接时的处理逻辑，比如：
        // - 用户在线状态更新
        // - 连接统计
        // - 安全验证等
    }
    
    @Override
    public void onDisconnect(Channel channel) {
        logger.info("用户断开连接 - 通道ID: {}", channel.id());
        
        // 清理用户绑定
        String userId = channelManager.getUserIdByChannel(channel);
        if (userId != null) {
            channelManager.unbindUser(userId);
            logger.info("用户 {} 已断开连接并解绑", userId);
        }
        
        // 移除通道
        channelManager.removeChannel(channel);
        
        // 可以在这里添加其他断开连接时的处理逻辑，比如：
        // - 用户离线状态更新
        // - 清理用户相关数据
        // - 通知其他用户等
    }
    
    /**
     * 处理文本消息
     */
    private void handleTextMessage(Channel channel, WebSocketMessage message) {
        String content = message.getContent();
        
        // 示例：如果消息内容是"login:用户ID"，则绑定用户
        if (content != null && content.startsWith("login:")) {
            String userId = content.substring(6);
            handleUserLogin(channel, userId);
        }
        // 示例：如果消息内容是"logout"，则解绑用户
        else if ("logout".equals(content)) {
            handleUserLogout(channel);
        }
        // 其他文本消息处理
        else {
            logger.info("收到文本消息: {}", content);
            // 可以在这里添加其他文本消息处理逻辑
            // 比如聊天消息、命令处理等
        }
    }
    
    /**
     * 处理用户登录
     */
    private void handleUserLogin(Channel channel, String userId) {
        try {
            // 检查用户是否已经登录
            if (channelManager.getUserIdByChannel(channel) != null) {
                sendErrorMessage(channel, "用户已登录，请勿重复登录");
                return;
            }
            
            // 绑定用户
            channelManager.bindUser(userId, channel);
            
            // 发送登录成功消息
            WebSocketMessage response = WebSocketMessage.system("登录成功");
            response.setToUserId(userId);
            sendMessage(channel, response);
            
            logger.info("用户 {} 登录成功", userId);
            
        } catch (Exception e) {
            logger.error("处理用户登录失败", e);
            sendErrorMessage(channel, "登录处理失败");
        }
    }
    
    /**
     * 处理用户登出
     */
    private void handleUserLogout(Channel channel) {
        try {
            String userId = channelManager.getUserIdByChannel(channel);
            if (userId != null) {
                channelManager.unbindUser(userId);
                
                WebSocketMessage response = WebSocketMessage.system("登出成功");
                sendMessage(channel, response);
                
                logger.info("用户 {} 登出成功", userId);
            } else {
                sendErrorMessage(channel, "用户未登录");
            }
        } catch (Exception e) {
            logger.error("处理用户登出失败", e);
            sendErrorMessage(channel, "登出处理失败");
        }
    }
    
    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(Channel channel, WebSocketMessage message) {
        // 回复心跳
        WebSocketMessage pong = WebSocketMessage.heartbeat();
        pong.setContent("pong");
        sendMessage(channel, pong);
        
        logger.debug("处理心跳消息，通道: {}", channel.id());
    }
    
    /**
     * 处理系统消息
     */
    private void handleSystemMessage(Channel channel, WebSocketMessage message) {
        logger.info("收到系统消息: {}", message.getContent());
        
        // 可以在这里处理系统级别的消息，比如：
        // - 用户认证
        // - 权限验证
        // - 系统通知
        // - 配置更新等
        
        String content = message.getContent();
        if ("ping".equals(content)) {
            // 系统级心跳检测
            WebSocketMessage response = WebSocketMessage.system("pong");
            sendMessage(channel, response);
        } else if ("status".equals(content)) {
            // 返回连接状态
            String userId = channelManager.getUserIdByChannel(channel);
            String status = userId != null ? "已登录用户: " + userId : "未登录";
            WebSocketMessage response = WebSocketMessage.system(status);
            sendMessage(channel, response);
        }
    }
    
    /**
     * 处理广播消息
     */
    private void handleBroadcastMessage(Channel channel, WebSocketMessage message) {
        logger.info("处理广播消息: {}", message.getContent());
        
        // 可以在这里添加广播消息的业务逻辑，比如：
        // - 消息过滤
        // - 权限检查
        // - 消息审核
        // - 广播范围控制等
        
        String userId = channelManager.getUserIdByChannel(channel);
        if (userId == null) {
            sendErrorMessage(channel, "请先登录后再发送广播消息");
            return;
        }
        
        // 设置发送者信息
        message.setFromUserId(userId);
        
        try {
            // 广播给所有在线用户
            channelManager.broadcastMessage(objectMapper.writeValueAsString(message));
            logger.info("用户 {} 发送广播消息成功", userId);
        } catch (Exception e) {
            logger.error("广播消息失败", e);
            sendErrorMessage(channel, "广播消息发送失败");
        }
    }
    
    /**
     * 处理私聊消息
     */
    private void handlePrivateMessage(Channel channel, WebSocketMessage message) {
        logger.info("处理私聊消息: {}", message.getContent());
        
        String fromUserId = channelManager.getUserIdByChannel(channel);
        String toUserId = message.getToUserId();
        
        if (fromUserId == null) {
            sendErrorMessage(channel, "请先登录后再发送私聊消息");
            return;
        }
        
        if (toUserId == null || toUserId.trim().isEmpty()) {
            sendErrorMessage(channel, "请指定接收者用户ID");
            return;
        }
        
        // 设置发送者信息
        message.setFromUserId(fromUserId);
        
        try {
            // 发送给指定用户
            boolean success = channelManager.sendToUser(toUserId, objectMapper.writeValueAsString(message));
            
            if (success) {
                logger.info("用户 {} 向用户 {} 发送私聊消息成功", fromUserId, toUserId);
                
                // 向发送者确认消息已发送
                WebSocketMessage confirmMessage = WebSocketMessage.system("私聊消息发送成功");
                sendMessage(channel, confirmMessage);
            } else {
                sendErrorMessage(channel, "接收者不在线或不存在");
            }
        } catch (Exception e) {
            logger.error("发送私聊消息失败", e);
            sendErrorMessage(channel, "私聊消息发送失败");
        }
    }
    
    /**
     * 发送消息到指定通道
     */
    private void sendMessage(Channel channel, WebSocketMessage message) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            channelManager.sendToChannel(channel, jsonMessage);
        } catch (Exception e) {
            logger.error("发送消息到通道失败", e);
        }
    }
    
    /**
     * 发送错误消息
     */
    private void sendErrorMessage(Channel channel, String errorMsg) {
        WebSocketMessage errorMessage = WebSocketMessage.system("错误: " + errorMsg);
        sendMessage(channel, errorMessage);
    }
}
```

# Winter Netty Spring Boot Starter

一个简单易用的 Spring Boot Starter，用于快速集成基于 Netty 的 WebSocket 服务器和客户端。支持服务端和客户端双向通信、心跳检测、SSL/TLS加密、消息压缩等特性。

## 功能特点

- 🚀 快速集成：一键启用 WebSocket 服务端或客户端
- 🔐 安全通信：支持 SSL/TLS 加密（支持自定义证书和自签名证书）
- 💗 心跳检测：自动的连接活性检测和维护
- 🔄 自动重连：客户端断线自动重连，支持指数退避策略
- 📦 消息压缩：支持 WebSocket 消息压缩，减少传输数据量
- 🎯 灵活路由：支持多种消息类型（文本、广播、私聊等）
- 🎨 优雅设计：完善的生命周期管理和异常处理
- 📈 性能优化：使用线程池处理业务逻辑，避免阻塞IO线程

## 快速开始

### 1. 添加依赖

在你的 Spring Boot 项目的 pom.xml 中添加以下依赖：

```xml
<dependency>
    <groupId>com.zsq.winter</groupId>
    <artifactId>winter-netty-spring-boot-starter</artifactId>
    <version>${latest.version}</version>
</dependency>
```

### 2. 配置属性

在 application.yml 或 application.properties 中添加相关配置：

```yaml
netty:
  # 是否启用服务端组件
  enable-server: true
  # 是否启用客户端组件
  enable-client: false
  
  # 服务端配置
  server:
    # 服务端口
    port: 8888
    # WebSocket路径
    path: /websocket
    # 最大连接数
    max-connections: 1000
    # 最大帧长度
    max-frame-size: 65536
    # 心跳间隔(秒)
    heartbeat-interval: 30
    # Boss线程数
    boss-threads: 1
    # Worker线程数（0表示使用CPU核心数*2）
    worker-threads: 0
    # 是否启用SSL
    ssl-enabled: false
    # SSL证书路径
    ssl-cert-path: 
    # SSL私钥路径
    ssl-key-path: 
    
    # 服务端线程池配置
    thread-pool:
      core-pool-size: 10
      max-pool-size: 100
      queue-capacity: 1000
      keep-alive-seconds: 60
      name-prefix: winterNettyServer-
      await-termination-seconds: 60
      wait-for-tasks-to-complete-on-shutdown: true
    
    # 重试配置
    retry:
      enabled: true
      max-attempts: 3
      initial-delay: 1
      max-delay: 30
      backoff-multiplier: 2.0

  # 客户端配置
  client:
    # 服务器地址
    host: localhost
    # 服务器端口
    port: 8888
    # 最大重连次数
    max-retry-attempts: 3
    # 重连延迟（秒）
    reconnect-delay: 5
    # 心跳间隔（秒）
    heartbeat-interval: 30
    # 是否启用SSL
    ssl-enabled: false
    # SSL证书路径
    ssl-cert-path:
    # SSL私钥路径
    ssl-key-path:
    # SSL信任证书路径
    ssl-trust-cert-path:
    
    # 客户端线程池配置
    thread-pool:
      core-pool-size: 5
      max-pool-size: 50
      queue-capacity: 500
      keep-alive-seconds: 60
      name-prefix: winterNettyClient-
      await-termination-seconds: 60
      wait-for-tasks-to-complete-on-shutdown: true
```

### 3. 服务端使用示例

#### 3.1 实现自定义消息处理服务

```java
@Service
public class CustomMessageService implements NettyMessageService {
    @Override
    public void handleMessage(Channel channel, NettyMessage message) {
        // 处理接收到的消息
        switch (message.getType()) {
            case TEXT:
                // 处理文本消息
                break;
            case BROADCAST:
                // 处理广播消息
                break;
            case PRIVATE:
                // 处理私聊消息
                break;
            // ... 处理其他类型消息
        }
    }

    @Override
    public void onConnect(Channel channel) {
        // 处理客户端连接事件
    }

    @Override
    public void onDisconnect(Channel channel) {
        // 处理客户端断开连接事件
    }
}
```

#### 3.2 使用消息推送服务

```java
@Service
public class MessagePushService {
    @Autowired
    private NettyPushTemplate pushTemplate;

    // 发送私聊消息
    public void sendPrivateMessage(String userId, String content) {
        pushTemplate.pushToUser(userId, content);
    }

    // 发送广播消息
    public void broadcast(String content) {
        pushTemplate.broadcast(content);
    }

    // 发送带额外数据的消息
    public void sendWithExtra(String userId, String content, Map<String, Object> extra) {
        pushTemplate.pushToUser(userId, content, extra);
    }

    // 检查用户在线状态
    public boolean isUserOnline(String userId) {
        return pushTemplate.isUserOnline(userId);
    }

    // 获取在线用户数
    public int getOnlineCount() {
        return pushTemplate.getOnlineUserCount();
    }
}
```

### 4. 客户端使用示例

#### 4.1 注入并使用客户端

```java
@Service
public class NettyClientService {
    @Autowired
    private NettyClient nettyClient;

    public void sendMessage(String message) {
        nettyClient.sendMessage(message);
    }

    public void sendMessage(NettyMessage message) {
        nettyClient.sendMessage(message);
    }
}
```

### 5. 消息类型说明

支持的消息类型包括：

- TEXT: 普通文本消息
- HEARTBEAT: 心跳消息
- SYSTEM: 系统消息
- BROADCAST: 广播消息
- PRIVATE: 私聊消息

消息格式示例：

```json
{
    "messageId": "unique-message-id",
    "type": "TEXT",
    "fromUserId": "sender-id",
    "toUserId": "receiver-id",
    "content": "Hello, World!",
    "extra": {
        "key1": "value1",
        "key2": "value2"
    },
    "timestamp": "2024-03-20 12:34:56"
}
```

## 高级特性

### SSL/TLS 配置

1. 使用自定义证书：

```yaml
netty:
  server:
    ssl-enabled: true
    ssl-cert-path: /path/to/server.crt
    ssl-key-path: /path/to/server.key
```

2. 客户端SSL配置：

```yaml
netty:
  client:
    ssl-enabled: true
    ssl-trust-cert-path: /path/to/ca.crt
```

### 自定义线程池配置

可以根据业务需求调整线程池参数：

```yaml
netty:
  server:
    thread-pool:
      core-pool-size: 20
      max-pool-size: 200
      queue-capacity: 2000
```

### 重试策略配置

支持灵活的重试策略配置：

```yaml
netty:
  server:
    retry:
      enabled: true
      max-attempts: 3
      initial-delay: 1
      max-delay: 30
      backoff-multiplier: 2.0
```

## 注意事项

1. 服务端和客户端可以同时启用，也可以单独使用
2. 建议根据实际需求调整线程池参数
3. 生产环境建议使用自定义SSL证书
4. 注意合理配置心跳间隔，避免过于频繁
5. 大规模部署时注意调整最大连接数和线程池参数

## 常见问题

1. Q: 如何处理连接断开重连？
   A: 客户端会自动进行重连，可以通过配置 `max-retry-attempts` 和 `reconnect-delay` 调整重连策略。

2. Q: 如何实现自定义的消息处理？
   A: 实现 `NettyMessageService` 接口，并注册为 Spring Bean。

3. Q: 如何确保消息可靠送达？
   A: 可以在消息中添加确认机制，或使用消息ID进行跟踪。

## 贡献指南

欢迎提交 Issue 和 Pull Request。在提交 PR 前，请确保：

1. 代码符合项目规范
2. 添加必要的测试用例
3. 更新相关文档

## 许可证

[Apache License 2.0](LICENSE)