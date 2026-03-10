package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.domain.RateLimitRule;
import java.util.List;
import java.util.Map;

/**
 * 配置变更监听器接口
 * 用于监听限流规则和全局配置的变更事件
 * 
 * @author wenx
 */
public interface ConfigChangeListener {
    
    /**
     * 限流规则变更回调
     * 
     * @param oldRules 旧的限流规则列表
     * @param newRules 新的限流规则列表
     */
    void onRateLimitRulesChanged(List<RateLimitRule> oldRules, List<RateLimitRule> newRules);
    
    /**
     * 全局配置变更回调
     * 
     * @param oldSettings 旧的全局配置
     * @param newSettings 新的全局配置
     */
    void onGlobalSettingsChanged(Map<String, Object> oldSettings, 
                                Map<String, Object> newSettings);
    
    /**
     * 配置重新加载完成回调
     * 
     * @param success 是否成功重新加载
     * @param errorMessage 错误信息（如果失败）
     */
    void onConfigReloadCompleted(boolean success, String errorMessage);
}