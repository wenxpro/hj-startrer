package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * IP黑名单管理服务
 * 提供黑名单的增删改查和自动清理功能
 */
public class BlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(BlacklistService.class);
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DDoSProtectionProperties properties;
    private final ObjectMapper objectMapper;

    // Redis键前缀
    private static final String BLACKLIST_PREFIX = "ddos:blacklist:";
    private static final String BLACKLIST_INFO_PREFIX = "ddos:blacklist_info:";
    private static final String BLACKLIST_STATS_KEY = "ddos:blacklist_stats";

    public BlacklistService(ReactiveRedisTemplate<String, String> redisTemplate,
                           DDoSProtectionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 检查IP是否在黑名单中
     */
    public Mono<Boolean> isBlacklisted(String ip) {
        String blacklistKey = BLACKLIST_PREFIX + ip;
        return redisTemplate.hasKey(blacklistKey)
                .doOnNext(isBlacklisted -> {
                    if (isBlacklisted) {
                        logger.debug("IP {} 在黑名单中", ip);
                    }
                });
    }

    /**
     * 添加IP到黑名单
     */
    public Mono<Void> addToBlacklist(String ip, String reason) {
        return addToBlacklist(ip, reason, Duration.ofMinutes(properties.getBlacklistDurationMinutes()));
    }

    /**
     * 添加IP到黑名单（指定持续时间）
     */
    public Mono<Void> addToBlacklist(String ip, String reason, Duration duration) {
        String blacklistKey = BLACKLIST_PREFIX + ip;
        String infoKey = BLACKLIST_INFO_PREFIX + ip;
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = now.plus(duration);
        
        BlacklistInfo info = new BlacklistInfo(
            ip, 
            reason, 
            now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            expireTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            duration.toMinutes()
        );

        try {
            String infoJson = objectMapper.writeValueAsString(info);
            
            return redisTemplate.opsForValue()
                    .set(blacklistKey, now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), duration)
                    .then(redisTemplate.opsForValue().set(infoKey, infoJson, duration))
                    .then(updateBlacklistStats(ip, "ADD"))
                    .doOnSuccess(unused -> logger.warn("IP {} 已添加到黑名单，原因: {}, 持续时间: {} 分钟", 
                        ip, reason, duration.toMinutes()))
                    .then();
        } catch (JsonProcessingException e) {
            logger.error("序列化黑名单信息失败", e);
            return Mono.error(e);
        }
    }

    /**
     * 从黑名单中移除IP
     */
    public Mono<Void> removeFromBlacklist(String ip) {
        String blacklistKey = BLACKLIST_PREFIX + ip;
        String infoKey = BLACKLIST_INFO_PREFIX + ip;
        
        return redisTemplate.delete(blacklistKey, infoKey)
                .then(updateBlacklistStats(ip, "REMOVE"))
                .doOnSuccess(unused -> logger.info("IP {} 已从黑名单中移除", ip))
                .then();
    }

    /**
     * 获取黑名单信息
     */
    public Mono<BlacklistInfo> getBlacklistInfo(String ip) {
        String infoKey = BLACKLIST_INFO_PREFIX + ip;
        
        return redisTemplate.opsForValue()
                .get(infoKey)
                .flatMap(infoJson -> {
                    try {
                        BlacklistInfo info = objectMapper.readValue(infoJson, BlacklistInfo.class);
                        return Mono.just(info);
                    } catch (JsonProcessingException e) {
                        logger.error("反序列化黑名单信息失败", e);
                        return Mono.empty();
                    }
                });
    }

    /**
     * 获取所有黑名单IP
     */
    public Flux<String> getAllBlacklistedIps() {
        String pattern = BLACKLIST_PREFIX + "*";
        
        return redisTemplate.keys(pattern)
                .map(key -> key.substring(BLACKLIST_PREFIX.length()));
    }

    /**
     * 获取黑名单统计信息
     */
    public Mono<BlacklistStats> getBlacklistStats() {
        return redisTemplate.opsForHash()
                .entries(BLACKLIST_STATS_KEY)
                .collectMap(entry -> entry.getKey().toString(), entry -> entry.getValue().toString())
                .map(statsMap -> {
                    int totalBlocked = Integer.parseInt(statsMap.getOrDefault("total_blocked", "0"));
                    int currentBlacklisted = Integer.parseInt(statsMap.getOrDefault("current_blacklisted", "0"));
                    String lastUpdate = statsMap.getOrDefault("last_update", "");
                    
                    return new BlacklistStats(totalBlocked, currentBlacklisted, lastUpdate);
                })
                .defaultIfEmpty(new BlacklistStats(0, 0, ""));
    }

    /**
     * 清理过期的黑名单统计
     */
    public Mono<Void> cleanupExpiredStats() {
        return getAllBlacklistedIps()
                .count()
                .flatMap(currentCount -> {
                    Map<String, String> updates = new HashMap<>();
                    updates.put("current_blacklisted", String.valueOf(currentCount));
                    updates.put("last_cleanup", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    
                    return redisTemplate.opsForHash()
                            .putAll(BLACKLIST_STATS_KEY, updates);
                })
                .then();
    }

    /**
     * 批量移除黑名单IP
     */
    public Mono<Void> batchRemoveFromBlacklist(Set<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(ips)
                .flatMap(this::removeFromBlacklist)
                .then()
                .doOnSuccess(unused -> logger.info("批量移除 {} 个IP从黑名单", ips.size()));
    }

    /**
     * 更新黑名单统计
     */
    private Mono<Void> updateBlacklistStats(String ip, String operation) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        Map<String, String> updates = new HashMap<>();
        updates.put("last_update", now);
        
        if ("ADD".equals(operation)) {
            return redisTemplate.opsForHash()
                    .increment(BLACKLIST_STATS_KEY, "total_blocked", 1)
                    .then(redisTemplate.opsForHash().increment(BLACKLIST_STATS_KEY, "current_blacklisted", 1))
                    .then(redisTemplate.opsForHash().putAll(BLACKLIST_STATS_KEY, updates))
                    .then();
        } else if ("REMOVE".equals(operation)) {
            return redisTemplate.opsForHash()
                    .increment(BLACKLIST_STATS_KEY, "current_blacklisted", -1)
                    .then(redisTemplate.opsForHash().putAll(BLACKLIST_STATS_KEY, updates))
                    .then();
        }
        
        return Mono.empty();
    }

    /**
     * 黑名单信息
     */
    public static class BlacklistInfo {
        private String ip;
        private String reason;
        private String addTime;
        private String expireTime;
        private long durationMinutes;

        public BlacklistInfo() {}

        public BlacklistInfo(String ip, String reason, String addTime, String expireTime, long durationMinutes) {
            this.ip = ip;
            this.reason = reason;
            this.addTime = addTime;
            this.expireTime = expireTime;
            this.durationMinutes = durationMinutes;
        }

        // Getters and Setters
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        
        public String getAddTime() { return addTime; }
        public void setAddTime(String addTime) { this.addTime = addTime; }
        
        public String getExpireTime() { return expireTime; }
        public void setExpireTime(String expireTime) { this.expireTime = expireTime; }
        
        public long getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(long durationMinutes) { this.durationMinutes = durationMinutes; }
    }

    /**
     * 黑名单统计信息
     */
    public static class BlacklistStats {
        private final int totalBlocked;
        private final int currentBlacklisted;
        private final String lastUpdate;

        public BlacklistStats(int totalBlocked, int currentBlacklisted, String lastUpdate) {
            this.totalBlocked = totalBlocked;
            this.currentBlacklisted = currentBlacklisted;
            this.lastUpdate = lastUpdate;
        }

        public int getTotalBlocked() { return totalBlocked; }
        public int getCurrentBlacklisted() { return currentBlacklisted; }
        public String getLastUpdate() { return lastUpdate; }
    }
}