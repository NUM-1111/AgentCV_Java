package com.jobagent.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * TraceIdFilter — 请求追踪 ID 注入器
 *
 * 核心概念：MDC (Mapped Diagnostic Context)
 * ==========================================
 * MDC 是 SLF4J 提供的「线程本地存储」，可以在当前线程的任意位置往日志里塞额外字段。
 * 我们在这里把 traceId 放进去，logback-spring.xml 里用 %X{traceId} 就能自动打印出来。
 *
 * 为什么用 OncePerRequestFilter？
 * ================================
 * Spring 的 Filter 链可能因为 forward/include 被多次触发，
 * OncePerRequestFilter 保证每个 HTTP 请求只执行一次过滤逻辑。
 *
 * 为什么要在 finally 里清理 MDC？
 * =================================
 * Spring Boot 使用线程池处理请求，线程会被复用。
 * 如果不清理，上一个请求的 traceId 会「污染」下一个请求的日志。
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key，与 logback-spring.xml 中的 %X{traceId} 对应 */
    public static final String TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 生成短 traceId：取 UUID 前8位，足够区分请求，又不会太长
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        try {
            // 注入 MDC，此线程后续所有日志都会携带这个 traceId
            MDC.put(TRACE_ID_KEY, traceId);

            // 也写入响应头，方便前端/调用方排查问题
            response.setHeader("X-Trace-Id", traceId);

            // 继续执行后续 Filter 和 Controller
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理！防止线程复用时 traceId 串号
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
