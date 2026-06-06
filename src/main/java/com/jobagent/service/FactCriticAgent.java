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
            
            ★ 精确数值审查规则（优先级最高）：
            1. 数字逐字比对——原文中出现的任何具体数值，bullet 中必须完全一致。例如：
               · 原文"QPS从500提升至2000"，bullet 写"QPS提升至5000" → 不通过（5000 ≠ 2000）
               · 原文"响应时间降低60%"，bullet 写"响应时间降低60%" → 通过（数值一致）
               · 原文无具体数字仅说"优化了性能"，bullet 写"性能提升30%" → 不通过（原文没有30%）
            2. 技术栈逐项差集——将 bullet 中提到的技术栈与原文做差集：
               · 原文使用{RocketMQ, Redis}，bullet 写{Kafka, Redis} → 不通过（Kafka 不在原文中）
               · 原文使用{Spring Boot}，bullet 写{Spring Boot, Spring Cloud} → 不通过
            3. 定性描述容忍——原文说"优化了系统性能"，bullet 说"提升了系统响应速度" → 通过（同义表达，无假数字）
            4. 角色措辞审查——原文说"参与/负责"，bullet 说"主导/设计/从零搭建" → 判 EXAGGERATION, severity=3。
               原文说"主导/设计"，bullet 同义表达 → 通过。
            
            violations 字段填写规则：
            - bulletIndex: 有问题的 bullet point 序号（从 1 开始）
            - violationType: 违规类型，必须是以下之一
              · FAKE_DATA: 捏造了不存在的量化数据（如：原文没有 QPS 数据，但 bullet 中写了具体数字；或数值与原文不一致）
              · FAKE_TECH: 引入了原文中未提及的技术栈（如：原文只用 RocketMQ，bullet 中写了 Kafka）
              · EXAGGERATION: 严重夸大了成果或角色定位（如："参与开发"被改成"主导设计"）
              · MINOR_EMBELLISHMENT: 轻度润色越界（如：合理的语言润色略微过度）
            - severity: 严重程度 1-5
              · 1-2: 措辞可以接受，调整即可通过
              · 3: 明显的夸张，需要修正
              · 4-5: 严重捏造，完全无事实依据
            - detail: 具体说明哪个 bullet point 的哪部分描述违规，为什么不符合原文。必须引用原文中的对应语句作为证据。
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
