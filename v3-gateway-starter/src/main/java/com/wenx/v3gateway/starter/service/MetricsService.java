package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.domain.UserContext;
import com.wenx.v3gateway.starter.enums.UserType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一指标收集服务
 * 整合基础DDoS指标和增强版多维度指标收集功能
 * 
 * @author wenx
 * @since 2024-01-01
 */
@Service
@Slf4j
public class MetricsService {
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    
    // 基础指标计数器
    private final Counter totalRequestsCounter;
    private final Counter blockedRequestsCounter;
    private final Counter blacklistedIpsCounter;
    private final Timer requestProcessingTimer;
    
    // 按用户类型分类的指标
    private final Map<UserType, Counter> userTypeRequestCounters = new ConcurrentHashMap<>();
    private final Map<UserType, Counter> userTypeBlockedCounters = new ConcurrentHashMap<>();
    
    // 按限流规则分类的指标
    private final Map<String, Counter> ruleRequestCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> ruleBlockedCounters = new ConcurrentHashMap<>();
    
    // 按路径分类的指标
    private final Map<String, Counter> pathRequestCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> pathBlockedCounters = new ConcurrentHashMap<>();
    
    // 实时统计
    private final AtomicLong currentActiveConnections = new AtomicLong(0);
    private final AtomicInteger currentBlacklistedIps = new AtomicInteger(0);
    private final AtomicLong totalProcessedRequests = new AtomicLong(0);
    private final AtomicLong totalBlockedRequests = new AtomicLong(0);
    private final Map<UserType, AtomicLong> userTypeStats = new ConcurrentHashMap<>();
    
    // Redis键前缀
    private static final String METRICS_PREFIX = "ddos:metrics:";
    private static final String ENHANCED_PREFIX = "enhanced:metrics:";
    private static final String USER_TYPE_PREFIX = ENHANCED_PREFIX + "user_type:";
    private static final String RULE_PREFIX = ENHANCED_PREFIX + "rule:";
    private static final String PATH_PREFIX = ENHANCED_PREFIX + "path:";
    private static final String ALERT_PREFIX = "ddos:alert:";

    public MetricsService(ReactiveRedisTemplate<String, String> redisTemplate,
                         MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        
        // 初始化基础指标
        this.totalRequestsCounter = Counter.builder("ddos.requests.total")
                .description("Total number of requests processed by DDoS protection")
                .register(meterRegistry);
                
        this.blockedRequestsCounter = Counter.builder("ddos.requests.blocked")
                .description("Total number of requests blocked by DDoS protection")
                .register(meterRegistry);
                
        this.blacklistedIpsCounter = Counter.builder("ddos.ips.blacklisted")
                .description("Total number of IPs added to blacklist")
                .register(meterRegistry);
                
        this.requestProcessingTimer = Timer.builder("ddos.request.processing.time")
                .description("Time taken to process DDoS protection checks")
                .register(meterRegistry);

        initializeGaugeMetrics();
        initializeEnhancedMetrics();
        initializeUserTypeStats();
    }

    /**
     * 初始化Gauge指标
     */
    private void initializeGaugeMetrics() {
        // 基础Gauge指标
        Gauge.builder("ddos.connections.active", this, service -> (double) service.currentActiveConnections.get())
                .description("Current number of active connections")
                .register(meterRegistry);
        
        Gauge.builder("ddos.ips.blacklisted.current", this, service -> (double) service.currentBlacklistedIps.get())
                .description("Current number of blacklisted IPs")
                .register(meterRegistry);
        
        // 增强版Gauge指标
        Gauge.builder("enhanced.ddos.requests.processed.total", this, 
                service -> (double) service.totalProcessedRequests.get())
                .description("Total processed requests")
                .register(meterRegistry);
        
        Gauge.builder("enhanced.ddos.requests.blocked.total", this, 
                service -> (double) service.totalBlockedRequests.get())
                .description("Total blocked requests")
                .register(meterRegistry);
    }

