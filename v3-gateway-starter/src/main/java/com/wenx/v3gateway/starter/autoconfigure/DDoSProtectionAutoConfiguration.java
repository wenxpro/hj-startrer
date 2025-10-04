package com.wenx.v3gateway.starter.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenx.v3gateway.starter.filter.DDoSProtectionFilter;
import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import com.wenx.v3gateway.starter.service.BlacklistService;
import com.wenx.v3gateway.starter.service.DDoSAlertService;
import com.wenx.v3gateway.starter.service.ConfigManagementService;
import com.wenx.v3gateway.starter.service.MetricsService;
import com.wenx.v3gateway.starter.service.EnhancedRateLimitService;
import com.wenx.v3gateway.starter.service.PathMatcherService;
import com.wenx.v3gateway.starter.service.RateLimitRuleService;
import com.wenx.v3gateway.starter.service.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * DDoS防护自动配置
 * 支持基础和增强版DDoS防护功能
 * 基于Redis提供分布式限流和黑名单功能，集成监控和告警
 *
 * @author wenx
 * @since 2024-01-01
 */
@AutoConfiguration
@EnableConfigurationProperties(DDoSProtectionProperties.class)
@ConditionalOnProperty(prefix = "cloud.gateway.ddos", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DDoSProtectionAutoConfiguration {

    /**
     * 默认 ReactiveRedisTemplate Bean
     * 提供 String -> String 的序列化方案
     */
    @Bean
    @ConditionalOnMissingBean(name = "reactiveRedisTemplate")
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {

        StringRedisSerializer serializer = new StringRedisSerializer();

        RedisSerializationContext<String, String> context =
                RedisSerializationContext.<String, String>newSerializationContext(serializer)
                        .key(serializer)
                        .value(serializer)
                        .hashKey(serializer)
                        .hashValue(serializer)
                        .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    /**
     * ReactiveRedisTemplate<String, Object> Bean
     * 为RateLimitRuleService提供对象序列化支持
     */
    @Bean
    @ConditionalOnMissingBean(name = "reactiveRedisTemplateForObject")
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplateForObject(
            ReactiveRedisConnectionFactory factory) {

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        RedisSerializationContext<String, Object> context =
                RedisSerializationContext.<String, Object>newSerializationContext(keySerializer)
                        .key(keySerializer)
                        .value(valueSerializer)
                        .hashKey(keySerializer)
                        .hashValue(valueSerializer)
                        .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }
    /**
     * 路径匹配服务 - 基础服务，始终可用
     */
    @Bean
    @ConditionalOnMissingBean
    public PathMatcherService pathMatcherService() {
        return new PathMatcherService();
    }

    /**
     * 基础限流服务
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimitService rateLimitService(ReactiveRedisTemplate<String, String> redisTemplate,
                                           DDoSProtectionProperties properties,
                                           PathMatcherService pathMatcherService) {
        return new RateLimitService(redisTemplate, properties, pathMatcherService);
    }

    /**
     * 限流规则服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "cloud.gateway.ddos", name = "enhanced-enabled", havingValue = "true")
    public RateLimitRuleService rateLimitRuleService() {
        return new RateLimitRuleService();
    }

    /**
     * 增强版限流服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "cloud.gateway.ddos", name = "enhanced-enabled", havingValue = "true")
    public EnhancedRateLimitService enhancedRateLimitService() {
        return new EnhancedRateLimitService();
    }

    /**
     * 黑名单服务
     */
    @Bean
    public BlacklistService blacklistService(ReactiveRedisTemplate<String, String> redisTemplate,
                                           DDoSProtectionProperties properties) {
        return new BlacklistService(redisTemplate, properties);
    }

    /**
     * 监控指标服务
     */
    @Bean
    public MetricsService metricsService(ReactiveRedisTemplate<String, String> redisTemplate,
                                       MeterRegistry meterRegistry) {
        return new MetricsService(redisTemplate, meterRegistry);
    }

    /**
     * 告警服务
     */
    @Bean
    public DDoSAlertService ddosAlertService(ReactiveRedisTemplate<String, String> redisTemplate,
                                           DDoSProtectionProperties properties) {
        return new DDoSAlertService(redisTemplate, properties);
    }

    /**
     * 配置管理服务
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cloud.gateway.ddos", name = "dynamic-rules-enabled", havingValue = "true")
    public ConfigManagementService configManagementService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            RateLimitRuleService ruleService,
            ObjectMapper objectMapper,
            DDoSProtectionProperties properties) {
        return new ConfigManagementService(redisTemplate, ruleService, objectMapper, properties);
    }

    /**
     * DDoS防护过滤器
     */
    @Bean
    @ConditionalOnMissingBean
    public DDoSProtectionFilter ddosProtectionFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                                                    DDoSProtectionProperties properties,
                                                    RateLimitService rateLimitService,
                                                    BlacklistService blacklistService,
                                                    MetricsService metricsService,
                                                    DDoSAlertService alertService) {
        return new DDoSProtectionFilter(redisTemplate, properties, rateLimitService, 
                                      blacklistService, metricsService, alertService);
    }
}