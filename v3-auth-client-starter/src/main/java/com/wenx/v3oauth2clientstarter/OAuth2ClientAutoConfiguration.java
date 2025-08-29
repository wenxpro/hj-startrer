package com.wenx.v3oauth2clientstarter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OAuth2 客户端自动配置
 * 
 * @author wenx
 * @description 提供OAuth2客户端的自动配置，包括负载均衡支持
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass({OAuth2AuthorizedClientManager.class, RestTemplate.class})
@ConditionalOnProperty(name = "cloud.auth.oauth2.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OAuth2ClientAutoConfiguration {

    /**
     * 负载均衡的WebClient.Builder
     */
    @Bean
    @LoadBalanced
    @ConditionalOnMissingBean(name = "loadBalancedWebClientBuilder")
    @ConditionalOnClass({WebClient.class, LoadBalancerClient.class})
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    /**
     * OAuth2授权客户端管理器
     * 仅在存在客户端注册时创建
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ClientRegistrationRepository.class)
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            @Qualifier("oauth2LoadBalancedRestTemplate") RestTemplate restTemplate) {

        // 配置支持负载均衡的token响应客户端
        DefaultClientCredentialsTokenResponseClient tokenResponseClient = 
                new DefaultClientCredentialsTokenResponseClient();
        tokenResponseClient.setRestOperations(restTemplate);

        // 用于 client_credentials 模式
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials(configurer -> configurer
                                .accessTokenResponseClient(tokenResponseClient))
                        .refreshToken()
                        .build();

        DefaultOAuth2AuthorizedClientManager authorizedClientManager =
                new DefaultOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientRepository);
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }
}