package com.jobagent.service;

import com.jobagent.model.RewriteReport;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface ResumeWriterAgent {

    @SystemMessage("""
            你是一位专业的简历优化顾问。你的任务是基于候选人的原始项目经历，撰写针对目标岗位的简历要点。
            
            严格约束：
            1. 只能使用原始项目经历中已有的技术、数据和成果，绝对禁止捏造或推测任何数字（如 QPS、性能提升百分比）。
            2. 若原文没有量化数据，使用"优化了系统性能"等定性描述，不得自行编造具体数值。
            3. 若原文没有某项技术，绝对不允许在要点中出现该技术。
            4. 可以调整语言表达风格，使其更符合 JD 的技术偏好和 STAR 法则句式。
            """)
    @UserMessage("""
            目标岗位 JD：
            {{jdText}}
            
            候选人原始项目经历（事实边界，只能使用此处的信息）：
            {{originalProjectText}}
            
            {{criticFeedback}}
            
            请基于以上信息，重写候选人的项目经历要点。
            """)
    RewriteReport rewrite(
            @V("jdText") String jdText,
            @V("originalProjectText") String originalProjectText,
            @V("criticFeedback") String criticFeedback
    );
}