    /**
     * 初始化增强版指标
     */
    private void initializeEnhancedMetrics() {
        // 为每种用户类型初始化指标
        for (UserType userType : UserType.values()) {
            userTypeRequestCounters.put(userType, 
                Counter.builder("enhanced.ddos.requests.by.user.type")
                    .tag("user_type", userType.name())
                    .description("Requests by user type")
                    .register(meterRegistry));
            
            userTypeBlockedCounters.put(userType, 
                Counter.builder("enhanced.ddos.blocked.by.user.type")
                    .tag("user_type", userType.name())
                    .description("Blocked requests by user type")
                    .register(meterRegistry));
        }
    }

    /**
     * 初始化用户类型统计
     */
    private void initializeUserTypeStats() {
        for (UserType userType : UserType.values()) {
            userTypeStats.put(userType, new AtomicLong(0));
            
            // 为每种用户类型注册Gauge
            Gauge.builder("enhanced.ddos.user.type.stats", this, 
                    service -> (double) service.userTypeStats.get(userType).get())
                    .tag("user_type", userType.name())
                    .description("Statistics by user type")
                    .register(meterRegistry);
        }
    }

    /**
     * 记录基础请求处理（兼容原有接口）
     */
    public void recordRequest(String ip, boolean blocked, Duration processingTime) {
        totalRequestsCounter.increment();
        totalProcessedRequests.incrementAndGet();
        
        if (blocked) {
            blockedRequestsCounter.increment();
            totalBlockedRequests.incrementAndGet();
            log.debug("记录被阻止的请求 - IP: {}, 处理时间: {}ms", ip, processingTime.toMillis());
        }
        
        requestProcessingTimer.record(processingTime);
        
        // 异步更新Redis统计
        updateRedisMetrics(ip, blocked).subscribe();
    }

    /**
     * 记录增强版请求处理（支持用户上下文和规则）
     */
    public void recordRequest(UserContext userContext, RateLimitRule rule, 
                             boolean blocked, Duration processingTime) {
        // 记录基础指标
        recordRequest(userContext != null ? userContext.getClientIp() : "unknown", blocked, processingTime);
        
        // 记录增强版指标
        if (userContext != null) {
            recordUserTypeMetrics(userContext.getUserType(), blocked);
        }
        
        if (rule != null) {
            recordRuleMetrics(rule, blocked);
        }
        
        if (userContext != null && userContext.getRequestPath() != null) {
            recordPathMetrics(userContext.getRequestPath(), blocked);
        }
        
        // 异步更新Redis增强版统计
        updateEnhancedRedisMetrics(userContext, rule, blocked).subscribe();
    }

    /**
     * 记录IP被加入黑名单
     */
    public void recordBlacklistedIp(String ip, String reason) {
        blacklistedIpsCounter.increment();
        currentBlacklistedIps.incrementAndGet();
        
        log.info("IP {} 被加入黑名单，原因: {}", ip, reason);
        
        // 检查是否需要触发告警
        checkAndTriggerAlert(ip, reason).subscribe();
    }

    /**
     * 记录IP从黑名单移除
     */
    public void recordBlacklistRemoval(String ip) {
        currentBlacklistedIps.decrementAndGet();
        log.info("IP {} 从黑名单中移除", ip);
    }

    /**
     * 更新活跃连接数
     */
    public void updateActiveConnections(long delta) {
        long newValue = currentActiveConnections.addAndGet(delta);
        if (newValue < 0) {
            currentActiveConnections.set(0);
        }
    }

    /**
     * 记录用户类型指标
     */
    private void recordUserTypeMetrics(UserType userType, boolean blocked) {
        userTypeRequestCounters.get(userType).increment();
        userTypeStats.get(userType).incrementAndGet();
        
        if (blocked) {
            userTypeBlockedCounters.get(userType).increment();
        }
    }

