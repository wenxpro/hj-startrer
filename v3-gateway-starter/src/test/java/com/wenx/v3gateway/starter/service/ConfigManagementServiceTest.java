package com.wenx.v3gateway.starter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.enums.UserType;
import com.wenx.v3gateway.starter.properties.DDoSProtectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ReactiveSetOperations;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ConfigManagementService单元测试
 * 测试统一配置管理服务的功能
 * 
 * @author wenx
 */
@ExtendWith(MockitoExtension.class)
class ConfigManagementServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    
    @Mock
    private ReactiveValueOperations<String, String> valueOperations;
    
    @Mock
    private ReactiveSetOperations<String, String> setOperations;
    
    @Mock
    private RateLimitRuleService ruleService;
    
    @Mock
    private DDoSProtectionProperties properties;
    
    private ObjectMapper objectMapper;
    private ConfigManagementService configManagementService;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(properties.getRuleCacheRefreshInterval()).thenReturn(30);
        
        configManagementService = new ConfigManagementService(
            redisTemplate, ruleService, objectMapper, properties);
    }
    
    @Test
    void testSaveRateLimitRules() {
        // 准备测试数据
        RateLimitRule rule = createTestRule();
        List<RateLimitRule> rules = Arrays.asList(rule);
        
        // Mock Redis操作
        when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
        doReturn(Mono.empty()).when(ruleService).saveRule(any(RateLimitRule.class));
        
        // 执行测试
        Mono<Void> result = configManagementService.saveRateLimitRules(rules);
        assertNotNull(result);
        result.block();
        
        // 验证调用
        verify(valueOperations, times(1)).set(anyString(), anyString());
        verify(ruleService, times(1)).saveRule(rule);
    }
    
    @Test
    void testSaveGlobalSettings() {
        // 准备测试数据
        Map<String, Object> settings = Map.of("maxRequestsPerSecond", 100);
        
        // Mock Redis操作
        when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
        
        // 执行测试
        Mono<Void> result = configManagementService.saveGlobalSettings(settings);
        assertNotNull(result);
        result.block();
        
        // 验证调用
        verify(valueOperations, times(1)).set(anyString(), anyString());
    }
    
    @Test
    void testGetCurrentVersion() {
        // Mock Redis操作
        when(valueOperations.get(anyString())).thenReturn(Mono.just("1"));
        
        // 执行测试
        Mono<String> result = configManagementService.getCurrentVersion();
        assertNotNull(result);
        String version = result.block();
        
        // 验证结果
        assertNotNull(version);
        assertEquals("1", version);
    }
    
    @Test
    void testForceRefreshConfig() {
        // Mock Redis操作
        when(valueOperations.get(anyString())).thenReturn(Mono.just("[]"));
        
        // 执行测试
        Mono<Void> result = configManagementService.forceRefreshConfig();
        assertNotNull(result);
        result.block();
        
        // 验证调用
        verify(valueOperations, atLeastOnce()).get(anyString());
    }
    
    @Test
    void testConfigChangeListener() {
        // 创建监听器
        ConfigChangeListener listener = new TestConfigChangeListener();
        
        // 添加监听器
        configManagementService.addConfigChangeListener(listener);
        
        // 准备测试数据
        RateLimitRule rule = createTestRule();
        List<RateLimitRule> rules = Arrays.asList(rule);
        
        // Mock Redis操作
        when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
        doReturn(Mono.empty()).when(ruleService).saveRule(any(RateLimitRule.class));
        
        // 执行测试
        Mono<Void> result = configManagementService.saveRateLimitRules(rules);
        assertNotNull(result);
        result.block();
        
        // 验证监听器被调用
        assertTrue(((TestConfigChangeListener) listener).isRateLimitRulesChanged());
    }
    
    @Test
    void testValidateConfiguration() {
        // 准备测试数据
        RateLimitRule rule = createTestRule();
        List<RateLimitRule> rules = Arrays.asList(rule);
        
        // 执行测试
        Mono<ConfigManagementService.ConfigValidationResult> result = 
            configManagementService.validateRateLimitRules(rules);
        
        // 验证结果
        assertNotNull(result);
        ConfigManagementService.ConfigValidationResult validationResult = result.block();
        assertNotNull(validationResult);
        assertTrue(validationResult.isValid());
    }
    
    @Test
    void testBackupConfiguration() {
        // Mock Redis操作
        when(valueOperations.get(anyString())).thenReturn(Mono.just("[]"));
        when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
        when(setOperations.add(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(setOperations.members(anyString())).thenReturn(Flux.empty());
        
        // 执行测试
        Mono<String> result = configManagementService.backupCurrentConfig();
        assertNotNull(result);
        String backupId = result.block();
        assertNotNull(backupId);
        assertTrue(backupId.contains("_"));
        
        // 验证调用
        verify(valueOperations, atLeastOnce()).set(anyString(), anyString());
        verify(setOperations, times(1)).add(anyString(), anyString());
    }
    
    @Test
    void testRestoreConfiguration() {
        // 准备测试数据
        String backupId = "test-backup-20240101_120000";
        String backupData = "{\"rules\":[],\"globalSettings\":{}}";
        
        // Mock Redis操作
        when(valueOperations.get(anyString())).thenReturn(Mono.just(backupData));
        when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
        
        // 执行测试
        Mono<Void> result = configManagementService.restoreConfig(backupId);
        assertNotNull(result);
        result.block();
        
        // 验证调用
        verify(valueOperations, atLeastOnce()).get(anyString());
        verify(valueOperations, atLeastOnce()).set(anyString(), anyString());
    }
    
    @Test
    void testGetBackupList() {
        // Mock Redis操作
        when(setOperations.members(anyString())).thenReturn(Flux.just("backup1", "backup2"));
        when(valueOperations.get(anyString())).thenReturn(Mono.just("{}"));
        
        // 执行测试
        Mono<List<ConfigManagementService.BackupInfo>> result = configManagementService.getBackupList();
        assertNotNull(result);
        List<ConfigManagementService.BackupInfo> backups = result.block();
        assertNotNull(backups);
        assertEquals(2, backups.size());
        
        // 验证调用
        verify(setOperations, times(1)).members(anyString());
    }
    
    @Test
    void testDeleteBackup() {
        // 准备测试数据
        String backupId = "test-backup-20240101_120000";
        
        // Mock Redis操作
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
        when(setOperations.remove(anyString(), anyString())).thenReturn(Mono.just(1L));
        
        // 执行测试
        Mono<Void> result = configManagementService.deleteBackup(backupId);
        assertNotNull(result);
        result.block();
        
        // 验证调用
        verify(redisTemplate, times(1)).delete(anyString());
        verify(setOperations, times(1)).remove(anyString(), anyString());
    }
    
    private RateLimitRule createTestRule() {
        RateLimitRule rule = new RateLimitRule();
        rule.setRuleId("test-rule");
        rule.setRuleName("测试规则");
        rule.setPathPatterns(Arrays.asList("/api/v1/test/**"));
        rule.setUserType(UserType.TENANT);
        rule.setMaxRequestsPerSecond(10);
        rule.setMaxRequestsPerMinute(100);
        rule.setMaxRequestsPerHour(1000);
        rule.setWindowSize(Duration.ofMinutes(1));
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.setDescription("单元测试规则");
        return rule;
    }
    
    /**
     * 测试用配置变更监听器
     */
    private static class TestConfigChangeListener implements ConfigChangeListener {
        private boolean rateLimitRulesChanged = false;
        private boolean globalSettingsChanged = false;
        private boolean configReloadCompleted = false;
        
        @Override
        public void onRateLimitRulesChanged(List<RateLimitRule> oldRules, List<RateLimitRule> newRules) {
            rateLimitRulesChanged = true;
        }
        
        @Override
        public void onGlobalSettingsChanged(DDoSProtectionProperties oldProperties, 
                                          DDoSProtectionProperties newProperties) {
            globalSettingsChanged = true;
        }
        
        @Override
        public void onConfigReloadCompleted(boolean success, String errorMessage) {
            configReloadCompleted = true;
        }
        
        public boolean isRateLimitRulesChanged() {
            return rateLimitRulesChanged;
        }
        
        public boolean isGlobalSettingsChanged() {
            return globalSettingsChanged;
        }
        
        public boolean isConfigReloadCompleted() {
            return configReloadCompleted;
        }
    }
}