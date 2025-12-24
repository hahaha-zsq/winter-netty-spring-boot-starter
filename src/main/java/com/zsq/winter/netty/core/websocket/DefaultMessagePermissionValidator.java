package com.zsq.winter.netty.core.websocket;

import lombok.extern.slf4j.Slf4j;

/**
 * 默认的消息权限验证器实现（仅用于开发环境）
 */
@Slf4j
public class DefaultMessagePermissionValidator implements MessagePermissionValidator {
    @Override
    public boolean hasPermission(String userId, Operation operation, String targetUserId) {
        log.debug("默认权限验证器：允许用户 {} 执行操作 {} (目标用户: {})", 
                userId, operation.getDescription(), targetUserId);
        return true;
    }
}
