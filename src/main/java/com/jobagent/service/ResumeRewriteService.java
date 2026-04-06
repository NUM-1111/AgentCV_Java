package com.jobagent.service;

import com.jobagent.model.RewriteReport;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface ResumeRewriteService {

    @SystemMessage("你是一位顶级互联网大厂的技术猎头兼简历辅导专家。你的拿手好戏是化腐朽为神奇，将候选人平庸的语言重写为极具技术深度的 STAR 法则（Situation, Task, Action, Result）句式。")
    @UserMessage("""
            目标岗位 JD：
            {{jdText}}

            候选人原始项目经历：
            {{originalProjectText}}

            请严格根据 JD 的技术偏好，重写候选人的项目经历。
            """)
    RewriteReport rewrite(@V("jdText") String jdText, @V("originalProjectText") String originalProjectText);
}
