package com.zsq.winter.netty.core.websocket;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息权限验证器接口
 * 
 * 用于验证用户是否有权限执行特定的消息操作，包括：
 * - 发送系统消息
 * - 发送私聊消息
 * - 广播消息
 * - 批量发送消息
 * 
 * 实现类应该根据业务需求提供具体的权限验证逻辑
 * 
 * 使用示例：
 * <pre>
 * {@code
 * @Component
 * public class CustomMessagePermissionValidator implements MessagePermissionValidator {
 *     
 *     @Override
 *     public boolean hasPermission(String userId, Operation operation, String targetUserId) {
 *         switch (operation) {
 *             case SEND_SYSTEM_MESSAGE:
 *             case BROADCAST_SYSTEM_MESSAGE:
 *                 return isAdmin(userId);
 *             case SEND_PRIVATE_MESSAGE:
 *                 return canSendPrivateMessage(userId, targetUserId);
 *             case BROADCAST_MESSAGE:
 *                 return canBroadcast(userId);
 *             case SEND_SYSTEM_MESSAGE_BATCH:
 *                 return isAdmin(userId);
 *             default:
 *                 return false;
 *         }
 *     }
 * }
 * }
 * </pre>
 */
@FunctionalInterface
public interface MessagePermissionValidator {
    
    /**
     * 检查用户是否有权限执行指定操作
     * 
     * @param userId 用户ID
     * @param operation 操作类型枚举
     * @param targetUserId 目标用户ID（可为null，如广播消息）
     * @return true表示有权限，false表示无权限
     */
    boolean hasPermission(String userId, Operation operation, String targetUserId);

    // ==================== 操作类型枚举 ====================

    /**
     * 操作类型枚举
     * <p>
     * 定义了WebSocket消息服务支持的所有操作类型，提供类型安全的操作标识
     */
    enum Operation {
        /** 发送系统消息 - 向指定用户发送系统级别的消息 */
        SEND_SYSTEM_MESSAGE("发送系统消息"),
        
        /** 发送私聊消息 - 向指定用户发送私人消息 */
        SEND_PRIVATE_MESSAGE("发送私聊消息"),
        
        /** 广播系统消息 - 向所有在线用户广播系统消息 */
        BROADCAST_SYSTEM_MESSAGE("广播系统消息"),
        
        /** 广播普通消息 - 向所有在线用户广播普通消息 */
        BROADCAST_MESSAGE("广播普通消息"),
        
        /** 批量发送系统消息 - 向多个指定用户批量发送系统消息 */
        SEND_SYSTEM_MESSAGE_BATCH("批量发送系统消息");
        
        private final String description;
        
        Operation(String description) {
            this.description = description;
        }
        
        /**
         * 获取操作描述
         * 
         * @return 操作的中文描述
         */
        public String getDescription() {
            return description;
        }
        
        /**
         * 根据操作名称获取枚举值
         * 
         * @param name 操作名称
         * @return 对应的枚举值，如果不存在则返回 null
         */
        public static Operation fromName(String name) {
            if (name == null) {
                return null;
            }
            
            for (Operation operation : values()) {
                if (operation.name().equals(name)) {
                    return operation;
                }
            }
            return null;
        }
    }

    // ==================== 内部结果类 ====================

    /**
     * 安全验证结果
     */
    @Getter
    class SecurityValidationResult {
        private final boolean success;
        private final String senderId;
        private final String errorMessage;

        private SecurityValidationResult(boolean success, String senderId, String errorMessage) {
            this.success = success;
            this.senderId = senderId;
            this.errorMessage = errorMessage;
        }

        public static SecurityValidationResult success(String senderId) {
            return new SecurityValidationResult(true, senderId, null);
        }

        public static SecurityValidationResult failure(String errorMessage) {
            return new SecurityValidationResult(false, null, errorMessage);
        }

        @Override
        public String toString() {
            return success ?
                String.format("SecurityValidationResult{success=true, senderId='%s'}", senderId) :
                String.format("SecurityValidationResult{success=false, error='%s'}", errorMessage);
        }
    }

    /**
     * 消息发送结果
     */
    @Getter
    class SendResult {
        private final boolean success;
        private final String errorMessage;

        private SendResult(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public static SendResult success() {
            return new SendResult(true, null);
        }

        public static SendResult failure(String errorMessage) {
            return new SendResult(false, errorMessage);
        }

        @Override
        public String toString() {
            return success ? "SendResult{success=true}" :
                String.format("SendResult{success=false, error='%s'}", errorMessage);
        }
    }

    /**
     * 广播消息结果
     */
    @Getter
    class BroadcastResult {
        private final boolean success;
        private final int successCount;
        private final String errorMessage;

