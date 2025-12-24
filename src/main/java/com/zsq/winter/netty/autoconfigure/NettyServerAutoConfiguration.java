package com.zsq.winter.netty.autoconfigure;

import com.zsq.winter.netty.core.server.*;
import com.zsq.winter.netty.core.websocket.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Netty服务端自动配置类
 * <p>
 * 负责在Spring Boot启动时自动注册Netty服务端相关的Bean，包括：
 * - 线程池执行器：用于处理业务逻辑的异步执行
 * - WebSocket会话管理器：管理WebSocket连接的生命周期
 * - 认证器和权限验证器：提供安全认证功能
 * - 消息服务：处理WebSocket消息的收发
 * - 管道定制器：配置Netty处理器链
 * - 服务器实例：启动和管理Netty服务器
 * <p>
 * 所有Bean的创建都基于配置条件，确保在禁用服务端功能时不会创建不必要的Bean
 * 
 * @author zsq
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NettyProperties.class)
@ConditionalOnProperty(prefix = "winter-netty", name = "enable-server", havingValue = "true", matchIfMissing = true)
public class NettyServerAutoConfiguration {

    // ==================== 线程池配置 ====================

    /**
     * 创建Netty服务端专用线程池执行器
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * <p>
     * 此线程池专门用于处理Netty服务端的业务逻辑，包括：
     * - WebSocket消息处理
     * - 业务逻辑异步执行
     * - 数据库操作等耗时任务
     * <p>
     * 线程池配置说明：
     * - corePoolSize：核心线程数，线程池创建时的初始线程数
     * - maxPoolSize：最大线程数，当任务队列满时可创建的最大线程数
     * - queueCapacity：任务队列容量，核心线程忙碌时新任务会放入队列等待
     * - keepAliveSeconds：线程空闲超时时间，超过核心线程数的线程空闲后会被回收
     * - threadNamePrefix：线程名称前缀，便于在日志中识别线程来源
     * - waitForTasksToCompleteOnShutdown：关闭时是否等待所有任务完成
     * - awaitTerminationSeconds：关闭前等待时间
     * - rejectedExecutionHandler：拒绝策略，使用CallerRunsPolicy让调用线程执行任务
     *
     * @param properties Netty配置属性，包含线程池相关配置
     * @return 配置好的线程池执行器
     */
    @Bean("winterNettyServerTaskExecutor")
    @ConditionalOnMissingBean(name = "winterNettyServerTaskExecutor")
    public ThreadPoolTaskExecutor winterNettyServerTaskExecutor(NettyProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        NettyProperties.ThreadProperties threadProps = properties.getServer().getThreadPool();
        
        // 设置核心线程数：线程池创建时的初始线程数
        executor.setCorePoolSize(threadProps.getCorePoolSize());
        // 设置最大线程数：当任务队列满时，可以创建的最大线程数
        executor.setMaxPoolSize(threadProps.getMaxPoolSize());
        // 设置任务队列容量：当核心线程都在工作时，新任务会放入队列等待
        executor.setQueueCapacity(threadProps.getQueueCapacity());
        // 线程空闲超时时间：超过核心线程数的线程如果空闲，会在指定时间后被回收
        executor.setKeepAliveSeconds(threadProps.getKeepAliveSeconds());
        // 设置线程名称前缀：便于在日志中识别线程来源
        executor.setThreadNamePrefix(threadProps.getNamePrefix() + "server-");
        // 设置关闭策略：是否等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(threadProps.getWaitForTasksToCompleteOnShutdown());
        // 设置关闭前等待时间：在关闭线程池时，等待指定秒数让任务完成
        executor.setAwaitTerminationSeconds(threadProps.getAwaitTerminationSeconds());
        // 设置拒绝策略：当任务队列满时，新任务会执行拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 初始化线程池
        executor.initialize();
        
        log.info("Netty服务端线程池已创建 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}", 
                threadProps.getCorePoolSize(), threadProps.getMaxPoolSize(), threadProps.getQueueCapacity());

        return executor;
    }

    // ==================== WebSocket 核心组件配置 ====================

