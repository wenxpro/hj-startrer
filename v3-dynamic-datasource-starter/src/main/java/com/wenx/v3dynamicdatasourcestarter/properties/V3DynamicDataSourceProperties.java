package com.wenx.v3dynamicdatasourcestarter.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * V3 动态数据源配置属性
 * 
 * @author wenx
 */
@Data
@ConfigurationProperties(prefix = "cloud.dynamic-datasource")
public class V3DynamicDataSourceProperties {

    /**
     * 是否启用动态数据源
     */
    private boolean enabled = true;

    /**
     * 租户检测配置
     */
    private TenantDetection tenantDetection = new TenantDetection();

    /**
     * 拦截器配置
     */
    private Interceptor interceptor = new Interceptor();

    /**
     * 租户检测配置
     */
    @Data
    public static class TenantDetection {

        /**
         * 租户ID请求头名称
         */
        private String headerName = "X-Tenant-Id";

        /**
         * 租户ID请求参数名称
         */
        private String paramName = "tenantId";

        /**
         * 是否从JWT Token中解析租户ID
         */
        private boolean enableJwtParsing = false;

        /**
         * JWT Token中租户ID的声明名称
         */
        private String jwtTenantClaim = "tenantId";
    }

    /**
     * 拦截器配置
     */
    @Data
    public static class Interceptor {

        /**
         * 是否启用租户拦截器
         */
        private boolean enabled = true;

        /**
         * 拦截路径模式
         */
        private List<String> includePatterns = List.of("/**");

        /**
         * 排除路径模式
         */
        private List<String> excludePatterns = new ArrayList<>(List.of(
                "/platform/**",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/actuator/**",
                "/error",
                "/favicon.ico",
                "/webjars/**",
                "/static/**",
                "/public/**"
        ));

        /**
         * 拦截器执行顺序
         */
        private int order = 0;
    }
} 