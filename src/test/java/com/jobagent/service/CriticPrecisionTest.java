package com.jobagent.service;

import com.jobagent.model.CriticReport;
import com.jobagent.model.Violation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Critic 精确数据审查专项测试
 * <p>
 * 使用同一份原文 + 3 个刻意注入违规的 dirty draft，
 * 验证强化后的 Critic SystemMessage 能否准确召回已知违规。
 * <p>
 * 每个 dirty draft 运行 3 次（检测稳定性），共 9 次 API 调用。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Critic 精确数据审查专项测试")
class CriticPrecisionTest {

    static {
        io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
    }

    private static final Logger log = LoggerFactory.getLogger(CriticPrecisionTest.class);

    @Autowired
    private FactCriticAgent criticAgent;

    // 固定原文
    private static final String ORIGINAL = """
            负责订单系统优化，通过缓存和异步处理，QPS从500提升至2000。
            使用了Redis和RocketMQ。参与了数据库查询优化的相关工作。""";

    // 采样次数
    private static final int SAMPLES = 3;

    // ── Dirty Drafts ───────────────────────────────────────────────────────

    /**
     * CT-1: 数字膨胀 —— QPS 数值被篡改
     * 预期: approved=false, violations 包含 FAKE_DATA (QPS 50000 ≠ 2000)
     */
    private static final String CT1_DIRTY = """
            1. 主导设计高并发订单系统，QPS从500提升至50000
            2. 使用Redis缓存方案优化系统性能
            3. 优化数据库查询，使用RocketMQ实现异步处理""";

    /**
     * CT-2: 技术栈替换 —— RocketMQ 被替换为 Kafka
     * 预期: approved=false, violations 包含 FAKE_TECH (Kafka ∉ {Redis, RocketMQ})
     */
    private static final String CT2_DIRTY = """
            1. 负责订单系统优化，使用缓存和异步处理提升吞吐量
            2. 使用Redis和Kafka实现消息驱动的异步架构
            3. 参与数据库查询优化相关工作""";

    /**
     * CT-3: 捏造百分比 —— 原文无响应时间数据，bullet 编造了"降低60%"
     * 预期: approved=false, violations 包含 FAKE_DATA (原文无60%)
     */
    private static final String CT3_DIRTY = """
            1. 负责订单系统优化，QPS从500提升至2000
            2. 系统响应时间降低60%，大幅改善用户体验
            3. 使用Redis和RocketMQ实现缓存和异步处理""";

    @Test
    @DisplayName("CT-1: 数字膨胀召回测试")
    void testFakeDataDetection_ct1() {
        log.info("=== CT-1: 数字膨胀 (QPS 50000 ≠ 2000) ===");
        runAndReport("CT-1", ORIGINAL, CT1_DIRTY,
                Set.of("FAKE_DATA"),
                "QPS 数值被从 2000 改为 50000，应检测到 FAKE_DATA");
    }

    @Test
    @DisplayName("CT-2: 技术栈替换召回测试")
    void testFakeTechDetection_ct2() {
        log.info("=== CT-2: 技术栈替换 (Kafka ∉ {Redis, RocketMQ}) ===");
        runAndReport("CT-2", ORIGINAL, CT2_DIRTY,
                Set.of("FAKE_TECH"),
                "Kafka 不在原文技术栈中，应检测到 FAKE_TECH");
    }

    @Test
    @DisplayName("CT-3: 捏造百分比召回测试")
    void testFakeDataDetection_ct3() {
        log.info("=== CT-3: 捏造百分比 (原文无 60%) ===");
        runAndReport("CT-3", ORIGINAL, CT3_DIRTY,
                Set.of("FAKE_DATA"),
                "原文无响应时间百分比数据，应检测到 FAKE_DATA");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void runAndReport(String testId, String original, String dirtyDraft,
                              Set<String> expectedViolationTypes, String rationale) {
        int passCount = 0;
        int falsePositive = 0;
        List<List<Violation>> allViolations = new ArrayList<>();

        for (int i = 0; i < SAMPLES; i++) {
            CriticReport report = criticAgent.check(original, dirtyDraft);

            if (!report.approved()) {
                List<Violation> v = report.violations() != null ? report.violations() : List.of();
                allViolations.add(v);
                Set<String> actualTypes = v.stream().map(Violation::violationType).collect(Collectors.toSet());

                // 检查是否命中了至少一种预期违规类型
                boolean hit = actualTypes.stream().anyMatch(expectedViolationTypes::contains);
                if (hit) {
                    passCount++;
                    log.info("  [sample {}] ✓ approved=false, violations={}", i + 1, summarize(v));
                } else {
                    falsePositive++;
                    log.warn("  [sample {}] ✗ approved=false but violations={} (期望命中 {})",
                            i + 1, actualTypes, expectedViolationTypes);
                }
            } else {
                // 假阴性：approved=true 但应该有违规
                log.warn("  [sample {}] ✗ approved=true —— 假阴性，漏判了预期违规", i + 1);
            }
        }

        log.info("  {} 结果: 命中率={}/{}, 假阳性={}, 假阴性={}",
                testId, passCount, SAMPLES, falsePositive, SAMPLES - passCount - falsePositive);

        // 检查是否至少有一次正确命中（最低标准）
        if (passCount == 0) {
            log.error("  ❌ {} 未通过：{} 次采样中 0 次命中预期违规 {}", testId, SAMPLES, expectedViolationTypes);
            log.error("     预期行为：{}", rationale);
        } else {
            log.info("  ✓ {} 通过：{}/{} 次正确命中", testId, passCount, SAMPLES);
        }
    }

    private String summarize(List<Violation> violations) {
        return violations.stream()
                .map(v -> String.format("[BP%d %s(sev=%d): %s]",
                        v.bulletIndex(), v.violationType(), v.severity(), truncate(v.detail(), 60)))
                .collect(Collectors.joining(", "));
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}