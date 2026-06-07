package com.jobagent.aspect;

import com.jobagent.model.MatchReport;
import com.jobagent.service.RewriteCoordinatorService.RewriteResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AiCallLogAspect — AI 调用全链路日志切面
 *
 * 为什么要单独给 AI 调用做一个切面？
 * =====================================
 * AI 调用与普通业务调用有本质区别：
 *   - 耗时长（秒级，而非毫秒级）
 *   - 输入/输出需要特殊处理（不能打印全文，但要记录关键指标）
 *   - 失败模式多样（token超限、解析失败、网络超时）
 *   - 是整个系统最核心的可观测点
 *
 * 切点精准性原则：
 * ================
 * 这里用 execution 表达式精准切入两个 AI 调用的入口方法，
 * 而不是切整个 service 包，原因：
 *   1. 只拦截真正执行 LLM 调用的方法，避免 TextTrimmer、TokenEstimator 产生噪音
 *   2. match 和 rewrite 两个流程的入参/出参结构不同，需要分别提取字段
 *   3. 精准切点 = 精准可观测，面试时也能体现你对业务的理解
 *
 * execution 表达式语法：
 *   execution(返回类型 包名.类名.方法名(参数类型))
 *   * 表示任意，.. 表示任意数量的参数
 *
 * 日志格式：
 *   match 成功: [AI-CALL] match | jdLen=800 resumeLen=1200 | cost=3456ms | score=85 matchedSkills=5 missingSkills=2
 *   match 失败: [AI-CALL] match | jdLen=800 resumeLen=1200 | cost=1200ms | ERROR=...
 *   rewrite 成功: [AI-CALL] rewrite | jdLen=1000 projectLen=600 | cost=4567ms | rounds=2 bulletPoints=3 optimizationReasons=3
 *   rewrite 失败: [AI-CALL] rewrite | jdLen=1000 projectLen=600 | cost=1200ms | ERROR=...
 */
@Aspect
@Component
public class AiCallLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AiCallLogAspect.class);

    /**
     * 切点：精准切入 MatchEvaluationFacadeService 的 evaluate 方法。
     *
     * 为什么不用 @within 切整个 service 包？
     * 因为 @AiService 接口（JdAnalyzeService 等）由 LangChain4j 动态代理实现，
     * Spring AOP 对动态代理的切入有限制，精准 execution 更可靠。
     */
    @Pointcut("execution(* com.jobagent.service.MatchEvaluationFacadeService.evaluate(..))")
    public void aiMatchMethod() {}

    @Pointcut("execution(* com.jobagent.service.RewriteCoordinatorService.evaluate(..))")
    public void aiRewriteMethod() {}

    /**
     * 环绕通知：记录 AI 调用的完整链路信息。
     *
     * 入参处理策略：
     *   - 只记录字符数，不记录全文（防止日志文件爆炸，也避免敏感信息泄露）
     *   - jdLen 和 resumeLen 足以判断是否触发 token 超限
     *
     * 出参处理策略：
     *   - 记录 matchScore（核心指标）
     *   - 记录 matchedSkills/missingSkills 数量（结构完整性验证）
     *   - 不记录 improvementAdvice 全文（可能很长）
     */
    // ==================== Match 流程 ====================

    @Around("aiMatchMethod()")
    public Object logMatchCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startMs = System.currentTimeMillis();

        // 入参：jdText, resumeText（只记字符数）
        Object[] args = joinPoint.getArgs();
        int jdLen     = args.length > 0 && args[0] instanceof String s ? s.length() : 0;
        int resumeLen = args.length > 1 && args[1] instanceof String s ? s.length() : 0;

        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - startMs;

            // 出参：MatchReport → matchScore, matchedSkills 数, missingSkills 数
            if (result instanceof MatchReport report) {
                int matchedCount = report.matchedSkills() != null ? report.matchedSkills().size() : 0;
                int missingCount = report.missingSkills() != null ? report.missingSkills().size() : 0;
                log.info("[AI-CALL] match | jdLen={} resumeLen={} | cost={}ms | score={} matchedSkills={} missingSkills={}",
                        jdLen, resumeLen, cost, report.matchScore(), matchedCount, missingCount);
            } else {
                log.info("[AI-CALL] match | jdLen={} resumeLen={} | cost={}ms | status=OK",
                        jdLen, resumeLen, cost);
            }
            return result;
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - startMs;
            String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            if (errMsg.length() > 150) { errMsg = errMsg.substring(0, 150) + "..."; }
            log.warn("[AI-CALL] match | jdLen={} resumeLen={} | cost={}ms | ERROR={}:{}",
                    jdLen, resumeLen, cost, ex.getClass().getSimpleName(), errMsg);
            throw ex;
        }
    }

    // ==================== Rewrite 流程 ====================

    @Around("aiRewriteMethod()")
    public Object logRewriteCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startMs = System.currentTimeMillis();

        // 入参：jdText, originalProjectText（只记字符数）
        Object[] args = joinPoint.getArgs();
        int jdLen      = args.length > 0 && args[0] instanceof String s ? s.length() : 0;
        int projectLen = args.length > 1 && args[1] instanceof String s ? s.length() : 0;

        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - startMs;

            // 出参：RewriteResult → rounds, bulletPoints 数, optimizationReasons 数
            if (result instanceof RewriteResult rr) {
                int bulletCount  = rr.report().rewrittenBulletPoints() != null ? rr.report().rewrittenBulletPoints().size() : 0;
                int reasonsCount = rr.report().optimizationReasons() != null ? rr.report().optimizationReasons().size() : 0;
                log.info("[AI-CALL] rewrite | jdLen={} projectLen={} | cost={}ms | rounds={} bulletPoints={} optimizationReasons={}",
                        jdLen, projectLen, cost, rr.rounds(), bulletCount, reasonsCount);
            } else {
                log.info("[AI-CALL] rewrite | jdLen={} projectLen={} | cost={}ms | status=OK",
                        jdLen, projectLen, cost);
            }
            return result;
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - startMs;
            String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            if (errMsg.length() > 150) { errMsg = errMsg.substring(0, 150) + "..."; }
            log.warn("[AI-CALL] rewrite | jdLen={} projectLen={} | cost={}ms | ERROR={}:{}",
                    jdLen, projectLen, cost, ex.getClass().getSimpleName(), errMsg);
            throw ex;
        }
    }
}
