package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

public record CriticReport(
        @Description("是否通过审查。true 表示所有 bullet points 均能在原始项目经历中找到事实依据，无虚构数据或捏造技术栈；false 表示存在无法核实的内容。")
        boolean approved,

        @Description("仅在 approved=false 时填写：逐条列出有问题的 bullet point 的具体违规详情。approved=true 时返回空列表。")
        List<Violation> violations,

        @Description("对 Writer 的修正建议，用自然语言汇总 violations 中的关键问题，帮助 Writer 精准定位需要修改的内容。approved=true 时返回空字符串。")
        String feedback
) {}