package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

public record RewriteReport(
        @Description("使用 STAR 法则重写的项目经历要点。每条要点必须以动词开头（如：主导、设计、优化），紧扣 JD 需求。语言要极度精简专业。只能使用原始项目经历中已有的数据，不得推测或编造任何量化指标。")
        List<String> rewrittenBulletPoints,

        @Description("解释为什么进行这样的改写。例如'针对JD中要求的Redis技能，在要点中显式补充了Redis分布式锁的应用'。")
        List<String> optimizationReasons
) {}
