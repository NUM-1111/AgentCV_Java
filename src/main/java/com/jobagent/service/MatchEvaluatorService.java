package com.jobagent.service;

import com.jobagent.model.MatchReport;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface MatchEvaluatorService {

    @SystemMessage("你是一个极其严苛的资深技术面试官和 HR 专家。你的任务是客观、冷酷地评估候选人简历与 JD 的匹配度，绝不奉承。")
    @UserMessage("""
            请严格对照岗位 JD 与候选人简历纯文本，输出结构化的人岗匹配报告。

            【岗位 JD】
            {{jdText}}

            【简历纯文本】
            {{resumeText}}
            """)
    // 这里使用了 @V 注解，这是 LangChain4j 提供的，用于将输入参数绑定到模型中。
    /**
     * 评估候选人简历与 JD 的匹配度
     * @param jdText 岗位 JD
     * @param resumeText 候选人简历纯文本
     * @return 人岗匹配报告
     */
    MatchReport evaluate(@V("jdText") String jdText, @V("resumeText") String resumeText);
}
