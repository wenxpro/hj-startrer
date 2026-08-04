package com.wenx.v3oauth2clientstarter;

import com.wenx.v3oauth2clientstarter.interceptor.W3CPropagationRestTemplateInterceptor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;

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
     * 用于JWT解码器和OAuth2客户端；
     * 链路传播由 Micrometer Propagator 按 W3C traceparent 标准注入（log 标准化改造），
     * 替代原手写 TraceRestTemplateInterceptor 的自造 X-Trace-Id header。
     */
    @Bean("oauth2LoadBalancedRestTemplate")
    @Primary
    @LoadBalanced
    public RestTemplate oauth2LoadBalancedRestTemplate(ObjectProvider<Tracer> tracerProvider,
                                                       ObjectProvider<Propagator> propagatorProvider) {
        var restTemplate = new RestTemplate();

        // 存在 Micrometer Tracing（Tracer + Propagator）时自动加 W3C 传播拦截器
        Tracer tracer = tracerProvider.getIfAvailable();
        Propagator propagator = propagatorProvider.getIfAvailable();
        if (tracer != null && propagator != null) {
            var interceptors = new ArrayList<>(restTemplate.getInterceptors());
            interceptors.add(new W3CPropagationRestTemplateInterceptor(tracer, propagator));
            restTemplate.setInterceptors(interceptors);
        }

        return restTemplate;
    }
}
