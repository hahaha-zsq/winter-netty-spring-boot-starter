package com.zsq.winter.netty.core.websocket;

/**
 * Token 认证器接口
 * 
 * 使用者需要实现此接口来提供具体的 Token 验证逻辑
 * 
 * 使用示例：
 * <pre>
 * {@code
 * @Component
 * public class JwtTokenAuthenticator implements TokenAuthenticator {
 *     
 *     @Override
 *     public AuthResult authenticate(String token) {
 *         try {
 *             // 解析 JWT Token
 *             Claims claims = Jwts.parser()
 *                 .setSigningKey(secretKey)
 *                 .parseClaimsJws(token)
 *                 .getBody();
 *             
 *             String userId = claims.getSubject();
 *             return AuthResult.success(userId);
 *         } catch (Exception e) {
 *             return AuthResult.failure("Token 验证失败");
 *         }
 *     }
 * }
 * }
 * </pre>
 */
@FunctionalInterface
public interface TokenAuthenticator {

    /**
     * 验证 Token 并返回认证结果
     * 
     * @param token 客户端提供的认证令牌
     * @return 认证结果，包含是否成功、用户ID、错误信息
     */
    AuthResult authenticate(String token);

    /**
     * Token 认证结果
     */
    class AuthResult {
        
        /**
         * 认证是否成功
         */
        private final boolean success;
        
        /**
         * 用户唯一标识
         * 认证成功时必填
         */
        private final String userId;
        
        /**
         * 错误信息
         * 认证失败时说明失败原因
         */
        private final String errorMessage;

        private AuthResult(boolean success, String userId, String errorMessage) {
            this.success = success;
            this.userId = userId;
            this.errorMessage = errorMessage;
        }

        /**
         * 创建认证成功结果
         * 
         * @param userId 用户唯一标识
         * @return 认证成功的结果
         */
        public static AuthResult success(String userId) {
            if (userId == null || userId.trim().isEmpty()) {
                throw new IllegalArgumentException("userId 不能为空");
            }
            return new AuthResult(true, userId, null);
        }

        /**
         * 创建认证失败结果
         * 
         * @param errorMessage 失败原因
         * @return 认证失败的结果
         */
        public static AuthResult failure(String errorMessage) {
            return new AuthResult(false, null, errorMessage);
        }

        /**
         * 判断认证是否成功
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 获取用户ID
         */
        public String getUserId() {
            return userId;
        }

        /**
         * 获取错误信息
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
