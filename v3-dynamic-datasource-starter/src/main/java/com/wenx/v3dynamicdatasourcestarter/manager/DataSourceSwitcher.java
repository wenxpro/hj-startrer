package com.wenx.v3dynamicdatasourcestarter.manager;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DataSourceProperty;
import com.wenx.v3dynamicdatasourcestarter.config.DynamicDataSourceConfig;
import com.wenx.v3dynamicdatasourcestarter.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态数据源切换工具类
 * 支持运行时动态添加和切换租户数据源
 * 
 * @author wenx
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceSwitcher {

    private final DataSource dataSource;
    private final DefaultDataSourceCreator dataSourceCreator;

    /**
     * 动态添加租户数据源
     *
     * @param tenantId 租户ID
     * @param url      数据库连接URL
     * @param username 用户名
     * @param password 密码
     */
    public void addTenantDataSource(String tenantId, String url, String username, String password) {
        String dataSourceKey = DynamicDataSourceConfig.buildTenantDataSource(tenantId);
        
        // 检查数据源是否已存在
        if (isDataSourceExists(dataSourceKey)) {
            log.debug("租户数据源已存在: {} (租户ID: {})", dataSourceKey, tenantId);
            return;
        }

        try {
            // 创建数据源配置
            DataSourceProperty dataSourceProperty = new DataSourceProperty();
            dataSourceProperty.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSourceProperty.setUrl(url);
            dataSourceProperty.setUsername(username);
            dataSourceProperty.setPassword(password);
            dataSourceProperty.setType(com.alibaba.druid.pool.DruidDataSource.class);

            // 创建数据源
            DataSource newDataSource = dataSourceCreator.createDataSource(dataSourceProperty);
            
            // 添加到动态数据源
            DynamicRoutingDataSource ds = (DynamicRoutingDataSource) dataSource;
            ds.addDataSource(dataSourceKey, newDataSource);
            
            log.info("成功添加租户数据源: {} (租户ID: {})", dataSourceKey, tenantId);
        } catch (Exception e) {
            log.error("添加租户数据源失败: {} (租户ID: {})", dataSourceKey, tenantId, e);
            throw new RuntimeException("添加租户数据源失败: " + dataSourceKey, e);
        }
    }

    /**
     * 使用DataSourceProperty添加租户数据源
     *
     * @param tenantId           租户ID
     * @param dataSourceProperty 数据源配置
     */
    public void addTenantDataSource(String tenantId, DataSourceProperty dataSourceProperty) {
        String dataSourceKey = DynamicDataSourceConfig.buildTenantDataSource(tenantId);
        
        // 检查数据源是否已存在
        if (isDataSourceExists(dataSourceKey)) {
            log.debug("租户数据源已存在: {} (租户ID: {})", dataSourceKey, tenantId);
            return;
        }

        try {
            // 创建数据源
            DataSource newDataSource = dataSourceCreator.createDataSource(dataSourceProperty);
            
            // 添加到动态数据源
            DynamicRoutingDataSource ds = (DynamicRoutingDataSource) dataSource;
            ds.addDataSource(dataSourceKey, newDataSource);
            
            log.info("成功添加租户数据源: {} (租户ID: {})", dataSourceKey, tenantId);
        } catch (Exception e) {
            log.error("添加租户数据源失败: {} (租户ID: {})", dataSourceKey, tenantId, e);
            throw new RuntimeException("添加租户数据源失败: " + dataSourceKey, e);
        }
    }

    /**
     * 移除租户数据源
     *
     * @param tenantId 租户ID
     */
    public void removeTenantDataSource(String tenantId) {
        String dataSourceKey = DynamicDataSourceConfig.buildTenantDataSource(tenantId);
        
        try {
            DynamicRoutingDataSource ds = (DynamicRoutingDataSource) dataSource;
            ds.removeDataSource(dataSourceKey);
            log.info("成功移除租户数据源: {} (租户ID: {})", dataSourceKey, tenantId);
        } catch (Exception e) {
            log.error("移除租户数据源失败: {} (租户ID: {})", dataSourceKey, tenantId, e);
        }
    }

    /**
     * 检查数据源是否存在
     *
     * @param dataSourceKey 数据源key
     * @return 是否存在
     */
    public boolean isDataSourceExists(String dataSourceKey) {
        try {
            DynamicRoutingDataSource ds = (DynamicRoutingDataSource) dataSource;
            Set<String> currentDataSources = ds.getDataSources().keySet();
            return currentDataSources.contains(dataSourceKey);
        } catch (Exception e) {
            log.error("检查数据源是否存在失败: {}", dataSourceKey, e);
            return false;
        }
    }

    /**
     * 检查租户数据源是否存在
     *
     * @param tenantId 租户ID
     * @return 是否存在
     */
    public boolean isTenantDataSourceExists(String tenantId) {
        String dataSourceKey = DynamicDataSourceConfig.buildTenantDataSource(tenantId);
        return isDataSourceExists(dataSourceKey);
    }

    /**
     * 获取租户数据源key
     *
     * @param tenantId 租户ID
     * @return 数据源key
     */
    public static String getTenantDataSourceKey(String tenantId) {
        return DynamicDataSourceConfig.buildTenantDataSource(tenantId);
    }

    /**
     * 获取所有数据源名称
     *
     * @return 数据源名称集合
     */
    public Set<String> getAllDataSourceNames() {
        try {
            DynamicRoutingDataSource ds = (DynamicRoutingDataSource) dataSource;
            return ds.getDataSources().keySet();
        } catch (Exception e) {
            log.error("获取所有数据源名称失败", e);
            return Set.of();
        }
    }

    /**
     * 获取所有租户ID
     *
     * @return 租户ID集合
     */
    public Set<String> getAllTenantIds() {
        return getAllDataSourceNames().stream()
                .filter(DynamicDataSourceConfig::isTenantDataSource)
                .map(DynamicDataSourceConfig::extractTenantId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 根据租户上下文获取当前应该使用的数据源名称
     *
     * @return 数据源名称
     */
    public String getCurrentDataSourceName() {
        return TenantContext.getCurrentDataSource();
    }
} 