package com.jobagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FactCheckTool 单元测试 —— 验证 Tool 方法本身的逻辑正确性。
 *
 * <p>这些测试不依赖 LLM，是纯 Java 逻辑验证：
 * <ul>
 *   <li>正则提取（数字匹配）的准确性</li>
 *   <li>集合差集（技术栈比对）的正确性</li>
 *   <li>关键词检测（角色措辞）的覆盖率</li>
 * </ul>
 *
 * <p>设计原则：每个 @Nested 对应一个 @Tool 方法，每个 @Test 覆盖一个边界条件。
 * 这让你可以快速定位是哪个 Tool 在哪种输入下出了问题——不需要连 LLM 调试。
 */
@DisplayName("FactCheckTool — 工具方法单元测试")
class FactCheckToolTest {

    private final FactCheckTool tool = new FactCheckTool();

    // ==================================================================
    // checkClaim — 数值比对
    // ==================================================================

    @Nested
    @DisplayName("checkClaim — 数值精确比对")
    class CheckClaimTests {

        @Test
        @DisplayName("数字完全一致 → 返回一致")
        void exactMatch() {
            String result = tool.checkClaim(
                    "QPS从500提升至2000",
                    "QPS从500提升至2000"
            );
            assertThat(result).startsWith("一致");
        }

        @Test
        @DisplayName("数字不一致 → 检测出差异（面试经典案例：QPS 2000 vs 5000）")
        void numberMismatch() {
            String result = tool.checkClaim(
                    "QPS从500提升至2000",
                    "将QPS从500提升到5000"
            );
            assertThat(result).startsWith("不一致");
            assertThat(result).contains("5000");
            assertThat(result).contains("2000");
        }

        @Test
        @DisplayName("原文无数字，改写编造了数字 → 检测为不一致")
        void fabricatedNumber() {
            String result = tool.checkClaim(
                    "优化了系统性能",
                    "系统性能提升了30%"
            );
            assertThat(result).startsWith("不一致");
            assertThat(result).contains("原文无具体数值");
            assertThat(result).contains("30%");
        }

        @Test
        @DisplayName("原文有数字，改写未提 → 返回注意（非严重）")
        void numberOmitted() {
            String result = tool.checkClaim(
                    "QPS从500提升至2000，响应时间降低60%",
                    "显著提升了系统性能"
            );
            assertThat(result).startsWith("注意");
            assertThat(result).contains("2000");
        }

        @Test
        @DisplayName("双方均无数字 → 一致（纯定性描述）")
        void noNumbersOnEitherSide() {
            String result = tool.checkClaim(
                    "优化了系统架构",
                    "提升了系统整体性能"
            );
            assertThat(result).startsWith("一致");
        }

        @Test
        @DisplayName("百分比数值比对：60% vs 60% → 一致")
        void percentageMatch() {
            String result = tool.checkClaim(
                    "响应时间降低60%",
                    "响应时间降低60%"
            );
            assertThat(result).startsWith("一致");
        }

        @Test
        @DisplayName("带单位的数字比对：5000QPS ≠ 5000 → 逐字比对检测差异")
        void numberWithUnit() {
            String result = tool.checkClaim(
                    "系统达到5000QPS",
                    "系统QPS达到5000"
            );
            // 正则将 "5000QPS" 作为一个整体 token 匹配，"5000" 是另一个 token
            // 逐字比对：5000QPS ≠ 5000 → 正确！这防止 LLM 做语义等同（把 "5000QPS" 理解为 5000）
            assertThat(result).startsWith("不一致");
            assertThat(result).contains("5000QPS");
        }

        @Test
        @DisplayName("空输入 → 安全处理")
        void nullAndEmptyInputs() {
            // null 和空字符串不应抛异常，null 被当作空字符串处理
            assertThat(tool.checkClaim(null, "性能提升30%")).contains("原文无具体数值");
            assertThat(tool.checkClaim("QPS达到2000", null)).contains("注意");  // 原文有数字但改写为空 → 注意
            assertThat(tool.checkClaim("", "")).contains("双方均无具体数值");
        }
    }

    // ==================================================================
    // checkTechStack — 技术栈差集
    // ==================================================================

