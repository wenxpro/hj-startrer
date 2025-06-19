package com.wenx.v3oauth2clientstarter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * JWT解码器配置
 * 
 * @author wenx
 * @description 配置支持负载均衡的JWT解码器
 */
@Configuration
@AutoConfiguration
@AutoConfigureBefore(OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnClass(JwtDecoder.class)
@ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
public class OAuth2JwtDecoderConfiguration {

    /**
     * 创建支持负载均衡的JWT解码器
     * 这个配置会优先于Spring Security的默认配置
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Qualifier("oauth2LoadBalancedRestTemplate") RestTemplate restTemplate) {
        
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .restOperations(restTemplate)
                .build();
        
        return jwtDecoder;
    }
} 