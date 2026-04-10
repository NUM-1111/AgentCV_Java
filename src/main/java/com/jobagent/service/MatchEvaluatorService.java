package com.jobagent.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface MatchEvaluatorService {

    @SystemMessage("你是一个极其严苛的资深技术面试官和 HR 专家。你的任务是客观、冷酷地评估候选人简历与 JD 的匹配度，绝不奉承。你必须严格按照指定 JSON Schema 输出，禁止输出任何解释、前后缀、Markdown、注释或额外字段。")
    @UserMessage("""
            请严格对照岗位 JD 与候选人简历纯文本，输出结构化的人岗匹配报告。
            你只能返回一个 JSON 对象，且字段必须严格如下：
            {
              "matchScore": 78,
              "matchedSkills": ["Java", "Spring Boot"],
              "missingSkills": ["Redis"],
              "improvementAdvice": "补充 Redis 与高并发项目经验。"
            }

            强制要求：
            1. 只能包含这 4 个字段，禁止输出 strengths、weaknesses、summary、candidate_name 等任何其他字段。
            2. `matchScore` 必须是 0-100 的整数，不能是小数。
            3. `matchedSkills` 和 `missingSkills` 必须是字符串数组，即使为空也要返回 []。
            4. `improvementAdvice` 必须是非空字符串。
            5. 不要使用 Markdown 代码块，不要添加注释，不要添加解释文字。

            【岗位 JD】
            {{jdText}}

            【简历纯文本】
            {{resumeText}}
            """)
    /**
     * 评估候选人简历与 JD 的匹配度
     * @param jdText 岗位 JD
     * @param resumeText 候选人简历纯文本
     * @return 模型返回的原始文本
     */
    String evaluate(@V("jdText") String jdText, @V("resumeText") String resumeText);
}
