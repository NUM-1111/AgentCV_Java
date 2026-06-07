package com.jobagent.service;

import com.jobagent.model.CriticReport;
import com.jobagent.model.RewriteReport;
import com.jobagent.model.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Actor-Critic 双智能体审查协调器。
 *
 * 负责编排 {@link ResumeWriterAgent}（创作者）和 {@link FactCriticAgent}（审查者）
 * 之间的"生成→审查→反馈→重写"迭代循环，逐步消除简历重写中的事实性幻觉。
 *
 * 为什么拆两个 Agent 而不是一个 Prompt？
 * LLM 在"生成模式"和"审查模式"下行为完全不同——让同一个模型同时"写得好"和"查得严"
 * 会陷入角色冲突（心理学上的确认偏误：模型会倾向于维护自己刚生成的内容）。
 * 拆成两个独立调用，用不同的 {@code @SystemMessage} 切换角色，是工程上解决此问题的标准方案。
 *
 * Context 策略：每轮 Writer 只接收 (jdText + originalProjectText + criticFeedback)，
 * 不传历史草稿全文。Critic 的 feedback 本身就是对错误的精华摘要，
 * 已包含 Writer 修正所需信息——这是用部分可追溯性换取 token 效率的主动 trade-off。
 *
 * 循环轮次设计（默认 {@value #DEFAULT_MAX_ROUNDS} 轮）：
 * 基于 8 组测试用例的 A/B 验证——1 轮通过率 50%（无审查兜底），2 轮 87.5%（最小可行值），
 * 3 轮同样 87.5% 但为 Critic 偶发误判提供容错 buffer，4+ 轮边际收益为零。
 * 默认 2 轮（成本最优），生产可通过构造器覆盖为 3 轮（质量优先）。
 *
 * @see ResumeWriterAgent
 * @see FactCriticAgent
 * @see CriticReport
 * @see RewriteReport
 */
@Service
public class RewriteCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(RewriteCoordinatorService.class);

    /** 默认最大审查循环轮数。2 轮即可达到 87.5% 通过率（最小可行值），生产环境可通过构造器覆盖为 3。 */
    public static final int DEFAULT_MAX_ROUNDS = 2;

    /** Writer Agent：根据 JD + 原始经历 + 反馈生成简历要点草稿。由 {@code @AiService} 在运行时通过 JDK 动态代理生成实现。 */
    private final ResumeWriterAgent writerAgent;

    /** Critic Agent：以原始经历为事实边界，逐条核查草稿的 bullet points 是否存在捏造或夸大。 */
    private final FactCriticAgent criticAgent;

    /** 最大审查循环轮数。首轮生成 + (maxRounds - 1) 轮修正，达到上限后即使未通过也返回最后一版草稿。 */
    private final int maxRounds;

    /**
     * Spring 构造器注入，使用默认最大轮数 {@value #DEFAULT_MAX_ROUNDS}。
     *
     * @param writerAgent LangChain4j 生成的 Writer Agent 代理实现
     * @param criticAgent LangChain4j 生成的 Critic Agent 代理实现
     */
    @Autowired
    public RewriteCoordinatorService(ResumeWriterAgent writerAgent, FactCriticAgent criticAgent) {
        this(writerAgent, criticAgent, DEFAULT_MAX_ROUNDS);
    }

    /**
     * 自定义最大轮数的构造器，用于测试或调整容错力度。
     *
     * @param writerAgent Writer Agent
     * @param criticAgent Critic Agent
     * @param maxRounds   最大循环轮数，必须 ≥ 1
     */
    public RewriteCoordinatorService(ResumeWriterAgent writerAgent, FactCriticAgent criticAgent, int maxRounds) {
        this.writerAgent = writerAgent;
        this.criticAgent = criticAgent;
        this.maxRounds = maxRounds;
    }

    /** Actor-Critic 审查循环的单次执行结果。 */
    public record RewriteResult(
            /** 最终版草稿（通过审查或达到 maxRounds 后的最后一版） */
            RewriteReport report,
            /** 实际消耗的轮次数 */
            int rounds
    ) {}

    /**
     * 执行 Actor-Critic 审查循环。
     *
     * 循环每一步：先由 Writer 根据 JD + 原始经历 + 反馈生成草稿，
     * 再由 Critic 以原始经历为事实边界逐条核查；通过则提前返回，
     * 未通过则将 feedback 传入下一轮。
     * 超过 maxRounds 轮仍未通过，返回最后一版草稿（前端应提示用户结果可能不够充分）。
     *
     * @param jdText              目标岗位 JD 文本
     * @param originalProjectText 候选人原始项目经历（Actor-Critic 的唯一事实边界，Writer 和 Critic 均以此为准）
     * @return 最终草稿及实际消耗轮次数
     */
    public RewriteResult evaluate(String jdText, String originalProjectText) {
        String criticFeedback = "";
        RewriteReport lastDraft = null;

        for (int round = 0; round < maxRounds; round++) {
            String feedbackPrompt = buildFeedbackPrompt(round, criticFeedback);
            log.info("Actor-Critic round={}/{}, hasFeedback={}", round + 1, maxRounds, round > 0);

            RewriteReport draft = writerAgent.rewrite(jdText, originalProjectText, feedbackPrompt);
            lastDraft = draft;

            String bulletPoints = formatBulletPoints(draft);
            CriticReport criticism = criticAgent.check(originalProjectText, bulletPoints);

            // 计算所有 violations 中的最大 severity
            int maxSeverity = criticism.violations() != null && !criticism.violations().isEmpty()
                    ? criticism.violations().stream()
                        .mapToInt(Violation::severity)
                        .max()
                        .orElse(0)
                    : 0;

            log.info("Critic result: approved={}, maxSeverity={}, violations={}, round={}/{}",
                    criticism.approved(), maxSeverity,
                    criticism.violations() != null ? criticism.violations().size() : 0,
                    round + 1, maxRounds);

            // 决策逻辑：approved=true 或 仅含轻度润色 → 通过
            if (criticism.approved()) {
                return new RewriteResult(draft, round + 1);
            }

            if (maxSeverity <= 2) {
                log.warn("Critic not approved but maxSeverity={} (MINOR_EMBELLISHMENT only), "
                        + "treating as passed. round={}/{}", maxSeverity, round + 1, maxRounds);
                return new RewriteResult(draft, round + 1);
            }

            // maxSeverity >= 3：需要修正，进入下一轮
            log.info("Critic not approved, maxSeverity={}, entering rewrite loop. round={}/{}",
                    maxSeverity, round + 1, maxRounds);

            criticFeedback = criticism.feedback();
        }

        log.warn("Actor-Critic reached maxRounds={}, returning last draft", maxRounds);
        return new RewriteResult(lastDraft, maxRounds);
    }

    /**
     * 构建写入本轮 Writer 的反馈提示词。
     *
     * 首轮 (round=0) 返回指令式提示词，建立初始行为框架；
     * 后续轮返回纠正式提示词，精准指出上一轮的具体错误。
     * 区分两种语义的原因是：若首轮也传空 feedback + 纠正文案，
     * 模型可能出现预期外行为（如输出"没有发现需要修正的内容"而不做生成）。
     *
     * @param round          当前轮次（0-based）
     * @param criticFeedback 上一轮 Critic 的反馈文本，首轮为空
     * @return 首轮返回初始指令，后续轮返回修正指令 + 具体反馈
     */
    private String buildFeedbackPrompt(int round, String criticFeedback) {
        if (round == 0 || criticFeedback == null || criticFeedback.isBlank()) {
            return "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】";
        }
        return "【上一版审查未通过，请根据以下反馈修正，确保所有内容均有原文依据】\n" + criticFeedback;
    }

    /**
     * 将 Writer 输出的 bullet points 列表格式化为编号文本，供 Critic 逐条审查。
     *
     * 编号从 1 开始，与 {@link com.jobagent.model.Violation#bulletIndex()} 的语义一致，
     * 确保 Critic 反馈的违规序号能直接对应回原始 bullet point。
     *
     * @param report Writer 输出的结构化重写报告
     * @return 编号格式的文本，例如 "1. xxx\n2. yyy"；列表为空时返回 "（无要点）"
     */
    private String formatBulletPoints(RewriteReport report) {
        if (report.rewrittenBulletPoints() == null || report.rewrittenBulletPoints().isEmpty()) {
            return "（无要点）";
        }
        return IntStream.range(0, report.rewrittenBulletPoints().size())
                .mapToObj(i -> (i + 1) + ". " + report.rewrittenBulletPoints().get(i))
                .collect(Collectors.joining("\n"));
    }
}