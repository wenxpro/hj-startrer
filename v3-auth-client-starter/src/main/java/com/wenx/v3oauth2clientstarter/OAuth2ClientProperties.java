package com.wenx.v3oauth2clientstarter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth2客户端配置属性
 * 
 * @author wenx
 * @description 使用cloud前缀的OAuth2客户端配置
 */
@ConfigurationProperties(prefix = "cloud.auth.oauth2")
public class OAuth2ClientProperties {

    /**
     * 是否启用OAuth2客户端功能
     */
    private boolean enabled = true;

    /**
     * 是否启用负载均衡
     */
    private boolean loadBalancerEnabled = true;

    /**
     * JWT配置
     */
    private Jwt jwt = new Jwt();

    /**
     * 默认服务地址配置
     */
    private DefaultService defaultService = new DefaultService();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLoadBalancerEnabled() {
        return loadBalancerEnabled;
    }

    public void setLoadBalancerEnabled(boolean loadBalancerEnabled) {
        this.loadBalancerEnabled = loadBalancerEnabled;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public DefaultService getDefaultService() {
        return defaultService;
    }

    public void setDefaultService(DefaultService defaultService) {
        this.defaultService = defaultService;
    }

    /**
     * JWT相关配置
     */
    public static class Jwt {
        /**
         * 默认JWK Set URI
         */
        private String defaultJwkSetUri = "http://v3-auth/oauth2/jwks";

        /**
         * 默认Issuer URI
         */
        private String defaultIssuerUri = "http://v3-auth";

        public String getDefaultJwkSetUri() {
            return defaultJwkSetUri;
        }

        public void setDefaultJwkSetUri(String defaultJwkSetUri) {
            this.defaultJwkSetUri = defaultJwkSetUri;
        }

        public String getDefaultIssuerUri() {
            return defaultIssuerUri;
        }

        public void setDefaultIssuerUri(String defaultIssuerUri) {
            this.defaultIssuerUri = defaultIssuerUri;
        }
    }

    /**
     * 默认服务配置
     */
    public static class DefaultService {
        /**
         * 认证服务名称
         */
        private String authServiceName = "v3-auth";

        public String getAuthServiceName() {
            return authServiceName;
        }

        public void setAuthServiceName(String authServiceName) {
            this.authServiceName = authServiceName;
        }
    }
} 