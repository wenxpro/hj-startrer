package com.wenx.v3oauth2clientstarter.interceptor;

import com.wenx.v3core.util.HttpServletUtil;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * RestTemplate 追踪拦截器
 * 用于在 RestTemplate 调用时自动传递 Trace ID 和 Request ID
 * 
 * @author wenx
 * @since 1.0
 */
public class TraceRestTemplateInterceptor implements ClientHttpRequestInterceptor {
    
    /**
     * Trace ID 请求头名称
     */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    
    /**
     * Request ID 请求头名称
     */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    

    
    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request, 
                                      @NonNull byte[] body, 
                                      @NonNull ClientHttpRequestExecution execution) throws IOException {
        
        // 获取统一的 Trace ID（从当前请求头中获取）
        String traceId = getTraceIdFromCurrentRequest();
        if (traceId != null) {
            request.getHeaders().add(TRACE_ID_HEADER, traceId);
        }
        
        // 获取 Request ID（从 MDC 中获取）
        String requestId = MDC.get("request-id");
        if (requestId != null) {
            request.getHeaders().add(REQUEST_ID_HEADER, requestId);
        }
        

        
        return execution.execute(request, body);
    }
    
    /**
     * 从当前HTTP请求中获取Trace ID
     */
    private String getTraceIdFromCurrentRequest() {
        var request = HttpServletUtil.getRequest();
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null) {
            traceId = request.getHeader("traceid");
        }
        if (traceId == null) {
            traceId = request.getHeader("trace-id");
        }
        return traceId;
    }
}