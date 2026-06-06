package com.jobagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.JacksonConfig;
import com.jobagent.model.CriticReport;
import com.jobagent.model.RewriteReport;
import com.jobagent.model.Violation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 循环次数优化验证器 —— 轻量版 A/B 对比实验
 * <p>
 * 对 8 组黄金测试用例，分别以 MAX_ROUNDS = 1 ~ 5 运行 Actor-Critic 流程，
 * 收集每轮的 approved、violations、feedback 和预估 token 消耗，
 * 最后输出统计报告以确定最优循环次数。
 * <p>
 * 注意：此测试会真实调用 DeepSeek API，预计消耗约 120-200 次调用（~¥0.65）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("循环次数优化验证器")
class RoundOptimizationValidatorTest {

    static {
        io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
    }

    private static final Logger log = LoggerFactory.getLogger(RoundOptimizationValidatorTest.class);
    private static final int[] MAX_ROUNDS_VARIANTS = {1, 2, 3, 4, 5};

    @Autowired
    private ResumeWriterAgent writerAgent;

    @Autowired
    private FactCriticAgent criticAgent;

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();
    private List<TestCase> testCases;

    // 每轮 token 估算基数
    private static final int AVG_INPUT_TOKENS_PER_WRITER_CALL = 3200;
    private static final int AVG_OUTPUT_TOKENS_PER_WRITER_CALL = 750;
    private static final int AVG_INPUT_TOKENS_PER_CRITIC_CALL = 2900;
    private static final int AVG_OUTPUT_TOKENS_PER_CRITIC_CALL = 150;

    // 实验结果收集
    private final Map<Integer, VariantResult> results = new TreeMap<>();

