package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Writer Agent 的结构化重写结果。
 *
 * 包含两个维度：
 * - rewrittenBulletPoints：优化后的简历要点（STAR 法则 + JD 对齐）
 * - optimizationReasons：各条改写的依据，例如"针对JD中要求的Redis技能，在要点中显式补充了Redis分布式锁的应用"
 *
 * 设计决策：Writer 的约束写在 @SystemMessage 中而非此处——
 * 三条硬约束（禁止捏造量化数据、禁止引入原文没有的技术、允许定性描述）是行为宪法，
 * 持久作用于整个对话；@Description 只告诉 LLM 每个字段的含义和格式。
 *
 * @see com.jobagent.service.ResumeWriterAgent
 * @see com.jobagent.service.RewriteCoordinatorService
 */
public record RewriteReport(
        @Description("使用 STAR 法则重写的项目经历要点。每条要点必须以动词开头（如：主导、设计、优化），紧扣 JD 需求。语言要极度精简专业。只能使用原始项目经历中已有的数据，不得推测或编造任何量化指标。")
        List<String> rewrittenBulletPoints,

        @Description("解释为什么进行这样的改写。例如'针对JD中要求的Redis技能，在要点中显式补充了Redis分布式锁的应用'。")
        List<String> optimizationReasons
) {}