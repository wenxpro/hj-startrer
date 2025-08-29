package com.wenx.v3oauth2clientstarter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Component;

/**
 * OAuth2 Feign客户端工具类
 * 提供获取OAuth2 token的功能，用于手动添加到Feign请求中
 * 
 * @author wenx
 */
@Slf4j
@Component
public class OAuth2FeignClient {

    @Autowired(required = false)
    private OAuth2AuthorizedClientManager authorizedClientManager;
    
    @Value("${spring.application.name:unknown-app}")
    private String applicationName;
    
    @Value("${spring.security.oauth2.client.registration.default.client-id:v3-auth-client}")
    private String clientRegistrationId;

    /**
     * 获取OAuth2访问令牌
     * 
     * @return OAuth2访问令牌，如果获取失败返回null
     */
    public String getAccessToken() {
        if (authorizedClientManager == null) {
            log.warn("[{}] OAuth2AuthorizedClientManager未配置，无法获取访问令牌", applicationName);
            return null;
        }

        // 构建OAuth2授权请求
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(getClientRegistrationId())
                .principal(applicationName)
                .build();

        // 获取授权客户端
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);

        if (authorizedClient != null) {
            OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
            if (accessToken != null) {
                return accessToken.getTokenValue();
            }
        }

        return null;
    }
    
    /**
     * 获取客户端注册ID
     * 优先使用配置的值，如果没有配置则使用默认值
     * 
     * @return 客户端注册ID
     */
    private String getClientRegistrationId() {
        // 如果clientRegistrationId为默认值，尝试使用应用名称作为客户端注册ID
        if ("v3-auth-client".equals(clientRegistrationId) && applicationName != null && !"unknown-app".equals(applicationName)) {
            return applicationName + "-client";
        }
        return clientRegistrationId;
    }
}