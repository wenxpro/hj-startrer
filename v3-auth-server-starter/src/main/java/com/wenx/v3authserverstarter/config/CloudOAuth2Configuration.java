package com.wenx.v3authserverstarter.config;

import com.wenx.v3authserverstarter.handler.DefaultAuthenticationHandler;
import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import com.wenx.v3authserverstarter.service.RedisOAuth2AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 云OAuth2授权服务器自动配置
 *
 * @author wenx
 * @description 提供OAuth2授权服务器的自动配置功能
 */
@AutoConfiguration
@ConditionalOnClass({
    HttpSecurity.class,
    OAuth2AuthorizationServerConfigurer.class
})
@ConditionalOnProperty(prefix = "cloud.auth.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CloudAuthServerProperties.class)
@RequiredArgsConstructor
public class CloudOAuth2Configuration {

    private final CloudAuthServerProperties properties;

    /**
     * OAuth2授权服务器安全过滤器链
     */
    @Bean
    @Order(1)
    @ConditionalOnMissingBean(name = "authorizationServerSecurityFilterChain")
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            AuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = 
            new OAuth2AuthorizationServerConfigurer();
        
        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
            .with(authorizationServerConfigurer, (authorizationServer) -> {
                // 根据配置决定是否启用OIDC
                if (properties.getOidc().isEnabled()) {
                    authorizationServer.oidc(oidc -> {
                        // 配置UserInfo端点（如果启用）
                        if (properties.getOidc().isUserInfoEnabled()) {
                            oidc.userInfoEndpoint(userInfo -> userInfo
                                .userInfoMapper(this::createUserInfo)
                            );
                        }
                        // 配置客户端注册端点（如果启用）
                        if (properties.getOidc().isClientRegistrationEnabled()) {
                            oidc.clientRegistrationEndpoint(Customizer.withDefaults());
                        }
                    });
                }
            })
            // 当未认证用户访问需要认证的端点时，使用自定义认证入口点
            .exceptionHandling(exceptions ->
                exceptions.authenticationEntryPoint(authenticationEntryPoint)
            )
            // 允许授权服务器同时作为资源服务器，验证JWT令牌
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            // 禁用CSRF，因为这是API服务
            .csrf(AbstractHttpConfigurer::disable)
            // 启用CORS支持
            .cors(Customizer.withDefaults());
            
        return http.build();
    }

    /**
     * 授权服务器设置 - 根据配置包含OIDC相关配置
     */
    @Bean
    @ConditionalOnMissingBean
    public AuthorizationServerSettings authorizationServerSettings() {
        AuthorizationServerSettings.Builder builder = AuthorizationServerSettings.builder()
            .issuer(properties.getIssuer())
            // OAuth2核心端点（总是启用）
            .authorizationEndpoint("/oauth2/authorize")
            .deviceAuthorizationEndpoint("/oauth2/device_authorization")
            .deviceVerificationEndpoint("/oauth2/device_verification")
            .tokenEndpoint("/oauth2/token")
            .jwkSetEndpoint("/oauth2/jwks")
            .tokenRevocationEndpoint("/oauth2/revoke")
            .tokenIntrospectionEndpoint("/oauth2/introspect");
        
        // 根据配置决定是否添加OIDC端点
        if (properties.getOidc().isEnabled()) {
            if (properties.getOidc().isClientRegistrationEnabled()) {
                builder.oidcClientRegistrationEndpoint("/connect/register");
            }
            if (properties.getOidc().isUserInfoEnabled()) {
                builder.oidcUserInfoEndpoint("/userinfo");
            }
            builder.oidcLogoutEndpoint("/connect/logout");
        }
        
        return builder.build();
    }

    /**
     * 注册客户端仓库
     */
    @Bean
    @ConditionalOnMissingBean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    /**
     * OAuth2授权服务
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RedisTemplate.class)
    public OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository,
            RedisTemplate<String, Object> redisTemplate) {
        return new RedisOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository, redisTemplate);
    }

    /**
     * 默认认证处理器
     */
    @Bean
    @ConditionalOnMissingBean({
        AuthenticationEntryPoint.class,
        AccessDeniedHandler.class,
        AuthenticationSuccessHandler.class,
        AuthenticationFailureHandler.class
    })
    public DefaultAuthenticationHandler defaultAuthenticationHandler() {
        return new DefaultAuthenticationHandler();
    }

    /**
     * 创建用户信息 - 支持配置化的简化版本
     */
    private OidcUserInfo createUserInfo(OidcUserInfoAuthenticationContext context) {
        OidcUserInfoAuthenticationToken authentication = context.getAuthentication();
        String username = authentication.getName();
        String emailDomain = properties.getOidc().getDefaultEmailDomain();
        
        return OidcUserInfo.builder()
            .subject(username)
            .name(username)
            .preferredUsername(username)
            .email(username + "@" + emailDomain)
            .emailVerified(true)
            .claim("scope", "openid profile email")
            .build();
    }
} 