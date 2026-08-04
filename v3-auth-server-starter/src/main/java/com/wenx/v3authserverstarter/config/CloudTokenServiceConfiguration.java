package com.wenx.v3authserverstarter.config;

import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import com.wenx.v3authserverstarter.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/**
 * 云Token服务自动配置
 *
 * @author wenx
 * @description 提供Token服务的自动配置
 */
@AutoConfiguration
@ConditionalOnClass(JwtEncoder.class)
@ConditionalOnProperty(prefix = "cloud.auth.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CloudAuthServerProperties.class)
@RequiredArgsConstructor
@Slf4j
public class CloudTokenServiceConfiguration {

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("[自动配置] Token 服务（自签 JWT 签发） 已启用（cloud.auth.server.enabled）");
    }

    private final CloudAuthServerProperties properties;

    /**
     * Token服务
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenService tokenService(JwtEncoder jwtEncoder, io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new TokenService(jwtEncoder, properties, meterRegistry);
    }
} 