        private BroadcastResult(boolean success, int successCount, String errorMessage) {
            this.success = success;
            this.successCount = successCount;
            this.errorMessage = errorMessage;
        }

        public static BroadcastResult success(int successCount) {
            return new BroadcastResult(true, successCount, null);
        }

        public static BroadcastResult failure(String errorMessage) {
            return new BroadcastResult(false, 0, errorMessage);
        }

        @Override
        public String toString() {
            return success ? String.format("BroadcastResult{success=true, count=%d}", successCount) :
                String.format("BroadcastResult{success=false, error='%s'}", errorMessage);
        }
    }

    /**
     * 批量发送消息结果
     */
    @Getter
    class BatchSendResult {
        private final boolean success;
        private final int successCount;
        private final int failedCount;
        private final int offlineCount;
        private final String errorMessage;

        private BatchSendResult(boolean success, int successCount, int failedCount, int offlineCount, String errorMessage) {
            this.success = success;
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.offlineCount = offlineCount;
            this.errorMessage = errorMessage;
        }

        public static BatchSendResult success(int successCount, int failedCount, int offlineCount) {
            return new BatchSendResult(true, successCount, failedCount, offlineCount, null);
        }

        public static BatchSendResult failure(String errorMessage) {
            return new BatchSendResult(false, 0, 0, 0, errorMessage);
        }

        public int getTotalCount() { return successCount + failedCount + offlineCount; }

        public double getSuccessRate() {
            int total = getTotalCount();
            return total > 0 ? (double) successCount / total : 0.0;
        }

        @Override
        public String toString() {
            return success ?
                String.format("BatchSendResult{success=%d, failed=%d, offline=%d, total=%d, successRate=%.2f%%}",
                    successCount, failedCount, offlineCount, getTotalCount(), getSuccessRate() * 100) :
                String.format("BatchSendResult{success=false, error='%s'}", errorMessage);
        }
    }

    /**
     * 消息统计信息
     */
    @Getter
    class MessageStatistics {
        private final int totalSentMessages;
        private final int totalFailedMessages;
        private final int totalBlockedMessages;
        private final int cacheSize;

        public MessageStatistics(int totalSentMessages, int totalFailedMessages, int totalBlockedMessages, int cacheSize) {
            this.totalSentMessages = totalSentMessages;
            this.totalFailedMessages = totalFailedMessages;
            this.totalBlockedMessages = totalBlockedMessages;
            this.cacheSize = cacheSize;
        }

        public int getTotalMessages() { return totalSentMessages + totalFailedMessages; }

        public double getSuccessRate() {
            int total = getTotalMessages();
            return total > 0 ? (double) totalSentMessages / total : 0.0;
        }

        public double getBlockedRate() {
            int totalAttempts = totalSentMessages + totalFailedMessages + totalBlockedMessages;
            return totalAttempts > 0 ? (double) totalBlockedMessages / totalAttempts : 0.0;
        }

        @Override
        public String toString() {
            return String.format("MessageStatistics{sent=%d, failed=%d, blocked=%d, total=%d, successRate=%.2f%%, blockedRate=%.2f%%, cacheSize=%d}",
                totalSentMessages, totalFailedMessages, totalBlockedMessages, getTotalMessages(),
                getSuccessRate() * 100, getBlockedRate() * 100, cacheSize);
        }
    }

    /**
     * 简单的频率限制器
     */
    class RateLimiter {
        private final int maxRequests;
        private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger requestCount = new AtomicInteger(0);

        public RateLimiter(int maxRequestsPerSecond) {
            if (maxRequestsPerSecond <= 0) {
                throw new IllegalArgumentException("每秒最大请求数必须大于0");
            }
            this.maxRequests = maxRequestsPerSecond;
        }

        public boolean tryAcquire() {
            long currentTime = System.currentTimeMillis();
            long lastReset = lastResetTime.get();

            // 如果超过1秒，重置计数器
            if (currentTime - lastReset >= 1000) {
                if (lastResetTime.compareAndSet(lastReset, currentTime)) {
                    requestCount.set(0);
                }
            }

            // 检查是否超过限制
            return requestCount.incrementAndGet() <= maxRequests;
        }

        public int getCurrentRequestCount() {
            return requestCount.get();
        }

        public long getLastResetTime() {
            return lastResetTime.get();
        }

        public boolean hasAvailablePermits() {
            return requestCount.get() < maxRequests;
        }

        @Override
        public String toString() {
            return String.format("RateLimiter{maxRequests=%d, currentCount=%d, lastReset=%d}",
                maxRequests, requestCount.get(), lastResetTime.get());
        }
    }
}