package com.wenx.v3authserverstarter.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.wenx.v3authserverstarter.handler.DefaultAuthenticationHandler;
import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import com.wenx.v3authserverstarter.service.RedisOAuth2AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * 云OAuth2授权服务器自动配置
 *
 * @author wenx
 * @description 提供OAuth2授权服务器的自动配置功能
 */
@Slf4j
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
     * 使用Spring Authorization Server推荐的配置方式
     */
    @Bean
    @Order(1) // 确保授权服务器的过滤器链优先处理
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
            throws Exception {

        // 关键：应用授权服务器的默认配置
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        http
                // 当未认证用户访问需要认证的端点时，重定向到登录页面
                .exceptionHandling((exceptions) -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                )
                // 允许授权服务器同时作为资源服务器，验证JWT令牌
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        // 如果您有 OIDC 相关的配置，可以保留 OIDC 模块的配置
        if (properties.getOidc().isEnabled()) {
            http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                    .oidc(oidc -> {
                        if (properties.getOidc().isUserInfoEnabled()) {
                            oidc.userInfoEndpoint(userInfo -> userInfo
                                    .userInfoMapper(this::createUserInfo)
                            );
                        }
                        if (properties.getOidc().isClientRegistrationEnabled()) {
                            oidc.clientRegistrationEndpoint(Customizer.withDefaults());
                        }
                    });
        }

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
                .authorizationEndpoint("/oauth2/authorize")
                .deviceAuthorizationEndpoint("/oauth2/device_authorization")
                .deviceVerificationEndpoint("/oauth2/device_verification")
                .tokenEndpoint("/oauth2/token")
                .jwkSetEndpoint("/oauth2/jwks")
                .tokenRevocationEndpoint("/oauth2/revoke")
                .tokenIntrospectionEndpoint("/oauth2/introspect");

        if (properties.getOidc().isEnabled()) {
            if (properties.getOidc().isClientRegistrationEnabled()) {
                builder.oidcClientRegistrationEndpoint("/connect/register");
            }
            if (properties.getOidc().isUserInfoEnabled()) {
                builder.oidcUserInfoEndpoint("/userinfo");
            }
            builder.oidcLogoutEndpoint("/connect/logout");
        }

        AuthorizationServerSettings settings = builder.build();
        return settings;
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
    public OAuth2AuthorizationService authorizationService( //
                                                            JdbcTemplate jdbcTemplate,
                                                            RegisteredClientRepository registeredClientRepository,
                                                            RedisTemplate<String, Object> redisTemplate) {
        return new RedisOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository, redisTemplate);
    }

    /**
     * 默认认证处理器
     */
    @Bean
    @ConditionalOnMissingBean({ //
            AuthenticationEntryPoint.class,
            AccessDeniedHandler.class,
            AuthenticationSuccessHandler.class,
            AuthenticationFailureHandler.class
    })
    public DefaultAuthenticationHandler defaultAuthenticationHandler() { //
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

    /**
     * JWKSource Bean，用于JWT令牌签名
     */
    @Bean
    @ConditionalOnMissingBean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static KeyPair generateRsaKey() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return keyPair;
    }
}