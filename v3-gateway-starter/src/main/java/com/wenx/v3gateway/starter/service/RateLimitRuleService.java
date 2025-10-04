package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.enums.UserType;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 限流规则管理服务
 * 负责限流规则的加载、缓存、动态更新和查询
 * 
 * @author wenx
 */
@Service
public class RateLimitRuleService {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimitRuleService.class);
    
    private static final String RULE_CACHE_KEY = "gateway:rate_limit:rules";
    private static final String RULE_VERSION_KEY = "gateway:rate_limit:rules:version";

    @Autowired
    @Qualifier("reactiveRedisTemplateForObject")
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private PathMatcherService pathMatcherService;
    
    /**
     * 本地规则缓存
     */
    private final Map<String, RateLimitRule> ruleCache = new ConcurrentHashMap<>();
    
    /**
     * 按用户类型分组的规则缓存
     */
    private final Map<UserType, List<RateLimitRule>> rulesByUserType = new ConcurrentHashMap<>();
    
    /**
     * 读写锁，保证缓存更新的线程安全
     */
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    
    /**
     * 当前规则版本号
     * -- GETTER --
     *  获取当前规则版本

     */
    @Getter
    private volatile long currentVersion = 0L;
    
    @PostConstruct
    public void init() {
        // 初始化默认限流规则
        initDefaultRules();
        // 从Redis加载规则
        loadRulesFromRedis();
        log.info("RateLimitRuleService initialized with {} rules", ruleCache.size());
    }
    
    /**
     * 初始化默认限流规则
     */
    private void initDefaultRules() {
        List<RateLimitRule> defaultRules = createDefaultRules();
        
        cacheLock.writeLock().lock();
        try {
            ruleCache.clear();
            rulesByUserType.clear();
            
            for (RateLimitRule rule : defaultRules) {
                ruleCache.put(rule.getRuleId(), rule);
                addToUserTypeCache(rule);
            }
            
            log.info("Initialized {} default rate limit rules", defaultRules.size());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * 创建默认限流规则
     */
    private List<RateLimitRule> createDefaultRules() {
        List<RateLimitRule> rules = new ArrayList<>();
        
        // 匿名用户限流规则
        RateLimitRule anonymousRule = new RateLimitRule();
        anonymousRule.setRuleId("anonymous-default");
        anonymousRule.setRuleName("匿名用户默认限流");
        anonymousRule.setPathPatterns(Arrays.asList("/api/**", "/public/**"));
        anonymousRule.setUserType(UserType.ANONYMOUS);
        anonymousRule.setMaxRequestsPerSecond(10);
        anonymousRule.setMaxRequestsPerMinute(100);
        anonymousRule.setMaxRequestsPerHour(1000);
        anonymousRule.setPriority(100);
        anonymousRule.setDescription("匿名用户访问API的默认限流策略");
        rules.add(anonymousRule);
        
        // 系统用户限流规则
        RateLimitRule systemRule = new RateLimitRule();
        systemRule.setRuleId("system-default");
        systemRule.setRuleName("系统用户默认限流");
        systemRule.setPathPatterns(Arrays.asList("/api/v1/sys/**"));
        systemRule.setUserType(UserType.SYSTEM);
        systemRule.setMaxRequestsPerSecond(50);
        systemRule.setMaxRequestsPerMinute(1000);
        systemRule.setMaxRequestsPerHour(10000);
        systemRule.setPriority(200);
        systemRule.setDescription("系统用户访问系统API的默认限流策略");
        rules.add(systemRule);
        
        // 平台用户限流规则
        RateLimitRule platformRule = new RateLimitRule();
        platformRule.setRuleId("platform-default");
        platformRule.setRuleName("平台用户默认限流");
        platformRule.setPathPatterns(Arrays.asList("/api/v1/platform/**"));
        platformRule.setUserType(UserType.PLATFORM);
        platformRule.setMaxRequestsPerSecond(100);
        platformRule.setMaxRequestsPerMinute(2000);
        platformRule.setMaxRequestsPerHour(20000);
        platformRule.setPriority(300);
        platformRule.setDescription("平台用户访问平台API的默认限流策略");
        rules.add(platformRule);
        
        // 租户用户限流规则
        RateLimitRule tenantRule = new RateLimitRule();
        tenantRule.setRuleId("tenant-default");
        tenantRule.setRuleName("租户用户默认限流");
        tenantRule.setPathPatterns(Arrays.asList("/api/v1/tenant/**"));
        tenantRule.setUserType(UserType.TENANT);
        tenantRule.setMaxRequestsPerSecond(30);
        tenantRule.setMaxRequestsPerMinute(500);
        tenantRule.setMaxRequestsPerHour(5000);
        tenantRule.setPriority(150);
        tenantRule.setDescription("租户用户访问租户API的默认限流策略");
        rules.add(tenantRule);
        
        // 高频API特殊限流规则
        RateLimitRule highFreqRule = new RateLimitRule();
        highFreqRule.setRuleId("high-freq-api");
        highFreqRule.setRuleName("高频API限流");
        highFreqRule.setPathPatterns(Arrays.asList("/api/**/get","/api/**/search", "/api/**/list", "/api/**/query"));
        highFreqRule.setUserType(null); // 适用于所有用户类型
        highFreqRule.setMaxRequestsPerSecond(5);
        highFreqRule.setMaxRequestsPerMinute(50);
        highFreqRule.setMaxRequestsPerHour(500);
        highFreqRule.setPriority(500); // 高优先级
        highFreqRule.setDescription("高频查询API的特殊限流策略");
        rules.add(highFreqRule);
        
        return rules;
    }
    
    /**
     * 从Redis加载规则
     */
    @SuppressWarnings("unchecked")
    private void loadRulesFromRedis() {
        try {
            Object rulesObj = redisTemplate.opsForValue().get(RULE_CACHE_KEY);
            Object versionObj = redisTemplate.opsForValue().get(RULE_VERSION_KEY);
            
            if (rulesObj instanceof List && versionObj instanceof Long) {
                List<RateLimitRule> rules = (List<RateLimitRule>) rulesObj;
                long version = (Long) versionObj;
                
                if (version > currentVersion) {
                    updateRulesCache(rules, version);
                    log.info("Loaded {} rules from Redis, version: {}", rules.size(), version);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load rules from Redis: {}", e.getMessage());
        }
    }
    
    /**
     * 更新规则缓存
     */
    private void updateRulesCache(List<RateLimitRule> rules, long version) {
        cacheLock.writeLock().lock();
        try {
            ruleCache.clear();
            rulesByUserType.clear();
            
            for (RateLimitRule rule : rules) {
                if (rule.isEnabled()) {
                    ruleCache.put(rule.getRuleId(), rule);
                    addToUserTypeCache(rule);
                }
            }
            
            currentVersion = version;
            log.info("Updated rule cache with {} rules, version: {}", ruleCache.size(), version);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * 添加规则到用户类型缓存
     */
    private void addToUserTypeCache(RateLimitRule rule) {
        if (rule.getUserType() != null) {
            rulesByUserType.computeIfAbsent(rule.getUserType(), k -> new ArrayList<>()).add(rule);
        } else {
            // 规则适用于所有用户类型
            for (UserType userType : UserType.values()) {
                rulesByUserType.computeIfAbsent(userType, k -> new ArrayList<>()).add(rule);
            }
        }
    }
    
    /**
     * 查找匹配的限流规则
     * 
     * @param requestPath 请求路径
     * @param userType 用户类型
     * @return 匹配的限流规则
     */
    public Optional<RateLimitRule> findMatchingRule(String requestPath, UserType userType) {
        cacheLock.readLock().lock();
        try {
            List<RateLimitRule> candidateRules = rulesByUserType.getOrDefault(userType, Collections.emptyList());
            return pathMatcherService.findMatchingRule(requestPath, userType, candidateRules);
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * 获取所有规则
     */
    public List<RateLimitRule> getAllRules() {
        cacheLock.readLock().lock();
        try {
            return new ArrayList<>(ruleCache.values());
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * 根据用户类型获取规则
     */
    public List<RateLimitRule> getRulesByUserType(UserType userType) {
        cacheLock.readLock().lock();
        try {
            return new ArrayList<>(rulesByUserType.getOrDefault(userType, Collections.emptyList()));
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * 添加或更新规则
     */
    public void saveRule(RateLimitRule rule) {
        if (rule == null || rule.getRuleId() == null) {
            throw new IllegalArgumentException("Rule and ruleId cannot be null");
        }
        
        cacheLock.writeLock().lock();
        try {
            ruleCache.put(rule.getRuleId(), rule);
            
            // 更新用户类型缓存
            rulesByUserType.values().forEach(list -> list.removeIf(r -> r.getRuleId().equals(rule.getRuleId())));
            if (rule.isEnabled()) {
                addToUserTypeCache(rule);
            }
            
            // 保存到Redis
            saveRulesToRedis();
            
            log.info("Saved rule: {}", rule.getRuleName());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * 删除规则
     */
    public void deleteRule(String ruleId) {
        if (ruleId == null) {
            return;
        }
        
        cacheLock.writeLock().lock();
        try {
            RateLimitRule removed = ruleCache.remove(ruleId);
            if (removed != null) {
                // 从用户类型缓存中移除
                rulesByUserType.values().forEach(list -> list.removeIf(r -> r.getRuleId().equals(ruleId)));
                
                // 保存到Redis
                saveRulesToRedis();
                
                log.info("Deleted rule: {}", removed.getRuleName());
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * 保存规则到Redis
     */
    private void saveRulesToRedis() {
        try {
            List<RateLimitRule> rules = new ArrayList<>(ruleCache.values());
            long newVersion = System.currentTimeMillis();
            
            redisTemplate.opsForValue().set(RULE_CACHE_KEY, rules, Duration.ofHours(24));
            redisTemplate.opsForValue().set(RULE_VERSION_KEY, newVersion, Duration.ofHours(24));
            
            currentVersion = newVersion;
            log.debug("Saved {} rules to Redis, version: {}", rules.size(), newVersion);
        } catch (Exception e) {
            log.error("Failed to save rules to Redis: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 刷新规则缓存
     */
    public void refreshRules() {
        loadRulesFromRedis();
    }

    /**
     * 获取规则统计信息
     */
    public Map<String, Object> getRuleStatistics() {
        cacheLock.readLock().lock();
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalRules", ruleCache.size());
            stats.put("enabledRules", ruleCache.values().stream().mapToInt(r -> r.isEnabled() ? 1 : 0).sum());
            stats.put("rulesByUserType", rulesByUserType.entrySet().stream()
                    .collect(HashMap::new, (m, e) -> m.put(e.getKey().name(), e.getValue().size()), HashMap::putAll));
            stats.put("currentVersion", currentVersion);
            return stats;
        } finally {
            cacheLock.readLock().unlock();
        }
    }
}