package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Critic Agent 的结构化审查结果。
 *
 * 三个字段协同工作：
 * - approved：二元判决，Coordinator 直接据此分支（true=提前返回，false=继续循环）
 * - violations：结构化违规详情（类型 + 严重度 + 原文证据），支持 severity 阈值容忍决策
 * - feedback：自然语言修正建议，直接作为下一轮 Writer 的 criticFeedback 参数
 *
 * 设计决策——为什么传结构化对象而不是自由文本？
 * - 可靠解析：approved 是 boolean，无需解析"通过/没问题/可以/行"等自然语言变体
 * - Token 节省：避免"好的我来审查一下…经过仔细比对…结论是…"等冗余对话
 * - 可统计：支持后续做"平均几轮通过""哪种违规类型最频繁"等数据分析
 *
 * @see Violation
 * @see com.jobagent.service.FactCriticAgent
 * @see com.jobagent.service.RewriteCoordinatorService
 */
public record CriticReport(
        @Description("是否通过审查。true 表示所有 bullet points 均能在原始项目经历中找到事实依据，无虚构数据或捏造技术栈；false 表示存在无法核实的内容。")
        boolean approved,

        @Description("仅在 approved=false 时填写：逐条列出有问题的 bullet point 的具体违规详情。approved=true 时返回空列表。")
        List<Violation> violations,

        @Description("对 Writer 的修正建议，用自然语言汇总 violations 中的关键问题，帮助 Writer 精准定位需要修改的内容。approved=true 时返回空字符串。")
        String feedback
) {}