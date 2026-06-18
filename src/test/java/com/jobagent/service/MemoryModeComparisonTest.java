package com.jobagent.service;

import com.jobagent.model.CriticReport;
import com.jobagent.model.RewriteReport;
import com.jobagent.model.Violation;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实验1: 显式传参 vs 会话记忆 A/B对比（2026-06-17 二面冲刺验证）。
 *
 * 用信息稀疏用例C2-1，对比两种模式在完全相同的JD+简历条件下的表现。
 * - 模式A（显式传参，当前方案）: 每轮只传上一轮Critic feedback
 * - 模式B（会话记忆）: 每轮累积全部历史对话
 *
 * 预计 API 调用: 1用例 × 2模式 × 2轮 × 2次(Writer+Critic) = ~8次
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("实验1: 显式传参 vs 会话记忆 A/B对比")
class MemoryModeComparisonTest {

    static {
        io.github.cdimascio.dotenv.Dotenv dotenv =
                io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
    }

    private static final Logger log = LoggerFactory.getLogger(MemoryModeComparisonTest.class);

    @Autowired private ResumeWriterAgent writerAgent;
    @Autowired private FactCriticAgent criticAgent;

    // 信息稀疏用例 — 原文只有基本Java开发，JD要求高级微服务+中间件
    private static final String JD = """
            【岗位】高级Java后端工程师
            【任职要求】
            1. 精通微服务架构，有Spring Cloud Alibaba实战经验
            2. 掌握分布式事务解决方案，如Seata
            3. 熟练使用Redis集群、Kafka等中间件
            4. 有大规模系统性能优化经验，熟悉JVM调优""";

    private static final String PROJECT = """
            2020.07 - 2023.02  某中型电商公司  Java工程师
            - 参与订单系统开发，使用Spring Boot + MyBatis实现核心业务逻辑。
            - 负责支付模块对接，集成支付宝、微信支付SDK。""";

    private static final int MAX_ROUNDS = 2;

    /* ==================== 模式A: 显式传参（当前方案） ==================== */

    @Test
    @DisplayName("模式A: 显式传参 — 每轮只传 Critic feedback 文本")
    void modeA_explicitFeedback() {
        log.info("========== 模式A: 显式传参 ==========");
        String feedback = "";
        int totalViolations = 0;
        int maxSeverity = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            // 首轮写指令，后续轮写修正指令
            String feedbackPrompt = (round == 0)
                    ? "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】"
                    : "【上一版审查未通过，请根据以下反馈修正，确保所有内容均有原文依据】\n" + feedback;

            RewriteReport draft = writerAgent.rewrite(JD, PROJECT, feedbackPrompt);
            String bulletPoints = formatBulletPoints(draft);

            CriticReport criticism = criticAgent.check(PROJECT, bulletPoints);

            totalViolations = criticism.violations() != null ? criticism.violations().size() : 0;
            maxSeverity = criticism.violations() != null && !criticism.violations().isEmpty()
                    ? criticism.violations().stream().mapToInt(Violation::severity).max().orElse(0) : 0;

            log.info("A轮{}: approved={}, maxSeverity={}, violations={}",
                    round + 1, criticism.approved(), maxSeverity, totalViolations);

            if (criticism.approved() || maxSeverity <= 2) {
                log.info("  -> 通过 (round={})", round + 1);
                break;
            }

            feedback = criticism.feedback();
            log.info("  -> 未通过, feedback长度={}", feedback != null ? feedback.length() : 0);
        }

        log.info("模式A 最终: maxSeverity={}, totalViolations={}", maxSeverity, totalViolations);
        // 不硬编码断言——记录数据即可
    }

    /* ==================== 模式B: 会话记忆（累积历史） ==================== */

    @Test
    @DisplayName("模式B: 会话记忆 — 每轮累积全部历史对话到 prompt")
    void modeB_sessionMemory() {
        log.info("========== 模式B: 会话记忆 ==========");
        StringBuilder history = new StringBuilder();
        int totalViolations = 0;
        int maxSeverity = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            // 构建含累积历史的 prompt
            String basePrompt = (round == 0)
                    ? "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】"
                    : "【以下是你之前的所有对话历史，请基于历史修正当前版本。"
                    + "上一版审查未通过，请根据以下反馈修正，确保所有内容均有原文依据】";

            String fullPrompt = basePrompt + "\n\n" + history.toString();
            if (round > 0) {
                fullPrompt += "\n\n【本轮修正目标】Critic指出上版存在违规，请修正这些问题。";
            }

            RewriteReport draft = writerAgent.rewrite(JD, PROJECT, fullPrompt);
            String bulletPoints = formatBulletPoints(draft);

            CriticReport criticism = criticAgent.check(PROJECT, bulletPoints);

            totalViolations = criticism.violations() != null ? criticism.violations().size() : 0;
            maxSeverity = criticism.violations() != null && !criticism.violations().isEmpty()
                    ? criticism.violations().stream().mapToInt(Violation::severity).max().orElse(0) : 0;

            log.info("B轮{}: approved={}, maxSeverity={}, violations={}",
                    round + 1, criticism.approved(), maxSeverity, totalViolations);

            // 累积本轮对话到历史
            history.append("\n=== 第").append(round + 1).append("轮 ===\n");
            history.append("Writer输出:\n").append(bulletPoints).append("\n");
            history.append("Critic反馈: ").append(criticism.feedback()).append("\n");

            if (criticism.approved() || maxSeverity <= 2) {
                log.info("  -> 通过 (round={})", round + 1);
                break;
            }

            log.info("  -> 未通过, 累积历史长度={}", history.length());
        }

        log.info("模式B 最终: maxSeverity={}, totalViolations={}", maxSeverity, totalViolations);
    }

    /* ==================== 汇总对比 ==================== */

    @Test
    @DisplayName("汇总: 打印 A vs B 对比结论指引")
    void summary() {
        log.info("\n========== 实验1 汇总 ==========");
        log.info("对比模式: A=显式传参(仅feedback) B=会话记忆(累积历史)");
        log.info("对比指标: 查看上方日志中的 approved/maxSeverity/violations");
        log.info("如果A的maxSeverity更低或通过更早 → 显式传参更好（当前方案有数据支撑）");
        log.info("如果B的maxSeverity更低或通过更早 → 应切换到会话记忆");
        log.info("如果两者接近 → 选显式传参（更简单、token更省）");
    }

    /* ==================== 工具方法 ==================== */

    private String formatBulletPoints(RewriteReport report) {
        if (report.rewrittenBulletPoints() == null || report.rewrittenBulletPoints().isEmpty()) {
            return "（无要点）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < report.rewrittenBulletPoints().size(); i++) {
            sb.append(i + 1).append(". ").append(report.rewrittenBulletPoints().get(i)).append("\n");
        }
        return sb.toString();
    }
}