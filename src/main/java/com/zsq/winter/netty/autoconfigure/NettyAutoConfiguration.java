package com.zsq.winter.netty.autoconfigure;

import com.zsq.winter.netty.core.client.NettyClient;
import com.zsq.winter.netty.core.client.NettyClientChannelInitializer;
import com.zsq.winter.netty.core.client.NettyClientHandler;
import com.zsq.winter.netty.core.client.NettyClientChannelManager;
import com.zsq.winter.netty.core.server.NettyServer;
import com.zsq.winter.netty.core.server.NettyServerChannelInitializer;
import com.zsq.winter.netty.core.server.NettyServerChannelManager;
import com.zsq.winter.netty.core.server.NettyServerHandler;
import com.zsq.winter.netty.service.NettyClientMessageService;
import com.zsq.winter.netty.service.NettyServerMessageService;
import com.zsq.winter.netty.service.NettyServerPushTemplate;
import com.zsq.winter.netty.service.NettyClientPushTemplate;
import com.zsq.winter.netty.service.impl.DefaultNettyClientMessageServiceImpl;
import com.zsq.winter.netty.service.impl.DefaultNettyServerMessageServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;

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
    @Bean("winterNettyServerTaskExecutor")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
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

    /**
     * 创建服务端Channel管理器Bean
     */
    @Bean("nettyServerChannelManager")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyServerChannelManager nettyServerChannelManager() {
        return new NettyServerChannelManager();
    }

    /**
     * 创建默认的服务端消息处理服务Bean
     */
    @Bean("nettyServerMessageService")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn("nettyServerChannelManager")
    public NettyServerMessageService defaultNettyServerMessageService(
            @Qualifier("nettyServerChannelManager") NettyServerChannelManager channelManager) {
        return new DefaultNettyServerMessageServiceImpl(channelManager);
    }

    /**
     * 创建服务端消息处理器Bean
     */
    @Bean("nettyServerHandler")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn({"nettyServerMessageService", "winterNettyServerTaskExecutor"})
    public NettyServerHandler nettyServerHandler(
            @Qualifier("nettyServerChannelManager") NettyServerChannelManager channelManager,
            @Qualifier("nettyServerMessageService") NettyServerMessageService messageService,
            @Qualifier("winterNettyServerTaskExecutor") ThreadPoolTaskExecutor executor) {
        return new NettyServerHandler(channelManager, messageService, executor);
    }

    /**
     * 创建服务端管道初始化器Bean
     */
    @Bean("nettyServerChannelInitializer")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn("nettyServerHandler")
    public NettyServerChannelInitializer nettyServerChannelInitializer(
            NettyProperties properties,
            @Qualifier("nettyServerHandler") NettyServerHandler handler) {
        return new NettyServerChannelInitializer(properties, handler);
    }

    /**
     * 创建Netty服务器Bean
     */
    @Bean("nettyServer")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn({"nettyServerChannelInitializer", "winterNettyServerTaskExecutor"})
    public NettyServer nettyServer(
            NettyProperties properties,
            @Qualifier("nettyServerChannelInitializer") NettyServerChannelInitializer initializer,
            @Qualifier("winterNettyServerTaskExecutor") ThreadPoolTaskExecutor executor) {
        return new NettyServer(properties, initializer, executor);
    }

    /**
     * 创建服务端消息推送服务Bean
     */
    @Bean("nettyServerPushTemplate")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn("nettyServerChannelManager")
    public NettyServerPushTemplate nettyServerPushTemplate(
            @Qualifier("nettyServerChannelManager") NettyServerChannelManager channelManager) {
        return new NettyServerPushTemplate(channelManager);
    }

    @Bean("winterNettyClientTaskExecutor")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
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
     * 创建客户端Channel管理器Bean
     */
    @Bean("nettyClientChannelManager")
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyClientChannelManager nettyClientChannelManager() {
        return new NettyClientChannelManager();
    }

    /**
     * 创建客户端消息处理服务Bean
     */
    @Bean("nettyClientMessageService")
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn("nettyClientChannelManager")
    public NettyClientMessageService defaultNettyClientMessageService(
            @Qualifier("nettyClientChannelManager") NettyClientChannelManager channelManager) {
        return new DefaultNettyClientMessageServiceImpl(channelManager);
    }

    /**
     * 创建客户端消息处理器Bean
     */
    @Bean("nettyClientHandler")
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn({"nettyClientMessageService", "nettyClientChannelManager"})
    public NettyClientHandler nettyClientHandler(
            @Qualifier("nettyClientChannelManager") NettyClientChannelManager channelManager,
            @Qualifier("nettyClientMessageService") NettyClientMessageService messageService,
            @Qualifier("winterNettyClientTaskExecutor") ThreadPoolTaskExecutor executor) {
        return new NettyClientHandler(channelManager, messageService,  executor);
    }

    /**
     * 创建客户端管道初始化器Bean
     */
    @Bean("nettyClientChannelInitializer")
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn("nettyClientHandler")
    public NettyClientChannelInitializer nettyClientChannelInitializer(
            NettyProperties properties,
            @Qualifier("nettyClientHandler") NettyClientHandler handler) {
        return new NettyClientChannelInitializer(handler, properties);
    }

    /**
     * 创建客户端Bean
     */
    @Bean("nettyClient")
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn({"nettyClientChannelInitializer", "winterNettyClientTaskExecutor"})
    public NettyClient nettyClient(
            NettyProperties properties,
            @Qualifier("nettyClientChannelInitializer") NettyClientChannelInitializer initializer,
            @Qualifier("winterNettyClientTaskExecutor") ThreadPoolTaskExecutor executor) {
        return new NettyClient(properties, initializer, executor);
    }

    /**
     * 创建客户端消息发送模板Bean
     */
    @Bean("nettyClientTemplate")
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn({"nettyClient", "nettyClientMessageService"})
    public NettyClientPushTemplate nettyClientTemplate(
            @Qualifier("nettyClientChannelManager") NettyClientChannelManager channelManager ) {
        return new NettyClientPushTemplate(channelManager);
    }
}
