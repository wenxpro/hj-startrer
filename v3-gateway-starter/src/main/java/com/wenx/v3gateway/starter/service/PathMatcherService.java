package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.enums.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.List;
import java.util.Optional;

/**
 * 路径匹配服务
 * 基于Ant风格路径模式匹配，支持通配符：
 * - ? 匹配一个字符
 * - * 匹配零个或多个字符
 * - ** 匹配零个或多个目录
 * 
 * @author wenx
 */
@Service
public class PathMatcherService {
    
    private static final Logger log = LoggerFactory.getLogger(PathMatcherService.class);
    
    private final PathMatcher pathMatcher;
    
    public PathMatcherService() {
        this.pathMatcher = new AntPathMatcher();
        // 设置路径分隔符
        ((AntPathMatcher) this.pathMatcher).setPathSeparator("/");
        // 设置大小写敏感
        ((AntPathMatcher) this.pathMatcher).setCaseSensitive(true);
    }
    
    /**
     * 查找匹配的限流规则
     * 按优先级排序，返回第一个匹配的规则
     * 
     * @param requestPath 请求路径
     * @param userType 用户类型
     * @param rules 限流规则列表
     * @return 匹配的限流规则
     */
    public Optional<RateLimitRule> findMatchingRule(String requestPath, UserType userType, List<RateLimitRule> rules) {
        if (requestPath == null || rules == null || rules.isEmpty()) {
            return Optional.empty();
        }
        
        log.debug("Finding matching rule for path: {}, userType: {}", requestPath, userType);
        
        Optional<RateLimitRule> matchingRule = rules.stream()
                .filter(RateLimitRule::isEnabled)
                .filter(rule -> matchesUserType(rule, userType))
                .filter(rule -> matchesAnyPattern(rule.getPathPatterns(), requestPath))
                .min((r1, r2) -> Integer.compare(r2.getPriority(), r1.getPriority())); // 高优先级优先
        
        if (matchingRule.isPresent()) {
            log.debug("Found matching rule: {} for path: {}", matchingRule.get().getRuleName(), requestPath);
        }
        
        return matchingRule;
    }
    
    /**
     * 检查路径是否匹配任一模式
     * 
     * @param patterns 路径模式列表
     * @param path 实际路径
     * @return 是否匹配
     */
    private boolean matchesAnyPattern(List<String> patterns, String path) {
        if (patterns == null || patterns.isEmpty() || path == null) {
            return false;
        }
        
        return patterns.stream().anyMatch(pattern -> matchesPath(pattern, path));
    }
    
    /**
     * 检查路径是否匹配
     * 
     * @param pattern 路径模式
     * @param path 实际路径
     * @return 是否匹配
     */
    public boolean matchesPath(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        
        boolean matches = pathMatcher.match(pattern, path);
        log.debug("Path matching: pattern='{}', path='{}', result={}", pattern, path, matches);
        return matches;
    }
    
    /**
     * 检查用户类型是否匹配
     * 
     * @param rule 限流规则
     * @param userType 用户类型
     * @return 是否匹配
     */
    private boolean matchesUserType(RateLimitRule rule, UserType userType) {
        if (rule.getUserType() == null) {
            // 规则未指定用户类型，匹配所有用户
            return true;
        }
        
        boolean matches = rule.getUserType() == userType;
        log.debug("UserType matching: rule='{}', ruleUserType='{}', requestUserType='{}', result={}", 
                rule.getRuleName(), rule.getUserType(), userType, matches);
        return matches;
    }
    
    /**
     * 提取路径变量
     * 
     * @param pattern 路径模式
     * @param path 实际路径
     * @return 路径变量映射
     */
    public java.util.Map<String, String> extractUriTemplateVariables(String pattern, String path) {
        if (!matchesPath(pattern, path)) {
            return java.util.Collections.emptyMap();
        }
        
        return pathMatcher.extractUriTemplateVariables(pattern, path);
    }
    
    /**
     * 获取最具体的模式
     * 当多个模式都匹配时，返回最具体的那个
     * 
     * @param patterns 模式列表
     * @param path 路径
     * @return 最具体的模式
     */
    public String getMostSpecificPattern(List<String> patterns, String path) {
        if (patterns == null || patterns.isEmpty()) {
            return null;
        }
        
        return patterns.stream()
                .filter(pattern -> matchesPath(pattern, path))
                .min(pathMatcher.getPatternComparator(path))
                .orElse(null);
    }
    
    /**
     * 验证路径模式的有效性
     * 
     * @param pattern 路径模式
     * @return 是否有效
     */
    public boolean isValidPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return false;
        }
        
        try {
            // 尝试编译模式，检查语法是否正确
            pathMatcher.match(pattern, "/test");
            return true;
        } catch (Exception e) {
            log.warn("Invalid path pattern: {}, error: {}", pattern, e.getMessage());
            return false;
        }
    }
    
    /**
     * 标准化路径模式
     * 移除多余的斜杠，确保模式格式正确
     * 
     * @param pattern 原始模式
     * @return 标准化后的模式
     */
    public String normalizePattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        
        String normalized = pattern.trim();
        
        // 确保以/开头
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        
        // 移除多余的斜杠
        normalized = normalized.replaceAll("/+", "/");
        
        // 移除末尾的斜杠（除非是根路径）
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        
        return normalized;
    }
    
    /**
     * 计算模式的复杂度
     * 用于排序和优先级判断
     * 
     * @param pattern 路径模式
     * @return 复杂度分数（越高越具体）
     */
    public int calculatePatternComplexity(String pattern) {
        if (pattern == null) {
            return 0;
        }
        
        int complexity = 0;
        
        // 基础分数：路径长度
        complexity += pattern.length();
        
        // 减分：通配符
        complexity -= pattern.split("\\*").length - 1; // * 通配符
        complexity -= pattern.split("\\?").length - 1; // ? 通配符
        complexity -= pattern.split("/\\*\\*").length - 1; // ** 通配符额外减分
        
        // 加分：具体路径段
        String[] segments = pattern.split("/");
        for (String segment : segments) {
            if (!segment.contains("*") && !segment.contains("?")) {
                complexity += 10; // 具体路径段加分
            }
        }
        
        return Math.max(0, complexity);
    }
}