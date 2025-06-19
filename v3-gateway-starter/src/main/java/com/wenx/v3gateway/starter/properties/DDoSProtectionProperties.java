package com.wenx.v3gateway.starter.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DDoS防护配置属性
 */
@ConfigurationProperties(prefix = "cloud.gateway.ddos")
public class DDoSProtectionProperties {

    /**
     * 是否启用DDoS防护
     */
    private boolean enabled = true;

    /**
     * 每分钟最大请求数
     */
    private int maxRequestsPerMinute = 100;

    /**
     * 每秒最大请求数
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
} 