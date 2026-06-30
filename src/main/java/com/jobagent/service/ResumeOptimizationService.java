package com.jobagent.service;

import com.jobagent.model.CriticReport;
import com.jobagent.model.OptimizationReport;
import com.jobagent.model.OptimizationReport.ScoreResult;
import com.jobagent.model.Violation;
import com.jobagent.model.WriterDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 简历优化统一管线 — 评分 → 改写 → 审查 三阶段服务。
 */
@Service
public class ResumeOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(ResumeOptimizationService.class);
    public static final int DEFAULT_MAX_ROUNDS = 2;

    private final ResumeScoringAgent scoringAgent;
    private final ResumeWriterAgent writerAgent;
    private final FactCriticAgent criticAgent;
    private final int maxRounds;

    @Autowired
    public ResumeOptimizationService(ResumeScoringAgent scoringAgent,
                                     ResumeWriterAgent writerAgent,
                                     FactCriticAgent criticAgent) {
        this(scoringAgent, writerAgent, criticAgent, DEFAULT_MAX_ROUNDS);
    }

    public ResumeOptimizationService(ResumeScoringAgent scoringAgent,
                                     ResumeWriterAgent writerAgent,
                                     FactCriticAgent criticAgent,
                                     int maxRounds) {
        this.scoringAgent = scoringAgent;
        this.writerAgent = writerAgent;
        this.criticAgent = criticAgent;
        this.maxRounds = maxRounds;
    }

    public OptimizationReport optimize(String jdText, String originalProjectText) {
        long start = System.currentTimeMillis();
        log.info("Phase 1: scoring...");
        ScoreResult score = scoringAgent.score(jdText, originalProjectText);
        log.info("Phase 1 done: matchScore={}", score.matchScore());

        log.info("Phase 2+3: rewrite + review");
        RewriteResult rr = runActorCritic(jdText, originalProjectText);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Pipeline complete: score={}, approved={}, rounds={}, {}ms",
                score.matchScore(), rr.approved, rr.rounds, elapsed);
        return OptimizationReport.fullResult(score, rr.bullets, rr.reasons,
                rr.approved, rr.rounds, rr.violations, elapsed);
    }

    /** 流式完整优化——每一步都通过 Consumer 推送事件 */
    public void optimizeStreaming(String jdText, String originalProjectText,
                                   Consumer<StreamEvent> onEvent) {
        long start = System.currentTimeMillis();

        try {
            // Phase 1
            onEvent.accept(StreamEvent.of(StreamEvent.Type.SCORING_START, "正在分析 JD 并评估匹配度…"));
            ScoreResult score = scoringAgent.score(jdText, originalProjectText);
            onEvent.accept(StreamEvent.of(StreamEvent.Type.SCORING_DONE, "匹配评分完成", score));

            // Phase 2+3
            String feedback = "";
            WriterDraft lastDraft = null;
            boolean passed = false;
            int rounds = 0;

            for (int r = 0; r < maxRounds; r++) {
                onEvent.accept(StreamEvent.of(StreamEvent.Type.WRITER_START, "第 " + (r + 1) + " 轮改写…"));
                WriterDraft draft = writerAgent.rewrite(jdText, originalProjectText,
                        buildFeedbackPrompt(r, feedback));
                lastDraft = draft;
                onEvent.accept(StreamEvent.of(StreamEvent.Type.WRITER_DONE, "草稿生成完成", draft));

                onEvent.accept(StreamEvent.of(StreamEvent.Type.CRITIC_START, "Critic 审查中…"));
                CriticReport c = criticAgent.check(originalProjectText, formatBulletPoints(draft));
                onEvent.accept(StreamEvent.of(StreamEvent.Type.CRITIC_DONE,
                        c.approved() ? "审查通过" : "发现 " + c.violations().size() + " 条违规", c));

                int maxSev = c.violations() != null && !c.violations().isEmpty()
                        ? c.violations().stream().mapToInt(Violation::severity).max().orElse(0) : 0;

                if (c.approved() || maxSev <= 2) {
                    passed = true;
                    rounds = r + 1;
                    break;
                }
                feedback = c.feedback();
                rounds = r + 1;
            }

            long elapsed = System.currentTimeMillis() - start;
            OptimizationReport report = OptimizationReport.fullResult(score,
                    lastDraft.rewrittenBulletPoints(), lastDraft.optimizationReasons(),
                    passed, rounds, passed ? null : criticAgent.check(originalProjectText,
                            formatBulletPoints(lastDraft)).violations(), elapsed);
            onEvent.accept(StreamEvent.of(StreamEvent.Type.COMPLETE, "完成", report));

        } catch (Exception e) {
            onEvent.accept(StreamEvent.of(StreamEvent.Type.ERROR, e.getMessage()));
        }
    }

    public OptimizationReport scoreOnly(String jdText, String originalProjectText) {
        long start = System.currentTimeMillis();
        ScoreResult score = scoringAgent.score(jdText, originalProjectText);
        long elapsed = System.currentTimeMillis() - start;
        log.info("Quick score: matchScore={}, {}ms", score.matchScore(), elapsed);
        return OptimizationReport.scoreOnly(score, elapsed);
    }

    private RewriteResult runActorCritic(String jdText, String originalProjectText) {
        String feedback = "";
        WriterDraft lastDraft = null;

        for (int round = 0; round < maxRounds; round++) {
            String prompt = buildFeedbackPrompt(round, feedback);
            log.info("Actor-Critic round={}/{}", round + 1, maxRounds);

            WriterDraft draft = writerAgent.rewrite(jdText, originalProjectText, prompt);
            lastDraft = draft;

            CriticReport c = criticAgent.check(originalProjectText, formatBulletPoints(draft));
            int maxSev = c.violations() != null && !c.violations().isEmpty()
                    ? c.violations().stream().mapToInt(Violation::severity).max().orElse(0) : 0;

            if (c.approved() || maxSev <= 2) {
                return new RewriteResult(draft.rewrittenBulletPoints(),
                        draft.optimizationReasons(), round + 1, true, null);
            }
            feedback = c.feedback();
        }

        CriticReport fc = criticAgent.check(originalProjectText, formatBulletPoints(lastDraft));
        return new RewriteResult(lastDraft.rewrittenBulletPoints(),
                lastDraft.optimizationReasons(), maxRounds, fc.approved(), fc.violations());
    }

    private static String buildFeedbackPrompt(int round, String fb) {
        if (round == 0 || fb == null || fb.isBlank())
            return "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】";
        return "【上一版审查未通过，请根据以下反馈修正】\n" + fb;
    }

    private static String formatBulletPoints(WriterDraft d) {
        if (d.rewrittenBulletPoints() == null || d.rewrittenBulletPoints().isEmpty())
            return "（无要点）";
        return IntStream.range(0, d.rewrittenBulletPoints().size())
                .mapToObj(i -> (i + 1) + ". " + d.rewrittenBulletPoints().get(i))
                .collect(Collectors.joining("\n"));
    }

    private record RewriteResult(
            List<String> bullets, List<String> reasons,
            int rounds, boolean approved, List<Violation> violations) {}
}