    /**
     * 创建WebSocket会话管理器
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - winter-netty.server.websocket.enabled=true（默认为false）
     * - 容器中不存在WebSocketSessionManager类型的Bean
     * <p>
     * 会话管理器负责：
     * - 管理WebSocket连接的生命周期
     * - 维护用户ID与Channel的映射关系
     * - 提供连接查询和管理功能
     * - 处理连接断开时的清理工作
     *
     * @return WebSocket会话管理器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WebSocketSessionManager webSocketSessionManager() {
        log.info("创建WebSocket会话管理器");
        return new WebSocketSessionManager();
    }

    /**
     * 创建默认的Token认证器（仅用于开发环境）
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - winter-netty.server.websocket.enabled=true（默认为false）
     * - 容器中不存在TokenAuthenticator类型的Bean
     * <p>
     * 默认实现提供基础的Token验证功能，生产环境建议提供自定义实现。
     * 自定义实现需要实现TokenAuthenticator接口，提供以下功能：
     * - Token有效性验证
     * - 用户身份解析
     * - 权限检查等
     *
     * @return 默认Token认证器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(TokenAuthenticator.class)
    public TokenAuthenticator defaultTokenAuthenticator() {
        log.warn("未配置自定义 TokenAuthenticator，使用默认实现（仅适用于开发环境）");
        return new DefaultTokenAuthenticator();
    }

    /**
     * 创建默认的消息权限验证器
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - winter-netty.server.websocket.enabled=true（默认为false）
     * - 容器中不存在MessagePermissionValidator类型的Bean
     * <p>
     * 默认实现允许所有消息通过，生产环境建议提供自定义实现。
     * 自定义实现需要实现MessagePermissionValidator接口，提供：
     * - 消息类型权限检查
     * - 用户操作权限验证
     * - 业务规则校验等
     *
     * @return 默认消息权限验证器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(MessagePermissionValidator.class)
    public MessagePermissionValidator defaultMessagePermissionValidator() {
        log.warn("未配置自定义 MessagePermissionValidator，使用默认实现（允许所有消息）");
        return new DefaultMessagePermissionValidator();
    }

    /**
     * 创建WebSocket消息服务
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - winter-netty.server.websocket.enabled=true（默认为false）
     * - 容器中不存在WebSocketMessageService类型的Bean
     * <p>
     * 消息服务提供以下功能：
     * - 消息发送：支持单播、广播等多种发送方式
     * - 权限验证：发送前进行权限检查
     * - 参数校验：确保消息格式和参数的有效性
     * - 异常处理：统一处理发送过程中的异常
     *
     * @param sessionManager 会话管理器，用于管理WebSocket连接
     * @param tokenAuthenticator Token认证器，用于验证用户身份
     * @param permissionValidator 权限验证器，用于检查消息发送权限
     * @return WebSocket消息服务实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WebSocketMessageService webSocketMessageService(
            WebSocketSessionManager sessionManager,
            TokenAuthenticator tokenAuthenticator,
            MessagePermissionValidator permissionValidator) {
        
        log.info("创建WebSocket消息服务");
        return new WebSocketMessageService(sessionManager, tokenAuthenticator, permissionValidator);
    }

    /**
     * 创建WebSocket服务端处理器
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - winter-netty.server.websocket.enabled=true（默认为false）
     * - 容器中不存在WebSocketServerHandler类型的Bean
     * <p>
     * 服务端处理器负责：
     * - 处理WebSocket连接建立和断开事件
     * - 处理WebSocket消息的接收和分发
     * - 管理连接状态和异常处理
     * - 与会话管理器协作维护连接信息
     *
     * @param sessionManager 会话管理器，用于管理WebSocket连接
     * @return WebSocket服务端处理器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WebSocketServerHandler webSocketServerHandler(WebSocketSessionManager sessionManager) {
        log.info("创建WebSocket服务端处理器");
        return new WebSocketServerHandler(sessionManager);
    }

    // ==================== 管道定制器配置 ====================

    /**
     * 创建WebSocket管道定制器（当启用WebSocket时）
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - winter-netty.server.websocket.enabled=true（默认为false）
     * - 容器中不存在NettyServerPipelineCustomizer类型的Bean
     * <p>
     * 使用WebSocketPipelineCustomizer作为默认实现，提供完整的WebSocket服务功能：
     * - HTTP编解码器和消息聚合：处理HTTP请求和WebSocket握手
     * - CORS跨域支持：允许跨域WebSocket连接
     * - WebSocket握手认证：在握手阶段进行Token验证
     * - WebSocket协议处理：处理WebSocket帧的编解码
     * - 心跳检测和连接管理：维护连接活跃状态
     *
     * @param properties Netty配置属性
     * @param webSocketServerHandler WebSocket服务端处理器
     * @param sessionManager WebSocket会话管理器
     * @param tokenAuthenticator Token认证器
     * @return WebSocket管道定制器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(NettyServerPipelineCustomizer.class)
    public NettyServerPipelineCustomizer webSocketPipelineCustomizer(
            NettyProperties properties,
            WebSocketServerHandler webSocketServerHandler,
            WebSocketSessionManager sessionManager,
            TokenAuthenticator tokenAuthenticator) {
        log.info("WebSocket已启用，使用 WebSocketPipelineCustomizer 作为管道定制器");
        return new WebSocketPipelineCustomizer(properties, webSocketServerHandler, 
                tokenAuthenticator, sessionManager);
    }

    /**
     * 创建默认的Netty服务端管道定制器（当禁用WebSocket时）
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - winter-netty.server.websocket.enabled=false 或未配置
     * - 容器中不存在NettyServerPipelineCustomizer类型的Bean
     * <p>
     * 当禁用WebSocket时，提供基础的TCP服务器管道配置。
     * 用户可以通过实现NettyServerPipelineCustomizer接口来自定义管道配置。
     *
     * @return 默认管道定制器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(NettyServerPipelineCustomizer.class)
    public NettyServerPipelineCustomizer defaultNettyServerPipelineCustomizer() {
        log.info("WebSocket已禁用，使用默认的TCP服务器管道定制器");
        return new DefaultNettyServerPipelineCustomizer();
    }

    // ==================== 服务器核心组件配置 ====================

    /**
     * 创建Netty服务端管道初始化器
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - 容器中不存在NettyServerChannelInitializer类型的Bean
     * <p>
     * 管道初始化器负责：
     * - 初始化Netty服务器端的Channel管道
     * - 设置各种处理器的顺序和配置
     * - 与管道定制器协作完成管道的个性化配置
     * - 确保每个新连接都有正确的处理器链
     *
     * @param serverCustomizer 服务端管道定制器，用于自定义管道中的处理器配置
     * @return Netty服务端管道初始化器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NettyServerChannelInitializer nettyServerChannelInitializer(NettyServerPipelineCustomizer serverCustomizer) {
        log.info("创建Netty服务端管道初始化器");
        return new NettyServerChannelInitializer(serverCustomizer);
    }

    /**
     * 创建Netty服务器实例
     * <p>
     * 注入条件：
     * - winter-netty.enable-server=true（默认为true）
     * - 容器中不存在NettyServer类型的Bean
     * <p>
     * Netty服务器负责：
     * - 启动和管理Netty服务器实例
     * - 监听指定端口，接受客户端连接
     * - 使用配置的线程池处理业务逻辑
     * - 在Spring容器启动时自动启动服务器
     * - 在Spring容器关闭时优雅关闭服务器
     * <p>
     * 服务器配置包括：
     * - 监听端口：从配置文件中读取
     * - Boss线程组：用于接受连接
     * - Worker线程组：用于处理I/O操作
     * - 业务线程池：用于处理业务逻辑
     *
     * @param properties Netty配置属性，包含服务器端口、线程配置等
     * @param initializer 服务端管道初始化器，用于配置Netty Channel的处理器链
     * @param winterNettyServerTaskExecutor 专用线程池执行器，用于处理服务端业务逻辑
     * @return Netty服务器实例，服务器会在Spring容器启动时自动启动
     */
    @Bean
    @ConditionalOnMissingBean
    public NettyServer nettyServer(
            NettyProperties properties,
            NettyServerChannelInitializer initializer,
           ThreadPoolTaskExecutor winterNettyServerTaskExecutor) {
        log.info("创建Netty服务器实例，监听端口: {}", properties.getServer().getPort());
        return new NettyServer(properties, initializer, winterNettyServerTaskExecutor);
    }
}