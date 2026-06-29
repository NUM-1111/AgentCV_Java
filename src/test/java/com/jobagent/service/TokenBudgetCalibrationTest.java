package com.jobagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.util.TextTrimmer;
import com.jobagent.util.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token 预算约束校准管道。
 *
 * <p>本测试不验证特定常量值，而是验证三个组件之间的<b>约束关系</b>是否成立：
 * <ol>
 *   <li>约束A: TextTrimmer理论最大输出 × 估算系数 + Prompt开销 ≤ Token拦截阈值</li>
 *   <li>约束B: Golden Set 实测Token分布远在拦截阈值之下（不误杀正常用户）</li>
 *   <li>约束C: 英文简历场景链路不崩溃、不丢失核心段落</li>
 * </ol>
 *
 * <p>当 TextTrimmer 上限、估算公式、Prompt模板、拦截阈值 任一变更时，
 * 本测试会自动反映约束是否断裂，并在失败信息中给出修复建议。
 */
@DisplayName("Token 预算约束校准管道")
class TokenBudgetCalibrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 中文字符到token保守估算系数：1中文字符≈1token（TokenEstimator默认行为） */
    private static final double CN_CHAR_TO_TOKEN = 1.0;

    /** 最低安全系数：阈值至少是实测最大值的3倍 */
    private static final double MIN_SAFETY_FACTOR = 3.0;

    @Test
    @DisplayName("约束A: TextTrimmer理论最大输出 × 估算系数 + Prompt开销 ≤ Token拦截阈值")
    void constraintA_theoreticalMaxWithinBudget() throws Exception {
        int trimmerMax = getTotalCharLimit();
        assertTrue(trimmerMax > 0, "TOTAL_CHAR_LIMIT must be > 0, got: " + trimmerMax);

        int overhead = TokenEstimator.PROMPT_TEMPLATE_OVERHEAD;
        int maxTokens = TokenEstimator.MAX_INPUT_TOKENS;

        int theoreticalMax = (int) (trimmerMax * CN_CHAR_TO_TOKEN) + overhead;
        int remaining = maxTokens - theoreticalMax;
        double pct = remaining * 100.0 / maxTokens;

        assertTrue(theoreticalMax <= maxTokens,
                String.format(
                    "ConstraintA BROKEN: Trimmer max %d chars × %.1f + overhead %d = %d > threshold %d.%n"
                    + "Fix: either reduce TOTAL_CHAR_LIMIT to ≤ %d, or increase MAX_INPUT_TOKENS to ≥ %d",
                    trimmerMax, CN_CHAR_TO_TOKEN, overhead, theoreticalMax, maxTokens,
                    maxTokens - overhead, theoreticalMax));

        System.out.printf("[ConstraintA PASS] Trimmer max %d chars × %.1f + overhead %d = %d ≤ threshold %d, "
                + "margin %d tokens (%.0f%%)%n",
                trimmerMax, CN_CHAR_TO_TOKEN, overhead, theoreticalMax, maxTokens,
                remaining, pct);
    }

    @Test
    @DisplayName("约束B: Golden Set 实测Token分布远在拦截阈值之下")
    void constraintB_goldenSetMaxWithinBudget() throws Exception {
        List<Map<String, Object>> samples = loadGoldenSet();
        assertFalse(samples.isEmpty(), "Golden Set must not be empty");

        int maxTokens = 0;
        String maxId = "";
        int minTokens = Integer.MAX_VALUE;
        int overhead = TokenEstimator.PROMPT_TEMPLATE_OVERHEAD;
        int threshold = TokenEstimator.MAX_INPUT_TOKENS;

        System.out.println("\n[ConstraintB] Golden Set Token Distribution:");
        System.out.println("| ID | JD tok | Resume tok | +" + overhead + " | Total |");
        System.out.println("|----|--------|-----------|------|-------|");

        for (Map<String, Object> sample : samples) {
            String id = (String) sample.get("id");
            String jdText = (String) sample.get("jdText");
            String resumeText = (String) sample.get("originalProjectText");

            String tJd = TextTrimmer.trimJd(jdText);
            String tResume = TextTrimmer.trimResume(resumeText);

            int jdTok = TokenEstimator.estimate(tJd);
            int resumeTok = TokenEstimator.estimate(tResume);
            int total = jdTok + resumeTok + overhead;

            System.out.printf("| %s | %d | %d | %d | %d |%n", id, jdTok, resumeTok, overhead, total);

            if (total > maxTokens) { maxTokens = total; maxId = id; }
            if (total < minTokens) minTokens = total;
        }

        double safetyFactor = (double) threshold / maxTokens;

        System.out.printf("%nMetrics:%n");
        System.out.printf("  %d samples, range: %d ~ %d tokens%n", samples.size(), minTokens, maxTokens);
        System.out.printf("  Max: %s = %d tokens%n", maxId, maxTokens);
        System.out.printf("  Safety margin: %.1f×%n", safetyFactor);

        assertTrue(maxTokens <= threshold,
                String.format("ConstraintB-1 BROKEN: max sample %d > threshold %d. Fix: raise threshold to ≥ %d",
                        maxTokens, threshold, maxTokens));

        assertTrue(safetyFactor >= MIN_SAFETY_FACTOR,
                String.format("ConstraintB-2 WARNING: threshold %d / max %d = %.1f× < min %.1f×. "
                        + "Fix: raise threshold to ≥ %d",
                        threshold, maxTokens, safetyFactor, MIN_SAFETY_FACTOR,
                        (int) (maxTokens * MIN_SAFETY_FACTOR)));
    }

    @Test
    @DisplayName("约束C: 英文简历场景链路不崩溃、不丢失核心段落")
    void constraintC_englishResumeDoesNotBreak() {
        String enJd = """
                Job Description: Senior Backend Engineer

                Responsibilities:
                1. Design and implement scalable microservices using Java
                2. Optimize system performance and database queries
                3. Collaborate with cross-functional teams

                Requirements:
                1. 5+ years in Java and Spring Boot
                2. Proficient in microservices and distributed systems
                3. Experience with Redis, Kafka, PostgreSQL
                4. Strong system design and performance optimization skills
                5. Familiar with Docker and Kubernetes
                """;

        String enResume = """
                Work Experience:
                Senior Java Developer at TechCorp (2020-2024)
                - Designed microservices using Spring Boot and Spring Cloud
                - Optimized DB queries, reducing response time by 60%
                - Built real-time pipeline with Kafka processing 1M events/day

                Projects:
                E-commerce Platform
                - Tech Stack: Java, Spring Boot, Redis, Kafka, MySQL, Docker
                - Led migration from monolith to microservices
                - Implemented distributed caching layer handling 50K QPS

                Skills:
                Java (Expert), Python (Intermediate), Spring Boot, Spring Cloud,
                MySQL, Redis, Kafka, Docker, Kubernetes, Git, CI/CD
                """;

        String tJd = TextTrimmer.trimJd(enJd);
        String tResume = TextTrimmer.trimResume(enResume);

        int jdTok = TokenEstimator.estimate(tJd);
        int resumeTok = TokenEstimator.estimate(tResume);
        int total = TokenEstimator.estimateTotal(tJd, tResume);

        System.out.printf("%n[ConstraintC] English Resume Scenario:%n");
        System.out.printf("  JD after trim: %d chars → %d tokens%n", tJd.length(), jdTok);
        System.out.printf("  Resume after trim: %d chars → %d tokens%n", tResume.length(), resumeTok);
        System.out.printf("  Total (with %d overhead): %d tokens%n",
                TokenEstimator.PROMPT_TEMPLATE_OVERHEAD, total);

        assertTrue(total > 0, "English resume token estimate must be > 0");
        assertTrue(total < TokenEstimator.MAX_INPUT_TOKENS,
                String.format("English resume %d tokens exceeds threshold %d", total, TokenEstimator.MAX_INPUT_TOKENS));

        boolean jdOk = tJd.toLowerCase().contains("requirements")
                || tJd.toLowerCase().contains("responsibilities");
        assertTrue(jdOk, "English JD trim must not lose Requirements/Responsibilities sections");

        boolean resumeOk = tResume.toLowerCase().contains("experience")
                || tResume.contains("Work");
        assertTrue(resumeOk, "English resume trim must not lose Work Experience section");

        System.out.println("  Core sections preserved ✅");
    }

    /** 用反射读取 TOTAL_CHAR_LIMIT，保证常量变更时自动重新计算 */
    private static int getTotalCharLimit() throws Exception {
        Field field = TextTrimmer.class.getDeclaredField("TOTAL_CHAR_LIMIT");
        field.setAccessible(true);
        assertEquals(int.class, field.getType(), "TOTAL_CHAR_LIMIT type must be int");
        return field.getInt(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadGoldenSet() throws Exception {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("golden-test-set.json")) {
            if (is == null) {
                throw new IllegalStateException("golden-test-set.json not found in test resources");
            }
            return objectMapper.readValue(is, new TypeReference<>() {});
        }
    }
}