    /**
     * 记录规则指标
     */
    private void recordRuleMetrics(RateLimitRule rule, boolean blocked) {
        if (rule.getRuleId() != null) {
            String ruleKey = rule.getRuleId();
            
            ruleRequestCounters.computeIfAbsent(ruleKey, 
                key -> Counter.builder("enhanced.ddos.requests.by.rule")
                    .tag("rule_id", key)
                    .description("Requests by rule")
                    .register(meterRegistry))
                .increment();
            
            if (blocked) {
                ruleBlockedCounters.computeIfAbsent(ruleKey, 
                    key -> Counter.builder("enhanced.ddos.blocked.by.rule")
                        .tag("rule_id", key)
                        .description("Blocked requests by rule")
                        .register(meterRegistry))
                    .increment();
            }
        }
    }

    /**
     * 记录路径指标
     */
    private void recordPathMetrics(String path, boolean blocked) {
        String simplifiedPath = simplifyPath(path);
        
        pathRequestCounters.computeIfAbsent(simplifiedPath, 
            key -> Counter.builder("enhanced.ddos.requests.by.path")
                .tag("path", key)
                .description("Requests by path")
                .register(meterRegistry))
            .increment();
        
        if (blocked) {
            pathBlockedCounters.computeIfAbsent(simplifiedPath, 
                key -> Counter.builder("enhanced.ddos.blocked.by.path")
                    .tag("path", key)
                    .description("Blocked requests by path")
                    .register(meterRegistry))
                .increment();
        }
    }

