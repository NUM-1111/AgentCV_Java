package com.jobagent.service;

import com.jobagent.model.CriticReport;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

/**
 * FactCritic Agent — 严格的事实核查员。
 *
 * <p>在 Actor-Critic 架构中扮演"笼子的守卫"角色：
 * 以原始项目经历为唯一事实边界，逐条核查 Writer 生成的 bullet points
 * 是否存在捏造或夸大。不看舞姿好不好看（语言表达优不优美），只判断有没有越界。
 *
 * <h3>Agent vs Tool 分工</h3>
 * <p>Critic 是 Agent（Judge，有决策权），负责最终判决"通过/不通过 + severity 分级"。
 * 它注册了 {@link FactCheckTool} 中的核查工具（Checker，无决策权），当遇到需要精确比对
 * 的数字、技术栈差集、角色措辞时，自动调用对应 Tool 获取精确比对结果，再基于结果做最终判断。
 * 这就是"Agent vs Skills"的核心区别——Agent 有决策权，Skills/Tool 是被调用的能力模块。
 *
 * <h3>Tool 注册方式</h3>
 * <p>通过 {@code @AiService(tools = {"factCheckTool"})} 引用 Spring Bean 名称。
 * 框架在代理生成时自动扫描 {@code @Tool} 方法，为每个方法生成 JSON Schema
 * 并注入到每次 LLM 请求的 {@code tools} 字段中。
 *
 * <p>审查规则要点：
 * - 数字逐字比对：原文 QPS 2000 → bullet QPS 5000 = 不通过 → 调 checkClaim 获取精确比对
 * - 技术栈逐项差集：原文 {RocketMQ, Redis} → bullet {Kafka, Redis} = 不通过 → 调 checkTechStack
 * - 定性描述容忍：原文"优化了性能" → bullet"提升了响应速度" = 通过（同义表达，无假数字）
 * - 角色措辞审查：原文"参与" → bullet"主导" = 判 EXAGGERATION → 调 checkRoleWording
 *
 * @see ResumeWriterAgent
 * @see RewriteCoordinatorService
 * @see FactCheckTool
 * @see CriticReport
 * @see com.jobagent.model.Violation
 */
@AiService(tools = {"factCheckTool"})
public interface FactCriticAgent {

    @SystemMessage("""
            你是一位严格的事实核查员。你的唯一任务是交叉比对简历要点与原始项目经历，检查是否存在捏造或夸大。
            
            ★ 可用工具（按需调用，不要凭感觉判断）：
            你可以使用以下核查工具来做精确比对。请务必在需要精确比对时调用，而不是凭"感觉"判断：
            · checkClaim: 精确比对原文和改写声明中的数值是否一致。当你发现 bullet 中出现了原文没有的数字、或数字与原文不同时，必须调用此工具验证，不要自己凭记忆比对。
            · checkTechStack: 核查技术栈差异。当 bullet 中列出了技术名词而你不确定原文是否包含时，调用此工具做精确差集运算。
            · checkRoleWording: 核查角色措辞是否夸大。当原文使用"参与/协助"等弱措辞而 bullet 使用"主导/设计"等强措辞时，调用此工具获取客观比对结果。
            
            ★ 如何结合 Tool 结果做判断：
            - 如果 checkClaim 返回"不一致"，则你必须在 violations 中记录 FAKE_DATA，severity 根据数值差异幅度判定。
            - 如果 checkTechStack 返回"新增技术"，则记录 FAKE_TECH。
            - 如果 checkRoleWording 返回"角色夸大"，则记录 EXAGGERATION。
            - 如果 Tool 返回"一致"但你仍发现其他问题（如语义层面的夸大），你仍然可以基于自己的判断给出违规。
            
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
    /**
     * 以原始经历为事实边界，逐条核查 bullet points 是否存在捏造或夸大。
     *
     * @param originalProjectText 原始项目经历（唯一事实边界，Critic 的所有判断均以此为准）
     * @param bulletPoints        待审查的简历要点（已由 Coordinator 格式化为编号文本）
     * @return 结构化审查结果，包含通过状态、违规详情列表和修正建议
     */
    CriticReport check(
            @V("originalProjectText") String originalProjectText,
            @V("bulletPoints") String bulletPoints
    );
}