package com.wenx.v3oauth2clientstarter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/**
 * OAuth2 负载均衡配置
 * 
 * @author wenx
 * @description 提供统一的负载均衡RestTemplate配置
 */
@AutoConfiguration
@ConditionalOnClass({RestTemplate.class, LoadBalancerClient.class})
@ConditionalOnProperty(name = "cloud.auth.oauth2.load-balancer-enabled", havingValue = "true", matchIfMissing = true)
public class OAuth2LoadBalancedConfiguration {

    /**
     * 统一的负载均衡RestTemplate
     * 用于JWT解码器和OAuth2客户端
     */
    @Bean("oauth2LoadBalancedRestTemplate")
    @Primary
    @LoadBalanced
    public RestTemplate oauth2LoadBalancedRestTemplate() {
        return new RestTemplate();
    }
} 