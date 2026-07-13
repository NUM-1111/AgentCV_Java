package com.jobagent.aspect;

import com.jobagent.model.OptimizationReport;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 调用全链路日志切面。
 *
 * <p>适配三阶段 Pipeline 架构：scoreOnly() 和 optimize()。
 */
@Aspect
@Component
public class AiCallLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AiCallLogAspect.class);

    @Pointcut("execution(* com.jobagent.service.ResumeOptimizationService.scoreOnly(..))")
    public void aiScoreMethod() {}

    @Pointcut("execution(* com.jobagent.service.ResumeOptimizationService.optimize(..))")
    public void aiOptimizeMethod() {}

    @Around("aiScoreMethod()")
    public Object logScoreCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startMs = System.currentTimeMillis();
        Object[] args = joinPoint.getArgs();
        int jdLen = args.length > 0 && args[0] instanceof String s ? s.length() : 0;
        int cvLen = args.length > 1 && args[1] instanceof String s ? s.length() : 0;

        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - startMs;
            if (result instanceof OptimizationReport r && r.scoreResult() != null) {
                log.info("[AI-CALL] score | jdLen={} resumeLen={} | cost={}ms | overallScore={}",
                        jdLen, cvLen, cost, r.scoreResult().overallScore());
            } else {
                log.info("[AI-CALL] score | jdLen={} resumeLen={} | cost={}ms | status=OK", jdLen, cvLen, cost);
            }
            return result;
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - startMs;
            log.warn("[AI-CALL] score | jdLen={} resumeLen={} | cost={}ms | ERROR={}",
                    jdLen, cvLen, cost, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    @Around("aiOptimizeMethod()")
    public Object logOptimizeCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startMs = System.currentTimeMillis();
        Object[] args = joinPoint.getArgs();
        int jdLen = args.length > 0 && args[0] instanceof String s ? s.length() : 0;
        int cvLen = args.length > 1 && args[1] instanceof String s ? s.length() : 0;

        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - startMs;
            if (result instanceof OptimizationReport r) {
                int bullets = r.rewrittenBulletPoints() != null ? r.rewrittenBulletPoints().size() : 0;
                log.info("[AI-CALL] optimize | jdLen={} resumeLen={} | cost={}ms | score={} rounds={} bullets={} approved={}",
                        jdLen, cvLen, cost,
                        r.scoreResult() != null ? r.scoreResult().overallScore() : -1,
                        r.reviewRounds(), bullets, r.criticApproved());
            } else {
                log.info("[AI-CALL] optimize | jdLen={} resumeLen={} | cost={}ms | status=OK", jdLen, cvLen, cost);
            }
            return result;
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - startMs;
            log.warn("[AI-CALL] optimize | jdLen={} resumeLen={} | cost={}ms | ERROR={}",
                    jdLen, cvLen, cost, ex.getClass().getSimpleName());
            throw ex;
        }
    }
}