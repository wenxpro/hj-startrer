package com.wenx.v3gateway.starter.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * V3 网关自动配置类
 * 提供基础的增强功能，使用Spring Cloud Gateway的默认配置方式
 */
@Slf4j
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.cloud.gateway.config.GatewayAutoConfiguration")
@EnableConfigurationProperties(GatewayCorsProperties.class)
public class V3GatewayAutoConfiguration {

    /**
     * 自动配置启用提示（starter 热启用可观测性）
     */
    @PostConstruct
    public void init() {
        log.info("[自动配置] V3GatewayAutoConfiguration 已启用（cloud.gateway.cors.enabled 控制 CORS 白名单）");
    }

    @Bean("reactiveRedisTemplateForObject")
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplateForObject(ReactiveRedisConnectionFactory factory) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        Jackson2JsonRedisSerializer<Object> valueSerializer = new Jackson2JsonRedisSerializer<>(om, Object.class);

        RedisSerializationContext<String, Object> context = RedisSerializationContext
                .<String, Object>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .hashKey(keySerializer)
                .hashValue(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    /**
     * CORS跨域配置
     * 允许的源通过 cloud.gateway.cors.allowed-origin-patterns 配置（P0.2 收敛为白名单），
     * 未配置时保持通配以兼容存量环境
     */
    @Bean
    @ConditionalOnProperty(name = "cloud.gateway.cors.enabled", havingValue = "true", matchIfMissing = true)
    public CorsWebFilter corsWebFilter(GatewayCorsProperties corsProperties) {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(true);
        if (corsProperties.getAllowedOriginPatterns().isEmpty()) {
            // 未配置白名单时回退通配，兼容存量环境；配置后为严格白名单
            corsConfiguration.addAllowedOriginPattern("*");
        } else {
            corsProperties.getAllowedOriginPatterns().forEach(corsConfiguration::addAllowedOriginPattern);
        }
        corsConfiguration.addAllowedHeader("*");
        corsConfiguration.addAllowedMethod("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);

        return new CorsWebFilter(source);
    }
} 