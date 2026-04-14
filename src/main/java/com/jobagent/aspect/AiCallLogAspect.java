package com.jobagent.aspect;

import com.jobagent.model.MatchReport;
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
 * 这里用 execution 表达式精准切入 MatchEvaluationFacadeService.evaluate()，
 * 而不是切整个 service 包，原因：
 *   1. evaluate() 是 AI 调用的唯一入口，是我们最关心的方法
 *   2. 避免切到 TextTrimmer、TokenEstimator 等工具方法，产生噪音日志
 *   3. 精准切点 = 精准可观测，面试时也能体现你对业务的理解
 *
 * execution 表达式语法：
 *   execution(返回类型 包名.类名.方法名(参数类型))
 *   * 表示任意，.. 表示任意数量的参数
 *
 * 日志格式：
 *   成功: [AI-CALL] evaluate | jdLen=800 resumeLen=1200 | cost=3456ms | score=85 matchedSkills=5 missingSkills=2
 *   失败: [AI-CALL] evaluate | jdLen=800 resumeLen=1200 | cost=1200ms | ERROR=ContextWindowExceededException: 估算token=12000超过上限10000
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
    public void aiEvaluateMethod() {}

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
    @Around("aiEvaluateMethod()")
    public Object logAiCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startMs = System.currentTimeMillis();

        // 从入参中提取字符数（入参顺序：jdText, resumeText）
        Object[] args = joinPoint.getArgs();
        int jdLen     = args.length > 0 && args[0] instanceof String s ? s.length() : 0;
        int resumeLen = args.length > 1 && args[1] instanceof String s ? s.length() : 0;

        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - startMs;

            // 从返回的 MatchReport 中提取关键指标
            if (result instanceof MatchReport report) {
                int matchedCount = report.matchedSkills() != null ? report.matchedSkills().size() : 0;
                int missingCount = report.missingSkills() != null ? report.missingSkills().size() : 0;
                log.info("[AI-CALL] evaluate | jdLen={} resumeLen={} | cost={}ms | score={} matchedSkills={} missingSkills={}",
                        jdLen, resumeLen, cost, report.matchScore(), matchedCount, missingCount);
            } else {
                log.info("[AI-CALL] evaluate | jdLen={} resumeLen={} | cost={}ms | status=OK",
                        jdLen, resumeLen, cost);
            }

            return result;
        } catch (Throwable ex) {
            long cost = System.currentTimeMillis() - startMs;
            // 记录异常类型和消息摘要，不打印完整堆栈（堆栈由 GlobalExceptionHandler 负责）
            String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            if (errMsg.length() > 150) {
                errMsg = errMsg.substring(0, 150) + "...";
            }
            log.warn("[AI-CALL] evaluate | jdLen={} resumeLen={} | cost={}ms | ERROR={}:{}",
                    jdLen, resumeLen, cost, ex.getClass().getSimpleName(), errMsg);
            throw ex;
        }
    }
}
