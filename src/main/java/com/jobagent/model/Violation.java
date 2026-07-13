package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;

/**
 * 单条违规记录，描述一个 bullet point 的具体违规情况。
 * Critic 审查时每条违规生成一个 Violation 实例。
 *
 * 三种违规类型，任一出现即触发重写：
 * - FAKE_DATA：捏造量化数据/数值不一致
 * - FAKE_TECH：引入原文不存在的技术栈
 * - EXAGGERATION："参与开发"被改成"主导设计"等角色/成果夸大
 *
 * @see CriticReport
 * @see com.jobagent.service.FactCriticAgent
 */
public record Violation(
        @Description("有问题的 bullet point 的序号，从 1 开始。")
        int bulletIndex,

        @Description("违规类型：FAKE_DATA（捏造数据/量化指标）、FAKE_TECH（引入原文不存在的技术栈）、EXAGGERATION（夸大成果/角色）")
        String violationType,

        @Description("具体违规说明。指出有问题的 bullet point 原文片段，并说明为什么不符合原文事实。")
        String detail
) {}
