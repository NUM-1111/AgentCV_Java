package com.jobagent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MatchReport(
                //@JsonAlias 注解用于指定 JSON 字段名称的别名，当 JSON 字段名称与 Java 对象属性名称不一致时，可以使用该注解进行映射，是兜底措施
                @JsonAlias({
                                "match_score",
                                "score" }) @Min(0) @Max(100) @Description("0-100分的整数。评分极其严格，80分以上说明非常匹配，低于60分说明完全不匹配。重点考察硬性技能是否包含。") int matchScore,

                @JsonAlias({ "matched_skills" }) @NotNull(message = "matchedSkills 不能为空") @Description("提取为简短的词组，如'Java', 'Spring Boot', '微服务架构'，不要长句。") List<String> matchedSkills,

                @JsonAlias({ "missing_skills" }) @Description("提取为简短的词组。") List<String> missingSkills,

                @JsonAlias({ "improvement_advice",
                                "advice" }) @Description("给候选人的一段 100 字以内的犀利修改建议，直接指出简历的致命弱点或缺失项，语气要专业且一针见血。") String improvementAdvice) {
}
