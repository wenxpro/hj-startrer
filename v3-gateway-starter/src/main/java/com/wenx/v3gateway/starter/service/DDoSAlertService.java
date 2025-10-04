package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DDoS告警服务
 * 提供多种告警通知方式和告警规则管理
 */
public class DDoSAlertService {

    private static final Logger logger = LoggerFactory.getLogger(DDoSAlertService.class);
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DDoSProtectionProperties properties;
    
    // 告警规则缓存
    private final Map<String, AlertRule> alertRules = new ConcurrentHashMap<>();
    
    // 告警抑制缓存（防止重复告警）
    private final Map<String, LocalDateTime> alertSuppressionCache = new ConcurrentHashMap<>();
    
    // Redis键前缀
    private static final String ALERT_PREFIX = "ddos:alert:";
    private static final String ALERT_HISTORY_PREFIX = "ddos:alert:history:";
    private static final String ALERT_CONFIG_PREFIX = "ddos:alert:config:";

    public DDoSAlertService(ReactiveRedisTemplate<String, String> redisTemplate,
                           DDoSProtectionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        initializeDefaultAlertRules();
    }

    /**
     * 初始化默认告警规则
     */
    private void initializeDefaultAlertRules() {
        // 高频攻击告警
        alertRules.put("HIGH_FREQUENCY_ATTACK", new AlertRule(
            "HIGH_FREQUENCY_ATTACK",
            "检测到高频攻击",
            AlertLevel.CRITICAL,
            Duration.ofMinutes(5), // 抑制时间
            true
        ));
        
        // 黑名单IP数量过多
        alertRules.put("HIGH_BLACKLIST_COUNT", new AlertRule(
            "HIGH_BLACKLIST_COUNT", 
            "黑名单IP数量异常",
            AlertLevel.WARNING,
            Duration.ofMinutes(10),
            true
        ));
        
        // 请求阻止率过高
        alertRules.put("HIGH_BLOCK_RATE", new AlertRule(
            "HIGH_BLOCK_RATE",
            "请求阻止率异常",
            AlertLevel.WARNING,
            Duration.ofMinutes(5),
            true
        ));
        
        // 可疑IP行为
        alertRules.put("SUSPICIOUS_IP_BEHAVIOR", new AlertRule(
            "SUSPICIOUS_IP_BEHAVIOR",
            "检测到可疑IP行为",
            AlertLevel.INFO,
            Duration.ofMinutes(2),
            true
        ));
        
        // 系统性能告警
        alertRules.put("SYSTEM_PERFORMANCE_DEGRADATION", new AlertRule(
            "SYSTEM_PERFORMANCE_DEGRADATION",
            "系统性能下降",
            AlertLevel.CRITICAL,
            Duration.ofMinutes(3),
            true
        ));
    }

    /**
     * 触发告警
     */
    public Mono<Void> triggerAlert(String alertType, String message, Map<String, Object> context) {
        AlertRule rule = alertRules.get(alertType);
        if (rule == null || !rule.isEnabled()) {
            return Mono.empty();
        }
        
        // 检查告警抑制
        if (isAlertSuppressed(alertType, rule.getSuppressionDuration())) {
            logger.debug("告警被抑制: {}", alertType);
            return Mono.empty();
        }
        
        // 创建告警事件
        AlertEvent alertEvent = new AlertEvent(
            alertType,
            rule.getLevel(),
            message,
            context,
            LocalDateTime.now()
        );
        
        // 更新抑制缓存
        alertSuppressionCache.put(alertType, LocalDateTime.now());
        
        // 发送告警
        return sendAlert(alertEvent)
                .then(saveAlertHistory(alertEvent))
                .doOnSuccess(v -> logger.info("告警发送成功: {} - {}", alertType, message))
                .doOnError(e -> logger.error("告警发送失败: {} - {}", alertType, message, e));
    }

    /**
     * 发送告警通知
     */
    private Mono<Void> sendAlert(AlertEvent alertEvent) {
        return Mono.fromRunnable(() -> {
            // 日志告警
            logAlert(alertEvent);
            
            // 这里可以扩展其他告警方式：
            // - 邮件通知
            // - 短信通知  
            // - 钉钉/企业微信通知
            // - Webhook通知
            // - 消息队列通知
        });
    }

    /**
     * 日志告警
     */
    private void logAlert(AlertEvent alertEvent) {
        String logMessage = String.format(
            "[DDoS告警] 类型: %s, 级别: %s, 消息: %s, 时间: %s, 上下文: %s",
            alertEvent.getType(),
            alertEvent.getLevel(),
            alertEvent.getMessage(),
            alertEvent.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            alertEvent.getContext()
        );
        
        switch (alertEvent.getLevel()) {
            case CRITICAL:
                logger.error(logMessage);
                break;
            case WARNING:
                logger.warn(logMessage);
                break;
            case INFO:
                logger.info(logMessage);
                break;
        }
    }

    /**
     * 保存告警历史
     */
    private Mono<Void> saveAlertHistory(AlertEvent alertEvent) {
        String historyKey = ALERT_HISTORY_PREFIX + alertEvent.getType() + ":" + System.currentTimeMillis();
        String alertData = String.format(
            "{\"type\":\"%s\",\"level\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\",\"context\":%s}",
            alertEvent.getType(),
            alertEvent.getLevel(),
            alertEvent.getMessage(),
            alertEvent.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            alertEvent.getContext().toString()
        );
        
        return redisTemplate.opsForValue()
                .set(historyKey, alertData, Duration.ofDays(30))
                .then();
    }

