package com.wenx.v3gateway.starter.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * DDoS防护配置属性
 * 支持基础和增强版DDoS防护功能
 * 
 * @author wenx
 */
@ConfigurationProperties(prefix = "cloud.gateway.ddos")
public class DDoSProtectionProperties {

    /**
     * 是否启用DDoS防护
     */
    private boolean enabled = true;

    /**
     * 是否启用增强版功能
     */
    private boolean enhancedEnabled = false;

    /**
     * 每分钟最大请求数（基础版）
     */
    private int maxRequestsPerMinute = 100;

    /**
     * 每秒最大请求数（基础版）
     */
    private int maxRequestsPerSecond = 10;

    /**
     * 黑名单持续时间（分钟）
     */
    private int blacklistDurationMinutes = 30;

    /**
     * 可疑行为阈值（每分钟请求数）
     */
    private int suspiciousThreshold = 50;

    /**
     * 白名单IP列表（逗号分隔）
     */
    private String whitelistIps = "";

    /**
     * 检查间隔（秒）
     */
    private int checkIntervalSeconds = 60;

    /**
     * 是否启用动态规则加载
     */
    private boolean dynamicRulesEnabled = true;

    /**
     * 规则缓存刷新间隔（秒）
     */
    private int ruleCacheRefreshInterval = 300;

    /**
     * 是否启用限流指标收集
     */
    private boolean metricsEnabled = true;

    /**
     * 是否启用限流告警
     */
    private boolean alertEnabled = true;

    /**
     * 默认限流规则配置
     */
    private DefaultRules defaultRules = new DefaultRules();

    /**
     * 路径特定限流规则配置
     */
    private Map<String, PathRule> pathRules = new HashMap<>();

