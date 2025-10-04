package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.domain.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 增强版限流服务
 * 支持基于用户类型和路径匹配的多维度差异化限流策略
 * 
 * @author wenx
 */
@Service
public class EnhancedRateLimitService {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedRateLimitService.class);
    
    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;
    
    @Autowired
    private RateLimitRuleService ruleService;
    
    @Autowired
    private MetricsService metricsService;
    
    // Redis Lua脚本 - 滑动窗口限流
    private static final String SLIDING_WINDOW_SCRIPT = """
        local key = KEYS[1]
        local window = tonumber(ARGV[1])
        local limit = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])
        
        -- 清理过期数据
        redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)
        
        -- 获取当前窗口内的请求数
        local current = redis.call('ZCARD', key)
        
        if current < limit then
            -- 添加当前请求
            redis.call('ZADD', key, now, now)
            redis.call('EXPIRE', key, window)
            return {1, limit - current - 1, current + 1}
        else
            return {0, 0, current}
        end
        """;

    // Redis Lua脚本 - 令牌桶限流
    private static final String TOKEN_BUCKET_SCRIPT = """
        local key = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local refill_rate = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])
        
        local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
        local current_tokens = tonumber(bucket[1]) or capacity
        local last_refill = tonumber(bucket[2]) or now
        
        -- 计算需要添加的令牌数
        local elapsed = (now - last_refill) / 1000.0
        local tokens_to_add = elapsed * refill_rate
        current_tokens = math.min(capacity, current_tokens + tokens_to_add)
        
        if current_tokens >= 1 then
            current_tokens = current_tokens - 1
            redis.call('HMSET', key, 'tokens', current_tokens, 'last_refill', now)
            redis.call('EXPIRE', key, 3600)
            return {1, math.floor(current_tokens)}
        else
            redis.call('HMSET', key, 'tokens', current_tokens, 'last_refill', now)
            redis.call('EXPIRE', key, 3600)
            return {0, math.floor(current_tokens)}
        end
        """;

    // Redis Lua脚本 - 固定窗口计数器
    private static final String FIXED_WINDOW_SCRIPT = """
        local key = KEYS[1]
        local window = tonumber(ARGV[1])
        local limit = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])
        
        -- 计算当前窗口的开始时间
        local window_start = math.floor(now / (window * 1000)) * (window * 1000)
        local window_key = key .. ':' .. window_start
        
        local current = redis.call('GET', window_key) or 0
        current = tonumber(current)
        
        if current < limit then
            local new_count = redis.call('INCR', window_key)
            redis.call('EXPIRE', window_key, window)
            return {1, limit - new_count, new_count}
        else
            return {0, 0, current}
        end
        """;
    
    /**
     * 执行限流检查
     * 根据用户上下文和请求路径，查找匹配的限流规则并执行检查
     * 
     * @param userContext 用户上下文
     * @return 限流检查结果
     */
    public Mono<RateLimitResult> checkRateLimit(UserContext userContext) {
        if (userContext == null) {
            log.warn("UserContext is null, allowing request");
            return Mono.just(RateLimitResult.allowed());
        }
        
        // 查找匹配的限流规则
        Optional<RateLimitRule> ruleOpt = ruleService.findMatchingRule(
            userContext.getRequestPath(), 
            userContext.getUserType()
        );
        
        if (ruleOpt.isEmpty()) {
            log.debug("No matching rate limit rule found for path: {}, userType: {}", 
                userContext.getRequestPath(), userContext.getUserType());
            return Mono.just(RateLimitResult.allowed());
        }
        
        RateLimitRule rule = ruleOpt.get();
        log.debug("Found matching rule: {} for user: {}, path: {}", 
            rule.getRuleName(), userContext.getUsername(), userContext.getRequestPath());
        
        // 生成限流键
        String rateLimitKey = generateRateLimitKey(userContext, rule);
        
        // 根据规则算法执行限流检查
        return executeRateLimitCheck(rateLimitKey, rule, userContext)
                .doOnNext(result -> {
                    // 记录限流指标
                    recordMetrics(userContext, rule, result);
                    
                    if (!result.isAllowed()) {
                        log.info("Rate limit exceeded - User: {}, Path: {}, Rule: {}, Key: {}", 
                            userContext.getUsername(), userContext.getRequestPath(), 
                            rule.getRuleName(), rateLimitKey);
                    }
                });
    }
    
    /**
     * 生成限流键
     */
    private String generateRateLimitKey(UserContext userContext, RateLimitRule rule) {
        StringBuilder keyBuilder = new StringBuilder("rate_limit:");
        keyBuilder.append(rule.getRuleId()).append(":");
        
        // 根据用户类型生成不同的键策略
        switch (userContext.getUserType()) {
            case ANONYMOUS:
                keyBuilder.append("ip:").append(userContext.getClientIp());
                break;
            case SYSTEM:
                keyBuilder.append("system:").append(userContext.getUsername());
                break;
            case PLATFORM:
                keyBuilder.append("platform:").append(userContext.getUserId());
                break;
            case TENANT:
                keyBuilder.append("tenant:").append(userContext.getTenantId())
                         .append(":user:").append(userContext.getUserId());
                break;
        }
        
        return keyBuilder.toString();
    }
    
    /**
     * 执行限流检查
     */
    private Mono<RateLimitResult> executeRateLimitCheck(String key, RateLimitRule rule, UserContext userContext) {
        switch (rule.getAlgorithm()) {
            case SLIDING_WINDOW:
                return executeSlidingWindowCheck(key, rule);
            case TOKEN_BUCKET:
                return executeTokenBucketCheck(key, rule);
            case FIXED_WINDOW_COUNTER:
                return executeFixedWindowCheck(key, rule);
            default:
                log.warn("Unknown rate limit algorithm: {}, using sliding window", rule.getAlgorithm());
                return executeSlidingWindowCheck(key, rule);
        }
    }
    
    /**
     * 执行滑动窗口限流检查
     */
    private Mono<RateLimitResult> executeSlidingWindowCheck(String key, RateLimitRule rule) {
        long now = Instant.now().toEpochMilli();
        int windowSeconds = (int) rule.getWindowSize().getSeconds();
        int limit = determineLimit(rule, windowSeconds);
        
        List<String> keys = Collections.singletonList(key);
        List<String> args = List.of(
            String.valueOf(windowSeconds),
            String.valueOf(limit),
            String.valueOf(now)
        );

        RedisScript<List> script = RedisScript.of(SLIDING_WINDOW_SCRIPT, List.class);
        
        return redisTemplate.execute(script, keys, args)
                .cast(List.class)
                .next()
                .map(result -> {
                    int allowed = ((Number) result.get(0)).intValue();
                    int remaining = ((Number) result.get(1)).intValue();
                    int current = ((Number) result.get(2)).intValue();
                    
                    return new RateLimitResult(
                        allowed == 1,
                        remaining,
                        windowSeconds,
                        System.currentTimeMillis() + windowSeconds * 1000L,
                        rule.getRuleName(),
                        current,
                        limit
                    );
                })
                .onErrorResume(throwable -> {
                    log.error("Sliding window rate limit check failed for key: {}", key, throwable);
                    return Mono.just(RateLimitResult.error("Sliding window check failed"));
                });
    }
    
    /**
     * 执行令牌桶限流检查
     */
    private Mono<RateLimitResult> executeTokenBucketCheck(String key, RateLimitRule rule) {
        long now = Instant.now().toEpochMilli();
        int capacity = rule.getBucketCapacity();
        double refillRate = rule.getRefillRate();
        
        List<String> keys = Collections.singletonList(key);
        List<String> args = List.of(
            String.valueOf(capacity),
            String.valueOf(refillRate),
            String.valueOf(now)
        );

        RedisScript<List> script = RedisScript.of(TOKEN_BUCKET_SCRIPT, List.class);
        
        return redisTemplate.execute(script, keys, args)
                .cast(List.class)
                .next()
                .map(result -> {
                    int allowed = ((Number) result.get(0)).intValue();
                    int remaining = ((Number) result.get(1)).intValue();
                    
                    int resetTimeSeconds = (int) (capacity / refillRate);
                    
                    return new RateLimitResult(
                        allowed == 1,
                        remaining,
                        resetTimeSeconds,
                        System.currentTimeMillis() + resetTimeSeconds * 1000L,
                        rule.getRuleName(),
                        capacity - remaining,
                        capacity
                    );
                })
                .onErrorResume(throwable -> {
                    log.error("Token bucket rate limit check failed for key: {}", key, throwable);
                    return Mono.just(RateLimitResult.error("Token bucket check failed"));
                });
    }
    
    /**
     * 执行固定窗口计数器限流检查
     */
    private Mono<RateLimitResult> executeFixedWindowCheck(String key, RateLimitRule rule) {
        long now = Instant.now().toEpochMilli();
        int windowSeconds = (int) rule.getWindowSize().getSeconds();
        int limit = determineLimit(rule, windowSeconds);
        
        List<String> keys = Collections.singletonList(key);
        List<String> args = List.of(
            String.valueOf(windowSeconds),
            String.valueOf(limit),
            String.valueOf(now)
        );

        RedisScript<List> script = RedisScript.of(FIXED_WINDOW_SCRIPT, List.class);
        
        return redisTemplate.execute(script, keys, args)
                .cast(List.class)
                .next()
                .map(result -> {
                    int allowed = ((Number) result.get(0)).intValue();
                    int remaining = ((Number) result.get(1)).intValue();
                    int current = ((Number) result.get(2)).intValue();
                    
                    return new RateLimitResult(
                        allowed == 1,
                        remaining,
                        windowSeconds,
                        System.currentTimeMillis() + windowSeconds * 1000L,
                        rule.getRuleName(),
                        current,
                        limit
                    );
                })
                .onErrorResume(throwable -> {
                    log.error("Fixed window rate limit check failed for key: {}", key, throwable);
                    return Mono.just(RateLimitResult.error("Fixed window check failed"));
                });
    }
    
    /**
     * 根据规则和时间窗口确定限制数量
     */
    private int determineLimit(RateLimitRule rule, int windowSeconds) {
        if (windowSeconds <= 1) {
            return rule.getMaxRequestsPerSecond();
        } else if (windowSeconds <= 60) {
            return rule.getMaxRequestsPerMinute();
        } else {
            return rule.getMaxRequestsPerHour();
        }
    }
    
    /**
     * 记录限流指标
     */
    private void recordMetrics(UserContext userContext, RateLimitRule rule, RateLimitResult result) {
        try {
            if (metricsService != null) {
                Duration processingTime = Duration.ofMillis(1); // 默认处理时间
                metricsService.recordRequest(userContext.getClientIp(), !result.isAllowed(), processingTime);
                
                if (!result.isAllowed()) {
                    metricsService.recordBlacklistedIp(userContext.getClientIp(), "RATE_LIMIT_EXCEEDED");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to record rate limit metrics: {}", e.getMessage());
        }
    }
    
    /**
     * 增强版限流结果
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final int remaining;
        private final int resetTimeSeconds;
        private final long resetTimeMs;
        private final String ruleName;
        private final int currentCount;
        private final int limit;
        private final String errorMessage;

        public RateLimitResult(boolean allowed, int remaining, int resetTimeSeconds, long resetTimeMs,
                              String ruleName, int currentCount, int limit) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.resetTimeSeconds = resetTimeSeconds;
            this.resetTimeMs = resetTimeMs;
            this.ruleName = ruleName;
            this.currentCount = currentCount;
            this.limit = limit;
            this.errorMessage = null;
        }
        
        private RateLimitResult(String errorMessage) {
            this.allowed = true; // 出错时允许通过
            this.remaining = 0;
            this.resetTimeSeconds = 0;
            this.resetTimeMs = 0;
            this.ruleName = null;
            this.currentCount = 0;
            this.limit = 0;
            this.errorMessage = errorMessage;
        }
        
        public static RateLimitResult allowed() {
            return new RateLimitResult(true, Integer.MAX_VALUE, 0, 0, "default", 0, Integer.MAX_VALUE);
        }
        
        public static RateLimitResult error(String message) {
            return new RateLimitResult(message);
        }

        // Getters
        public boolean isAllowed() { return allowed; }
        public int getRemaining() { return remaining; }
        public int getResetTimeSeconds() { return resetTimeSeconds; }
        public long getResetTimeMs() { return resetTimeMs; }
        public String getRuleName() { return ruleName; }
        public int getCurrentCount() { return currentCount; }
        public int getLimit() { return limit; }
        public String getErrorMessage() { return errorMessage; }
        public boolean hasError() { return errorMessage != null; }

        @Override
        public String toString() {
            return String.format("RateLimitResult{allowed=%s, remaining=%d, current=%d, limit=%d, rule='%s'}", 
                allowed, remaining, currentCount, limit, ruleName);
        }
    }
}