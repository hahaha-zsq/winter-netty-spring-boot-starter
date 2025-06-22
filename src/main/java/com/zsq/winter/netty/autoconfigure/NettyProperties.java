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
        /**
         * 服务端口
         */
        private int port = 8888;

        /**
         * 是否启用 WebSocket
         */
        private boolean enabled = true;

        /**
         * WebSocket路径
         */
        private String path = "/websocket";

        /**
         * 最大连接数
         */
        private int maxConnections = 1000;

        /**
         * 最大帧长度
         */
        private int maxFrameSize = 65536;

        /**
         * 心跳间隔(秒)
         */
        private int heartbeatInterval = 30;

        /**
         * 最大允许的连续心跳丢失次数
         */
        private int maxMissedHeartbeats = 3;

        /**
         * 心跳超时时间（毫秒）
         */
        private long heartbeatTimeoutMs = 5000;

        /**
         * 业务操作超时时间（毫秒）
         */
        private long businessTimeoutMs = 10000;

        /**
         * 僵尸连接判定时间（毫秒）
         */
        private long zombieConnectionTimeoutMs = 30000;

        /**
         * 最大心跳丢失次数，超过此值将关闭连接
         */
        private int maxHeartbeatMiss = 3;

        /**
         * 初始重试延迟（秒）
         */
        private int initialRetryDelay = 5;

        /**
         * 最大重试延迟（秒）
         */
        private int maxRetryDelay = 60;

        /**
         * 重试延迟增长系数，用于实现指数退避
         */
        private double backoffMultiplier = 1.5;

        /**
         * Boss线程数
         */
        private int bossThreads = 1;

        /**
         * Worker线程数
         */
        private int workerThreads = 0;

        /**
         * 是否启用SSL
         */
        private boolean sslEnabled = false;

        /**
         * SSL证书路径
         */
        private String sslCertPath;

        /**
         * SSL私钥路径
         */
        private String sslKeyPath;

        /**
         * 服务端线程池配置
         */
        private ThreadProperties threadPool = new ThreadProperties();

        /**
         * 重试配置
         */
        private RetryProperties retry = new RetryProperties();
    }

    @Data
    public static class ClientProperties {
        /**
         * 客户端连接地址
         */
        private String host = "localhost";
        /**
         * 是否启用 SSL（如需启用 TCP over TLS）
         */
        private boolean sslEnabled = false;

        /**
         * SSL证书路径（X.509格式）
         */
        private String sslCertPath;

        /**
         * SSL私钥路径（PKCS#8格式）
         */
        private String sslKeyPath;

        /**
         * SSL信任证书路径（用于验证服务器证书）
         */
        private String sslTrustCertPath;

        /**
         * 客户端连接端口
         */
        private int port = 8889;

        /**
         * 最大重连次数
         */
        private int maxRetryAttempts = 3;

        /**
         * 重连延迟（秒）
         */
        private long reconnectDelay = 5;

        /**
         * 心跳间隔（秒）
         */
        private int heartbeatInterval = 30;

        /**
         * 最大允许的连续心跳丢失次数
         */
        private int maxMissedHeartbeats = 3;

        /**
         * 心跳超时时间（毫秒）
         */
        private long heartbeatTimeoutMs = 5000;

        /**
         * 业务操作超时时间（毫秒）
         */
        private long businessTimeoutMs = 10000;

        /**
         * 僵尸连接判定时间（毫秒）
         */
        private long zombieConnectionTimeoutMs = 30000;

        /**
         * 最大心跳丢失次数，超过此值将关闭连接
         */
        private int maxHeartbeatMiss = 3;

        /**
         * 初始重试延迟（秒）
         */
        private int initialRetryDelay = 5;

        /**
         * 最大重试延迟（秒）
         */
        private int maxRetryDelay = 60;

        /**
         * 重试延迟增长系数，用于实现指数退避
         */
        private double backoffMultiplier = 1.5;

        /**
         * 客户端线程池配置
         */
        private ThreadProperties threadPool = new ThreadProperties();

        /**
         * 重试配置
         */
        private RetryProperties retry = new RetryProperties();
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

    @Data
    public static class RetryProperties {
        /**
         * 是否启用重试机制
         */
        private boolean enabled = true;

        /**
         * 最大重试次数，0表示无限重试
         */
        private int maxAttempts = 3;

        /**
         * 初始重试延迟时间（秒）
         */
        private long initialDelay = 1;

        /**
         * 最大重试延迟时间（秒）
         */
        private long maxDelay = 30;

        /**
         * 重试延迟时间的增长倍数
         */
        private double backoffMultiplier = 2.0;

        /**
         * 可重试的异常类型
         */
        @SuppressWarnings("unchecked")
        private Class<? extends Exception>[] retryOn = new Class[]{
                io.netty.channel.ChannelException.class,
                java.net.BindException.class,
                javax.net.ssl.SSLException.class
        };
    }
}
