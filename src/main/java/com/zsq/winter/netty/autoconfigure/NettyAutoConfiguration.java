package com.zsq.winter.netty.autoconfigure;

import com.zsq.winter.netty.core.client.*;
import com.zsq.winter.netty.core.server.*;
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

    /**
     * 创建服务端管道初始化器Bean
     */
    @Bean("nettyServerChannelInitializer")
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-server", havingValue = "true")
    @ConditionalOnMissingBean
    @DependsOn("nettyServerHandler")
    public NettyServerChannelInitializer nettyServerChannelInitializer() {
        return new NettyServerChannelInitializer(serverCustomizers);
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
     * 创建客户端Channel管理器Bean
     */
    @Bean("nettyClientChannelManager")
    @ConditionalOnProperty(prefix = "winter-netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyClientChannelManager nettyClientChannelManager() {
        return new NettyClientChannelManager();
    }


}
