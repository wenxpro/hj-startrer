package com.wenx.v3authserverstarter.config;

import com.wenx.v3authserverstarter.properties.CloudAuthServerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * 云CORS跨域自动配置
 *
 * @author wenx
 * @description 为OAuth2服务器配置跨域访问策略
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "cloud.auth.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CloudAuthServerProperties.class)
@RequiredArgsConstructor
public class CloudCorsConfiguration {

    private final CloudAuthServerProperties properties;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        CloudAuthServerProperties.Cors corsConfig = properties.getCors();
        
        // 允许的来源
        configuration.setAllowedOriginPatterns(Arrays.asList(corsConfig.getAllowedOrigins()));
        
        // 允许的HTTP方法
        configuration.setAllowedMethods(Arrays.asList(corsConfig.getAllowedMethods()));
        
        // 允许的请求头
        configuration.setAllowedHeaders(Arrays.asList(corsConfig.getAllowedHeaders()));
        
        // 暴露的响应头
        configuration.setExposedHeaders(Arrays.asList(corsConfig.getExposedHeaders()));
        
        // 允许发送Cookie
        configuration.setAllowCredentials(corsConfig.isAllowCredentials());
        
        // 预检请求缓存时间
        configuration.setMaxAge(corsConfig.getMaxAge());
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
} 