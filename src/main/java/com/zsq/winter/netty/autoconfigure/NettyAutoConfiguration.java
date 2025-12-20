package com.zsq.winter.netty.autoconfigure;

import com.zsq.winter.netty.core.client.*;
import com.zsq.winter.netty.core.server.*;
import com.zsq.winter.netty.core.websocket.TokenAuthenticator;
import com.zsq.winter.netty.core.websocket.WebSocketMessageService;
import com.zsq.winter.netty.core.websocket.WebSocketPipelineCustomizer;
import com.zsq.winter.netty.core.websocket.WebSocketServerHandler;
import com.zsq.winter.netty.core.websocket.WebSocketSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * WebSocket自动配置类
 * <p>
 * 负责在Spring Boot启动时自动注册WebSocket相关的Bean，
 * 包括连接管理器、处理器、管道初始化器、服务器等。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NettyProperties.class) // 启用配置属性绑定
public class NettyAutoConfiguration {

    // 自动收集 Spring 容器中所有实现了该接口的 Bean (如果没有则为空列表)
    @Autowired(required = false)
    private List<NettyServerPipelineCustomizer> serverCustomizers;

    @Autowired(required = false)
    private List<NettyClientPipelineCustomizer> clientCustomizers;


    /**
     * Token 认证器（可选）
     * 如果使用者提供了实现，则启用 Token 认证功能
     */
    @Autowired(required = false)
    private TokenAuthenticator tokenAuthenticator;

    @Bean("winterNettyServerTaskExecutor")
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-server", havingValue = "true", matchIfMissing = true)
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
        executor.setThreadNamePrefix(threadProps.getNamePrefix() + "server");
        // 设置关闭策略：是否等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(threadProps.getWaitForTasksToCompleteOnShutdown());
        // 设置关闭前等待时间：在关闭线程池时，等待指定秒数让任务完成
        executor.setAwaitTerminationSeconds(threadProps.getAwaitTerminationSeconds());
        //  设置拒绝策略：当任务队列满时，新任务会执行拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        return executor;
    }


    @Bean("winterNettyClientTaskExecutor")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-client", havingValue = "true")
    public ThreadPoolTaskExecutor winterNettyClientTaskExecutor(NettyProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        NettyProperties.ThreadProperties threadProps = properties.getClient().getThreadPool();
        executor.setCorePoolSize(threadProps.getCorePoolSize());
        executor.setMaxPoolSize(threadProps.getMaxPoolSize());
        executor.setQueueCapacity(threadProps.getQueueCapacity());
        executor.setKeepAliveSeconds(threadProps.getKeepAliveSeconds());
        executor.setThreadNamePrefix(threadProps.getNamePrefix() + "client");
        executor.setWaitForTasksToCompleteOnShutdown(threadProps.getWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(threadProps.getAwaitTerminationSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }


    /**
     * 创建服务端管道初始化器Bean
     */
    @Bean("nettyServerChannelInitializer")
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-server", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyServerChannelInitializer nettyServerChannelInitializer() {
        return new NettyServerChannelInitializer(serverCustomizers);
    }

    @Bean("nettyClientChannelInitializer")
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyClientChannelInitializer nettyClientChannelInitializer() {
        return new NettyClientChannelInitializer(clientCustomizers);
    }

    /**
     * 创建Netty服务器Bean
     */
    @Bean("nettyServer")
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-server", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn({"nettyServerChannelInitializer", "winterNettyServerTaskExecutor"})
    public NettyServer nettyServer(
            NettyProperties properties,
            @Qualifier("nettyServerChannelInitializer") NettyServerChannelInitializer initializer,
            @Qualifier("winterNettyServerTaskExecutor") ThreadPoolTaskExecutor executor) {
        return new NettyServer(properties, initializer, executor);
    }

    @Bean("nettyClient")
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn({"nettyClientChannelInitializer", "winterNettyClientTaskExecutor"})
    public NettyClient nettyClient(
            NettyProperties properties,
            @Qualifier("nettyClientChannelInitializer") NettyClientChannelInitializer initializer,
            @Qualifier("winterNettyClientTaskExecutor") ThreadPoolTaskExecutor executor) {
        return new NettyClient(properties, initializer, executor);
    }

    // ==================== WebSocket 相关配置 ====================

    /**
     * WebSocket 会话管理器
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WebSocketSessionManager webSocketSessionManager() {
        return new WebSocketSessionManager();
    }

    /**
     * WebSocket 消息服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WebSocketMessageService webSocketMessageService(WebSocketSessionManager sessionManager) {
        return new WebSocketMessageService(sessionManager);
    }

    /**
     * WebSocket 服务端处理器
     * 
     * 注入 TokenAuthenticator（可选）以支持 Token 认证
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WebSocketServerHandler webSocketServerHandler(WebSocketSessionManager sessionManager) {
        return new WebSocketServerHandler(sessionManager, tokenAuthenticator);
    }

    /**
     * WebSocket Pipeline 定制器
     */
    @Bean
    @ConditionalOnProperty(prefix = "winter-netty.server.websocket", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public WebSocketPipelineCustomizer webSocketPipelineCustomizer(
            NettyProperties properties,
            WebSocketServerHandler webSocketServerHandler) {
        return new WebSocketPipelineCustomizer(properties, webSocketServerHandler);
    }

}
