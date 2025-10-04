package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.domain.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 统一限流服务
 * 整合基础限流和增强限流功能，支持多维度差异化限流策略
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DDoSProtectionProperties properties;
    private final PathMatcherService pathMatcherService;

    /**
     * 滑动窗口限流 Lua 脚本
     */
    private static final String SLIDING_WINDOW_SCRIPT = """
        local key = KEYS[1]
        local window = tonumber(ARGV[1])
        local limit = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])
        
        -- 清理过期数据
        redis.call('zremrangebyscore', key, 0, now - window * 1000)
        
        -- 获取当前窗口内的请求数
        local current = redis.call('zcard', key)
        
        if current < limit then
            -- 添加当前请求
            redis.call('zadd', key, now, now)
            redis.call('expire', key, window)
            return {1, limit - current - 1}
        else
            return {0, 0}
        end
        """;

    /**
     * 令牌桶限流 Lua 脚本
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
        local key = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local tokens = tonumber(ARGV[2])
        local interval = tonumber(ARGV[3])
        local now = tonumber(ARGV[4])
        
        local bucket = redis.call('hmget', key, 'tokens', 'last_refill')
        local current_tokens = tonumber(bucket[1]) or capacity
        local last_refill = tonumber(bucket[2]) or now
        
        -- 计算需要添加的令牌数
        local elapsed = now - last_refill
        local tokens_to_add = math.floor(elapsed / interval * tokens)
        current_tokens = math.min(capacity, current_tokens + tokens_to_add)
        
        if current_tokens >= 1 then
            current_tokens = current_tokens - 1
            redis.call('hmset', key, 'tokens', current_tokens, 'last_refill', now)
            redis.call('expire', key, 3600)
            return {1, current_tokens}
        else
            redis.call('hmset', key, 'tokens', current_tokens, 'last_refill', now)
            redis.call('expire', key, 3600)
            return {0, 0}
        end
        """;

    /**
     * 固定窗口计数器限流 Lua 脚本
     */
    private static final String FIXED_WINDOW_SCRIPT = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])
        
        -- 计算当前窗口的开始时间
        local window_start = math.floor(now / (window * 1000)) * (window * 1000)
        local window_key = key .. ':' .. window_start
        
        local current = redis.call('get', window_key)
        if current == false then
            redis.call('set', window_key, 1)
            redis.call('expire', window_key, window)
            return {1, limit - 1}
        else
            current = tonumber(current)
            if current < limit then
                redis.call('incr', window_key)
                return {1, limit - current - 1}
            else
                return {0, 0}
            end
        end
        """;

    /**
     * 简单计数器限流 Lua 脚本
     */
    private static final String SIMPLE_COUNTER_SCRIPT = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        
        local current = redis.call('get', key)
        if current == false then
            redis.call('set', key, 1)
            redis.call('expire', key, window)
            return {1, limit - 1}
        else
            current = tonumber(current)
            if current < limit then
                redis.call('incr', key)
                return {1, limit - current - 1}
            else
                return {0, 0}
            end
        end
        """;

    /**
     * 基础限流检查（兼容原有接口）
     */
    public Mono<RateLimitResult> checkRateLimit(String clientIp, String path) {
        RateLimitRule rule = getRateLimitRule(path);
        if (rule == null) {
            return Mono.just(new RateLimitResult(true, 0, rule));
        }

        String key = buildRateLimitKey(clientIp, path);
        
        return switch (rule.getAlgorithm()) {
            case SLIDING_WINDOW -> checkSlidingWindow(key, rule);
            case TOKEN_BUCKET -> checkTokenBucket(key, rule);
            case FIXED_WINDOW_COUNTER -> checkFixedWindow(key, rule);
            default -> checkSimpleCounter(key, rule);
        };
    }

    /**
     * 增强限流检查（支持用户上下文和多维度策略）
     */
    public Mono<RateLimitResult> checkRateLimit(String clientIp, String path, UserContext userContext) {
        if (!properties.isEnhancedEnabled()) {
            return checkRateLimit(clientIp, path);
        }

        RateLimitRule rule = getEnhancedRateLimitRule(path, userContext);
        if (rule == null) {
            return Mono.just(new RateLimitResult(true, 0, rule));
        }

        String key = buildEnhancedRateLimitKey(clientIp, path, userContext);
        
        return switch (rule.getAlgorithm()) {
            case SLIDING_WINDOW -> checkSlidingWindow(key, rule);
            case TOKEN_BUCKET -> checkTokenBucket(key, rule);
            case FIXED_WINDOW_COUNTER -> checkFixedWindow(key, rule);
            default -> checkSimpleCounter(key, rule);
        };
    }

    /**
     * 滑动窗口限流检查
     */
    private Mono<RateLimitResult> checkSlidingWindow(String key, RateLimitRule rule) {
        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(rule.getWindowSize().getSeconds()),
            String.valueOf(rule.getMaxRequestsPerSecond()),
            String.valueOf(Instant.now().toEpochMilli())
        );

        return redisTemplate.execute(RedisScript.of(SLIDING_WINDOW_SCRIPT, List.class), keys, args)
            .cast(List.class)
            .next()
            .map(result -> {
                int allowed = ((Number) result.get(0)).intValue();
                int remaining = ((Number) result.get(1)).intValue();
                return new RateLimitResult(allowed == 1, remaining, rule);
            })
            .onErrorReturn(new RateLimitResult(true, 0, rule));
    }

    /**
     * 令牌桶限流检查
     */
    private Mono<RateLimitResult> checkTokenBucket(String key, RateLimitRule rule) {
        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(rule.getBucketCapacity()),
            String.valueOf(1), // 每次消耗1个令牌
            String.valueOf((int)(1000.0 / rule.getRefillRate())), // 令牌补充间隔
            String.valueOf(Instant.now().toEpochMilli())
        );

        return redisTemplate.execute(RedisScript.of(TOKEN_BUCKET_SCRIPT, List.class), keys, args)
            .cast(List.class)
            .next()
            .map(result -> {
                int allowed = ((Number) result.get(0)).intValue();
                int remaining = ((Number) result.get(1)).intValue();
                return new RateLimitResult(allowed == 1, remaining, rule);
            })
            .onErrorReturn(new RateLimitResult(true, 0, rule));
    }

    /**
     * 固定窗口计数器限流检查
     */
    private Mono<RateLimitResult> checkFixedWindow(String key, RateLimitRule rule) {
        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(rule.getMaxRequestsPerSecond()),
            String.valueOf(rule.getWindowSize().getSeconds()),
            String.valueOf(Instant.now().toEpochMilli())
        );

        return redisTemplate.execute(RedisScript.of(FIXED_WINDOW_SCRIPT, List.class), keys, args)
            .cast(List.class)
            .next()
            .map(result -> {
                int allowed = ((Number) result.get(0)).intValue();
                int remaining = ((Number) result.get(1)).intValue();
                return new RateLimitResult(allowed == 1, remaining, rule);
            })
            .onErrorReturn(new RateLimitResult(true, 0, rule));
    }

    /**
     * 简单计数器限流检查
     */
    private Mono<RateLimitResult> checkSimpleCounter(String key, RateLimitRule rule) {
        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(rule.getMaxRequestsPerSecond()),
            String.valueOf(rule.getWindowSize().getSeconds())
        );

        return redisTemplate.execute(RedisScript.of(SIMPLE_COUNTER_SCRIPT, List.class), keys, args)
            .cast(List.class)
            .next()
            .map(result -> {
                int allowed = ((Number) result.get(0)).intValue();
                int remaining = ((Number) result.get(1)).intValue();
                return new RateLimitResult(allowed == 1, remaining, rule);
            })
            .onErrorReturn(new RateLimitResult(true, 0, rule));
    }

    /**
     * 获取基础限流规则
     */
    private RateLimitRule getRateLimitRule(String path) {
        // 从配置中获取默认规则，这里简化处理
        RateLimitRule defaultRule = new RateLimitRule();
        defaultRule.setMaxRequestsPerSecond(properties.getMaxRequestsPerSecond());
        defaultRule.setMaxRequestsPerMinute(properties.getMaxRequestsPerMinute());
        defaultRule.setWindowSize(Duration.ofSeconds(60));
        return defaultRule;
    }

    /**
     * 获取增强限流规则（支持用户类型和路径匹配）
     */
    private RateLimitRule getEnhancedRateLimitRule(String path, UserContext userContext) {
        // 优先匹配用户类型特定规则
        if (userContext != null) {
            String userType = userContext.getIsPlatformUser() != null && userContext.getIsPlatformUser() ? "platform" : "tenant";
            
            // 这里简化处理，实际应该从配置中获取规则
            RateLimitRule rule = new RateLimitRule();
            if ("platform".equals(userType)) {
                rule.setMaxRequestsPerSecond(properties.getDefaultRules().getPlatform().getMaxRequestsPerSecond());
                rule.setMaxRequestsPerMinute(properties.getDefaultRules().getPlatform().getMaxRequestsPerMinute());
            } else {
                rule.setMaxRequestsPerSecond(properties.getDefaultRules().getTenant().getMaxRequestsPerSecond());
                rule.setMaxRequestsPerMinute(properties.getDefaultRules().getTenant().getMaxRequestsPerMinute());
            }
            rule.setWindowSize(Duration.ofSeconds(60));
            return rule;
        }

        // 回退到默认规则
        return getRateLimitRule(path);
    }

    /**
     * 构建基础限流键
     */
    private String buildRateLimitKey(String clientIp, String path) {
        return String.format("rate_limit:%s:%s", clientIp, path);
    }

    /**
     * 构建增强限流键（包含用户维度）
     */
    private String buildEnhancedRateLimitKey(String clientIp, String path, UserContext userContext) {
        if (userContext == null) {
            return buildRateLimitKey(clientIp, path);
        }
        
        String userType = userContext.getIsPlatformUser() != null && userContext.getIsPlatformUser() ? "platform" : "tenant";
        String userId = userContext.getUserId() != null ? userContext.getUserId().toString() : "anonymous";
        
        return String.format("rate_limit:%s:%s:%s:%s", userType, userId, clientIp, path);
    }

    // 以下为兼容原有接口的方法

    /**
     * 滑动窗口限流检查（兼容原有接口）
     */
    public Mono<RateLimitResult> slidingWindowCheck(String key, int windowSeconds, int limit) {
        long now = Instant.now().toEpochMilli();
        
        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(windowSeconds),
            String.valueOf(limit),
            String.valueOf(now)
        );

        return redisTemplate.execute(RedisScript.of(SLIDING_WINDOW_SCRIPT, List.class), keys, args)
                 .cast(List.class)
                 .next()
                 .map(result -> {
                    int allowed = ((Number) result.get(0)).intValue();
                    int remaining = ((Number) result.get(1)).intValue();
                    
                    log.debug("滑动窗口限流检查 - Key: {}, 允许: {}, 剩余: {}", key, allowed == 1, remaining);
                    
                    return new RateLimitResult(
                        allowed == 1, 
                        remaining, 
                        windowSeconds, 
                        System.currentTimeMillis() + windowSeconds * 1000L
                    );
                })
                .onErrorResume(throwable -> {
                    log.error("滑动窗口限流检查失败，Key: " + key, throwable);
                    // 发生错误时允许通过，避免影响正常业务
                    return Mono.just(new RateLimitResult(true, limit - 1, windowSeconds, 
                        System.currentTimeMillis() + windowSeconds * 1000L));
                });
    }

    /**
     * 令牌桶限流检查（兼容原有接口）
     */
    public Mono<RateLimitResult> tokenBucketCheck(String key, int capacity, double refillRate) {
        long now = Instant.now().toEpochMilli();
        int intervalMs = (int) (1000.0 / refillRate); // 每个令牌的时间间隔
        
        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(capacity),
            String.valueOf(1), // 每次补充1个令牌
            String.valueOf(intervalMs),
            String.valueOf(now)
        );

        return redisTemplate.execute(RedisScript.of(TOKEN_BUCKET_SCRIPT, List.class), keys, args)
                 .cast(List.class)
                 .next()
                 .map(result -> {
                    int allowed = ((Number) result.get(0)).intValue();
                    int remaining = ((Number) result.get(1)).intValue();
                    
                    log.debug("令牌桶限流检查 - Key: {}, 允许: {}, 剩余令牌: {}", key, allowed == 1, remaining);
                    
                    return new RateLimitResult(
                        allowed == 1, 
                        remaining, 
                        3600, // 令牌桶的重置时间设为1小时
                        System.currentTimeMillis() + 3600 * 1000L
                    );
                })
                .onErrorResume(throwable -> {
                    log.error("令牌桶限流检查失败，Key: " + key, throwable);
                    // 发生错误时允许通过，避免影响正常业务
                    return Mono.just(new RateLimitResult(true, capacity - 1, 
                        3600, System.currentTimeMillis() + 3600 * 1000L));
                });
    }

    /**
     * 简单计数器限流检查（兼容原有接口）
     */
    public Mono<RateLimitResult> simpleCounterCheck(String key, int limit, Duration window) {
        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(
            String.valueOf(limit),
            String.valueOf(window.getSeconds())
        );

        return redisTemplate.execute(RedisScript.of(SIMPLE_COUNTER_SCRIPT, List.class), keys, args)
                 .cast(List.class)
                 .next()
                 .map(result -> {
                    int allowed = ((Number) result.get(0)).intValue();
                    int remaining = ((Number) result.get(1)).intValue();
                    
                    return new RateLimitResult(
                        allowed == 1, 
                        remaining, 
                        (int) window.getSeconds(), 
                        System.currentTimeMillis() + window.toMillis()
                    );
                })
                .onErrorResume(throwable -> {
                    log.error("简单计数器限流检查失败，Key: " + key, throwable);
                    return Mono.just(new RateLimitResult(true, limit - 1, 
                        (int) window.getSeconds(), System.currentTimeMillis() + window.toMillis()));
                });
    }

    /**
     * 限流结果
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final int remaining;
        private final int resetTimeSeconds;
        private final long resetTimeMs;
        private final RateLimitRule rule;

        public RateLimitResult(boolean allowed, int remaining, int resetTimeSeconds, long resetTimeMs) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.resetTimeSeconds = resetTimeSeconds;
            this.resetTimeMs = resetTimeMs;
            this.rule = null;
        }

        public RateLimitResult(boolean allowed, int remaining, RateLimitRule rule) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.rule = rule;
            this.resetTimeSeconds = rule != null ? (int)rule.getWindowSize().getSeconds() : 0;
            this.resetTimeMs = System.currentTimeMillis() + (resetTimeSeconds * 1000L);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public int getRemaining() {
            return remaining;
        }

        public int getResetTimeSeconds() {
            return resetTimeSeconds;
        }

        public long getResetTimeMs() {
            return resetTimeMs;
        }

        public RateLimitRule getRule() {
            return rule;
        }

        @Override
        public String toString() {
            return String.format("RateLimitResult{allowed=%s, remaining=%d, resetTimeSeconds=%d, resetTimeMs=%d}", 
                allowed, remaining, resetTimeSeconds, resetTimeMs);
        }
    }
}