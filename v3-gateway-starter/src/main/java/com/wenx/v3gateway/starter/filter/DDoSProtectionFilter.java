package com.wenx.v3gateway.starter.filter;

import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DDoS防护过滤器
 * 提供基于Redis的分布式限流和黑名单功能
 */
@Component
public class DDoSProtectionFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(DDoSProtectionFilter.class);
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DDoSProtectionProperties properties;
    private final ObjectMapper objectMapper;

    // Redis键前缀
    private static final String RATE_LIMIT_PREFIX = "ddos:rate_limit:";
    private static final String BLACKLIST_PREFIX = "ddos:blacklist:";
    private static final String COUNTER_PREFIX = "ddos:counter:";

    public DDoSProtectionFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                               DDoSProtectionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange.getRequest());
        
        // 检查白名单
        if (isWhitelisted(clientIp)) {
            logger.debug("IP {} 在白名单中，跳过DDoS检查", clientIp);
            return chain.filter(exchange);
        }

        return checkBlacklist(clientIp)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        logger.warn("IP {} 在黑名单中，拒绝请求", clientIp);
                        return createBlockedResponse(exchange, "IP已被封禁，请稍后再试");
                    }
                    return checkRateLimit(clientIp)
                            .flatMap(isRateLimited -> {
                                if (Boolean.TRUE.equals(isRateLimited)) {
                                    logger.warn("IP {} 触发限流，拒绝请求", clientIp);
                                    return createBlockedResponse(exchange, "请求过于频繁，请稍后再试");
                                }
                                return chain.filter(exchange);
                            });
                });
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIp(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) {
            return xRealIp;
        }
        
        String remoteAddr = request.getRemoteAddress() != null ? 
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        return remoteAddr;
    }

    /**
     * 检查IP是否在白名单中
     */
    private boolean isWhitelisted(String ip) {
        if (!StringUtils.hasText(properties.getWhitelistIps())) {
            return false;
        }
        
        List<String> whitelistIps = Arrays.asList(properties.getWhitelistIps().split(","));
        return whitelistIps.stream()
                .map(String::trim)
                .anyMatch(whiteIp -> whiteIp.equals(ip));
    }

    /**
     * 检查IP是否在黑名单中
     */
    private Mono<Boolean> checkBlacklist(String ip) {
        String blacklistKey = BLACKLIST_PREFIX + ip;
        return redisTemplate.hasKey(blacklistKey);
    }

    /**
     * 检查限流
     */
    private Mono<Boolean> checkRateLimit(String ip) {
        return checkPerSecondLimit(ip)
                .flatMap(perSecondLimited -> {
                    if (perSecondLimited) {
                        return Mono.just(true);
                    }
                    return checkPerMinuteLimit(ip);
                });
    }

    /**
     * 检查每秒限流
     */
    private Mono<Boolean> checkPerSecondLimit(String ip) {
        String key = RATE_LIMIT_PREFIX + "second:" + ip;
        return redisTemplate.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        // 设置过期时间
                        return redisTemplate.expire(key, Duration.ofSeconds(1))
                                .then(Mono.just(count > properties.getMaxRequestsPerSecond()));
                    }
                    return Mono.just(count > properties.getMaxRequestsPerSecond());
                });
    }

    /**
     * 检查每分钟限流
     */
    private Mono<Boolean> checkPerMinuteLimit(String ip) {
        String key = RATE_LIMIT_PREFIX + "minute:" + ip;
        return redisTemplate.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        // 设置过期时间
                        return redisTemplate.expire(key, Duration.ofMinutes(1))
                                .then(checkSuspiciousBehavior(ip, count))
                                .then(Mono.just(count > properties.getMaxRequestsPerMinute()));
                    }
                    return checkSuspiciousBehavior(ip, count)
                            .then(Mono.just(count > properties.getMaxRequestsPerMinute()));
                });
    }

    /**
     * 检查可疑行为并添加到黑名单
     */
    private Mono<Void> checkSuspiciousBehavior(String ip, Long requestCount) {
        if (requestCount >= properties.getSuspiciousThreshold()) {
            logger.warn("检测到可疑行为，IP: {}, 请求数: {}, 添加到黑名单", ip, requestCount);
            return addToBlacklist(ip);
        }
        return Mono.empty();
    }

    /**
     * 添加IP到黑名单
     */
    private Mono<Void> addToBlacklist(String ip) {
        String blacklistKey = BLACKLIST_PREFIX + ip;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        return redisTemplate.opsForValue()
                .set(blacklistKey, timestamp, Duration.ofMinutes(properties.getBlacklistDurationMinutes()))
                .then();
    }

    /**
     * 创建被阻止的响应
     */
    private Mono<Void> createBlockedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 429);
        result.put("message", message);
        result.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        try {
            String json = objectMapper.writeValueAsString(result);
            DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            logger.error("创建响应JSON失败", e);
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，在其他过滤器之前执行
    }
} 