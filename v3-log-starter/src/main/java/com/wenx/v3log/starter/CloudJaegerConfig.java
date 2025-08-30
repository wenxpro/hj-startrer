package com.wenx.v3log.starter;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ResourceAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PreDestroy;
import java.time.Duration;

/**
 * Cloud Jaeger链路追踪配置
 * 
 * <p>为微服务云平台配置Jaeger追踪</p>
 * <p>支持通过配置文件启用/禁用Jaeger功能</p>
 * 
 * @author wenx
 * @version 1.0
 */
@Slf4j
@org.springframework.context.annotation.Configuration
@ConditionalOnClass({OpenTelemetry.class, Tracer.class})
@ConditionalOnProperty(name = "cloud.jaeger.enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnMissingClass("io.micrometer.tracing.brave.bridge.BraveTracer")
public class CloudJaegerConfig {

    @Value("${cloud.jaeger.service-name:v3-cloud-service}")
    private String serviceName;
    
    @Value("${cloud.jaeger.collector-endpoint:http://localhost:14250}")
    private String collectorEndpoint;
    
    @Value("${cloud.jaeger.sampler-probability:1.0}")
    private float samplerProbability;
    
    @Value("${cloud.jaeger.flush-interval:1000}")
    private int flushInterval;
    
    @Value("${cloud.jaeger.max-queue-size:100}")
    private int maxQueueSize;

    @Value("${cloud.jaeger.schedule-delay:500}")
    private int scheduleDelay;

    private SdkTracerProvider tracerProvider;

    /**
     * 配置OpenTelemetry SDK
     * 
     * @return OpenTelemetry实例
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry cloudOpenTelemetry() {
        // 配置资源信息
        Resource resource = Resource.getDefault()
                .merge(Resource.builder()
                        .put(ResourceAttributes.SERVICE_NAME, serviceName)
                        .put(ResourceAttributes.SERVICE_NAMESPACE, "cloud")
                        .build());
        
        // 配置Jaeger导出器
        JaegerGrpcSpanExporter jaegerExporter = JaegerGrpcSpanExporter.builder()
                .setEndpoint(collectorEndpoint)
                .build();
        
        // 配置采样器
        Sampler sampler = Sampler.traceIdRatioBased(samplerProbability);
        
        // 配置TracerProvider
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(jaegerExporter)
                        .setMaxExportBatchSize(maxQueueSize)
                        .setExporterTimeout(Duration.ofMillis(flushInterval))
                        .setScheduleDelay(Duration.ofMillis(scheduleDelay))
                        .build())
                .setResource(resource)
                .setSampler(sampler)
                .build();
        
        // 构建OpenTelemetry SDK
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        log.info("OpenTelemetry configured for service: {}, Jaeger endpoint: {}, samplerProbability: {}",
                serviceName, collectorEndpoint, samplerProbability);

        return openTelemetry;
    }
    

    
    /**
     * 配置Micrometer Tracer（用于Spring Cloud集成）
     * 
     * @param openTelemetry OpenTelemetry实例
     * @return Micrometer Tracer实例
     */
    @Bean
    @ConditionalOnMissingBean(Tracer.class)
    @ConditionalOnClass(OtelTracer.class)
    public Tracer cloudMicrometerTracer(OpenTelemetry openTelemetry) {
        if (openTelemetry == null) {
            log.warn("OpenTelemetry is null, returning null Micrometer Tracer");
            return null;
        }
        
        log.info("Creating Micrometer Tracer with OpenTelemetry bridge for service: {}", serviceName);
        
        // 创建当前跟踪上下文
        io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext otelCurrentTraceContext = 
                new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext();
        
        // 使用OpenTelemetry桥接到Micrometer Tracer
        return new OtelTracer(openTelemetry.getTracer(serviceName), 
                otelCurrentTraceContext,
                event -> {},  // 事件监听器
                new io.micrometer.tracing.otel.bridge.OtelBaggageManager(
                        otelCurrentTraceContext,
                        java.util.Collections.emptyList(),  // remoteFields
                        java.util.Collections.emptyList()   // tagFields
                ));
    }

    @PreDestroy
    public void shutdown() {
        if (tracerProvider != null) {
            tracerProvider.close();
            log.info("TracerProvider shut down, pending spans flushed");
        }
    }
}