    // Getters and Setters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    public int getMaxRequestsPerSecond() {
        return maxRequestsPerSecond;
    }

    public void setMaxRequestsPerSecond(int maxRequestsPerSecond) {
        this.maxRequestsPerSecond = maxRequestsPerSecond;
    }

    public int getBlacklistDurationMinutes() {
        return blacklistDurationMinutes;
    }

    public void setBlacklistDurationMinutes(int blacklistDurationMinutes) {
        this.blacklistDurationMinutes = blacklistDurationMinutes;
    }

    public int getSuspiciousThreshold() {
        return suspiciousThreshold;
    }

    public void setSuspiciousThreshold(int suspiciousThreshold) {
        this.suspiciousThreshold = suspiciousThreshold;
    }

    public String getWhitelistIps() {
        return whitelistIps;
    }

    public void setWhitelistIps(String whitelistIps) {
        this.whitelistIps = whitelistIps;
    }

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(int checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }

    public boolean isEnhancedEnabled() {
        return enhancedEnabled;
    }

    public void setEnhancedEnabled(boolean enhancedEnabled) {
        this.enhancedEnabled = enhancedEnabled;
    }

    public boolean isDynamicRulesEnabled() {
        return dynamicRulesEnabled;
    }

    public void setDynamicRulesEnabled(boolean dynamicRulesEnabled) {
        this.dynamicRulesEnabled = dynamicRulesEnabled;
    }

    public int getRuleCacheRefreshInterval() {
        return ruleCacheRefreshInterval;
    }

    public void setRuleCacheRefreshInterval(int ruleCacheRefreshInterval) {
        this.ruleCacheRefreshInterval = ruleCacheRefreshInterval;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public boolean isAlertEnabled() {
        return alertEnabled;
    }

    public void setAlertEnabled(boolean alertEnabled) {
        this.alertEnabled = alertEnabled;
    }

    public DefaultRules getDefaultRules() {
        return defaultRules;
    }

    public void setDefaultRules(DefaultRules defaultRules) {
        this.defaultRules = defaultRules;
    }

    public Map<String, PathRule> getPathRules() {
        return pathRules;
    }

    public void setPathRules(Map<String, PathRule> pathRules) {
        this.pathRules = pathRules;
    }

    /**
     * 默认限流规则配置
     */
    public static class DefaultRules {
        /**
         * 匿名用户限流规则
         */
        private UserTypeRule anonymous = new UserTypeRule(10, 100, 1000);

        /**
         * 系统用户限流规则
         */
        private UserTypeRule system = new UserTypeRule(100, 1000, 10000);

        /**
         * 平台用户限流规则
         */
        private UserTypeRule platform = new UserTypeRule(50, 500, 5000);

        /**
         * 租户用户限流规则
         */
        private UserTypeRule tenant = new UserTypeRule(30, 300, 3000);

        // Getters and Setters
        public UserTypeRule getAnonymous() {
            return anonymous;
        }

        public void setAnonymous(UserTypeRule anonymous) {
            this.anonymous = anonymous;
        }

        public UserTypeRule getSystem() {
            return system;
        }

        public void setSystem(UserTypeRule system) {
            this.system = system;
        }

        public UserTypeRule getPlatform() {
            return platform;
        }

        public void setPlatform(UserTypeRule platform) {
            this.platform = platform;
        }

        public UserTypeRule getTenant() {
            return tenant;
        }

        public void setTenant(UserTypeRule tenant) {
            this.tenant = tenant;
        }
    }

    /**
     * 用户类型限流规则
     */
    public static class UserTypeRule {
        /**
         * 每秒最大请求数
         */
        private int maxRequestsPerSecond;

        /**
         * 每分钟最大请求数
         */
        private int maxRequestsPerMinute;

        /**
         * 每小时最大请求数
         */
        private int maxRequestsPerHour;

        /**
         * 限流算法类型
         */
        private String algorithm = "SLIDING_WINDOW";

        /**
         * 令牌桶容量
         */
        private int tokenBucketCapacity = 100;

        /**
         * 令牌补充速率
         */
        private double tokenRefillRate = 10.0;

        public UserTypeRule() {}

        public UserTypeRule(int maxRequestsPerSecond, int maxRequestsPerMinute, int maxRequestsPerHour) {
            this.maxRequestsPerSecond = maxRequestsPerSecond;
            this.maxRequestsPerMinute = maxRequestsPerMinute;
            this.maxRequestsPerHour = maxRequestsPerHour;
        }

        // Getters and Setters
        public int getMaxRequestsPerSecond() {
            return maxRequestsPerSecond;
        }

        public void setMaxRequestsPerSecond(int maxRequestsPerSecond) {
            this.maxRequestsPerSecond = maxRequestsPerSecond;
        }

        public int getMaxRequestsPerMinute() {
            return maxRequestsPerMinute;
        }

        public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
        }

        public int getMaxRequestsPerHour() {
            return maxRequestsPerHour;
        }

        public void setMaxRequestsPerHour(int maxRequestsPerHour) {
            this.maxRequestsPerHour = maxRequestsPerHour;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public int getTokenBucketCapacity() {
            return tokenBucketCapacity;
        }

        public void setTokenBucketCapacity(int tokenBucketCapacity) {
            this.tokenBucketCapacity = tokenBucketCapacity;
        }

        public double getTokenRefillRate() {
            return tokenRefillRate;
        }

        public void setTokenRefillRate(double tokenRefillRate) {
            this.tokenRefillRate = tokenRefillRate;
        }
    }

    /**
     * 路径特定限流规则
     */
    public static class PathRule {
        /**
         * 路径匹配模式
         */
        private String pathPattern;

        /**
         * 用户类型限流规则映射
         */
        private Map<String, UserTypeRule> userTypeRules = new HashMap<>();

        /**
         * 是否启用该规则
         */
        private boolean enabled = true;

        /**
         * 规则优先级
         */
        private int priority = 100;

        // Getters and Setters
        public String getPathPattern() {
            return pathPattern;
        }

        public void setPathPattern(String pathPattern) {
            this.pathPattern = pathPattern;
        }

        public Map<String, UserTypeRule> getUserTypeRules() {
            return userTypeRules;
        }

        public void setUserTypeRules(Map<String, UserTypeRule> userTypeRules) {
            this.userTypeRules = userTypeRules;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }
}