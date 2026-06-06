package com.jobagent.service;

import com.jobagent.model.CriticReport;
import com.jobagent.model.RewriteReport;
import com.jobagent.model.Violation;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端最小化全面验证
 * <p>
 * 覆盖已验证的 3 类问题场景，每类 1 个代表性用例，验证 Actor-Critic 全链路。
 * <p>
 * 场景设计（4 个用例，共约 20 次 API 调用）：
 *   E2E-1: 精确数据防篡改 —— Writer 不应修改 QPS 数值、不应替换 RocketMQ
 *   E2E-2: 信息稀疏防捏造 —— 原文仅参与开发，JD 要求高级技术,Writer 不应脑补
 *   E2E-3: 零交集防引入 —— JD 要求 K8s/Go，原文只有 Java/Spring Boot
 *   E2E-4: 模糊描述合理润色 —— 定性描述可用同义表达，不应被误判
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("端到端验证（4场景×2轮×2Agent全链路）")
class EndToEndValidationTest {

    static {
        io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
    }

    private static final Logger log = LoggerFactory.getLogger(EndToEndValidationTest.class);
    private static final int MAX_ROUNDS = 2;

    @Autowired private ResumeWriterAgent writerAgent;
    @Autowired private FactCriticAgent criticAgent;

    private RewriteCoordinatorService coordinator;

    @BeforeAll
    void setUp() {
        coordinator = new RewriteCoordinatorService(writerAgent, criticAgent, MAX_ROUNDS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E2E-1: 精确数据防篡改
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("E2E-1: 精确数据防篡改——QPS和RocketMQ应在bullet中保持原样")
    void e2e1_exactDataPreservation() {
        String jd = "【岗位】高并发系统架构师\n【要求】精通分布式缓存和消息队列";
        String original = """
                负责订单系统优化，通过缓存和异步处理，QPS从500提升至2000。
                使用了Redis和RocketMQ。""";

        RewriteCoordinatorService.RewriteResult result = coordinator.evaluate(jd, original);
        RewriteReport report = result.report();

        // 核心断言：最终输出必须包含原文的精确数据
        String output = String.join(" ", report.rewrittenBulletPoints());
        assertTrue(output.contains("2000"), "QPS 终值 2000 应保持不变");
        assertTrue(output.contains("RocketMQ"), "技术栈 RocketMQ 不应被替换为 Kafka");
        assertFalse(output.contains("Kafka"), "不应引入原文不存在的 Kafka");

        log.info("[E2E-1 ✓] rounds={} output={}", result.rounds(),
                report.rewrittenBulletPoints().stream().limit(3).collect(Collectors.joining(" | ")));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E2E-2: 信息稀疏防捏造
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("E2E-2: 信息稀疏防捏造——原文只有Spring Boot，不应出现Redis/Kafka")
    void e2e2_noTechHallucination() {
        String jd = "【岗位】高级Java后端\n【要求】精通微服务、Redis集群、Kafka";
        String original = "参与订单系统开发，使用Spring Boot + MyBatis实现核心业务逻辑。";

        RewriteCoordinatorService.RewriteResult result = coordinator.evaluate(jd, original);
        String output = String.join(" ", result.report().rewrittenBulletPoints());

        // 关键验证：不应凭空引入 JD 中要求但原文没有的技术
        assertFalse(output.contains("Redis"), "原文无 Redis，不应捏造");
        assertFalse(output.contains("Kafka"), "原文无 Kafka，不应捏造");

        // 同时验证 Critic 最终判定
        CriticReport finalCheck = criticAgent.check(original, formatBullets(result.report()));
        assertTrue(finalCheck.approved() || hasOnlyMinorViolations(finalCheck),
                "若未通过，违规应仅为 MINOR_EMBELLISHMENT（非捏造）");

        log.info("[E2E-2 ✓] rounds={} approved={} violations={}",
                result.rounds(), finalCheck.approved(),
                finalCheck.violations() != null ? finalCheck.violations().size() : 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E2E-3: 零交集防引入
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("E2E-3: 零交集防引入——JD要求K8s/Go，Writer不应凭空引入")
    void e2e3_zeroOverlapNoInjection() {
        String jd = "【岗位】云原生开发\n【要求】精通Kubernetes、Go语言";
        String original = "参与订单系统开发，使用Spring Boot + MyBatis。负责RESTful API设计。";

        RewriteCoordinatorService.RewriteResult result = coordinator.evaluate(jd, original);
        String output = String.join(" ", result.report().rewrittenBulletPoints());

        assertFalse(output.contains("Kubernetes"), "原文无 K8s，不应凭空引入");
        assertFalse(output.contains("K8s"), "原文无 K8s，不应凭空引入");
        assertFalse(output.contains("Go"), "原文无 Go，不应凭空引入");

        log.info("[E2E-3 ✓] rounds={} output={}",
                result.rounds(),
                result.report().rewrittenBulletPoints().stream().limit(3).collect(Collectors.joining(" | ")));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E2E-4: 模糊描述合理润色 + Critic 容忍
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("E2E-4: 模糊描述——定性表达可润色为同义STAR句式，Critic应容忍")
    void e2e4_qualitativeRefinementAllowed() {
        String jd = "【岗位】Java技术专家\n【要求】有系统性能优化实战经验";
        String original = "负责系统性能优化工作，改善了系统响应速度。参与微服务架构改造。";

        RewriteCoordinatorService.RewriteResult result = coordinator.evaluate(jd, original);
        CriticReport finalCheck = criticAgent.check(original, formatBullets(result.report()));

        // 定性描述的合理润色不应被拦截
        assertTrue(finalCheck.approved() || hasOnlyMinorViolations(finalCheck),
                "定性润色应被容忍（通过 or 仅 MINOR_EMBELLISHMENT）\n" +
                        "violations: " + (finalCheck.violations() != null ?
                        finalCheck.violations().stream()
                                .map(v -> v.violationType() + "(sev=" + v.severity() + ")")
                                .collect(Collectors.joining(", ")) : "null"));

        log.info("[E2E-4 ✓] rounds={} approved={} violations={}",
                result.rounds(), finalCheck.approved(),
                finalCheck.violations() != null ? finalCheck.violations().size() : 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════════════

    private String formatBullets(RewriteReport report) {
        if (report.rewrittenBulletPoints() == null || report.rewrittenBulletPoints().isEmpty())
            return "（无要点）";
        var sb = new StringBuilder();
        for (int i = 0; i < report.rewrittenBulletPoints().size(); i++) {
            sb.append(i + 1).append(". ").append(report.rewrittenBulletPoints().get(i)).append("\n");
        }
        return sb.toString();
    }

    private boolean hasOnlyMinorViolations(CriticReport report) {
        List<Violation> v = report.violations();
        if (v == null || v.isEmpty()) return false;
        Set<String> types = v.stream().map(Violation::violationType).collect(Collectors.toSet());
        int maxSev = v.stream().mapToInt(Violation::severity).max().orElse(0);
        // 仅 MINOR_EMBELLISHMENT + severity ≤ 2 → 可容忍
        return types.size() == 1 && types.contains("MINOR_EMBELLISHMENT") && maxSev <= 2;
    }
}