package com.wenx.v3log.starter;

import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 云链路追踪自动配置（log-starter 标准化改造）
 *
 * <p>采用 Micrometer Tracing 标准方案，而非手写 OpenTelemetry SDK：</p>
 * <ul>
 *   <li>本类只提供 <b>SpanExporter</b>（Jaeger）——链路导出器</li>
 *   <li>Tracer / Propagator / MDC 关联 / W3C traceparent 传播由
 *       Spring Boot Actuator {@code TracingAutoConfiguration} + micrometer-tracing-bridge-otel 自动组装</li>
 *   <li>业务日志的 traceId/spanId 由 micrometer 自动写入 MDC（标准键 traceId/spanId），
 *       与 Jaeger 链路一致</li>
 * </ul>
 *
 * <p>配置：</p>
 * <pre>
 * cloud.tracing.enabled: true
 * cloud.jaeger.endpoint: http://localhost:14250
 * management.tracing.sampling.probability: 1.0
 * </pre>
 *
 * @author wenx
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({SpanExporter.class, io.micrometer.tracing.Tracer.class})
@ConditionalOnProperty(prefix = "cloud.tracing", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(CloudTracingProperties.class)
public class CloudTracingAutoConfiguration {

    /**
     * Jaeger SpanExporter：链路导出器，其余由 Spring Boot Tracing 自动配置接管
     */
    @Bean
    @ConditionalOnMissingBean(SpanExporter.class)
    public SpanExporter jaegerSpanExporter(CloudTracingProperties properties) {
        JaegerGrpcSpanExporter exporter = JaegerGrpcSpanExporter.builder()
                .setEndpoint(properties.getEndpoint())
                .build();
        log.info("链路追踪已启用：Jaeger exporter endpoint={}", properties.getEndpoint());
        return exporter;
    }
}
