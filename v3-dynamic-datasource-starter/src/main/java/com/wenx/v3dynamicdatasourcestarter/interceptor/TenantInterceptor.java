package com.wenx.v3dynamicdatasourcestarter.interceptor;

import com.wenx.v3dynamicdatasourcestarter.context.TenantContext;
import com.wenx.v3dynamicdatasourcestarter.properties.V3DynamicDataSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 租户拦截器（P1.4 收敛保安全版）
 * 租户 ID 只从认证主体（JWT claim）解析，作为唯一权威来源；
 * 客户端自报的 {@code X-Tenant-Id} 头/参数仅在与 claim 一致时接受，否则直接 403，
 * 杜绝"改请求头即跨租户"的越权隐患。
 *
 * @author wenx
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private final V3DynamicDataSourceProperties properties;
    private final ObjectProvider<JwtDecoder> jwtDecoderProvider;

    public TenantInterceptor(V3DynamicDataSourceProperties properties, ObjectProvider<JwtDecoder> jwtDecoderProvider) {
        this.properties = properties;
        this.jwtDecoderProvider = jwtDecoderProvider;
    }

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

        // 1. 权威租户 ID：仅从 JWT claim 解析（P1.4）
        String claimTenantId = extractTenantFromToken(request);

        // 2. 客户端自报的租户标识：仅在与 claim 一致时接受，否则 403
        V3DynamicDataSourceProperties.TenantDetection tenantDetection = properties.getTenantDetection();
        String headerTenantId = request.getHeader(tenantDetection.getHeaderName());
        if (!StringUtils.hasText(headerTenantId)) {
            headerTenantId = request.getParameter(tenantDetection.getParamName());
        }
        if (StringUtils.hasText(headerTenantId)) {
            if (!StringUtils.hasText(claimTenantId)) {
                log.warn("请求携带租户标识但 token 无租户 claim，拒绝: uri={}, tenant={}", requestURI, headerTenantId);
                reject(response);
                return false;
            }
            if (!headerTenantId.equals(claimTenantId)) {
                log.warn("请求租户头与 token 租户 claim 不一致，拒绝: uri={}, header={}, claim={}",
                        requestURI, headerTenantId, claimTenantId);
                reject(response);
                return false;
            }
        }

        // 3. 以 claim 为准设置租户上下文
        if (StringUtils.hasText(claimTenantId)) {
            TenantContext.setCurrentTenant(claimTenantId);
            log.debug("设置租户上下文: tenant={}, uri={}", claimTenantId, requestURI);
        } else {
            log.debug("未检测到租户 ID，使用默认数据源: {}", requestURI);
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
     * 从 JWT claim 解析租户 ID（P1.4：唯一权威来源，禁止客户端自报）
     * 未启用 jwt 解析或解析失败时返回 null（此时任何自报租户头都会被拒绝）
     */
    private String extractTenantFromToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        if (!properties.getTenantDetection().isEnableJwtParsing()) {
            return null;
        }
        try {
            JwtDecoder decoder = jwtDecoderProvider.getIfAvailable();
            if (decoder == null) {
                return null;
            }
            Jwt jwt = decoder.decode(authorization.substring(7));
            Object tenant = jwt.getClaim(properties.getTenantDetection().getJwtTenantClaim());
            return tenant != null ? tenant.toString() : null;
        } catch (Exception e) {
            log.warn("解析 token 中的租户信息失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 拒绝请求并返回 403
     */
    private void reject(HttpServletResponse response) {
        try {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"data\":null,\"msg\":\"租户标识不合法\"}");
        } catch (IOException e) {
            log.warn("写入 403 响应失败: {}", e.getMessage());
        }
    }
}
