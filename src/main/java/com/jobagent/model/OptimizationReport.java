package com.jobagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * 简历优化统一报告 — 评分 + 改写 + 审查 三阶段结果。
 *
 * <p>替代旧架构中分散的 {@code MatchReport} + {@code RewriteReport} + {@code CriticReport} 三元组。
 * 三阶段 Pipeline：评分(score) → 改写(rewrite) → 审查(critic)，每个阶段是一个可独立替换的插槽。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OptimizationReport(

        // ========== Phase 1: 评分 ==========
        @Description("匹配评分结果。仅 Phase 1 执行后填充，Phase 2/3 阶段此字段为 null")
        ScoreResult scoreResult,

        // ========== Phase 2: 改写 ==========
        @Description("优化后的简历要点（STAR 法则 + JD 对齐）。Phase 2 Writer Agent 输出")
        List<String> rewrittenBulletPoints,

        @Description("各条改写的依据说明")
        List<String> optimizationReasons,

        // ========== Phase 3: 审查 ==========
        @Description("Critic Agent 审查结果。approved=true 表示通过事实核查")
        Boolean criticApproved,

        @Description("审查轮次（Actor-Critic 迭代次数）")
        Integer reviewRounds,

        @Description("违规详情列表。仅当 criticApproved=false 时非空")
        List<Violation> violations,

        // ========== 元数据 ==========
        @Description("总耗时（毫秒）")
        Long totalElapsedMs
) {

        /**
         * 评分阶段的子结果。
         */
        public record ScoreResult(
                @Description("匹配度评分 0-100")
                int matchScore,

                @Description("匹配的技能关键词")
                List<String> matchedSkills,

                @Description("缺失的技能关键词")
                List<String> missingSkills,

                @Description("改进建议（100 字以内）")
                String improvementAdvice
        ) {}

        /** 创建仅包含评分结果的报告（快速评分模式） */
        public static OptimizationReport scoreOnly(ScoreResult score, long elapsedMs) {
                return new OptimizationReport(score, null, null, null, null, null, elapsedMs);
        }

        /** 创建完整的改写+审查报告 */
        public static OptimizationReport fullResult(
                        ScoreResult score,
                        List<String> bullets,
                        List<String> reasons,
                        boolean approved,
                        int rounds,
                        List<Violation> violations,
                        long elapsedMs) {
                return new OptimizationReport(score, bullets, reasons, approved, rounds, violations, elapsedMs);
        }
}