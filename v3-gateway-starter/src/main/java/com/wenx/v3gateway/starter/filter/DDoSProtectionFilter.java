package com.wenx.v3gateway.starter.filter;

import com.wenx.v3gateway.starter.domain.UserContext;
import com.wenx.v3gateway.starter.enums.UserType;
import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import com.wenx.v3gateway.starter.service.BlacklistService;
import com.wenx.v3gateway.starter.service.DDoSAlertService;
import com.wenx.v3gateway.starter.service.MetricsService;
import com.wenx.v3gateway.starter.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
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
 * 统一版本，使用MetricsService和RateLimitService
 */
@Component
public class DDoSProtectionFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(DDoSProtectionFilter.class);
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DDoSProtectionProperties properties;
    private final ObjectMapper objectMapper;
    
    // 统一服务依赖
    private final RateLimitService rateLimitService;
    private final BlacklistService blacklistService;
    private final MetricsService metricsService;
    private final DDoSAlertService alertService;

    // Redis键前缀
    private static final String RATE_LIMIT_PREFIX = "ddos:rate_limit:";
    private static final String BLACKLIST_PREFIX = "ddos:blacklist:";
    private static final String COUNTER_PREFIX = "ddos:counter:";

    public DDoSProtectionFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                               DDoSProtectionProperties properties,
                               RateLimitService rateLimitService,
                               BlacklistService blacklistService,
                               MetricsService metricsService,
                               DDoSAlertService alertService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.rateLimitService = rateLimitService;
        this.blacklistService = blacklistService;
        this.metricsService = metricsService;
        this.alertService = alertService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查DDoS防护是否启用
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        String clientIp = getClientIp(exchange.getRequest());
        
        logger.debug("DDoS protection check for IP: {}", clientIp);
        
        return processRequest(exchange, chain, clientIp, startTime);
    }

    private Mono<Void> processRequest(ServerWebExchange exchange, GatewayFilterChain chain, 
                                    String clientIp, long startTime) {
        // 检查IP白名单
        if (isWhitelisted(clientIp)) {
            logger.debug("IP {} is whitelisted, skipping DDoS protection", clientIp);
            return chain.filter(exchange)
                    .doFinally(signalType -> recordMetrics(clientIp, false, startTime));
        }

        // 检查黑名单
        return blacklistService.isBlacklisted(clientIp)
                .flatMap(isBlacklisted -> {
                    if (isBlacklisted) {
                        logger.warn("Blocked request from blacklisted IP: {}", clientIp);
                        recordMetrics(clientIp, true, startTime);
                        return createBlockedResponse(exchange, "IP is blacklisted");
                    }
                    return performRateLimitCheck(exchange, chain, clientIp, startTime);
                });
    }

    private Mono<Void> performRateLimitCheck(ServerWebExchange exchange, GatewayFilterChain chain, 
                                           String clientIp, long startTime) {
        String path = exchange.getRequest().getPath().value();
        
        // 使用统一的RateLimitService进行限流检查
        return rateLimitService.checkRateLimit(clientIp, path)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        logger.warn("Rate limit exceeded for IP: {}, path: {}", clientIp, path);
                        recordMetrics(clientIp, true, startTime);
                        return handleRateLimitViolation(exchange, clientIp, result, startTime);
                    }
                    
                    // 通过限流检查，继续处理请求
                    return chain.filter(exchange)
                            .doFinally(signalType -> recordMetrics(clientIp, false, startTime));
                });
    }

    private Mono<Void> handleRateLimitViolation(ServerWebExchange exchange, String clientIp, 
                                              RateLimitService.RateLimitResult result, long startTime) {
        // 记录违规行为
        Duration processingTime = Duration.ofMillis(System.currentTimeMillis() - startTime);
        metricsService.recordRequest(clientIp, false, processingTime);
        
        // 检查是否需要加入黑名单
        if (shouldAddToBlacklist(result)) {
            blacklistService.addToBlacklist(clientIp, "Repeated rate limit violations")
                    .subscribe(unused -> {
                        logger.warn("Added IP {} to blacklist due to repeated rate limit violations", clientIp);
                        
                        // 创建告警事件
                        Map<String, Object> context = new HashMap<>();
                        context.put("ip", clientIp);
                        context.put("reason", "Repeated rate limit violations");
                        alertService.triggerAlert("SUSPICIOUS_IP_BEHAVIOR", 
                                                "IP " + clientIp + " added to blacklist", 
                                                context);
                    });
        }

        return createRateLimitResponse(exchange, result);
    }

    private boolean shouldAddToBlacklist(RateLimitService.RateLimitResult result) {
        // 简化的黑名单逻辑：当剩余请求数为0且重置时间较长时加入黑名单
        return result.getRemaining() == 0 && result.getResetTimeSeconds() > 60;
    }

    private Mono<Void> createRateLimitResponse(ServerWebExchange exchange, RateLimitService.RateLimitResult result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        
        // 添加限流相关头部
        addRateLimitHeaders(response, result);
        
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Too Many Requests");
        body.put("message", "Rate limit exceeded");
        body.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        body.put("remaining", result.getRemaining());
        body.put("resetTime", result.getResetTimeSeconds());
        
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            DataBuffer buffer = response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            logger.error("Error creating rate limit response", e);
            return response.setComplete();
        }
    }

    private void addRateLimitHeaders(ServerHttpResponse response, RateLimitService.RateLimitResult result) {
        response.getHeaders().add("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        response.getHeaders().add("X-RateLimit-Reset", String.valueOf(result.getResetTimeSeconds()));
        response.getHeaders().add("Retry-After", String.valueOf(result.getResetTimeSeconds()));
    }

    private String getClientIp(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) {
            return xRealIp.trim();
        }
        
        String remoteAddr = request.getRemoteAddress() != null ? 
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        
        return remoteAddr;
    }

    private boolean isWhitelisted(String ip) {
        if (!StringUtils.hasText(properties.getWhitelistIps())) {
            return false;
        }
        
        List<String> whitelist = Arrays.asList(properties.getWhitelistIps().split(","));
        return whitelist.stream()
                .map(String::trim)
                .anyMatch(whiteIp -> whiteIp.equals(ip) || ip.startsWith(whiteIp));
    }

    private void recordMetrics(String clientIp, boolean blocked, long startTime) {
        try {
            Duration processingTime = Duration.ofMillis(System.currentTimeMillis() - startTime);
            metricsService.recordRequest(clientIp, blocked, processingTime);
        } catch (Exception e) {
            logger.error("Error recording metrics", e);
        }
    }

    private Mono<Void> createBlockedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Forbidden");
        body.put("message", message);
        body.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            DataBuffer buffer = response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            logger.error("Error creating blocked response", e);
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，在其他过滤器之前执行
    }
}