package com.wenx.v3dynamicdatasourcestarter.context;

import com.wenx.v3dynamicdatasourcestarter.config.DynamicDataSourceConfig;

/**
 * 租户上下文管理器
 * 用于在请求处理过程中存储和获取当前租户ID
 * 
 * @author wenx
 */
public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * 设置当前租户ID
     * 
     * @param tenantId 租户ID
     */
    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * 获取当前租户ID
     * 
     * @return 租户ID，如果没有设置则返回null
     */
    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    /**
     * 清除当前租户ID
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * 检查是否设置了租户ID
     * 
     * @return 是否有租户ID
     */
    public static boolean hasTenant() {
        String tenantId = CURRENT_TENANT.get();
        return tenantId != null && !tenantId.trim().isEmpty();
    }

    /**
     * 获取当前应该使用的数据源名称
     * 
     * @return 数据源名称
     */
    public static String getCurrentDataSource() {
        String tenantId = getCurrentTenant();
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            return DynamicDataSourceConfig.buildTenantDataSource(tenantId);
        }
        return DynamicDataSourceConfig.DEFAULT_DATASOURCE;
    }
} 