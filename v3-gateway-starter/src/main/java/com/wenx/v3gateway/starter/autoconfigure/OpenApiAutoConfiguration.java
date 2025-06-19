package com.wenx.v3gateway.starter.autoconfigure;

import com.wenx.v3gateway.starter.properties.OpenApiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * OpenAPI文档聚合自动配置类
 * 支持数组方式配置微服务列表，提供灵活的文档聚合功能
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.webflux.ui.SwaggerWelcomeWebFlux")
@ConditionalOnProperty(name = "cloud.gateway.openapi.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OpenApiProperties.class)
public class OpenApiAutoConfiguration {

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    private final OpenApiProperties openApiProperties;

    public OpenApiAutoConfiguration(OpenApiProperties openApiProperties) {
        this.openApiProperties = openApiProperties;
    }

    /**
     * 配置Swagger UI资源路由
     */
    @Bean
    public RouterFunction<ServerResponse> openApiRouterFunction() {
        return route(GET("/swagger-resources"), 
                request -> ServerResponse.ok().bodyValue(getSwaggerResources()))
                .andRoute(GET("/swagger-resources/configuration/ui"), 
                        request -> ServerResponse.ok().bodyValue(getUiConfiguration()))
                .andRoute(GET("/swagger-resources/configuration/security"), 
                        request -> ServerResponse.ok().bodyValue(getSecurityConfiguration()))
                .andRoute(GET("/v3/api-docs/swagger-config"), 
                        request -> ServerResponse.ok().bodyValue(buildSwaggerConfig()))
                .andRoute(GET("/v3/api-docs/services"), 
                        request -> ServerResponse.ok().bodyValue(getConfiguredServices()));
    }

    /**
     * 获取配置的服务列表
     */
    private List<OpenApiProperties.ServiceConfig> getConfiguredServices() {
        return openApiProperties.getServices().stream()
                .filter(OpenApiProperties.ServiceConfig::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * 获取Swagger资源列表（基于配置的服务数组）
     */
    private List<Map<String, Object>> getSwaggerResources() {
        List<Map<String, Object>> resources = new ArrayList<>();
        
        // 从配置的服务数组中获取服务
        for (OpenApiProperties.ServiceConfig serviceConfig : getConfiguredServices()) {
            addSwaggerResource(resources, 
                    serviceConfig.getServiceId(), 
                    serviceConfig.getDisplayName(), 
                    serviceConfig.getUrl());
        }
        
        // 如果启用了服务发现，动态添加发现的服务
        if (openApiProperties.isDiscoveryEnabled() && discoveryClient != null) {
            addDiscoveredServices(resources);
        }
        
        return resources;
    }

    /**
     * 添加从服务发现中获取的服务
     */
    private void addDiscoveredServices(List<Map<String, Object>> resources) {
        try {
            List<String> discoveredServices = discoveryClient.getServices();
            List<String> configuredServiceIds = openApiProperties.getServices().stream()
                    .map(OpenApiProperties.ServiceConfig::getServiceId)
                    .collect(Collectors.toList());
            
            for (String serviceId : discoveredServices) {
                // 跳过已配置的服务和排除的服务
                if (configuredServiceIds.contains(serviceId) || isExcludedService(serviceId)) {
                    continue;
                }
                
                String displayName = getServiceDisplayName(serviceId);
                String url = "/" + serviceId + "/v3/api-docs";
                addSwaggerResource(resources, serviceId, displayName, url);
            }
        } catch (Exception e) {
            // 服务发现失败时不影响已配置的服务
        }
    }

    /**
     * 检查服务是否被排除
     */
    private boolean isExcludedService(String serviceId) {
        return openApiProperties.getExcludedServices().stream()
                .anyMatch(excluded -> serviceId.startsWith(excluded));
    }

    /**
     * 添加Swagger资源
     */
    private void addSwaggerResource(List<Map<String, Object>> resources, 
                                   String serviceId, String displayName, String url) {
        Map<String, Object> resource = new HashMap<>();
        resource.put("name", serviceId + " (" + displayName + ")");
        resource.put("url", url);
        resource.put("swaggerVersion", "3.0.3");
        resource.put("location", url);
        resources.add(resource);
    }

    /**
     * 获取服务的显示名称
     */
    private String getServiceDisplayName(String serviceId) {
        // 先从配置中查找
        return openApiProperties.getServices().stream()
                .filter(service -> serviceId.equals(service.getServiceId()))
                .findFirst()
                .map(OpenApiProperties.ServiceConfig::getDisplayName)
                .orElse("微服务");
    }

    /**
     * 构建Swagger配置信息
     */
    private Map<String, Object> buildSwaggerConfig() {
        Map<String, Object> config = new HashMap<>();
        
        // 基础UI配置
        config.put("configUrl", "/v3/api-docs/swagger-config");
        config.put("domId", "#swagger-ui");
        config.put("layout", "StandaloneLayout");
        config.put("deepLinking", true);
        config.put("displayOperationId", false);
        config.put("defaultModelsExpandDepth", 1);
        config.put("defaultModelExpandDepth", 1);
        config.put("defaultModelRendering", "example");
        config.put("displayRequestDuration", true);
        config.put("docExpansion", "none");
        config.put("filter", "");
        config.put("maxDisplayedTags", -1);
        config.put("operationsSorter", "alpha");
        config.put("showExtensions", false);
        config.put("showCommonExtensions", false);
        config.put("tagsSorter", "alpha");
        config.put("supportedSubmitMethods", new String[]{"get", "put", "post", "delete", "options", "head", "patch", "trace"});
        config.put("validatorUrl", "");
        
        // 动态构建服务文档URL列表
        List<Map<String, String>> urls = buildServiceUrls();
        config.put("urls", urls);
        
        return config;
    }

    /**
     * 构建所有微服务的API文档URL（基于配置的服务数组）
     */
    private List<Map<String, String>> buildServiceUrls() {
        List<Map<String, String>> urls = new ArrayList<>();
        
        // 从配置的服务数组中构建URL
        for (OpenApiProperties.ServiceConfig serviceConfig : getConfiguredServices()) {
            addServiceUrl(urls, 
                    serviceConfig.getServiceId(), 
                    serviceConfig.getDisplayName(), 
                    serviceConfig.getUrl());
        }
        
        // 如果启用了服务发现，动态添加发现的服务
        if (openApiProperties.isDiscoveryEnabled() && discoveryClient != null) {
            addDiscoveredServiceUrls(urls);
        }
        
        return urls;
    }

    /**
     * 添加从服务发现中获取的服务URL
     */
    private void addDiscoveredServiceUrls(List<Map<String, String>> urls) {
        try {
            List<String> discoveredServices = discoveryClient.getServices();
            List<String> configuredServiceIds = openApiProperties.getServices().stream()
                    .map(OpenApiProperties.ServiceConfig::getServiceId)
                    .collect(Collectors.toList());
            
            for (String serviceId : discoveredServices) {
                // 跳过已配置的服务和排除的服务
                if (configuredServiceIds.contains(serviceId) || isExcludedService(serviceId)) {
                    continue;
                }
                
                String displayName = getServiceDisplayName(serviceId);
                String url = "/" + serviceId + "/v3/api-docs";
                addServiceUrl(urls, serviceId, displayName, url);
            }
        } catch (Exception e) {
            // 服务发现失败时不影响已配置的服务
        }
    }

    /**
     * 添加服务URL
     */
    private void addServiceUrl(List<Map<String, String>> urls, 
                              String serviceId, String displayName, String url) {
        Map<String, String> serviceDoc = new HashMap<>();
        serviceDoc.put("name", serviceId + " (" + displayName + ")");
        serviceDoc.put("url", url);
        urls.add(serviceDoc);
    }

    /**
     * 获取UI配置
     */
    private Map<String, Object> getUiConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("deepLinking", true);
        config.put("displayOperationId", false);
        config.put("defaultModelsExpandDepth", 1);
        config.put("defaultModelExpandDepth", 1);
        config.put("defaultModelRendering", "example");
        config.put("displayRequestDuration", true);
        config.put("docExpansion", "none");
        config.put("filter", false);
        config.put("maxDisplayedTags", null);
        config.put("operationsSorter", "alpha");
        config.put("showExtensions", false);
        config.put("showCommonExtensions", false);
        config.put("tagsSorter", "alpha");
        config.put("supportedSubmitMethods", new String[]{"get", "put", "post", "delete", "options", "head", "patch", "trace"});
        config.put("validatorUrl", "");
        return config;
    }

    /**
     * 获取安全配置
     */
    private Map<String, Object> getSecurityConfiguration() {
        return new HashMap<>();
    }
} 