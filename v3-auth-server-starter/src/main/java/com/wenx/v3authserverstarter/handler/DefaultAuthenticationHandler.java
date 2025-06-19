package com.wenx.v3authserverstarter.handler;

import com.alibaba.fastjson2.JSON;
import com.wenx.v3core.response.R;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 默认认证处理器
 *
 * @author wenx
 * @description 提供默认的认证处理逻辑
 */
@Slf4j
public class DefaultAuthenticationHandler implements AuthenticationEntryPoint, AccessDeniedHandler,
        AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final RequestCache requestCache = new HttpSessionRequestCache();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (isAjaxOrApiRequest(request)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            String message = authException != null ? authException.getMessage() : "未认证，请先登录";
            writeJson(response, R.failed(message));
        } else {
            redirectToLogin(request, response, null);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (isAjaxOrApiRequest(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            String message = accessDeniedException != null ? accessDeniedException.getMessage() : "权限不足，无法访问该资源";
            writeJson(response, R.failed(message));
        } else {
            response.sendError(HttpStatus.FORBIDDEN.value(), "您没有权限访问该资源");
        }
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (isAjaxOrApiRequest(request)) {
            try {
                // 生成简单的token响应
                Map<String, Object> tokenResponse = generateSimpleTokenResponse(authentication);

                // 如果是API请求，还需要在响应头中设置Authorization
                if (request.getRequestURI().contains("/api/")) {
                    response.setHeader("Authorization", "Bearer " + tokenResponse.get("access_token"));
                }

                // 返回token信息
                writeJson(response, R.ok(tokenResponse));

                log.info("用户 {} API登录成功", authentication.getName());
            } catch (Exception e) {
                log.error("生成token失败", e);
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
                writeJson(response, R.failed("生成token失败: " + e.getMessage()));
            }
        } else {
            String targetUrl = determineTargetUrl(request, response);
            log.info("用户 {} 网页登录成功，重定向到: {}", authentication.getName(), targetUrl);
            response.sendRedirect(targetUrl);
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        if (isAjaxOrApiRequest(request)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            String message = exception != null && exception.getMessage() != null ?
                    exception.getMessage() : "认证失败";
            writeJson(response, R.failed(message));
        } else {
            String message = exception != null && exception.getMessage() != null ?
                    exception.getMessage() : "认证失败";
            redirectToLogin(request, response, message, request.getParameter("redirect"));
        }
    }

    /**
     * 判断是否为AJAX或API请求
     */
    private boolean isAjaxOrApiRequest(HttpServletRequest request) {
        String ajaxHeader = request.getHeader("X-Requested-With");
        String contentType = request.getHeader("Content-Type");
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();

        return "XMLHttpRequest".equals(ajaxHeader) ||
                (contentType != null && contentType.contains("application/json")) ||
                (accept != null && accept.contains("application/json")) ||
                uri.contains("/api/") || uri.contains("/oauth2/");
    }

    /**
     * 写入JSON响应 - 统一使用R对象
     */
    private void writeJson(HttpServletResponse response, R result) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.toJSONString(result));
    }

    /**
     * 确定重定向目标URL
     */
    private String determineTargetUrl(HttpServletRequest request, HttpServletResponse response) {
        String targetUrl = request.getParameter("redirect");

        if (targetUrl == null || targetUrl.isEmpty()) {
            SavedRequest savedRequest = requestCache.getRequest(request, response);
            if (savedRequest != null) {
                targetUrl = savedRequest.getRedirectUrl();
            }
        }

        return (targetUrl == null || targetUrl.isEmpty()) ? "/" : targetUrl;
    }

    /**
     * 重定向到登录页面
     */
    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response, String targetUrl)
            throws IOException {
        if (targetUrl == null) {
            targetUrl = request.getRequestURI();
            if (request.getQueryString() != null) {
                targetUrl += "?" + request.getQueryString();
            }
        }

        String loginUrl = "/login";
        if (targetUrl != null && !targetUrl.isEmpty() && !"/".equals(targetUrl)) {
            loginUrl += "?redirect=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8);
        }

        log.debug("重定向到登录页面: {}", loginUrl);
        response.sendRedirect(loginUrl);
    }

    /**
     * 重定向到登录页面（带错误信息）
     */
    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response,
                                 String errorMessage, String redirect) throws IOException {
        StringBuilder loginUrl = new StringBuilder("/login");
        boolean hasParams = false;

        if (errorMessage != null && !errorMessage.isEmpty()) {
            loginUrl.append("?error=").append(URLEncoder.encode(errorMessage, StandardCharsets.UTF_8));
            hasParams = true;
        }

        if (redirect != null && !redirect.isEmpty() && !"/".equals(redirect)) {
            loginUrl.append(hasParams ? "&" : "?")
                    .append("redirect=")
                    .append(URLEncoder.encode(redirect, StandardCharsets.UTF_8));
        }

        log.debug("重定向到登录页面: {}", loginUrl);
        response.sendRedirect(loginUrl.toString());
    }

    /**
     * 生成简单的Token响应
     */
    private Map<String, Object> generateSimpleTokenResponse(Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        response.put("access_token", "simple-token-" + System.currentTimeMillis());
        response.put("token_type", "Bearer");
        response.put("expires_in", 3600);
        response.put("scope", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(" ")));

        // 添加用户信息
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("username", authentication.getName());
        userInfo.put("authorities", authentication.getAuthorities());
        response.put("user_info", userInfo);

        return response;
    }
} 