    /**
     * 检查告警是否被抑制
     */
    private boolean isAlertSuppressed(String alertType, Duration suppressionDuration) {
        LocalDateTime lastAlertTime = alertSuppressionCache.get(alertType);
        if (lastAlertTime == null) {
            return false;
        }
        
        return LocalDateTime.now().isBefore(lastAlertTime.plus(suppressionDuration));
    }

    /**
     * 获取告警历史
     */
    public Flux<AlertEvent> getAlertHistory(String alertType, Duration timeRange) {
        String pattern = ALERT_HISTORY_PREFIX + alertType + ":*";
        
        return redisTemplate.keys(pattern)
                .flatMap(key -> redisTemplate.opsForValue().get(key))
                .map(this::parseAlertEvent)
                .filter(event -> event.getTimestamp().isAfter(LocalDateTime.now().minus(timeRange)))
                .sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
    }

    /**
     * 获取所有告警历史
     */
    public Flux<AlertEvent> getAllAlertHistory(Duration timeRange) {
        String pattern = ALERT_HISTORY_PREFIX + "*";
        
        return redisTemplate.keys(pattern)
                .flatMap(key -> redisTemplate.opsForValue().get(key))
                .map(this::parseAlertEvent)
                .filter(event -> event.getTimestamp().isAfter(LocalDateTime.now().minus(timeRange)))
                .sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
    }

    /**
     * 更新告警规则
     */
    public Mono<Void> updateAlertRule(String alertType, AlertRule rule) {
        alertRules.put(alertType, rule);
        
        String configKey = ALERT_CONFIG_PREFIX + alertType;
        String ruleData = String.format(
            "{\"type\":\"%s\",\"description\":\"%s\",\"level\":\"%s\",\"suppressionMinutes\":%d,\"enabled\":%b}",
            rule.getType(),
            rule.getDescription(),
            rule.getLevel(),
            rule.getSuppressionDuration().toMinutes(),
            rule.isEnabled()
        );
        
        return redisTemplate.opsForValue()
                .set(configKey, ruleData)
                .then();
    }

    /**
     * 获取告警规则
     */
    public AlertRule getAlertRule(String alertType) {
        return alertRules.get(alertType);
    }

    /**
     * 获取所有告警规则
     */
    public Map<String, AlertRule> getAllAlertRules() {
        return Map.copyOf(alertRules);
    }

    /**
     * 启用/禁用告警规则
     */
    public Mono<Void> toggleAlertRule(String alertType, boolean enabled) {
        AlertRule rule = alertRules.get(alertType);
        if (rule != null) {
            AlertRule updatedRule = new AlertRule(
                rule.getType(),
                rule.getDescription(),
                rule.getLevel(),
                rule.getSuppressionDuration(),
                enabled
            );
            return updateAlertRule(alertType, updatedRule);
        }
        return Mono.empty();
    }

    /**
     * 解析告警事件
     */
    private AlertEvent parseAlertEvent(String alertData) {
        // 简单的JSON解析，实际项目中建议使用Jackson或Gson
        try {
            // 这里简化处理，实际应该用JSON库
            return new AlertEvent("UNKNOWN", AlertLevel.INFO, alertData, Map.of(), LocalDateTime.now());
        } catch (Exception e) {
            logger.error("解析告警事件失败: {}", alertData, e);
            return new AlertEvent("PARSE_ERROR", AlertLevel.INFO, alertData, Map.of(), LocalDateTime.now());
        }
    }

    /**
     * 告警级别枚举
     */
    public enum AlertLevel {
        INFO, WARNING, CRITICAL
    }

    /**
     * 告警规则
     */
    public static class AlertRule {
        private final String type;
        private final String description;
        private final AlertLevel level;
        private final Duration suppressionDuration;
        private final boolean enabled;

        public AlertRule(String type, String description, AlertLevel level, 
                        Duration suppressionDuration, boolean enabled) {
            this.type = type;
            this.description = description;
            this.level = level;
            this.suppressionDuration = suppressionDuration;
            this.enabled = enabled;
        }

        // Getters
        public String getType() { return type; }
        public String getDescription() { return description; }
        public AlertLevel getLevel() { return level; }
        public Duration getSuppressionDuration() { return suppressionDuration; }
        public boolean isEnabled() { return enabled; }
    }

    /**
     * 告警事件
     */
    public static class AlertEvent {
        private final String type;
        private final AlertLevel level;
        private final String message;
        private final Map<String, Object> context;
        private final LocalDateTime timestamp;

        public AlertEvent(String type, AlertLevel level, String message, 
                         Map<String, Object> context, LocalDateTime timestamp) {
            this.type = type;
            this.level = level;
            this.message = message;
            this.context = context;
            this.timestamp = timestamp;
        }

        // Getters
        public String getType() { return type; }
        public AlertLevel getLevel() { return level; }
        public String getMessage() { return message; }
        public Map<String, Object> getContext() { return context; }
        public LocalDateTime getTimestamp() { return timestamp; }
    }
}