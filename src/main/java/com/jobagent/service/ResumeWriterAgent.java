package com.jobagent.service;

import com.jobagent.model.RewriteReport;
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
 * 与 {@link FactCriticAgent} 的分工：Writer 只管在事实边界内优化表达，
 * Critic 只管核查是否越界（捏造数据/技术）。同一 LLM 拆分角色、
 * 用不同的 {@code @SystemMessage} 切换，避免"创意模式"和"审查模式"的角色冲突。
 *
 * LangChain4j 注解说明：
 * - {@code @AiService}：声明此接口为 AI Agent，启动时由 JDK 动态代理生成实现类并注入容器
 * - {@code @SystemMessage}：定义 Agent 的身份、边界、行为规则（对应 OpenAI 的 system role），在整个对话中持续生效
 * - {@code @UserMessage}：传递本轮任务输入，模板中的 {{变量名}} 通过 {@code @V} 注入
 * - {@code @V("变量名")}：将方法参数值注入 UserMessage 模板中的同名占位符
 *
 * @see FactCriticAgent
 * @see RewriteCoordinatorService
 * @see RewriteReport
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
     * @param jdText              目标岗位 JD，用于针对性调整技术关键词和表达风格
     * @param originalProjectText 原始项目经历（唯一事实边界，所有生成内容必须在此处有依据）
     * @param criticFeedback      反馈文本：首轮为初始指令，后续轮为上一轮 Critic 的具体修正建议
     * @return 结构化重写结果，包含优化后的 bullet points 和各条改写原因
     */
    RewriteReport rewrite(
            @V("jdText") String jdText,
            @V("originalProjectText") String originalProjectText,
            @V("criticFeedback") String criticFeedback
    );
}