package com.wenx.v3dynamicdatasourcestarter.autoconfigure;

import com.wenx.v3dynamicdatasourcestarter.interceptor.TenantInterceptor;
import com.wenx.v3dynamicdatasourcestarter.manager.DataSourceSwitcher;
import com.wenx.v3dynamicdatasourcestarter.service.TenantDataSourceService;
import com.wenx.v3dynamicdatasourcestarter.service.impl.TenantDataSourceServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * V3 动态数据源自动配置类
 * 
 * @author wenx
 */
@Slf4j
@AutoConfiguration
@ComponentScan(basePackages = "com.wenx.v3dynamicdatasourcestarter")
@ConditionalOnClass({DataSourceSwitcher.class})
@ConditionalOnProperty(prefix = "cloud.dynamic-datasource", name = "enabled", havingValue = "true", matchIfMissing = true)
public class V3DynamicDataSourceAutoConfiguration {

    /**
     * 租户数据源服务
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantDataSourceService tenantDataSourceService(DataSourceSwitcher dataSourceSwitcher) {
        log.info("初始化租户数据源服务");
        return new TenantDataSourceServiceImpl(dataSourceSwitcher);
    }

    /**
     * Web MVC 配置 - 注册租户拦截器
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = "v3DynamicDataSourceWebMvcConfigurer")
    public WebMvcConfigurer v3DynamicDataSourceWebMvcConfigurer(TenantInterceptor tenantInterceptor) {
        log.info("注册V3动态数据源Web MVC配置");
        
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(tenantInterceptor)
                        .addPathPatterns("/**")  // 拦截所有请求
                        .excludePathPatterns(
                                "/api/v3/platform/**",      // 排除平台管理请求
                                "/swagger-ui/**",    // 排除Swagger UI
                                "/v3/api-docs/**",   // 排除API文档
                                "/actuator/**",      // 排除监控端点
                                "/error",            // 排除错误页面
                                "/favicon.ico",      // 排除图标请求
                                "/webjars/**",       // 排除静态资源
                                "/static/**",        // 排除静态资源
                                "/public/**"         // 排除公共资源
                        );
                log.info("V3动态数据源租户拦截器已注册");
            }
        };
    }
} 