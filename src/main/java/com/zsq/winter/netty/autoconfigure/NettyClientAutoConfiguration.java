package com.zsq.winter.netty.autoconfigure;

import com.zsq.winter.netty.core.client.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Netty客户端自动配置类
 * <p>
 * 负责在Spring Boot启动时自动注册Netty客户端相关的Bean，包括：
 * - 线程池执行器：用于处理客户端业务逻辑的异步执行
 * - 管道定制器：配置客户端Netty处理器链
 * - 管道初始化器：初始化客户端Channel管道
 * - 客户端实例：管理Netty客户端连接
 * <p>
 * 所有Bean的创建都基于配置条件，确保在禁用客户端功能时不会创建不必要的Bean
 * 
 * @author zsq
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(NettyProperties.class)
@ConditionalOnProperty(prefix = "winter-netty", name = "enable-client", havingValue = "true")
public class NettyClientAutoConfiguration {

    /**
     * 自动注入客户端管道定制器列表
     * <p>
     * 允许用户提供多个自定义的客户端管道定制器，用于个性化配置客户端的处理器链。
     * 如果没有提供自定义定制器，则使用默认配置。
     */
    @Autowired(required = false)
    private List<NettyClientPipelineCustomizer> clientCustomizers;

    // ==================== 线程池配置 ====================

    /**
     * 创建Netty客户端专用线程池执行器
     * <p>
     * 注入条件：
     * - winter-netty.enable-client=true
     * - 容器中不存在名为winterNettyClientTaskExecutor的Bean
     * <p>
     * 此线程池专门用于处理Netty客户端的业务逻辑，包括：
     * - 客户端消息处理
     * - 业务逻辑异步执行
     * - 连接管理和重连逻辑
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
     * @param properties Netty配置属性，包含客户端线程池相关配置
     * @return 配置好的客户端线程池执行器
     */
    @Bean("winterNettyClientTaskExecutor")
    @ConditionalOnMissingBean(name = "winterNettyClientTaskExecutor")
    public ThreadPoolTaskExecutor winterNettyClientTaskExecutor(NettyProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        NettyProperties.ThreadProperties threadProps = properties.getClient().getThreadPool();
        
        // 设置核心线程数：线程池创建时的初始线程数
        executor.setCorePoolSize(threadProps.getCorePoolSize());
        // 设置最大线程数：当任务队列满时，可以创建的最大线程数
        executor.setMaxPoolSize(threadProps.getMaxPoolSize());
        // 设置任务队列容量：当核心线程都在工作时，新任务会放入队列等待
        executor.setQueueCapacity(threadProps.getQueueCapacity());
        // 线程空闲超时时间：超过核心线程数的线程如果空闲，会在指定时间后被回收
        executor.setKeepAliveSeconds(threadProps.getKeepAliveSeconds());
        // 设置线程名称前缀：便于在日志中识别线程来源
        executor.setThreadNamePrefix(threadProps.getNamePrefix() + "client-");
        // 设置关闭策略：是否等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(threadProps.getWaitForTasksToCompleteOnShutdown());
        // 设置关闭前等待时间：在关闭线程池时，等待指定秒数让任务完成
        executor.setAwaitTerminationSeconds(threadProps.getAwaitTerminationSeconds());
        // 设置拒绝策略：当任务队列满时，新任务会执行拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 初始化线程池
        executor.initialize();
        
        log.info("Netty客户端线程池已创建 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}", 
                threadProps.getCorePoolSize(), threadProps.getMaxPoolSize(), threadProps.getQueueCapacity());

        return executor;
    }

    // ==================== 客户端核心组件配置 ====================

    /**
     * 创建Netty客户端管道初始化器
     * <p>
     * 注入条件：
     * - winter-netty.enable-client=true
     * - 容器中不存在NettyClientChannelInitializer类型的Bean
     * <p>
     * 管道初始化器负责：
     * - 初始化Netty客户端的Channel管道
     * - 设置各种处理器的顺序和配置
     * - 与管道定制器协作完成管道的个性化配置
     * - 确保每个客户端连接都有正确的处理器链
     * <p>
     * 支持多个管道定制器，允许用户灵活配置客户端的处理器链：
     * - 编解码器配置
     * - 业务处理器配置
     * - 异常处理器配置
     * - 心跳检测配置等
     *
     * @return Netty客户端管道初始化器实例
     */
    @Bean("nettyClientChannelInitializer")
    @ConditionalOnMissingBean
    public NettyClientChannelInitializer nettyClientChannelInitializer() {
        log.info("创建Netty客户端管道初始化器，定制器数量: {}", 
                clientCustomizers != null ? clientCustomizers.size() : 0);
        return new NettyClientChannelInitializer(clientCustomizers);
    }

    /**
     * 创建Netty客户端实例
     * <p>
     * 注入条件：
     * - winter-netty.enable-client=true
     * - 容器中不存在NettyClient类型的Bean
     * <p>
     * Netty客户端负责：
     * - 管理与服务器的连接
     * - 处理连接建立和断开
     * - 支持自动重连机制
     * - 使用配置的线程池处理业务逻辑
     * - 提供消息发送和接收功能
     * <p>
     * 客户端配置包括：
     * - 目标服务器地址和端口
     * - 连接超时时间
     * - 重连策略
     * - 业务线程池配置
     *
     * @param properties Netty配置属性，包含客户端连接配置等
     * @param initializer 客户端管道初始化器，用于配置Netty Channel的处理器链
     * @param winterNettyClientTaskExecutor 专用线程池执行器，用于处理客户端业务逻辑
     * @return Netty客户端实例
     */
    @Bean("nettyClient")
    @ConditionalOnMissingBean(NettyClient.class)
    public NettyClient nettyClient(
            NettyProperties properties,
            NettyClientChannelInitializer initializer,
            ThreadPoolTaskExecutor winterNettyClientTaskExecutor) {
        log.info("创建Netty客户端实例，目标服务器: {}:{}", 
                properties.getClient().getHost(), properties.getClient().getPort());
        return new NettyClient(properties, initializer, winterNettyClientTaskExecutor);
    }
}