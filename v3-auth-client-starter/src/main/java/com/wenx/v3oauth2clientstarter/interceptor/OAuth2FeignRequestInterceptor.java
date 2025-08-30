package com.wenx.v3oauth2clientstarter.interceptor;

import cn.hutool.core.util.StrUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * OAuth2 Feign请求拦截器
 * 
 * @author wenx
 * @description 自动为Feign请求添加OAuth2访问令牌，支持多服务多客户端
 */
public class OAuth2FeignRequestInterceptor implements RequestInterceptor {

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final String applicationName;
    
    public OAuth2FeignRequestInterceptor(OAuth2AuthorizedClientManager authorizedClientManager, String applicationName) {
        this.authorizedClientManager = authorizedClientManager;
        this.applicationName = applicationName;
    }

    @Override
    public void apply(RequestTemplate template) {
        // 如果请求已经包含Authorization头，则不处理
        if (template.headers().containsKey("Authorization")) {
            return;
        }
        
        // 从URL中提取目标服务名
        String targetService = extractServiceName(template.url());
        
        // 获取OAuth2客户端凭证
        String accessToken = getAccessToken(targetService);
        if (accessToken != null) {
            // 添加Bearer Token到请求头
            template.header("Authorization", "Bearer " + accessToken);
        }
    }

    /**
     * 从URL中提取服务名
     */
    private String extractServiceName(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            // 如果是服务名（不包含点号），直接返回
            if (host != null && !host.contains(".")) {
                return host;
            }
            // 如果是域名，提取第一部分作为服务名
            if (host != null && host.contains(".")) {
                return host.substring(0, host.indexOf("."));
            }
            return null;
        } catch (Exception e) {
            // 如果URL解析失败，返回null
            return null;
        }
    }

    /**
     * 获取访问令牌
     * @param targetService 目标服务名
     */
    private String getAccessToken(String targetService) {
        // 使用当前应用名作为客户端注册ID
        String clientRegistrationId = applicationName + "-client";

        // 构建授权请求
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(clientRegistrationId)
                .principal(applicationName)
                .build();

        // 获取授权客户端
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);

        if (authorizedClient != null && authorizedClient.getAccessToken() != null) {
            return authorizedClient.getAccessToken().getTokenValue();
        }
        return null;
    }

}