package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;

/**
 * 单条违规记录，描述一个 bullet point 的具体违规情况。
 * Critic 审查时每条违规生成一个 Violation 实例。
 *
 * 四条违规类型的取舍逻辑：
 * - FAKE_DATA（severity 4-5）：捏造量化数据/数值不一致。最严重的违规，必须触发重写
 * - FAKE_TECH（severity 4-5）：引入原文不存在的技术栈。同样不可接受
 * - EXAGGERATION（severity 3）："参与开发"被改成"主导设计"。明显的夸大，需要修正
 * - MINOR_EMBELLISHMENT（severity 1-2）：轻度润色越界。可通过 severity 阈值容忍（severity≤2 仍视为通过）
 *
 * severity 阈值容忍的设计目的：
 * 避免 Critic 偶发性过度敏感（如把合理的同义改写判为违规）导致不必要的重写循环。
 * Coordinator 可检查所有 violations 的 max severity，若 ≤2 则仍视为通过。
 *
 * @see CriticReport
 * @see com.jobagent.service.FactCriticAgent
 */
public record Violation(
        @Description("有问题的 bullet point 的序号，从 1 开始。")
        int bulletIndex,

        @Description("违规类型：FAKE_DATA（捏造数据/量化指标）、FAKE_TECH（引入原文不存在的技术栈）、EXAGGERATION（夸大成果/角色）、MINOR_EMBELLISHMENT（轻度润色越界）")
        String violationType,

        @Description("违规严重程度 1-5。1=措辞轻微不当，5=完全捏造核心技术/数据。")
        int severity,

        @Description("具体违规说明。指出有问题的 bullet point 原文片段，并说明为什么不符合原文事实。")
        String detail
) {}