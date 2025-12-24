package com.zsq.winter.netty.core.websocket;

/**
 * 默认的Token认证器（仅用于开发环境）
 * 生产环境中应该提供自己的TokenAuthenticator实现
 */
public class DefaultTokenAuthenticator implements TokenAuthenticator {
    @Override
    public AuthResult authenticate(String token) {
        // 开发环境的简单实现：假设token格式为 "user:userId"
        if (token == null || token.trim().isEmpty()) {
            return AuthResult.failure("Token不能为空");
        }

        // 简单的token解析：user:userId
        if (token.startsWith("user:")) {
            String userId = token.substring(5);
            if (userId.trim().isEmpty()) {
                return AuthResult.failure("用户ID不能为空");
            }
            return AuthResult.success(userId);
        }

        // 管理员token：admin:adminId
        if (token.startsWith("admin:")) {
            String adminId = token.substring(6);
            if (adminId.trim().isEmpty()) {
                return AuthResult.failure("管理员ID不能为空");
            }
            return AuthResult.success(adminId);
        }

        return AuthResult.failure("无效的Token格式");
    }
}
    