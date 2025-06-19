package com.wenx.v3authserverstarter.config;

import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import com.wenx.v3authserverstarter.service.TokenService;
import lombok.RequiredArgsConstructor;
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
public class CloudTokenServiceConfiguration {

    private final CloudAuthServerProperties properties;

    /**
     * Token服务
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenService tokenService(JwtEncoder jwtEncoder) {
        return new TokenService(jwtEncoder, properties);
    }
} 