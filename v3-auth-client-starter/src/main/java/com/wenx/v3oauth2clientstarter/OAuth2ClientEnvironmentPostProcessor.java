package com.wenx.v3oauth2clientstarter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * OAuth2 客户端环境后处理器
 * 
 * @author wenx
 * @description 自动配置OAuth2资源服务器的默认属性
 */
public class OAuth2ClientEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "cloudOAuth2DefaultProperties";
    
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 获取属性源
        MutablePropertySources propertySources = environment.getPropertySources();
        
        // 创建默认属性映射
        Map<String, Object> defaultProperties = new HashMap<>();
        
        // 设置资源服务器默认配置
        String jwkUri = environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        if (jwkUri == null) {
            // 从cloud配置中获取默认值，如果没有配置则使用默认值
            String defaultJwkUri = environment.getProperty("cloud.auth.oauth2.jwt.default-jwk-set-uri", "http://v3-auth/oauth2/jwks");
            defaultProperties.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", defaultJwkUri);
        }
        String issuerUri = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        if(issuerUri == null){
            // 从cloud配置中获取默认值，如果没有配置则使用默认值
            String defaultIssuerUri = environment.getProperty("cloud.auth.oauth2.jwt.default-issuer-uri", "http://v3-auth");
            defaultProperties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", defaultIssuerUri);
        }


        // 如果有默认属性需要设置，创建属性源并添加到最低优先级
        if (!defaultProperties.isEmpty()) {
            PropertySource<?> propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, defaultProperties);
            // 添加到最低优先级，这样用户配置可以覆盖默认值
            propertySources.addLast(propertySource);
        }
    }

    @Override
    public int getOrder() {
        // 在ConfigDataEnvironmentPostProcessor之后执行
        return Ordered.LOWEST_PRECEDENCE;
    }
} 