    /**
     * 简化路径（移除动态参数）
     */
    private String simplifyPath(String path) {
        if (path == null) {
            return "unknown";
        }
        
        // 替换数字ID为占位符
        String simplified = path.replaceAll("/\\d+", "/{id}");
        
        // 替换UUID为占位符
        simplified = simplified.replaceAll("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "/{uuid}");
        
        // 限制路径长度
        if (simplified.length() > 100) {
            simplified = simplified.substring(0, 100) + "...";
        }
        
        return simplified;
    }

    /**
     * 获取当前统计信息
     */
    public DDoSStats getCurrentStats() {
        return new DDoSStats(
            totalProcessedRequests.get(),
            totalBlockedRequests.get(),
            currentActiveConnections.get(),
            currentBlacklistedIps.get(),
            calculateBlockRate()
        );
    }

    /**
     * 获取增强版统计信息
     */
    public EnhancedDDoSStats getEnhancedStats() {
        Map<UserType, Long> userStats = new HashMap<>();
        for (Map.Entry<UserType, AtomicLong> entry : userTypeStats.entrySet()) {
            userStats.put(entry.getKey(), entry.getValue().get());
        }
        
        // 获取Top规则和路径统计（简化实现）
        Map<String, Long> topRules = new HashMap<>();
        Map<String, Long> topPaths = new HashMap<>();
        
        return new EnhancedDDoSStats(
            totalProcessedRequests.get(),
            totalBlockedRequests.get(),
            userStats,
            topRules,
            topPaths,
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    /**
     * 获取监控报告
     */
    public Mono<DDoSMonitoringReport> getMonitoringReport() {
        return redisTemplate.opsForHash()
            .entries(METRICS_PREFIX + "daily:" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .collectMap(entry -> entry.getKey().toString(), entry -> Long.parseLong(entry.getValue().toString()))
            .map(dailyStats -> {
                DDoSMonitoringReport report = new DDoSMonitoringReport();
                report.setTotalRequests(dailyStats.getOrDefault("total_requests", 0L));
                report.setBlockedRequests(dailyStats.getOrDefault("blocked_requests", 0L));
                report.setUniqueIps(dailyStats.getOrDefault("unique_ips", 0L).intValue());
                report.setBlacklistedIps(currentBlacklistedIps.get());
                report.setAverageResponseTime(dailyStats.getOrDefault("avg_response_time", 0L).doubleValue());
                report.setReportTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                return report;
            });
    }

    /**
     * 检查并触发告警
     */
    private Mono<Void> checkAndTriggerAlert(String ip, String reason) {
        // 检查是否需要触发告警（简化实现）
        if (currentBlacklistedIps.get() > 100) {
            return triggerAlert("HIGH_BLACKLIST_COUNT", 
                String.format("黑名单IP数量过高: %d, 最新加入: %s (原因: %s)", 
                    currentBlacklistedIps.get(), ip, reason));
        }
        
        if (calculateBlockRate() > 0.5) {
            return triggerAlert("HIGH_BLOCK_RATE", 
                String.format("请求阻止率过高: %.2f%%, 最新阻止IP: %s", 
                    calculateBlockRate() * 100, ip));
        }
        
        return Mono.empty();
    }

    /**
     * 触发告警
     */
    private Mono<Void> triggerAlert(String alertType, String message) {
        String alertKey = ALERT_PREFIX + alertType + ":" + System.currentTimeMillis();
        
        return redisTemplate.opsForValue()
            .set(alertKey, message, Duration.ofHours(24))
            .doOnSuccess(result -> log.warn("DDoS告警触发 - 类型: {}, 消息: {}", alertType, message))
            .then();
    }

    /**
     * 更新Redis基础指标
     */
    private Mono<Void> updateRedisMetrics(String ip, boolean blocked) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String dailyKey = METRICS_PREFIX + "daily:" + today;
        String hourlyKey = METRICS_PREFIX + "hourly:" + today + ":" + LocalDateTime.now().getHour();
        
        return redisTemplate.opsForHash()
            .increment(dailyKey, "total_requests", 1)
            .then(redisTemplate.opsForHash().increment(hourlyKey, "total_requests", 1))
            .then(blocked ? 
                redisTemplate.opsForHash().increment(dailyKey, "blocked_requests", 1)
                    .then(redisTemplate.opsForHash().increment(hourlyKey, "blocked_requests", 1)) :
                Mono.empty())
            .then(redisTemplate.opsForSet().add(METRICS_PREFIX + "ips:" + today, ip))
            .then(redisTemplate.expire(dailyKey, Duration.ofDays(30)))
            .then(redisTemplate.expire(hourlyKey, Duration.ofDays(7)))
            .then();
    }

    /**
     * 更新Redis增强版指标
     */
    private Mono<Void> updateEnhancedRedisMetrics(UserContext userContext, RateLimitRule rule, boolean blocked) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        Mono<Void> userTypeUpdate = Mono.empty();
        if (userContext != null && userContext.getUserType() != null) {
            String userTypeKey = USER_TYPE_PREFIX + userContext.getUserType().name() + ":" + today;
            userTypeUpdate = redisTemplate.opsForHash()
                .increment(userTypeKey, "requests", 1)
                .then(blocked ? redisTemplate.opsForHash().increment(userTypeKey, "blocked", 1) : Mono.empty())
                .then(redisTemplate.expire(userTypeKey, Duration.ofDays(30)))
                .then();
        }
        
        Mono<Void> ruleUpdate = Mono.empty();
        if (rule != null && rule.getRuleId() != null) {
            String ruleKey = RULE_PREFIX + rule.getRuleId() + ":" + today;
            ruleUpdate = redisTemplate.opsForHash()
                .increment(ruleKey, "requests", 1)
                .then(blocked ? redisTemplate.opsForHash().increment(ruleKey, "blocked", 1) : Mono.empty())
                .then(redisTemplate.expire(ruleKey, Duration.ofDays(30)))
                .then();
        }
        
        Mono<Void> pathUpdate = Mono.empty();
        if (userContext != null && userContext.getRequestPath() != null) {
            String pathKey = PATH_PREFIX + simplifyPath(userContext.getRequestPath()) + ":" + today;
            pathUpdate = redisTemplate.opsForHash()
                .increment(pathKey, "requests", 1)
                .then(blocked ? redisTemplate.opsForHash().increment(pathKey, "blocked", 1) : Mono.empty())
                .then(redisTemplate.expire(pathKey, Duration.ofDays(30)))
                .then();
        }
        
        return userTypeUpdate.then(ruleUpdate).then(pathUpdate);
    }

    /**
     * 计算阻止率
     */
    private double calculateBlockRate() {
        long total = totalProcessedRequests.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) totalBlockedRequests.get() / total;
    }

    // Getter方法
    public long getCurrentActiveConnections() {
        return currentActiveConnections.get();
    }

    public int getCurrentBlacklistedIps() {
        return currentBlacklistedIps.get();
    }

    /**
     * DDoS统计信息
     */
    public static class DDoSStats {
        private final long totalRequests;
        private final long blockedRequests;
        private final long activeConnections;
        private final int blacklistedIps;
        private final double blockRate;

        public DDoSStats(long totalRequests, long blockedRequests, long activeConnections, 
                        int blacklistedIps, double blockRate) {
            this.totalRequests = totalRequests;
            this.blockedRequests = blockedRequests;
            this.activeConnections = activeConnections;
            this.blacklistedIps = blacklistedIps;
            this.blockRate = blockRate;
        }

        // Getter方法
        public long getTotalRequests() { return totalRequests; }
        public long getBlockedRequests() { return blockedRequests; }
        public long getActiveConnections() { return activeConnections; }
        public int getBlacklistedIps() { return blacklistedIps; }
        public double getBlockRate() { return blockRate; }
    }

    /**
     * 增强版DDoS统计信息
     */
    public static class EnhancedDDoSStats {
        private final long totalRequests;
        private final long blockedRequests;
        private final Map<UserType, Long> userTypeStats;
        private final Map<String, Long> topRules;
        private final Map<String, Long> topPaths;
        private final String timestamp;

        public EnhancedDDoSStats(long totalRequests, long blockedRequests,
                               Map<UserType, Long> userTypeStats,
                               Map<String, Long> topRules,
                               Map<String, Long> topPaths,
                               String timestamp) {
            this.totalRequests = totalRequests;
            this.blockedRequests = blockedRequests;
            this.userTypeStats = userTypeStats;
            this.topRules = topRules;
            this.topPaths = topPaths;
            this.timestamp = timestamp;
        }

        public double getBlockRate() {
            return totalRequests > 0 ? (double) blockedRequests / totalRequests : 0.0;
        }

        // Getter方法
        public long getTotalRequests() { return totalRequests; }
        public long getBlockedRequests() { return blockedRequests; }
        public Map<UserType, Long> getUserTypeStats() { return userTypeStats; }
        public Map<String, Long> getTopRules() { return topRules; }
        public Map<String, Long> getTopPaths() { return topPaths; }
        public String getTimestamp() { return timestamp; }
    }

    /**
     * DDoS监控报告
     */
    public static class DDoSMonitoringReport {
        private long totalRequests;
        private long blockedRequests;
        private int uniqueIps;
        private int blacklistedIps;
        private double averageResponseTime;
        private String reportTime;

        // Getter和Setter方法
        public long getTotalRequests() { return totalRequests; }
        public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
        
        public long getBlockedRequests() { return blockedRequests; }
        public void setBlockedRequests(long blockedRequests) { this.blockedRequests = blockedRequests; }
        
        public int getUniqueIps() { return uniqueIps; }
        public void setUniqueIps(int uniqueIps) { this.uniqueIps = uniqueIps; }
        
        public int getBlacklistedIps() { return blacklistedIps; }
        public void setBlacklistedIps(int blacklistedIps) { this.blacklistedIps = blacklistedIps; }
        
        public double getAverageResponseTime() { return averageResponseTime; }
        public void setAverageResponseTime(double averageResponseTime) { this.averageResponseTime = averageResponseTime; }
        
        public String getReportTime() { return reportTime; }
        public void setReportTime(String reportTime) { this.reportTime = reportTime; }
    }
}