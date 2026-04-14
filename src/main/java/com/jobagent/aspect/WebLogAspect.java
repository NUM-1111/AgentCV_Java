package com.jobagent.aspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * WebLogAspect — HTTP 层全链路日志切面
 *
 * AOP 核心概念速览：
 * ==================
 *
 * 1. @Aspect — 声明这是一个切面类（AOP 的核心配置单元）
 *    切面 = 切点（在哪里拦截）+ 通知（拦截后做什么）
 *
 * 2. @Pointcut — 定义切点表达式，描述「拦截哪些方法」
 *    @within(RestController) 表示：拦截所有被 @RestController 注解的类里的所有方法
 *    等价写法：execution(* com.jobagent.controller.*.*(..))
 *
 * 3. @Around — 环绕通知，最强大的通知类型
 *    可以在目标方法执行前后插入逻辑，还能捕获返回值和异常
 *    必须调用 joinPoint.proceed() 才会真正执行目标方法
 *
 * 4. ProceedingJoinPoint — 代表被拦截的连接点（即目标方法）
 *    .proceed()         → 执行目标方法，返回其返回值
 *    .getSignature()    → 获取方法签名（类名、方法名）
 *    .getArgs()         → 获取方法入参数组
 *
 * 日志格式：
 *   [WEB] POST /api/v1/match/evaluate | args={...} | cost=1234ms | status=OK
 *   [WEB] POST /api/v1/match/evaluate | args={...} | cost=56ms   | ERROR=IllegalArgumentException: jdText 不能为空
 */
@Aspect
@Component
public class WebLogAspect {

    private static final Logger log = LoggerFactory.getLogger(WebLogAspect.class);

    private final ObjectMapper objectMapper;

    public WebLogAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 切点：所有被 @RestController 注解的类中的所有方法。
     *
     * @within 与 @annotation 的区别：
     *   @within(X)     → 类上有 @X 注解，则类里所有方法都被拦截
     *   @annotation(X) → 方法上有 @X 注解，只拦截该方法
     */
    @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
    public void restControllerMethods() {}

    /**
     * 环绕通知：在 restControllerMethods() 切点匹配的方法上执行。
     *
     * 执行流程：
     *   1. 记录开始时间
     *   2. 获取 HTTP 请求信息（方法、URI、入参）
     *   3. joinPoint.proceed() → 真正执行 Controller 方法
     *   4. 记录耗时和结果
     *   5. 若有异常，记录异常信息后重新抛出（不吞异常！）
     */
    @Around("restControllerMethods()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long startMs = System.currentTimeMillis();

        // 从 Spring 的请求上下文中获取当前 HTTP 请求对象
        HttpServletRequest request = currentRequest();
        String method = request != null ? request.getMethod() : "UNKNOWN";
        String uri    = request != null ? request.getRequestURI() : joinPoint.getSignature().toShortString();

        // 将入参序列化为 JSON 摘要（超过200字符截断，防止日志爆炸）
        String argsSummary = summarizeArgs(joinPoint.getArgs());

        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - startMs;
            log.info("[WEB] {} {} | args={} | cost={}ms | status=OK", method, uri, argsSummary, cost);
            return result;
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - startMs;
            log.warn("[WEB] {} {} | args={} | cost={}ms | ERROR={}:{}",
                    method, uri, argsSummary, cost,
                    ex.getClass().getSimpleName(), ex.getMessage());
            // 重新抛出，让 GlobalExceptionHandler 继续处理
            throw ex;
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        try {
            String json = objectMapper.writeValueAsString(args);
            return json.length() > 200 ? json.substring(0, 200) + "..." : json;
        } catch (JsonProcessingException e) {
            return "[unserializable]";
        }
    }
}
