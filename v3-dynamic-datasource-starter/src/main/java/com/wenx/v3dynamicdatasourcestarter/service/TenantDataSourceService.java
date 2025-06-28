package com.wenx.v3dynamicdatasourcestarter.service;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DataSourceProperty;

import java.util.Set;

/**
 * 租户数据源管理服务接口
 * 
 * @author wenx
 */
public interface TenantDataSourceService {

    /**
     * 创建租户数据源
     * 
     * @param tenantId 租户ID
     * @param dbHost   数据库主机
     * @param dbPort   数据库端口
     * @param dbName   数据库名称
     * @param username 用户名
     * @param password 密码
     */
    void createTenantDataSource(String tenantId, String dbHost, Integer dbPort, 
                               String dbName, String username, String password);

    /**
     * 创建租户数据源（使用配置对象）
     * 
     * @param tenantId           租户ID
     * @param dataSourceProperty 数据源配置
     */
    void createTenantDataSource(String tenantId, DataSourceProperty dataSourceProperty);

    /**
     * 删除租户数据源
     * 
     * @param tenantId 租户ID
     */
    void removeTenantDataSource(String tenantId);

    /**
     * 检查租户数据源是否存在
     * 
     * @param tenantId 租户ID
     * @return 是否存在
     */
    boolean existsTenantDataSource(String tenantId);

    /**
     * 获取所有租户ID
     * 
     * @return 租户ID集合
     */
    Set<String> getAllTenantIds();

    /**
     * 测试租户数据源连接
     * 
     * @param tenantId 租户ID
     * @return 连接是否成功
     */
    boolean testTenantDataSourceConnection(String tenantId);

    /**
     * 刷新租户数据源
     * 重新加载租户数据源配置
     * 
     * @param tenantId 租户ID
     */
    void refreshTenantDataSource(String tenantId);
} 