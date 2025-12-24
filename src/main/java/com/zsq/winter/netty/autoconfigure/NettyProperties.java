package com.zsq.winter.netty.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 配置属性（区分客户端与服务端线程池）
 */
@Data
@ConfigurationProperties(prefix = "winter-netty")
public class NettyProperties {
    /**
     * 是否启用 服务端组件
     */
    private boolean enableServer = true;

    /**
     * 是否启用 客户端组件
     */
    private boolean enableClient = false;
    /**
     * 服务端配置
     */
    private ServerProperties server = new ServerProperties();

    /**
     * 客户端配置
     */
    private ClientProperties client = new ClientProperties();

    @Data
    public static class ServerProperties {
        private int port = 8888;
        private boolean enabled = true; // 仅表示组件是否启用
        private int maxHeartbeatMiss = 3;
        private int bossThreads = 5;
        private int workerThreads = 0;
        private ThreadProperties threadPool = new ThreadProperties();
        
        /**
         * WebSocket 配置
         */
        private WebSocketProperties websocket = new WebSocketProperties();
    }
    
    @Data
    public static class WebSocketProperties {
        /**
         * 是否启用内置 WebSocket 处理器
         */
        private boolean enabled = false;
        
        /**
         * WebSocket 路径
         */
        private String path = "/ws";
        
        /**
         * 是否启用心跳检测
         */
        private boolean heartbeatEnabled = true;
        
        /**
         * 心跳间隔（秒）
         */
        private int heartbeatInterval = 30;
        
        /**
         * 最大空闲时间（秒），超过此时间未收到消息则断开连接
         */
        private int maxIdleTime = 90;
        
        /**
         * 最大连接数限制
         */
        private int maxConnections = 1000;
    }

    @Data
    public static class ClientProperties {
        /**
         * 客户端连接地址
         */
        private String host = "localhost";

        /**
         * 客户端连接端口
         */
        private int port = 8889;
        /**
         * 客户端线程池配置
         */
        private ThreadProperties threadPool = new ThreadProperties();
    }

    @Data
    public static class ThreadProperties {
        /**
         * 核心线程数：线程池创建时候初始化的线程数(默认为可用的计算资源，而不是CPU物理核心数)
         * 核心线程会一直存活，即使没有任务需要执行
         */
        public Integer corePoolSize = 1;

        /**
         * 最大线程数(默认为10)
         * 当线程数>=corePoolSize，且任务队列已满时。线程池会创建新线程来处理任务
         * 当线程数=maxPoolSize，且任务队列已满时，线程池会拒绝处理任务而抛出异常
         */
        public Integer maxPoolSize = 10;

        /**
         * 任务队列容量(默认为50),当核心线程数达到最大时，新任务会放在队列中排队等待执行
         */
        public Integer queueCapacity = 50;

        /**
         * 允许线程的空闲时间/秒，默认为(10秒)：当超过了核心线程出之外的线程在空闲时间到达之后会被销毁
         */
        public Integer keepAliveSeconds = 10;

        /**
         * 线程池名的前缀(默认为winterAsyncServiceExecutor -)：设置好了之后可以方便我们定位处理任务所在的线程池
         */
        public String namePrefix = "winterNettyAsyncExecutor -";

        /**
         * 任务完成，然后等待多少秒再终止线程池(默认为60秒)，以便在容器的其余部分继续关闭之前等待剩余的任务完成他们的执行
         */
        public Integer awaitTerminationSeconds = 60;

        /**
         * 是否等待所有的任务结束后再关闭线程池(默认为true)
         */
        public Boolean waitForTasksToCompleteOnShutdown = true;
    }
}
