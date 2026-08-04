package com.wenx.v3gateway.starter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 统一限流服务（P3 清理：删除旧配置驱动限流死代码，仅保留滑动窗口限流）
 * 网关 {@code AuthRateLimitFilter} 的登录/validate 限流唯一实现。
 *
 * @author wenx
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

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
        
        -- 统计窗口内请求数
        local count = redis.call('zcard', key)
        
        -- 判断是否允许
        if count < limit then
            redis.call('zadd', key, now, now .. ':' .. math.random(1000000000))
            redis.call('expire', key, window)
            return {1, limit - count - 1}
        else
            return {0, 0}
        end
        """;

    /**
     * 滑动窗口限流检查
     *
     * @param key          限流键（IP 维度）
     * @param windowSeconds 窗口（秒）
     * @param limit         窗口内最大请求数
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
     * 限流结果
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final int remaining;
        private final int resetTimeSeconds;
        private final long resetTimeMs;

        public RateLimitResult(boolean allowed, int remaining, int resetTimeSeconds, long resetTimeMs) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.resetTimeSeconds = resetTimeSeconds;
            this.resetTimeMs = resetTimeMs;
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
    }
}
