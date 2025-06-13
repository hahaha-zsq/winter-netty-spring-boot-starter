package com.zsq.winter.netty.autoconfigure;

import com.zsq.winter.netty.core.client.NettyClient;
import com.zsq.winter.netty.core.client.NettyClientChannelInitializer;
import com.zsq.winter.netty.core.client.NettyClientHandler;
import com.zsq.winter.netty.core.client.NettyClientChannelManager;
import com.zsq.winter.netty.core.server.NettyServer;
import com.zsq.winter.netty.core.server.NettyServerChannelInitializer;
import com.zsq.winter.netty.core.server.NettyServerChannelManager;
import com.zsq.winter.netty.core.server.NettyServerHandler;
import com.zsq.winter.netty.service.NettyMessageService;
import com.zsq.winter.netty.service.NettyPushTemplate;
import com.zsq.winter.netty.service.impl.DefaultNettyMessageServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
     * 创建WebSocket连接管理器Bean
     * <p>
     * 用于管理所有WebSocket连接（如添加、移除、广播消息等）。
     */
    @Bean("webSocketChannelManager")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyServerChannelManager webSocketChannelManager() {
        return new NettyServerChannelManager();
    }

    /**
     * 创建默认的消息业务处理服务Bean
     * <p>
     * 如果开发者没有自定义实现WebSocketMessageService接口，
     * 则使用这个默认实现进行日志记录。
     *
     * @return DefaultWebSocketMessageServiceImpl 实例
     */
    @Bean("webSocketMessageService")
    @DependsOn("webSocketChannelManager")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyMessageService defaultWebSocketMessageService(NettyServerChannelManager nettyServerChannelManager) {
        return new DefaultNettyMessageServiceImpl(nettyServerChannelManager);
    }

    /**
     * 创建WebSocket处理器Bean
     * <p>
     * 处理客户端发来的WebSocket消息，包含心跳、文本、私聊、广播等类型。
     * 增加了对Jackson ObjectMapper的注入，以统一使用项目中的JSON配置。
     *
     * @param nettyServerChannelManager     管理WebSocket连接的组件
     * @param nettyMessageService           消息业务处理服务
     * @param winterNettyServerTaskExecutor 线程池
     * @return WebSocketHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    @DependsOn("webSocketMessageService")
    public NettyServerHandler webSocketHandler(
            NettyServerChannelManager nettyServerChannelManager,
            NettyMessageService nettyMessageService,
            ThreadPoolTaskExecutor winterNettyServerTaskExecutor) {
        return new NettyServerHandler(nettyServerChannelManager, nettyMessageService, winterNettyServerTaskExecutor);
    }

    /**
     * 创建WebSocket管道初始化器Bean
     * <p>
     * 初始化Netty ChannelPipeline，添加必要的协议处理器：
     * - HTTP编解码
     * - WebSocket握手与帧处理
     * - 心跳检测
     * - 自定义消息处理器
     *
     * @param properties         WebSocket配置属性
     * @param nettyServerHandler 自定义WebSocket处理器
     * @return WebSocketChannelInitializer 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    public NettyServerChannelInitializer webSocketChannelInitializer(
            NettyProperties properties,
            NettyServerHandler nettyServerHandler) {
        return new NettyServerChannelInitializer(properties, nettyServerHandler);
    }

    /**
     * 创建Netty WebSocket服务器Bean
     * <p>
     * 启动并运行Netty服务器，监听指定端口，处理WebSocket请求。
     *
     * @param properties         WebSocket配置属性
     * @param channelInitializer 管道初始化器
     * @return NettyWebSocketServer 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    public NettyServer nettyWebSocketServer(
            NettyProperties properties,
            NettyServerChannelInitializer channelInitializer,
            ThreadPoolTaskExecutor winterNettyServerTaskExecutor) {
        return new NettyServer(properties, channelInitializer, winterNettyServerTaskExecutor);
    }

    /**
     * 创建WebSocket消息推送服务Bean
     * <p>
     * 提供向指定用户或所有用户发送WebSocket消息的功能。
     *
     * @param nettyServerChannelManager 管理WebSocket连接的组件
     * @return WebSocketPushService 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @DependsOn("webSocketChannelManager")
    @ConditionalOnProperty(prefix = "netty", name = "enableServer", havingValue = "true")
    public NettyPushTemplate webSocketPushService(NettyServerChannelManager nettyServerChannelManager) {
        return new NettyPushTemplate(nettyServerChannelManager);
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


    @Bean
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyClientChannelManager webSocketClientManager() {
        return new NettyClientChannelManager();
    }

    @Bean
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyClientHandler webSocketClientHandler() {
        return new NettyClientHandler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "netty", name = "enable-client", havingValue = "true")
    @ConditionalOnMissingBean
    public NettyClient nettyWebSocketClient(
            NettyProperties properties,
            NettyClientChannelInitializer initializer,
            ThreadPoolTaskExecutor winterNettyClientTaskExecutor) {
        return new NettyClient(properties, initializer, winterNettyClientTaskExecutor);
    }


}
