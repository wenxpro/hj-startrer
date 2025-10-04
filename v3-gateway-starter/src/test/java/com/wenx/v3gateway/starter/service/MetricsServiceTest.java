package com.wenx.v3gateway.starter.service;

import com.wenx.v3gateway.starter.domain.RateLimitRule;
import com.wenx.v3gateway.starter.domain.UserContext;
import com.wenx.v3gateway.starter.enums.UserType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MetricsService单元测试
 * 测试统一指标收集服务的功能
 * 
 * @author wenx
 */
@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    
    @Mock
    private ReactiveValueOperations<String, String> valueOperations;
    
    private MeterRegistry meterRegistry;
    private MetricsService metricsService;
    
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        
        meterRegistry = new SimpleMeterRegistry();
        metricsService = new MetricsService(redisTemplate, meterRegistry);
    }
    
    @Test
    void testRecordRequest() {
        // 执行测试
        metricsService.recordRequest("192.168.1.1", false, Duration.ofMillis(100));
        
        // 验证指标计数器
        assertEquals(1.0, meterRegistry.counter("ddos.requests.total").count());
        assertEquals(0.0, meterRegistry.counter("ddos.requests.blocked").count());
    }
    
    @Test
    void testRecordBlockedRequest() {
        // 执行测试
        metricsService.recordRequest("192.168.1.1", true, Duration.ofMillis(100));
        
        // 验证指标计数器
        assertEquals(1.0, meterRegistry.counter("ddos.requests.total").count());
        assertEquals(1.0, meterRegistry.counter("ddos.requests.blocked").count());
    }
    
    @Test
    void testRecordEnhancedRequest() {
        // 准备测试数据
        UserContext userContext = new UserContext();
        userContext.setUserType(UserType.TENANT);
        userContext.setUserId("test-user");
        
        RateLimitRule rule = createTestRule();
        
        // 执行测试
        metricsService.recordRequest(userContext, rule, false, Duration.ofMillis(100));
        
        // 验证指标计数器
        assertEquals(1.0, meterRegistry.counter("ddos.requests.total").count());
        assertEquals(0.0, meterRegistry.counter("ddos.requests.blocked").count());
    }
    
    @Test
    void testRecordBlacklistedIp() {
        // 执行测试
        metricsService.recordBlacklistedIp("192.168.1.1", "Rate limit exceeded");
        
        // 验证指标计数器
        assertEquals(1.0, meterRegistry.counter("ddos.ips.blacklisted").count());
    }
    
    @Test
    void testRecordBlacklistRemoval() {
        // 执行测试
        metricsService.recordBlacklistRemoval("192.168.1.1");
        
        // 验证方法执行不抛异常
        assertDoesNotThrow(() -> metricsService.recordBlacklistRemoval("192.168.1.1"));
    }
    
    @Test
    void testUpdateActiveConnections() {
        // 执行测试
        metricsService.updateActiveConnections(5);
        assertEquals(5, metricsService.getCurrentActiveConnections());
        
        metricsService.updateActiveConnections(-2);
        assertEquals(3, metricsService.getCurrentActiveConnections());
    }
    
    @Test
    void testGetCurrentStats() {
        // 记录一些请求
        metricsService.recordRequest("192.168.1.1", false, Duration.ofMillis(100));
        metricsService.recordRequest("192.168.1.2", true, Duration.ofMillis(150));
        metricsService.updateActiveConnections(10);
        
        // 获取统计信息
        MetricsService.DDoSStats stats = metricsService.getCurrentStats();
        
        // 验证统计信息
        assertNotNull(stats);
        assertEquals(2, stats.getTotalRequests());
        assertEquals(1, stats.getBlockedRequests());
        assertEquals(10, stats.getActiveConnections());
        assertEquals(0.5, stats.getBlockRate(), 0.01);
    }
    
    @Test
    void testGetEnhancedStats() {
        // 准备测试数据
        UserContext userContext = new UserContext();
        userContext.setUserType(UserType.TENANT);
        userContext.setUserId("test-user");
        
        RateLimitRule rule = createTestRule();
        
        // 记录一些增强请求
        metricsService.recordRequest(userContext, rule, false, Duration.ofMillis(100));
        metricsService.recordRequest(userContext, rule, true, Duration.ofMillis(150));
        
        // 获取增强统计信息
        MetricsService.EnhancedDDoSStats stats = metricsService.getEnhancedStats();
        
        // 验证统计信息
        assertNotNull(stats);
        assertEquals(2, stats.getTotalRequests());
        assertEquals(1, stats.getBlockedRequests());
        assertNotNull(stats.getUserTypeStats());
        assertNotNull(stats.getTopRules());
        assertNotNull(stats.getTopPaths());
        assertNotNull(stats.getTimestamp());
    }
    
    @Test
    void testGetMonitoringReport() {
        // Mock Redis操作
        when(valueOperations.get(anyString())).thenReturn(Mono.just("10"));
        
        // 执行测试
        Mono<MetricsService.DDoSMonitoringReport> result = metricsService.getMonitoringReport();
        assertNotNull(result);
        
        MetricsService.DDoSMonitoringReport report = result.block();
        assertNotNull(report);
        assertNotNull(report.getReportTime());
    }
    
    @Test
    void testGetCurrentActiveConnections() {
        // 初始值应该为0
        assertEquals(0, metricsService.getCurrentActiveConnections());
        
        // 更新连接数
        metricsService.updateActiveConnections(15);
        assertEquals(15, metricsService.getCurrentActiveConnections());
    }
    
    @Test
    void testGetCurrentBlacklistedIps() {
        // 初始值应该为0
        assertEquals(0, metricsService.getCurrentBlacklistedIps());
        
        // 记录黑名单IP
        metricsService.recordBlacklistedIp("192.168.1.1", "Test reason");
        assertEquals(1, metricsService.getCurrentBlacklistedIps());
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
}