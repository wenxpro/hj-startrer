package com.wenx.v3dynamicdatasourcestarter.interceptor;

import com.wenx.v3dynamicdatasourcestarter.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 租户拦截器
 * 从请求中提取租户ID并设置到租户上下文中
 * 
 * @author wenx
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    /**
     * 租户ID请求头名称
     */
    private static final String TENANT_HEADER = "X-Tenant-Id";

    /**
     * 租户ID请求参数名称
     */
    private static final String TENANT_PARAM = "tenantId";

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, 
                           @NonNull HttpServletResponse response, 
                           @NonNull Object handler) {
        
        // 清除之前的租户上下文
        TenantContext.clear();
        
        // 跳过平台管理相关的请求
        String requestURI = request.getRequestURI();
        if (isPlatformRequest(requestURI)) {
            log.debug("跳过平台请求的租户检测: {}", requestURI);
            return true;
        }
        
        // 1. 优先从请求头获取租户ID
        String tenantId = request.getHeader(TENANT_HEADER);
        
        // 2. 如果请求头没有，从请求参数获取
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = request.getParameter(TENANT_PARAM);
        }
        
        // 3. 如果还是没有，尝试从JWT Token中解析（如果有认证信息）
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = extractTenantFromToken(request);
        }
        
        // 设置租户上下文
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            TenantContext.setCurrentTenant(tenantId);
            log.debug("检测到租户ID: {}, 请求: {}", tenantId, requestURI);
        } else {
            log.debug("未检测到租户ID，使用默认数据源: {}", requestURI);
        }
        
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, 
                              @NonNull HttpServletResponse response, 
                              @NonNull Object handler, 
                              Exception ex) {
        // 清除租户上下文，避免内存泄漏
        TenantContext.clear();
    }

    /**
     * 判断是否为平台管理请求
     * 平台管理请求不需要租户上下文
     */
    private boolean isPlatformRequest(String requestURI) {
        return requestURI.startsWith("/platform/") || 
               requestURI.startsWith("/api/platform/") ||
               requestURI.startsWith("/swagger-ui") ||
               requestURI.startsWith("/v3/api-docs") ||
               requestURI.startsWith("/actuator/");
    }

    /**
     * 从JWT Token中提取租户ID
     * 这里可以根据实际的Token结构来实现
     */
    private String extractTenantFromToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                // 这里可以解析JWT Token获取租户信息
                // 当前先返回null，后续可以根据实际需求实现
                return null;
            } catch (Exception e) {
                log.warn("解析Token中的租户信息失败: {}", e.getMessage());
            }
        }
        return null;
    }
} 