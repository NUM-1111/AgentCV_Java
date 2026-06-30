package com.jobagent.service;

import com.jobagent.model.WriterDraft;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

/**
 * Writer Agent — 简历优化创作者。
 *
 * 在 Actor-Critic 架构中扮演"在笼子里跳舞"的角色：
 * "笼子"是原始项目经历的事实边界（只能使用原文已有的技术、数据和成果），
 * "跳舞"是用 STAR 法则优化语言表达使其更贴合 JD 的技术偏好。
 *
 * @see FactCriticAgent
 * @see ResumeOptimizationService
 * @see WriterDraft
 */
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
    /**
     * 根据 JD 和原始经历生成简历要点草稿。
     *
     * @param jdText              目标岗位 JD
     * @param originalProjectText 原始项目经历（唯一事实边界）
     * @param criticFeedback      反馈文本：首轮为初始指令，后续轮为上一轮 Critic 的修正建议
     * @return 结构化重写草稿，包含优化后的 bullet points 和各条改写原因
     */
    WriterDraft rewrite(
            @V("jdText") String jdText,
            @V("originalProjectText") String originalProjectText,
            @V("criticFeedback") String criticFeedback
    );
}