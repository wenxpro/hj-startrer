package com.wenx.v3authserverstarter.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 云认证服务器配置属性
 *
 * @author wenx
 * @description OAuth2授权服务器配置属性
 */
@Data
@ConfigurationProperties(prefix = "cloud.auth.server")
public class CloudAuthServerProperties {

    /**
     * 是否启用认证服务器
     */
    private boolean enabled = true;

    /**
     * JWT令牌颁发者标识
     */
    private String issuer = "http://v3-auth";

    /**
     * JWT配置
     */
    private Jwt jwt = new Jwt();

    /**
     * 安全配置
     */
    private Security security = new Security();

    /**
     * CORS配置
     */
    private Cors cors = new Cors();

    @Data
    public static class Jwt {
        /**
         * 访问令牌过期时间（秒）
         */
        private long accessTokenExpiresIn = 3600; // 1小时

        /**
         * 刷新令牌过期时间（秒）
         */
        private long refreshTokenExpiresIn = 604800; // 7天
    }

    @Data
    public static class Security {
        /**
         * BCrypt密码编码强度
         */
        private int bcryptStrength = 12;

        /**
         * Remember Me密钥
         */
        private String rememberMeKey = "v3-auth-remember-me-key";

        /**
         * Remember Me令牌有效期（秒）
         */
        private int rememberMeTokenValiditySeconds = 7 * 24 * 60 * 60; // 7天

        /**
         * 公开访问路径
         */
        private String[] publicPaths = {
            "/oauth2/**", "/.well-known/**", "/login", "/logout", "/error",
            "/actuator/health", "/admin/token/health",
            "/api/token/statistics", "/api/token/health",
            "/api/auth/login", "/api/auth/logout", "/api/auth/refresh",
            "/api/auth/user/status/**", "/api/auth/token/validate", "/api/auth/user/current",
            "/css/**", "/js/**", "/images/**", "/favicon.ico", "/doc.html",
            "/v3/api-docs", "/api/v3/auth/**"
        };

        /**
         * 管理员访问路径
         */
        private String[] adminPaths = {
            "/admin/**", "/api/token/cleanup"
        };
    }

    @Data
    public static class Cors {
        /**
         * 允许的来源
         */
        private String[] allowedOrigins = {
            "http://localhost:*",
            "https://localhost:*",
            "http://127.0.0.1:*",
            "https://127.0.0.1:*"
        };

        /**
         * 允许的HTTP方法
         */
        private String[] allowedMethods = {
            "GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"
        };

        /**
         * 允许的请求头
         */
        private String[] allowedHeaders = {
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "Cache-Control",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"
        };

        /**
         * 暴露的响应头
         */
        private String[] exposedHeaders = {
            "Authorization",
            "Content-Type",
            "X-Total-Count"
        };

        /**
         * 是否允许发送Cookie
         */
        private boolean allowCredentials = true;

        /**
         * 预检请求缓存时间（秒）
         */
        private long maxAge = 3600L;
    }
} 