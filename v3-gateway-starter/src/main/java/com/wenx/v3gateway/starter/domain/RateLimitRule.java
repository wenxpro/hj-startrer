package com.wenx.v3gateway.starter.domain;

import com.wenx.v3gateway.starter.enums.UserType;
import lombok.Data;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 限流规则领域对象
 * 定义基于路径匹配和用户类型的差异化限流策略
 * 
 * @author wenx
 */
@Data
public class RateLimitRule {
    
    /**
     * 规则ID
     */
    private String ruleId;
    
    /**
     * 规则名称
     */
    private String ruleName;
    
    /**
     * 路径匹配模式（支持Ant风格通配符）
     */
    private List<String> pathPatterns;
    
    /**
     * 适用的用户类型
     */
    private UserType userType;
    
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
     * 时间窗口大小
     */
    private Duration windowSize;
    
    /**
     * 限流算法类型
     */
    private RateLimitAlgorithm algorithm;
    
    /**
     * 令牌桶容量（仅当算法为TOKEN_BUCKET时有效）
     */
    private int bucketCapacity;
    
    /**
     * 令牌补充速率（仅当算法为TOKEN_BUCKET时有效）
     */
    private double refillRate;
    
    /**
     * 规则优先级（数值越小优先级越高）
     */
    private int priority;
    
    /**
     * 是否启用
     */
    private boolean enabled;
    
    /**
     * 规则描述
     */
    private String description;
    
    public RateLimitRule() {
        this.enabled = true;
        this.algorithm = RateLimitAlgorithm.SLIDING_WINDOW;
        this.windowSize = Duration.ofMinutes(1);
        this.priority = 100;
    }
    
    public RateLimitRule(String ruleId, String ruleName, List<String> pathPatterns, 
                        UserType userType, int maxRequestsPerSecond, int maxRequestsPerMinute) {
        this();
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.pathPatterns = pathPatterns;
        this.userType = userType;
        this.maxRequestsPerSecond = maxRequestsPerSecond;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }
    
    /**
     * 限流算法枚举
     */
    public enum RateLimitAlgorithm {
        /**
         * 滑动窗口算法
         */
        SLIDING_WINDOW,
        
        /**
         * 令牌桶算法
         */
        TOKEN_BUCKET,
        
        /**
         * 固定窗口计数器
         */
        FIXED_WINDOW_COUNTER
    }
}