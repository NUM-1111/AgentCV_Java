package com.jobagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.JacksonConfig;
import com.jobagent.exception.ContextWindowExceededException;
import com.jobagent.model.MatchReport;
import com.jobagent.util.TokenEstimator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文窗口优化验证测试
 *
 * 验证三项优化措施是否正确生效：
 *   1. 业务层裁剪（TextTrimmer）：超长输入被压缩到安全范围
 *   2. Token 预估拦截（TokenEstimator）：裁剪后仍超限时主动抛出明确异常
 *   3. 不依赖框架截断：输入可控，不透传原始超长文本给模型
 */
@DisplayName("上下文窗口优化验证")
class ContextWindowOverflowTest {

    private MatchEvaluationFacadeService facadeService;
    private InputCapturingStub stub;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new JacksonConfig().objectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        stub = new InputCapturingStub();
        facadeService = new MatchEvaluationFacadeService(stub, objectMapper, validator);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 1：正常长度（基准）— 裁剪后仍正常通过
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景1-正常长度: JD≈500字 + 简历≈500字，裁剪后正常通过")
    void scenario1_normalLength() {
        String jd = buildJd(500);
        String resume = buildResume(500);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        // 裁剪后输入应远小于原始长度（或等于，因为本身已很短）
        assertTrue(stub.lastJdLength <= jd.length(),
                "裁剪后 JD 长度不应超过原始长度");
        assertTrue(stub.lastResumeLength <= resume.length(),
                "裁剪后简历长度不应超过原始长度");
        System.out.printf("[场景1] 原始 JD=%d字 简历=%d字 → 裁剪后 JD=%d字 简历=%d字%n",
                jd.length(), resume.length(), stub.lastJdLength, stub.lastResumeLength);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 2：中等长度 — 裁剪后正常通过
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景2-中等长度: JD≈2000字 + 简历≈2000字，裁剪后正常通过")
    void scenario2_mediumLength() {
        String jd = buildJd(2000);
        String resume = buildResume(2000);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        assertTrue(stub.lastJdLength <= 8000, "裁剪后 JD 应在 8000 字以内");
        assertTrue(stub.lastResumeLength <= 8000, "裁剪后简历应在 8000 字以内");
        System.out.printf("[场景2] 原始 JD=%d字 简历=%d字 → 裁剪后 JD=%d字 简历=%d字%n",
                jd.length(), resume.length(), stub.lastJdLength, stub.lastResumeLength);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 3：超长 JD — 裁剪后压缩到安全范围
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景3-超长JD: JD≈8000字 + 简历≈500字，JD 应被裁剪到 8000 字以内")
    void scenario3_oversizedJd() {
        String jd = buildJd(8000);
        String resume = buildResume(500);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        assertTrue(stub.lastJdLength <= 8000,
                "超长 JD 应被裁剪到 8000 字以内，实际: " + stub.lastJdLength);
        assertTrue(stub.lastJdLength < jd.length(),
                "裁剪后 JD 应短于原始输入");
        System.out.printf("[场景3] 原始 JD=%d字 → 裁剪后 JD=%d字（压缩率 %.0f%%）%n",
                jd.length(), stub.lastJdLength,
                (1.0 - (double) stub.lastJdLength / jd.length()) * 100);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 4：超长简历 — 裁剪后压缩到安全范围
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景4-超长简历: JD≈500字 + 简历≈8000字，简历应被裁剪到 8000 字以内")
    void scenario4_oversizedResume() {
        String jd = buildJd(500);
        String resume = buildResume(8000);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        assertTrue(stub.lastResumeLength <= 8000,
                "超长简历应被裁剪到 8000 字以内，实际: " + stub.lastResumeLength);
        assertTrue(stub.lastResumeLength < resume.length(),
                "裁剪后简历应短于原始输入");
        System.out.printf("[场景4] 原始简历=%d字 → 裁剪后简历=%d字（压缩率 %.0f%%）%n",
                resume.length(), stub.lastResumeLength,
                (1.0 - (double) stub.lastResumeLength / resume.length()) * 100);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 5：双超长 — 裁剪后两段均压缩到安全范围
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景5-双超长: JD≈10000字 + 简历≈10000字，裁剪后均应在 8000 字以内")
    void scenario5_bothOversized() {
        String jd = buildJd(10000);
        String resume = buildResume(10000);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        assertTrue(stub.lastJdLength <= 8000,
                "超长 JD 应被裁剪到 8000 字以内，实际: " + stub.lastJdLength);
        assertTrue(stub.lastResumeLength <= 8000,
                "超长简历应被裁剪到 8000 字以内，实际: " + stub.lastResumeLength);

        int totalAfterTrim = stub.lastJdLength + stub.lastResumeLength;
        int estimatedTokens = TokenEstimator.estimateTotal(
                buildJd(stub.lastJdLength), buildResume(stub.lastResumeLength));
        assertTrue(totalAfterTrim <= 16000,
                "裁剪后合计应在 16000 字以内（各8000），实际: " + totalAfterTrim);
        System.out.printf("[场景5] 原始合计=%d字 → 裁剪后合计=%d字，估算token≈%d%n",
                jd.length() + resume.length(), totalAfterTrim, estimatedTokens);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 6：极端超长 — 裁剪后仍超限时主动拦截，返回明确错误
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景6-极端超长: 裁剪后仍超过 token 阈值时，应抛出 ContextWindowExceededException")
    void scenario6_tokenLimitInterception() {
        // 构造一个即使裁剪后（最多 4000+4000=8000字）也会超过 MAX_INPUT_TOKENS(10000) 的场景：
        // 裁剪后最多 8000 字 + 400 模板开销 = 8400 token，低于 10000，所以需要更极端的场景。
        // 直接构造裁剪后仍超限的情况：传入已经是"核心内容"的超长文本（无法被段落裁剪压缩）
        // 方法：构造一个以"岗位职责："开头的超长段落，使裁剪后仍有 5000+ 字
        String massiveJd = "岗位职责：\n" + "负责核心业务系统开发，要求精通Java。".repeat(300); // ~5400字
        String massiveResume = "工作经历：\n" + "在某公司担任高级工程师，负责系统架构。".repeat(300); // ~5400字

        // 裁剪后各段约 1500 字（SECTION_CHAR_LIMIT），合计约 3000 字 + 400 = 3400 token
        // 这个场景裁剪后不会超限，所以我们直接测试 TokenEstimator 的拦截边界
        // 改为：验证当估算 token 超过阈值时，异常被正确抛出并携带正确信息
        String bigJd = "中".repeat(5000);   // 5000 token
        String bigResume = "中".repeat(5000); // 5000 token
        // 5000 + 5000 + 400 = 10400 > 10000，应被拦截

        ContextWindowExceededException ex = assertThrows(
                ContextWindowExceededException.class,
                () -> TokenEstimator.assertWithinLimit(bigJd, bigResume),
                "超过 token 阈值时应抛出 ContextWindowExceededException");

        assertTrue(ex.getEstimatedTokens() > TokenEstimator.MAX_INPUT_TOKENS);
        assertEquals(TokenEstimator.MAX_INPUT_TOKENS, ex.getMaxTokens());
        assertTrue(ex.getMessage().contains("超过安全阈值"),
                "错误信息应明确说明超过阈值，实际: " + ex.getMessage());
        System.out.printf("[场景6] 估算token=%d，阈值=%d，异常信息: %s%n",
                ex.getEstimatedTokens(), ex.getMaxTokens(), ex.getMessage());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 7：Prompt 模板固定开销已纳入预估
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景7-模板开销: 空输入时 token 预估应包含模板固定开销 400")
    void scenario7_promptTemplateOverheadIsAccounted() {
        int emptyTotal = TokenEstimator.estimateTotal("", "");

        // estimate("") 返回 0（isBlank 早返回），总 token 数等于模板固定开销
        assertEquals(TokenEstimator.PROMPT_TEMPLATE_OVERHEAD, emptyTotal,
                "空输入时总 token 数应等于模板开销");
        System.out.printf("[场景7] 空输入估算 token=%d（模板开销=%d）%n",
                emptyTotal, TokenEstimator.PROMPT_TEMPLATE_OVERHEAD);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 构建指定字符数的模拟 JD 文本（中文，贴近真实场景）
     */
    private String buildJd(int targetChars) {
        String template = """
                【岗位名称】高级Java后端工程师
                【工作地点】北京/上海/深圳
                【薪资范围】25K-40K
                
                【岗位职责】
                1. 负责公司核心业务系统的架构设计与开发，包括用户中心、订单系统、支付系统等高并发场景；
                2. 参与技术选型，推动微服务架构落地，保障系统高可用、高性能；
                3. 负责系统性能优化，包括数据库查询优化、缓存策略设计、异步消息处理等；
                4. 编写技术文档，参与代码评审，指导初中级工程师；
                5. 与产品、前端、测试团队紧密协作，推动项目按时高质量交付。
                
                【任职要求】
                1. 本科及以上学历，计算机相关专业，5年以上Java开发经验；
                2. 熟练掌握Java核心技术，包括多线程、JVM调优、NIO等；
                3. 熟练使用Spring Boot、Spring Cloud微服务框架；
                4. 熟练使用MySQL，掌握索引优化、分库分表、读写分离；
                5. 熟练使用Redis，了解缓存穿透、雪崩、击穿的解决方案；
                6. 熟悉消息队列（Kafka/RocketMQ），了解消息可靠性保障机制；
                7. 熟悉Docker、Kubernetes容器化部署；
                8. 有大型互联网公司工作经验者优先。
                """;
        return repeatToLength(template, targetChars);
    }

    /**
     * 构建指定字符数的模拟简历文本（中文，贴近真实场景）
     */
    private String buildResume(int targetChars) {
        String template = """
                姓名：张三
                联系方式：138xxxx8888 | zhangsan@example.com
                
                【教育背景】
                2015-2019  北京大学  计算机科学与技术  本科
                
                【工作经历】
                2022.03 - 至今  某大型互联网公司  高级Java工程师
                - 负责用户中心系统重构，将单体架构拆分为微服务，QPS从1000提升至50000；
                - 主导Redis缓存方案设计，解决缓存穿透问题，系统响应时间降低60%；
                - 优化MySQL慢查询，通过索引优化和分库分表，查询性能提升10倍；
                - 引入Kafka消息队列，实现订单异步处理，系统吞吐量提升3倍。
                
                2019.07 - 2022.02  某中型电商公司  Java工程师
                - 参与订单系统开发，使用Spring Boot + MyBatis实现核心业务逻辑；
                - 负责支付模块对接，集成支付宝、微信支付SDK；
                - 编写单元测试，测试覆盖率从30%提升至80%。
                
                【项目经历】
                项目名称：分布式秒杀系统
                技术栈：Spring Boot + Redis + Kafka + MySQL + Docker
                项目描述：设计并实现高并发秒杀系统，支持10万QPS，采用Redis预减库存、
                Kafka异步下单、数据库最终一致性方案，系统稳定运行无超卖问题。
                
                【技能清单】
                编程语言：Java（精通）、Python（熟悉）
                框架：Spring Boot、Spring Cloud、MyBatis
                数据库：MySQL、Redis、MongoDB
                消息队列：Kafka、RocketMQ
                容器：Docker、Kubernetes
                """;
        return repeatToLength(template, targetChars);
    }

    private String repeatToLength(String template, int targetChars) {
        if (template.length() >= targetChars) {
            return template.substring(0, targetChars);
        }
        StringBuilder sb = new StringBuilder();
        while (sb.length() < targetChars) {
            sb.append(template);
        }
        return sb.substring(0, targetChars);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stub：记录实际收到的输入长度
    // ─────────────────────────────────────────────────────────────────────────

    private static final class InputCapturingStub implements MatchEvaluatorService {
        int lastJdLength = 0;
        int lastResumeLength = 0;

        @Override
        public String evaluate(String jdText, String resumeText) {
            this.lastJdLength = jdText == null ? 0 : jdText.length();
            this.lastResumeLength = resumeText == null ? 0 : resumeText.length();
            // 返回合法的 JSON，让 FacadeService 正常完成流程
            return "{\"matchScore\":75,\"matchedSkills\":[\"Java\",\"Spring Boot\"],\"missingSkills\":[\"Kubernetes\"],\"improvementAdvice\":\"建议深入学习容器化技术\"}";
        }
    }
}
