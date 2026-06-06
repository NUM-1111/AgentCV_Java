package com.jobagent.service;

import com.jobagent.model.CriticReport;
import com.jobagent.model.RewriteReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 编排 Actor-Critic 双智能体审查流：
 * 1. WriterAgent 根据 JD 和原始经历生成草稿（RewriteReport）；
 * 2. FactCriticAgent 对照原始经历逐条核查草稿的 bullet points；
 * 3. 若核查不通过，将 Critic 的 feedback 传给下一轮 Writer 重写；
 * 4. 最多循环 MAX_ROUNDS 轮，超出后返回最后一版草稿。
 *
 * Context 策略：每轮 Writer 只接收 (jdText + originalProjectText + criticFeedback)，
 * 不传历史草稿全文，防止 context 爆炸。
 */
@Service
public class RewriteCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(RewriteCoordinatorService.class);
    public static final int DEFAULT_MAX_ROUNDS = 2;

    private final ResumeWriterAgent writerAgent;
    private final FactCriticAgent criticAgent;
    private final int maxRounds;

    @Autowired
    public RewriteCoordinatorService(ResumeWriterAgent writerAgent, FactCriticAgent criticAgent) {
        this(writerAgent, criticAgent, DEFAULT_MAX_ROUNDS);
    }

    public RewriteCoordinatorService(ResumeWriterAgent writerAgent, FactCriticAgent criticAgent, int maxRounds) {
        this.writerAgent = writerAgent;
        this.criticAgent = criticAgent;
        this.maxRounds = maxRounds;
    }

    public record RewriteResult(RewriteReport report, int rounds) {}

    /**
     * 执行 Actor-Critic 审查循环，返回通过审查的草稿（或超出轮次后的最后一版）及实际轮次数。
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

            log.info("Critic result: approved={}, round={}/{}", criticism.approved(), round + 1, maxRounds);

            if (criticism.approved()) {
                return new RewriteResult(draft, round + 1);
            }

            criticFeedback = criticism.feedback();
        }

        log.warn("Actor-Critic reached maxRounds={}, returning last draft", maxRounds);
        return new RewriteResult(lastDraft, maxRounds);
    }

    private String buildFeedbackPrompt(int round, String criticFeedback) {
        if (round == 0 || criticFeedback == null || criticFeedback.isBlank()) {
            return "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】";
        }
        return "【上一版审查未通过，请根据以下反馈修正，确保所有内容均有原文依据】\n" + criticFeedback;
    }

    private String formatBulletPoints(RewriteReport report) {
        if (report.rewrittenBulletPoints() == null || report.rewrittenBulletPoints().isEmpty()) {
            return "（无要点）";
        }
        return IntStream.range(0, report.rewrittenBulletPoints().size())
                .mapToObj(i -> (i + 1) + ". " + report.rewrittenBulletPoints().get(i))
                .collect(Collectors.joining("\n"));
    }
}