    @BeforeAll
    void loadTestCases() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("golden-test-set.json");
        if (is == null) {
            throw new IllegalStateException("golden-test-set.json 未找到，请确保文件在 src/test/resources/ 下");
        }
        testCases = objectMapper.readValue(is, new TypeReference<>() {});
        log.info("已加载 {} 组测试用例", testCases.size());
    }

    @Test
    @DisplayName("执行 MAX_ROUNDS=1~5 的完整 A/B 对比实验")
    void runAllVariants() {
        log.info("================================================================");
        log.info("  循环次数验证实验开始");
        log.info("  测试用例数: {}  |  变体数: {}  |  预计总 API 调用: ~{}次",
                testCases.size(), MAX_ROUNDS_VARIANTS.length, estimateTotalApiCalls());
        log.info("================================================================");

        for (int maxRounds : MAX_ROUNDS_VARIANTS) {
            VariantResult variant = runVariant(maxRounds);
            results.put(maxRounds, variant);
        }

        printReport();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 单个变体执行
    // ─────────────────────────────────────────────────────────────────────────

    private VariantResult runVariant(int maxRounds) {
        RewriteCoordinatorService coordinator = new RewriteCoordinatorService(
                writerAgent, criticAgent, maxRounds);
        VariantResult variant = new VariantResult(maxRounds);

        log.info("--- 开始 MAX_ROUNDS={} 变体 ---", maxRounds);

        for (TestCase tc : testCases) {
            CaseRun run = runSingleCase(coordinator, tc, maxRounds);
            variant.cases.add(run);
            log.info("  [{}] rounds={} approved={} violations={}",
                    tc.id, run.actualRounds, run.finalApproved,
                    run.totalViolations());
        }

        variant.summarize();
        log.info("  MAX_ROUNDS={} 汇总: 通过率={}/{} ({}%) 平均轮次={:.1f} 估算token={}",
                maxRounds, variant.passedCount, variant.totalCases,
                variant.passRate(), variant.avgRounds(), variant.estimatedTokens());
        return variant;
    }

    private CaseRun runSingleCase(RewriteCoordinatorService coordinator, TestCase tc, int maxRounds) {
        CaseRun run = new CaseRun(tc.id, tc.category, maxRounds);
        try {
            RewriteCoordinatorService.RewriteResult result = coordinator.evaluate(tc.jdText, tc.originalProjectText);
            run.actualRounds = result.rounds();
            run.finalBulletPoints = result.report().rewrittenBulletPoints();
            run.finalApproved = true; // evaluate 返回时表示通过或到达上限

            // 无法直接从 evaluate 获取最后一轮的 CriticReport，
            // 因此单独调用一次 Critic 对最终草稿做审查来收集 violations
            if (run.finalBulletPoints != null && !run.finalBulletPoints.isEmpty()) {
                String bp = IntStream.range(0, run.finalBulletPoints.size())
                        .mapToObj(i -> (i + 1) + ". " + run.finalBulletPoints.get(i))
                        .collect(Collectors.joining("\n"));
                CriticReport finalCheck = criticAgent.check(tc.originalProjectText, bp);
                run.finalApproved = finalCheck.approved();
                run.violations = finalCheck.violations() != null ? finalCheck.violations() : List.of();
                run.lastFeedback = finalCheck.feedback();
            }
        } catch (Exception e) {
            run.error = e.getMessage();
            log.warn("  [{}] 执行异常: {}", tc.id, e.getMessage());
        }
        return run;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 统计计算
    // ─────────────────────────────────────────────────────────────────────────

    private int estimateTotalApiCalls() {
        // 粗略估计：每变体 × 每用例 × (avgRounds × (1 Write + 1 Critic) + 1 final Critic)
        int callsPerCase = 6; // avg 2.5 rounds: 2.5*2 + 1 = 6
        return testCases.size() * MAX_ROUNDS_VARIANTS.length * callsPerCase;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 报告输出
    // ─────────────────────────────────────────────────────────────────────────

    private void printReport() {
        log.info("\n\n================================================================");
        log.info("  验证实验报告");
        log.info("================================================================\n");

        // 表格头
        String header = String.format("%-10s | %5s | %5s | %6s | %7s | %5s | %8s | %6s",
                "MAX_ROUNDS", "通过数", "通过率", "平均轮次", "平均违规", "死锁", "估算token", "成本¥");
        log.info(header);
        log.info("-".repeat(header.length()));

        for (int mr : MAX_ROUNDS_VARIANTS) {
            VariantResult v = results.get(mr);
            if (v == null) continue;
            String line = String.format("%-10d | %5d | %4.0f%% | %5.1f | %5.1f | %4d | %8d | %5.3f",
                    mr, v.passedCount, v.passRate(), v.avgRounds(),
                    v.avgViolations(), v.deadlockCount,
                    v.estimatedTokens(), v.estimatedTokens() * 0.0000014); // $0.14/1M input? no, this is rough
            log.info(line);
        }

        log.info("\n--- 详细用例结果 ---");
        for (int mr : MAX_ROUNDS_VARIANTS) {
            VariantResult v = results.get(mr);
            if (v == null) continue;
            log.info("\n[MAX_ROUNDS={}]", mr);
            for (CaseRun c : v.cases) {
                String status = c.finalApproved ? "✓" : "✗";
                String violations = c.violations.isEmpty() ? "无" :
                        c.violations.stream()
                                .map(vi -> String.format("[BP%d %s(sev=%d)]", vi.bulletIndex(), vi.violationType(), vi.severity()))
                                .collect(Collectors.joining(", "));
                log.info("  {} {} rounds={} violations={} {}",
                        status, c.caseId, c.actualRounds, violations,
                        c.error != null ? ("ERR:" + c.error) : "");
            }
        }

        // 追加 CSV 格式报告用于进一步分析
        log.info("\n--- CSV 导出 ---");
        log.info("maxRounds,caseId,category,actualRounds,approved,violationCount,violationTypes,error");
        for (int mr : MAX_ROUNDS_VARIANTS) {
            VariantResult v = results.get(mr);
            if (v == null) continue;
            for (CaseRun c : v.cases) {
                String vTypes = c.violations.stream()
                        .map(Violation::violationType)
                        .distinct()
                        .collect(Collectors.joining(";"));
                log.info("{},{},{},{},{},{},{},{}",
                        mr, c.caseId, c.category, c.actualRounds, c.finalApproved,
                        c.violations.size(), vTypes.isEmpty() ? "NONE" : vTypes,
                        c.error != null ? c.error : "");
            }
        }

        log.info("\n================================================================\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 数据类
    // ─────────────────────────────────────────────────────────────────────────

    static class TestCase {
        public String id;
        public String category;
        public String description;
        public String jdText;
        public String originalProjectText;
        public List<Map<String, String>> expectedViolations;
    }

    static class CaseRun {
        final String caseId;
        final String category;
        final int variantMaxRounds;
        int actualRounds = 0;
        boolean finalApproved = false;
        List<String> finalBulletPoints = List.of();
        List<Violation> violations = List.of();
        String lastFeedback = "";
        String error = null;

        CaseRun(String caseId, String category, int variantMaxRounds) {
            this.caseId = caseId;
            this.category = category;
            this.variantMaxRounds = variantMaxRounds;
        }

        int totalViolations() {
            return violations.size();
        }
    }

    static class VariantResult {
        final int maxRounds;
        final List<CaseRun> cases = new ArrayList<>();
        int totalCases = 0;
        int passedCount = 0;
        int deadlockCount = 0;

        VariantResult(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        void summarize() {
            totalCases = cases.size();
            passedCount = (int) cases.stream().filter(c -> c.finalApproved).count();
            // 死锁：达到最大轮次但仍未通过
            deadlockCount = (int) cases.stream()
                    .filter(c -> !c.finalApproved && c.actualRounds >= maxRounds)
                    .count();
        }

        double passRate() {
            return totalCases == 0 ? 0 : 100.0 * passedCount / totalCases;
        }

        double avgRounds() {
            return cases.stream().mapToInt(c -> c.actualRounds).average().orElse(0);
        }

        double avgViolations() {
            return cases.stream().mapToInt(CaseRun::totalViolations).average().orElse(0);
        }

        /**
         * 估算 token 消耗：
         *   - Writer 每轮: 约 3200 input + 750 output
         *   - Critic 每轮: 约 2900 input + 150 output
         *   - 额外 final Critic: 2900 input + 150 output
         */
        int estimatedTokens() {
            int total = 0;
            for (CaseRun c : cases) {
                int rounds = Math.max(c.actualRounds, 1);
                // Writer calls
                total += rounds * (AVG_INPUT_TOKENS_PER_WRITER_CALL + AVG_OUTPUT_TOKENS_PER_WRITER_CALL);
                // Critic calls (one per round + one final)
                total += (rounds + 1) * (AVG_INPUT_TOKENS_PER_CRITIC_CALL + AVG_OUTPUT_TOKENS_PER_CRITIC_CALL);
            }
            return total;
        }
    }
}