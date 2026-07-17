package com.jobagent.service;

import com.jobagent.model.OptimizationReport.ScoreResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

/**
 * 评分 Agent — Phase 1 评分阶段的执行者。
 *
 * <p>职责：客观评估简历与 JD 的匹配度，输出结构化评分结果。
 * 这是一个轻量 Agent——不做审查、不迭代、不调用 Tool，只做一次评分调用。
 */
@AiService
public interface ResumeScoringAgent {

    @SystemMessage("""
            你是简历评分专家。对候选人简历做三维评分（每维 0-100），输出 JSON。

            评分维度权重：
            - JD匹配度(40%)：技能关键词匹配、技术栈覆盖、业务领域契合
            - 内容质量(35%)：技术深度、量化成果、STAR结构完整性
            - 格式规范(25%)：排版清晰度、篇幅适当性、信息密度

            强制要求：
            1. overallScore = jdMatch.score×0.4 + contentQuality.score×0.35 + format.score×0.25（取整）。
            2. 每个子维度 score 必须是 0-100 的整数。
            3. 所有数组字段即使为空也必须返回 []。
            4. improvementAdvice 必须是非空字符串，给出具体可操作的建议。
            5. 不要使用 Markdown 代码块，不要添加注释，不要添加解释文字。
            """)
    @UserMessage("""
            【岗位 JD】
            {{jdText}}

            【简历纯文本】
            {{resumeText}}
            """)
    /** 对简历与 JD 做匹配评分。 */
    ScoreResult score(
            @V("jdText") String jdText,
            @V("resumeText") String resumeText
    );
}