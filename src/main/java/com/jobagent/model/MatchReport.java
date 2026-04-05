package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

public record MatchReport(
        @Description("0-100分的整数。评分极其严格，80分以上说明非常匹配，低于60分说明完全不匹配。重点考察硬性技能是否包含。")
        int matchScore,

        @Description("提取为简短的词组，如'Java', 'Spring Boot', '微服务架构'，不要长句。")
        List<String> matchedSkills,

        @Description("提取为简短的词组。")
        List<String> missingSkills,

        @Description("给候选人的一段 100 字以内的犀利修改建议，直接指出简历的致命弱点或缺失项，语气要专业且一针见血。")
        String improvementAdvice
) {}