    @Nested
    @DisplayName("checkTechStack — 技术栈差集运算")
    class CheckTechStackTests {

        @Test
        @DisplayName("技术栈完全一致 → 返回一致")
        void exactMatch() {
            String result = tool.checkTechStack(
                    "Spring Boot, RocketMQ, Redis",
                    "Spring Boot, RocketMQ, Redis"
            );
            assertThat(result).startsWith("技术栈一致");
        }

        @Test
        @DisplayName("新增技术（Kafka 不在原文） → 检测为新增")
        void addedTech() {
            String result = tool.checkTechStack(
                    "RocketMQ, Redis",
                    "Kafka, Redis"
            );
            assertThat(result).contains("新增技术");
            assertThat(result).contains("kafka");
        }

        @Test
        @DisplayName("遗漏技术（原文有的没提） → 检测为遗漏")
        void omittedTech() {
            String result = tool.checkTechStack(
                    "Spring Boot, RocketMQ, Redis",
                    "Spring Boot"
            );
            assertThat(result).contains("遗漏技术");
            assertThat(result).contains("rocketmq");
            assertThat(result).contains("redis");
        }

        @Test
        @DisplayName("中文逗号分隔 → 正确解析")
        void chineseCommaSeparator() {
            String result = tool.checkTechStack(
                    "Spring Boot，RocketMQ，Redis",
                    "Spring Boot, Kafka"
            );
            assertThat(result).contains("新增技术");
            assertThat(result).contains("kafka");
        }

        @Test
        @DisplayName("大小写不敏感：rocketmq == RocketMQ")
        void caseInsensitive() {
            String result = tool.checkTechStack(
                    "RocketMQ",
                    "rocketmq"
            );
            assertThat(result).startsWith("技术栈一致");
        }

        @Test
        @DisplayName("空输入 → 安全处理")
        void emptyInput() {
            assertThat(tool.checkTechStack("", "")).startsWith("技术栈一致");
            assertThat(tool.checkTechStack(null, "Kafka")).contains("kafka");
        }
    }

    // ==================================================================
    // checkRoleWording — 角色措辞
    // ==================================================================

    @Nested
    @DisplayName("checkRoleWording — 角色措辞夸大检测")
    class CheckRoleWordingTests {

        @Test
        @DisplayName("参与→主导：经典夸大 → 检测为角色夸大")
        void classicExaggeration() {
            String result = tool.checkRoleWording(
                    "参与核心模块开发",
                    "主导核心模块设计"
            );
            assertThat(result).contains("角色夸大");
            assertThat(result).contains("参与");
            assertThat(result).contains("主导");
        }

        @Test
        @DisplayName("协助→从零搭建：严重夸大 → 检测为夸大")
        void severeExaggeration() {
            String result = tool.checkRoleWording(
                    "协助团队完成日常维护",
                    "从零搭建系统架构"
            );
            assertThat(result).contains("角色夸大");
            assertThat(result).contains("协助");
            assertThat(result).contains("从零搭建");
        }

        @Test
        @DisplayName("原文已有强措辞 → 可能正常")
        void originalIsStrongAlready() {
            String result = tool.checkRoleWording(
                    "主导核心模块开发",
                    "主导核心模块设计"
            );
            // 不应判为夸大，因为原文本身就是强措辞
            assertThat(result).doesNotContain("角色夸大");
        }

        @Test
        @DisplayName("改写含强措辞'设计'但原文无弱措辞 → 注意提示")
        void designInStrongWords() {
            String result = tool.checkRoleWording(
                    "负责系统开发",
                    "负责系统设计"
            );
            // "设计" 在 STRONG_WORDS 中，所以 Tool 会返回注意提示
            // 这不是角色夸大（因为原文没有弱措辞），但需要人工判断
            assertThat(result).contains("注意");
            assertThat(result).contains("设计");
            assertThat(result).doesNotContain("角色夸大");
        }

        @Test
        @DisplayName("改写使用了强措辞但原文无弱措辞 → 注意提示")
        void strongWithoutMild() {
            String result = tool.checkRoleWording(
                    "负责系统开发",
                    "主导系统架构设计"
            );
            // 原文无"参与/协助"等弱措辞，但有"主导"出现在改写中
            assertThat(result).contains("注意");
        }
    }
}