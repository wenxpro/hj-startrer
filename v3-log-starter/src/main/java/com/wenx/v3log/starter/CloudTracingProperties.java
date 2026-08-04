package com.wenx.v3log.starter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 链路追踪配置（log-starter）
 *
 * @author wenx
 */
@Data
@ConfigurationProperties(prefix = "cloud.jaeger")
public class CloudTracingProperties {

    /**
     * Jaeger 采集器 gRPC 端点
     */
    private String endpoint = "http://localhost:14250";
}
