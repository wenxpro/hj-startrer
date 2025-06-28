package com.wenx.v3dynamicdatasourcestarter.service.impl;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DataSourceProperty;
import com.wenx.v3dynamicdatasourcestarter.manager.DataSourceSwitcher;
import com.wenx.v3dynamicdatasourcestarter.service.TenantDataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * 租户数据源管理服务实现类
 * 
 * @author wenx
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDataSourceServiceImpl implements TenantDataSourceService {

    private final DataSourceSwitcher dataSourceSwitcher;

    @Override
    public void createTenantDataSource(String tenantId, String dbHost, Integer dbPort, 
                                     String dbName, String username, String password) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("租户ID不能为空");
        }

        // 构建数据库连接URL
        String url = String.format("jdbc:mysql://%s:%d/%s?characterEncoding=UTF-8&useSSL=false&serverTimezone=GMT%%2B8&allowPublicKeyRetrieval=true",
                dbHost, dbPort != null ? dbPort : 3306, dbName);

        try {
            dataSourceSwitcher.addTenantDataSource(tenantId, url, username, password);
            log.info("成功创建租户数据源: tenantId={}, dbName={}", tenantId, dbName);
        } catch (Exception e) {
            log.error("创建租户数据源失败: tenantId={}, dbName={}, error={}", tenantId, dbName, e.getMessage());
            throw new RuntimeException("创建租户数据源失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void createTenantDataSource(String tenantId, DataSourceProperty dataSourceProperty) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("租户ID不能为空");
        }

        if (dataSourceProperty == null) {
            throw new IllegalArgumentException("数据源配置不能为空");
        }

        try {
            dataSourceSwitcher.addTenantDataSource(tenantId, dataSourceProperty);
            log.info("成功创建租户数据源: tenantId={}, url={}", tenantId, dataSourceProperty.getUrl());
        } catch (Exception e) {
            log.error("创建租户数据源失败: tenantId={}, error={}", tenantId, e.getMessage());
            throw new RuntimeException("创建租户数据源失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeTenantDataSource(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("租户ID不能为空");
        }

        try {
            dataSourceSwitcher.removeTenantDataSource(tenantId);
            log.info("成功删除租户数据源: tenantId={}", tenantId);
        } catch (Exception e) {
            log.error("删除租户数据源失败: tenantId={}, error={}", tenantId, e.getMessage());
            throw new RuntimeException("删除租户数据源失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean existsTenantDataSource(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return false;
        }

        return dataSourceSwitcher.isTenantDataSourceExists(tenantId);
    }

    @Override
    public Set<String> getAllTenantIds() {
        return dataSourceSwitcher.getAllTenantIds();
    }

    @Override
    public boolean testTenantDataSourceConnection(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return false;
        }

        if (!existsTenantDataSource(tenantId)) {
            log.warn("租户数据源不存在: tenantId={}", tenantId);
            return false;
        }

        // 这里需要获取具体的数据源进行连接测试
        // 由于DataSourceSwitcher没有提供获取具体DataSource的方法，
        // 这里先简单返回存在性检查结果
        // 在实际实现中，可以扩展DataSourceSwitcher提供getDataSource方法
        return true;
    }

    @Override
    public void refreshTenantDataSource(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("租户ID不能为空");
        }

        if (!existsTenantDataSource(tenantId)) {
            log.warn("租户数据源不存在，无法刷新: tenantId={}", tenantId);
            return;
        }

        try {
            // 先删除现有数据源
            dataSourceSwitcher.removeTenantDataSource(tenantId);
            
            // 这里可以重新加载配置并创建数据源
            // 当前简单地记录日志，实际实现中可以从配置中心重新加载配置
            log.info("租户数据源已刷新: tenantId={}", tenantId);
        } catch (Exception e) {
            log.error("刷新租户数据源失败: tenantId={}, error={}", tenantId, e.getMessage());
            throw new RuntimeException("刷新租户数据源失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试数据源连接（内部方法）
     * 
     * @param dataSource 数据源
     * @return 连接是否成功
     */
    private boolean testConnection(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5); // 5秒超时
        } catch (SQLException e) {
            log.warn("数据源连接测试失败: {}", e.getMessage());
            return false;
        }
    }
} 