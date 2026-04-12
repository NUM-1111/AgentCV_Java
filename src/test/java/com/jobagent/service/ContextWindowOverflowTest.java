package com.jobagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.JacksonConfig;
import com.jobagent.model.MatchReport;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 长文本上下文溢出问题探测测试
 *
 * 目标：验证当 JD + 简历文本超过模型 token 上限时，
 * 当前代码是否存在以下问题：
 *   1. 没有 token 预估 / 主动拦截逻辑
 *   2. 没有业务层裁剪
 *   3. 超长输入直接透传给模型（黑盒截断风险）
 *
 * 测试策略：使用 StubMatchEvaluatorService 记录实际收到的输入长度，
 * 模拟不同规模的 JD + 简历组合，观察系统行为。
 */
@DisplayName("长文本上下文溢出问题探测")
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
    // 场景 1：正常长度（基准）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景1-正常长度: JD≈500字 + 简历≈500字，应正常通过")
    void scenario1_normalLength() {
        String jd = buildJd(500);
        String resume = buildResume(500);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        int totalChars = stub.lastJdLength + stub.lastResumeLength;
        System.out.printf("[场景1] JD=%d字 | 简历=%d字 | 合计=%d字 | 估算token≈%d%n",
                stub.lastJdLength, stub.lastResumeLength, totalChars, estimateTokens(totalChars));
        System.out.println("[场景1] 结论: 正常通过，无任何 token 预算检查");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 2：中等长度
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景2-中等长度: JD≈2000字 + 简历≈2000字，接近边界")
    void scenario2_mediumLength() {
        String jd = buildJd(2000);
        String resume = buildResume(2000);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        int totalChars = stub.lastJdLength + stub.lastResumeLength;
        System.out.printf("[场景2] JD=%d字 | 简历=%d字 | 合计=%d字 | 估算token≈%d%n",
                stub.lastJdLength, stub.lastResumeLength, totalChars, estimateTokens(totalChars));
        System.out.println("[场景2] 结论: 正常通过，无任何 token 预算检查");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 3：超长 JD
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景3-超长JD: JD≈8000字 + 简历≈500字，JD 严重超长")
    void scenario3_oversizedJd() {
        String jd = buildJd(8000);
        String resume = buildResume(500);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        int totalChars = stub.lastJdLength + stub.lastResumeLength;
        System.out.printf("[场景3] JD=%d字 | 简历=%d字 | 合计=%d字 | 估算token≈%d%n",
                stub.lastJdLength, stub.lastResumeLength, totalChars, estimateTokens(totalChars));
        System.out.println("[场景3] 结论: 超长 JD 被原样透传，无裁剪/拦截");
        System.out.println("[场景3] 风险: 真实调用时模型将收到超长 JD，可能触发黑盒截断");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 4：超长简历
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景4-超长简历: JD≈500字 + 简历≈8000字，简历严重超长")
    void scenario4_oversizedResume() {
        String jd = buildJd(500);
        String resume = buildResume(8000);

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        int totalChars = stub.lastJdLength + stub.lastResumeLength;
        System.out.printf("[场景4] JD=%d字 | 简历=%d字 | 合计=%d字 | 估算token≈%d%n",
                stub.lastJdLength, stub.lastResumeLength, totalChars, estimateTokens(totalChars));
        System.out.println("[场景4] 结论: 超长简历被原样透传，无裁剪/拦截");
        System.out.println("[场景4] 风险: 真实调用时模型将收到超长简历，关键信息可能被截断");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 5：双超长（模拟真实溢出）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景5-双超长: JD≈10000字 + 简历≈10000字，合计≈20000字，模拟真实溢出")
    void scenario5_bothOversized() {
        String jd = buildJd(10000);
        String resume = buildResume(10000);

        // 当前代码没有任何拦截，调用会直接透传给 stub（真实场景会透传给模型）
        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        int totalChars = stub.lastJdLength + stub.lastResumeLength;
        System.out.printf("[场景5] JD=%d字 | 简历=%d字 | 合计=%d字 | 估算token≈%d%n",
                stub.lastJdLength, stub.lastResumeLength, totalChars, estimateTokens(totalChars));
        System.out.println("[场景5] 结论: 双超长输入被原样透传，无任何保护机制");
        System.out.println("[场景5] 风险: DeepSeek-chat 上下文窗口约 32K-64K token，");
        System.out.println("         加上 system prompt + user prompt 模板固定开销约 300 token，");
        System.out.println("         20000 中文字符 ≈ 20000+ token，极大概率触发截断或报错");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 6：验证是否存在 token 预算控制
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景6-无token预算控制验证: 超长输入不会被拦截，直接透传")
    void scenario6_noTokenBudgetControl() {
        // 构造一个明显超长的输入（约 50000 字）
        String jd = buildJd(25000);
        String resume = buildResume(25000);

        // 预期：当前代码不会抛出任何"输入过长"异常，直接透传
        // 这正是问题所在：没有主动拦截，依赖模型黑盒处理
        assertDoesNotThrow(() -> facadeService.evaluate(jd, resume),
                "当前代码没有 token 预算控制，超长输入不会被主动拦截");

        int totalChars = stub.lastJdLength + stub.lastResumeLength;
        System.out.printf("[场景6] JD=%d字 | 简历=%d字 | 合计=%d字 | 估算token≈%d%n",
                stub.lastJdLength, stub.lastResumeLength, totalChars, estimateTokens(totalChars));
        System.out.println("[场景6] 结论: 确认当前代码无 token 预算控制");
        System.out.println("[场景6] 问题: 50000 字 ≈ 50000+ token，远超大多数模型上下文窗口");
        System.out.println("[场景6] 后果: 真实调用时将触发 API 报错（context_length_exceeded）");
        System.out.println("         或模型黑盒截断导致结果不可信");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 场景 7：验证 prompt 模板固定开销
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("场景7-prompt固定开销: 即使输入为空，prompt模板本身也有固定token开销")
    void scenario7_promptTemplateOverhead() {
        // 空输入，只测量 prompt 模板本身的开销
        String jd = "";
        String resume = "";

        MatchReport report = facadeService.evaluate(jd, resume);

        assertNotNull(report);
        // MatchEvaluatorService 的 @SystemMessage + @UserMessage 模板固定文本约 400 字
        // 对应约 400 token 的固定开销，这部分在当前代码中完全不可见
        System.out.println("[场景7] JD=0字 | 简历=0字");
        System.out.println("[场景7] 但 @SystemMessage + @UserMessage 模板固定文本约 400 字 ≈ 400 token");
        System.out.println("[场景7] 结论: 当前代码对 prompt 模板固定开销无感知，token 预算计算不完整");
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

    /**
     * 粗略估算 token 数：中文约 1字=1token，英文约 4字符=1token
     * 这里保守估算：1字符 ≈ 1token（中文场景）
     */
    private int estimateTokens(int charCount) {
        return charCount; // 中文 1字≈1token，保守估算
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
