package com.wenx.v3oauth2clientstarter;

import com.wenx.v3oauth2clientstarter.interceptor.TraceRestTemplateInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

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
     * TraceRestTemplateInterceptor Bean定义
     */
    @Bean
    public TraceRestTemplateInterceptor traceRestTemplateInterceptor() {
        return new TraceRestTemplateInterceptor();
    }

    /**
     * 统一的负载均衡RestTemplate
     * 用于JWT解码器和OAuth2客户端，自动添加可用的拦截器（包括追踪信息）
     */
    @Bean("oauth2LoadBalancedRestTemplate")
    @Primary
    @LoadBalanced
    public RestTemplate oauth2LoadBalancedRestTemplate(TraceRestTemplateInterceptor traceInterceptor) {
        var restTemplate = new RestTemplate();
        
        // 添加追踪拦截器
        var interceptors = new ArrayList<>(restTemplate.getInterceptors());
        interceptors.add(traceInterceptor);
        restTemplate.setInterceptors(interceptors);
        
        return restTemplate;
    }
}