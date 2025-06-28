package com.wenx.v3dynamicdatasourcestarter.config;

/**
 * 动态数据源配置常量
 * 
 * @author wenx
 */
public class DynamicDataSourceConfig {

    /**
     * 默认数据源 - 系统基础功能（RBAC、系统配置等）
     * 当没有检测到租户ID时使用此数据源
     */
    public static final String DEFAULT_DATASOURCE = "v3-system";

    /**
     * 平台管理数据源 - 平台用户、租户管理等
     * 用于平台级别的管理功能
     */
    public static final String PLATFORM_DATASOURCE = "v3-platform";

    /**
     * 租户数据源前缀
     * 实际数据源名称为：v3-system-{tenantId}
     * 例如：v3-system-tenant001
     */
    public static final String TENANT_DATASOURCE_PREFIX = "v3-system-";

    /**
     * 构建租户数据源名称
     * 
     * @param tenantId 租户ID
     * @return 租户数据源名称
     */
    public static String buildTenantDataSource(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return DEFAULT_DATASOURCE;
        }
        return TENANT_DATASOURCE_PREFIX + tenantId;
    }

    /**
     * 检查是否为租户数据源
     * 
     * @param dataSourceName 数据源名称
     * @return 是否为租户数据源
     */
    public static boolean isTenantDataSource(String dataSourceName) {
        return dataSourceName != null && dataSourceName.startsWith(TENANT_DATASOURCE_PREFIX);
    }

    /**
     * 从数据源名称中提取租户ID
     * 
     * @param dataSourceName 数据源名称
     * @return 租户ID，如果不是租户数据源则返回null
     */
    public static String extractTenantId(String dataSourceName) {
        if (isTenantDataSource(dataSourceName)) {
            return dataSourceName.substring(TENANT_DATASOURCE_PREFIX.length());
        }
        return null;
    }
} 