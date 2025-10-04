package com.wenx.v3gateway.starter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 统一配置管理服务
 * 整合动态配置管理和配置备份恢复功能
 * 
 * @author wenx
 * @since 2024-01-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigManagementService {
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RateLimitRuleService ruleService;
    private final ObjectMapper objectMapper;
    private final DDoSProtectionProperties properties;
    
    // 配置相关Redis键
    private static final String CONFIG_KEY_PREFIX = "gateway:config:";
    private static final String RULE_CONFIG_KEY = CONFIG_KEY_PREFIX + "rate_limit_rules";
    private static final String GLOBAL_CONFIG_KEY = CONFIG_KEY_PREFIX + "global_settings";
    private static final String CONFIG_VERSION_KEY = CONFIG_KEY_PREFIX + "version";
    
    // 备份相关Redis键
    private static final String BACKUP_KEY_PREFIX = "gateway:config:backup:";
    private static final String BACKUP_INDEX_KEY = "gateway:config:backup:index";
    private static final int MAX_BACKUP_COUNT = 10;
    private static final DateTimeFormatter BACKUP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    
    // 配置监听器
    private final Map<String, ConfigChangeListener> listeners = new ConcurrentHashMap<>();
    private final List<ConfigChangeListener> globalListeners = new CopyOnWriteArrayList<>();
    
    // 当前配置版本
    private volatile String currentVersion = "0";
    
    @PostConstruct
    public void init() {
        // 启动配置监控
        startConfigMonitoring();
        
        // 初始化配置版本
        getCurrentVersion()
            .doOnNext(version -> currentVersion = version)
            .subscribe();
    }
    
    // ==================== 动态配置管理 ====================
    
    /**
     * 启动配置监控
     */
    private void startConfigMonitoring() {
        Flux.interval(Duration.ofSeconds(30))
            .flatMap(tick -> checkConfigChanges())
            .subscribe(
                unused -> {},
                error -> log.error("Config monitoring error", error)
            );
    }
    
    /**
     * 检查配置变更
     */
    private Mono<Void> checkConfigChanges() {
        return redisTemplate.opsForValue().get(CONFIG_VERSION_KEY)
                .defaultIfEmpty("0")
                .filter(version -> !version.equals(currentVersion))
                .flatMap(newVersion -> {
                    log.info("Config version changed from {} to {}, reloading configs", currentVersion, newVersion);
                    currentVersion = newVersion;
                    return reloadAllConfigs();
                })
                .then();
    }
    
    /**
     * 重新加载所有配置
     */
    private Mono<Void> reloadAllConfigs() {
        return Mono.when(
            reloadRateLimitRules(),
            reloadGlobalSettings()
        ).doOnSuccess(unused -> log.info("All configs reloaded successfully"))
         .doOnError(error -> log.error("Failed to reload configs", error));
    }
    
    /**
     * 重新加载限流规则
     */
    private Mono<Void> reloadRateLimitRules() {
        return redisTemplate.opsForValue().get(RULE_CONFIG_KEY)
                .flatMap(this::parseRateLimitRules)
                .doOnNext(rules -> {
                    List<RateLimitRule> oldRules = ruleService.getAllRules();
                    // 使用批量保存方法更新规则
                    rules.forEach(ruleService::saveRule);
                    notifyRateLimitRulesChanged(oldRules, rules);
                    log.info("Reloaded {} rate limit rules from Redis", rules.size());
                })
                .then()
                .doOnError(error -> {
                    log.error("Failed to reload rate limit rules", error);
                    notifyConfigReloadCompleted(false, error.getMessage());
                })
                .onErrorResume(error -> {
                    log.warn("Using default rate limit rules due to reload failure");
                    return Mono.<Void>empty();
                });
    }
    
    /**
     * 重新加载全局设置
     */
    private Mono<Void> reloadGlobalSettings() {
        return redisTemplate.opsForValue().get(GLOBAL_CONFIG_KEY)
                .flatMap(this::parseGlobalSettings)
                .doOnNext(settings -> {
                    // 这里可以更新全局配置
                    log.info("Reloaded global settings from Redis: {}", settings);
                    // 通知配置变更
                    notifyConfigReloadCompleted(true, null);
                })
                .then()
                .doOnError(error -> {
                    log.error("Failed to reload global settings", error);
                    notifyConfigReloadCompleted(false, error.getMessage());
                })
                .onErrorResume(error -> Mono.<Void>empty());
    }
    
    /**
     * 保存限流规则到配置中心
     */
    public Mono<Void> saveRateLimitRules(List<RateLimitRule> rules) {
        return Mono.fromCallable(() -> {
            try {
                return objectMapper.writeValueAsString(rules);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize rate limit rules", e);
            }
        })
        .flatMap(json -> redisTemplate.opsForValue().set(RULE_CONFIG_KEY, json))
        .then(incrementConfigVersion())
        .doOnSuccess(unused -> log.info("Saved {} rate limit rules to config center", rules.size()))
        .doOnError(error -> log.error("Failed to save rate limit rules", error));
    }
    
    /**
     * 保存全局设置到配置中心
     */
    public Mono<Void> saveGlobalSettings(Map<String, Object> settings) {
        return Mono.fromCallable(() -> {
            try {
                return objectMapper.writeValueAsString(settings);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize global settings", e);
            }
        })
        .flatMap(json -> redisTemplate.opsForValue().set(GLOBAL_CONFIG_KEY, json))
        .then(incrementConfigVersion())
        .doOnSuccess(unused -> log.info("Saved global settings to config center: {}", settings))
        .doOnError(error -> log.error("Failed to save global settings", error));
    }
    
    /**
     * 强制刷新配置
     */
    public Mono<Void> forceRefreshConfig() {
        log.info("Force refreshing all configs");
        return reloadAllConfigs();
    }
    
    // ==================== 配置验证 ====================
    
    /**
     * 验证限流规则
     */
    public Mono<ConfigValidationResult> validateRateLimitRules(List<RateLimitRule> rules) {
        return Mono.fromCallable(() -> {
            ConfigValidationResult result = new ConfigValidationResult();
            
            if (rules == null || rules.isEmpty()) {
                result.addWarning("No rate limit rules provided");
                return result;
            }
            
            for (RateLimitRule rule : rules) {
                validateRateLimitRule(rule, result);
            }
            
            // 验证规则冲突
            validateRuleConflicts(rules, result);
            
            result.setValid(result.getErrors().isEmpty());
            return result;
        });
    }
    
    /**
     * 验证单个限流规则
     */
    private void validateRateLimitRule(RateLimitRule rule, ConfigValidationResult result) {
        if (rule == null) {
            result.addError("Rate limit rule cannot be null");
            return;
        }
        
        if (rule.getRuleId() == null || rule.getRuleId().trim().isEmpty()) {
            result.addError("Rule ID cannot be empty");
        }
        
        if (rule.getMaxRequestsPerSecond() <= 0) {
            result.addError("Max requests per second must be positive for rule: " + rule.getRuleId());
        }
        
        if (rule.getMaxRequestsPerMinute() <= 0) {
            result.addError("Max requests per minute must be positive for rule: " + rule.getRuleId());
        }
        
        if (rule.getWindowSize() != null && rule.getWindowSize().isNegative()) {
            result.addError("Window size must be positive for rule: " + rule.getRuleId());
        }
        
        if (rule.getPathPatterns() == null || rule.getPathPatterns().isEmpty()) {
            result.addError("Path patterns cannot be empty for rule: " + rule.getRuleId());
        }
    }
    
    /**
     * 验证全局设置
     */
    public Mono<ConfigValidationResult> validateGlobalSettings(Map<String, Object> settings) {
        return Mono.fromCallable(() -> {
            ConfigValidationResult result = new ConfigValidationResult();
            
            if (settings == null || settings.isEmpty()) {
                result.addWarning("No global settings provided");
                return result;
            }
            
            for (Map.Entry<String, Object> entry : settings.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (key == null || key.trim().isEmpty()) {
                    result.addError("Configuration key cannot be empty");
                    continue;
                }
                
                // 验证特定配置项
                validateSpecificSetting(key, value, result);
            }
            
            result.setValid(result.getErrors().isEmpty());
            return result;
        });
    }
    
    // ==================== 配置备份与恢复 ====================
    
    /**
     * 备份当前配置
     */
    public Mono<String> backupCurrentConfig() {
        String backupId = generateBackupId();
        String backupKey = BACKUP_KEY_PREFIX + backupId;
        
        return getCurrentConfig()
            .flatMap(config -> {
                try {
                    String configJson = objectMapper.writeValueAsString(config);
                    return redisTemplate.opsForValue()
                        .set(backupKey, configJson, Duration.ofDays(30))
                        .then(addToBackupIndex(backupId))
                        .then(cleanupOldBackups())
                        .thenReturn(backupId);
                } catch (JsonProcessingException e) {
                    return Mono.error(new RuntimeException("Failed to serialize config for backup", e));
                }
            })
            .doOnSuccess(id -> log.info("Config backed up with ID: {}", id))
            .doOnError(error -> log.error("Failed to backup config", error));
    }
    
    /**
     * 恢复配置
     */
    public Mono<Void> restoreConfig(String backupId) {
        String backupKey = BACKUP_KEY_PREFIX + backupId;
        
        return redisTemplate.opsForValue()
            .get(backupKey)
            .cast(String.class)
            .switchIfEmpty(Mono.error(new RuntimeException("Backup not found: " + backupId)))
            .flatMap(this::parseAndRestoreConfig)
            .doOnSuccess(unused -> log.info("Config restored from backup: {}", backupId))
            .doOnError(error -> log.error("Failed to restore config from backup: {}", backupId, error));
    }
    
    /**
     * 获取备份列表
     */
    public Mono<List<BackupInfo>> getBackupList() {
        return redisTemplate.opsForSet()
            .members(BACKUP_INDEX_KEY)
            .cast(String.class)
            .flatMap(this::getBackupInfo)
            .collectList()
            .map(list -> {
                list.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));
                return list;
            });
    }
    
    /**
     * 删除备份
     */
    public Mono<Void> deleteBackup(String backupId) {
        String backupKey = BACKUP_KEY_PREFIX + backupId;
        
        return redisTemplate.delete(backupKey)
            .then(redisTemplate.opsForSet().remove(BACKUP_INDEX_KEY, backupId))
            .then()
            .doOnSuccess(unused -> log.info("Backup deleted: {}", backupId))
            .doOnError(error -> log.error("Failed to delete backup: {}", backupId, error));
    }
    
    // ==================== 配置监听器管理 ====================
    
    /**
     * 注册配置变更监听器
     */
    public void addConfigChangeListener(ConfigChangeListener listener) {
        if (listener != null && !globalListeners.contains(listener)) {
            globalListeners.add(listener);
            log.info("Added config change listener: {}", listener.getClass().getSimpleName());
        }
    }
    
    /**
     * 移除配置变更监听器
     */
    public void removeConfigChangeListener(ConfigChangeListener listener) {
        if (listener != null && globalListeners.remove(listener)) {
            log.info("Removed config change listener: {}", listener.getClass().getSimpleName());
        }
    }
    
    /**
     * 注册特定配置键的监听器
     */
    public void registerConfigChangeListener(String configKey, ConfigChangeListener listener) {
        listeners.put(configKey, listener);
        log.info("Registered config change listener for key: {}", configKey);
    }
    
    /**
     * 移除特定配置键的监听器
     */
    public void removeConfigChangeListener(String configKey) {
        listeners.remove(configKey);
        log.info("Removed config change listener for key: {}", configKey);
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取当前配置版本
     */
    public Mono<String> getCurrentVersion() {
        return redisTemplate.opsForValue()
            .get(CONFIG_VERSION_KEY)
            .defaultIfEmpty("0");
    }
    
    /**
     * 递增配置版本号
     */
    private Mono<Void> incrementConfigVersion() {
        return redisTemplate.opsForValue()
            .increment(CONFIG_VERSION_KEY)
            .map(String::valueOf)
            .doOnNext(version -> {
                currentVersion = version;
                log.debug("Config version incremented to: {}", version);
            })
            .then();
    }
    
    /**
     * 解析限流规则JSON
     */
    private Mono<List<RateLimitRule>> parseRateLimitRules(String json) {
        return Mono.fromCallable(() -> {
            try {
                return objectMapper.readValue(json, new TypeReference<List<RateLimitRule>>() {});
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse rate limit rules JSON", e);
            }
        });
    }
    
    /**
     * 解析全局设置JSON
     */
    private Mono<Map<String, Object>> parseGlobalSettings(String json) {
        return Mono.fromCallable(() -> {
            try {
                return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse global settings JSON", e);
            }
        });
    }
    
    /**
     * 验证规则冲突
     */
    private void validateRuleConflicts(List<RateLimitRule> rules, ConfigValidationResult result) {
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                RateLimitRule rule1 = rules.get(i);
                RateLimitRule rule2 = rules.get(j);
                
                if (rule1.getRuleId().equals(rule2.getRuleId())) {
                    result.addError("Duplicate rule ID: " + rule1.getRuleId());
                }
                
                if (rule1.getPathPatterns().equals(rule2.getPathPatterns()) &&
                    rule1.getUserType() == rule2.getUserType()) {
                    result.addWarning("Potential conflict between rules: " + 
                        rule1.getRuleId() + " and " + rule2.getRuleId());
                }
            }
        }
    }
    
    /**
     * 验证特定配置项
     */
    private void validateSpecificSetting(String key, Object value, ConfigValidationResult result) {
        switch (key) {
            case "maxRequestsPerSecond":
                if (!(value instanceof Number) || ((Number) value).intValue() <= 0) {
                    result.addError("maxRequestsPerSecond must be a positive number");
                }
                break;
            case "blacklistDurationMinutes":
                if (!(value instanceof Number) || ((Number) value).intValue() <= 0) {
                    result.addError("blacklistDurationMinutes must be a positive number");
                }
                break;
            case "enabled":
                if (!(value instanceof Boolean)) {
                    result.addError("enabled must be a boolean value");
                }
                break;
            default:
                // 其他配置项的验证逻辑
                break;
        }
    }
    
    /**
     * 获取当前配置
     */
    private Mono<Map<String, Object>> getCurrentConfig() {
        return Mono.fromCallable(() -> {
            // 构建当前配置快照
            return Map.of(
                "timestamp", System.currentTimeMillis(),
                "properties", properties,
                "version", currentVersion
            );
        });
    }
    
    /**
     * 生成备份ID
     */
    private String generateBackupId() {
        return LocalDateTime.now().format(BACKUP_TIME_FORMAT);
    }
    
    /**
     * 添加到备份索引
     */
    private Mono<Void> addToBackupIndex(String backupId) {
        return redisTemplate.opsForSet()
            .add(BACKUP_INDEX_KEY, backupId)
            .then();
    }
    
    /**
     * 清理旧备份
     */
    private Mono<Void> cleanupOldBackups() {
        return redisTemplate.opsForSet()
            .members(BACKUP_INDEX_KEY)
            .cast(String.class)
            .collectList()
            .flatMap(backupIds -> {
                if (backupIds.size() <= MAX_BACKUP_COUNT) {
                    return Mono.empty();
                }
                
                // 按时间排序，删除最旧的备份
                backupIds.sort(String::compareTo);
                List<String> toDelete = backupIds.subList(0, backupIds.size() - MAX_BACKUP_COUNT);
                
                return Flux.fromIterable(toDelete)
                    .flatMap(this::deleteBackup)
                    .then();
            });
    }
    
    /**
     * 解析并恢复配置
     */
    private Mono<Void> parseAndRestoreConfig(String configJson) {
        return Mono.fromCallable(() -> {
            try {
                // 解析配置并应用
                Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
                // 这里应该调用相应的服务来应用配置
                log.info("Config parsed and ready to restore: {}", config);
                return null;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse backup config", e);
            }
        });
    }
    
    /**
     * 获取备份信息
     */
    private Mono<BackupInfo> getBackupInfo(String backupId) {
        return Mono.fromCallable(() -> {
            BackupInfo info = new BackupInfo();
            info.setBackupId(backupId);
            info.setCreateTime(LocalDateTime.parse(backupId, BACKUP_TIME_FORMAT));
            return info;
        });
    }
    
    // ==================== 通知方法 ====================
    
    /**
     * 通知所有监听器限流规则变更
     */
    private void notifyRateLimitRulesChanged(List<RateLimitRule> oldRules, List<RateLimitRule> newRules) {
        for (ConfigChangeListener listener : globalListeners) {
            try {
                listener.onRateLimitRulesChanged(oldRules, newRules);
            } catch (Exception e) {
                log.error("Error notifying config change listener: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
    
    /**
     * 通知所有监听器全局配置变更
     */
    private void notifyGlobalSettingsChanged(DDoSProtectionProperties oldProperties, 
                                           DDoSProtectionProperties newProperties) {
        for (ConfigChangeListener listener : globalListeners) {
            try {
                listener.onGlobalSettingsChanged(oldProperties, newProperties);
            } catch (Exception e) {
                log.error("Error notifying config change listener: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
    
    /**
     * 通知所有监听器配置重新加载完成
     */
    private void notifyConfigReloadCompleted(boolean success, String errorMessage) {
        for (ConfigChangeListener listener : globalListeners) {
            try {
                listener.onConfigReloadCompleted(success, errorMessage);
            } catch (Exception e) {
                log.error("Error notifying config change listener: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
    
    /**
     * 通知监听器配置变更
     */
    private void notifyListeners(String configKey, Object newValue) {
        ConfigChangeListener listener = listeners.get(configKey);
        if (listener != null) {
            try {
                // ConfigChangeListener接口没有onConfigChanged方法，这里应该根据配置类型调用相应方法
                log.info("Config changed for key: {}, value: {}", configKey, newValue);
                // 可以根据需要扩展具体的通知逻辑
            } catch (Exception e) {
                log.error("Error notifying config change listener for key: {}", configKey, e);
            }
        }
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 配置验证结果
     */
    public static class ConfigValidationResult {
        private boolean valid = true;
        private List<String> errors = new java.util.ArrayList<>();
        private List<String> warnings = new java.util.ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
        
        // Getters and setters
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
    }
    
    /**
     * 备份信息
     */
    public static class BackupInfo {
        private String backupId;
        private LocalDateTime createTime;
        
        // Getters and setters
        public String getBackupId() { return backupId; }
        public void setBackupId(String backupId) { this.backupId = backupId; }
        public LocalDateTime getCreateTime() { return createTime; }
        public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    }
}