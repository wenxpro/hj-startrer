package com.wenx.v3gateway.starter.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI聚合文档配置属性
 * 支持通过配置文件定义微服务列表
 */
@ConfigurationProperties(prefix = "cloud.gateway.openapi")
public class OpenApiProperties {

    /**
     * 是否启用OpenAPI文档聚合
     */
    private boolean enabled = true;

    /**
     * 微服务列表配置
     */
    private List<ServiceConfig> services = new ArrayList<>();

    /**
     * 是否启用服务发现
     */
    private boolean discoveryEnabled = true;

    /**
     * 排除的服务列表（不聚合文档）
     */
    private List<String> excludedServices = new ArrayList<>();

    /**
     * 默认构造函数，初始化默认服务配置
     */
    public OpenApiProperties() {
        initDefaultServices();
    }

    /**
     * 初始化默认服务配置
     */
    private void initDefaultServices() {
        services.add(new ServiceConfig("v3-gateway", "网关服务", "/v3/api-docs"));
        services.add(new ServiceConfig("v3-auth", "认证服务", "/auth/v3/api-docs"));
        services.add(new ServiceConfig("v3-system", "系统服务", "/system/v3/api-docs"));
        services.add(new ServiceConfig("v3-batch", "批处理服务", "/batch/v3/api-docs"));
        services.add(new ServiceConfig("v3-storage", "存储服务", "/storage/v3/api-docs"));
        services.add(new ServiceConfig("v3-workflow", "工作流服务", "/workflow/v3/api-docs"));
        
        // 默认排除nacos相关服务
        excludedServices.add("nacos");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ServiceConfig> getServices() {
        return services;
    }

    public void setServices(List<ServiceConfig> services) {
        this.services = services;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    public List<String> getExcludedServices() {
        return excludedServices;
    }

    public void setExcludedServices(List<String> excludedServices) {
        this.excludedServices = excludedServices;
    }

    /**
     * 服务配置内部类
     */
    public static class ServiceConfig {
        /**
         * 服务ID
         */
        private String serviceId;

        /**
         * 服务显示名称
         */
        private String displayName;

        /**
         * API文档URL路径
         */
        private String url;

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 服务版本
         */
        private String version = "1.0.0";

        public ServiceConfig() {
        }

        public ServiceConfig(String serviceId, String displayName, String url) {
            this.serviceId = serviceId;
            this.displayName = displayName;
            this.url = url;
        }

        public String getServiceId() {
            return serviceId;
        }

        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        /**
         * 获取完整的显示名称
         */
        public String getFullDisplayName() {
            return serviceId + " (" + displayName + ")";
        }
    }
} 