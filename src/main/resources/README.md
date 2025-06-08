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