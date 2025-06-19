package com.wenx.v3gateway.starter.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * V3 网关自动配置类
 * 提供基础的增强功能，使用Spring Cloud Gateway的默认配置方式
 */
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.cloud.gateway.config.GatewayAutoConfiguration")
public class V3GatewayAutoConfiguration {

    /**
     * CORS跨域配置
     */
    @Bean
    @ConditionalOnProperty(name = "cloud.gateway.cors.enabled", havingValue = "true", matchIfMissing = true)
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.addAllowedOriginPattern("*");
        corsConfiguration.addAllowedHeader("*");
        corsConfiguration.addAllowedMethod("*");
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        
        return new CorsWebFilter(source);
    }
} 