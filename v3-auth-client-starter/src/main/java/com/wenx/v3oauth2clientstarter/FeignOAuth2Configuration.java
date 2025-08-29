package com.wenx.v3oauth2clientstarter;

import com.wenx.v3oauth2clientstarter.interceptor.OAuth2FeignRequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

/**
 * Feign OAuth2 配置类
 * @author wenx
 */
@Slf4j
@Configuration
@ConditionalOnProperty(value = "feign.oauth2.enabled", havingValue = "true", matchIfMissing = true)
public class FeignOAuth2Configuration {

    @Bean("oAuth2FeignRequestInterceptor")
    @ConditionalOnBean(OAuth2AuthorizedClientManager.class)
    public OAuth2FeignRequestInterceptor oAuth2FeignRequestInterceptor(
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${spring.application.name:service}") String applicationName) {
        
        return new OAuth2FeignRequestInterceptor(authorizedClientManager, applicationName);
    }
}