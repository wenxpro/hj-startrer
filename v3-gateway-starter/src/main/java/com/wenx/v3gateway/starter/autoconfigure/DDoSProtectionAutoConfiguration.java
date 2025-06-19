package com.wenx.v3gateway.starter.autoconfigure;

import com.wenx.v3gateway.starter.filter.DDoSProtectionFilter;
import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

/**
 * DDoS防护自动配置类
 * 提供基于Redis的分布式限流和黑名单功能
 */
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@ConditionalOnClass({ReactiveRedisTemplate.class})
@ConditionalOnProperty(name = "cloud.gateway.ddos.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DDoSProtectionProperties.class)
public class DDoSProtectionAutoConfiguration {

    /**
     * DDoS防护过滤器
     */
    @Bean
    public DDoSProtectionFilter ddosProtectionFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                                                    DDoSProtectionProperties properties) {
        return new DDoSProtectionFilter(redisTemplate, properties);
    }
} 