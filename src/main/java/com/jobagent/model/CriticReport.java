package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;

public record CriticReport(
        @Description("是否通过审查。true 表示所有 bullet points 均能在原始项目经历中找到事实依据，无虚构数据或捏造技术栈；false 表示存在无法核实的内容。")
        boolean approved,

        @Description("仅在 approved=false 时填写：逐条列出有问题的 bullet point 原文，并说明具体违规原因（如：捏造了 QPS 数据、引入了原文不存在的 K8s 技术栈）。approved=true 时返回空字符串。")
        String feedback
) {}
