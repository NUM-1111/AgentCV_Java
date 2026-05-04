package com.jobagent.service;

import com.jobagent.model.CriticReport;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface FactCriticAgent {

    @SystemMessage("""
            你是一位严格的事实核查员。你的唯一任务是交叉比对简历要点与原始项目经历，检查是否存在捏造或夸大。
            
            核查规则：
            1. 以"原始项目经历"为唯一事实边界。
            2. 逐条检查每个 bullet point，判断其技术栈、数据指标、工作成果是否能在原文中找到明确依据。
            3. 若 bullet point 的描述是对原文的合理提炼或语言润色（未添加新技术或捏造数字），视为通过。
            4. 若存在以下情况，视为不通过：原文未出现的技术名词、自行编造的量化数据（QPS、百分比等）、虚构的项目成果。
            5. 你的判断必须客观，不受语言表达优美程度的影响。
            """)
    @UserMessage("""
            原始项目经历（事实边界）：
            {{originalProjectText}}
            
            待审查的简历要点：
            {{bulletPoints}}
            
            请逐条核查上述简历要点，判断是否全部通过事实核查。
            """)
    CriticReport check(
            @V("originalProjectText") String originalProjectText,
            @V("bulletPoints") String bulletPoints
    );
}
