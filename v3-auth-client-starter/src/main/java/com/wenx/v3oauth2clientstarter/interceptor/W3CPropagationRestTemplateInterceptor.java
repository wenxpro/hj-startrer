package com.wenx.v3oauth2clientstarter.interceptor;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * W3C traceparent 传播拦截器（log 标准化改造）
 *
 * <p>替代原手写 {@code TraceRestTemplateInterceptor}（自造 X-Trace-Id header）：</p>
 * <ul>
 *   <li>用 Micrometer {@link Propagator}（bridge-otel 提供 W3C tracecontext 实现）</li>
 *   <li>把当前 span 上下文按标准 W3C {@code traceparent} 注入出站请求，
 *       与 Jaeger/Zipkin/SkyWalking 兼容，跨服务链路完整</li>
 *   <li>无当前 span（如 @Scheduled 线程）时跳过，不影响调用</li>
 * </ul>
 *
 * @author wenx
 */
public class W3CPropagationRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private final Tracer tracer;
    private final Propagator propagator;

    public W3CPropagationRestTemplateInterceptor(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            HttpHeaders headers = request.getHeaders();
            propagator.inject(context, headers, (HttpHeaders carrier, String key, String value) -> carrier.set(key, value));
        }
        return execution.execute(request, body);
    }
}
