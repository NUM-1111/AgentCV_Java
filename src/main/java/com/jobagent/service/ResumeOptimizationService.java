package com.jobagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.model.CriticReport;
import com.jobagent.model.OptimizationReport;
import com.jobagent.model.OptimizationReport.ScoreResult;
import com.jobagent.model.Violation;
import com.jobagent.model.WriterDraft;
import com.jobagent.util.FieldNameNormalizer;
import com.jobagent.util.ResumeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
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
    private final ObjectMapper objectMapper;
    private final int maxRounds;

    @Autowired
    public ResumeOptimizationService(ResumeScoringAgent scoringAgent,
                                     ResumeWriterAgent writerAgent,
                                     FactCriticAgent criticAgent,
                                     ObjectMapper objectMapper) {
        this(scoringAgent, writerAgent, criticAgent, objectMapper, DEFAULT_MAX_ROUNDS);
    }

    public ResumeOptimizationService(ResumeScoringAgent scoringAgent,
                                     ResumeWriterAgent writerAgent,
                                     FactCriticAgent criticAgent,
                                     ObjectMapper objectMapper,
                                     int maxRounds) {
        this.scoringAgent = scoringAgent;
        this.writerAgent = writerAgent;
        this.criticAgent = criticAgent;
        this.objectMapper = objectMapper;
        this.maxRounds = maxRounds;
    }

    public OptimizationReport optimize(String jdText, String originalProjectText) {
        long start = System.currentTimeMillis();
        log.info("Phase 1: scoring...");
        ScoreResult score = scoringAgent.score(jdText, originalProjectText);
        log.info("Phase 1 done: overallScore={}", score.overallScore());

        log.info("Phase 2+3: rewrite + review (with score guidance)");
        String scoreGuidance = buildScoreGuidance(score);
        RewriteResult rr = runActorCritic(jdText, originalProjectText, scoreGuidance);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Pipeline complete: score={}, approved={}, rounds={}, {}ms",
                score.overallScore(), rr.approved, rr.rounds, elapsed);
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
            String scoreGuidance = buildScoreGuidance(score);
            String feedback = "";
            WriterDraft lastDraft = null;
            boolean passed = false;
            int rounds = 0;

            for (int r = 0; r < maxRounds; r++) {
                onEvent.accept(StreamEvent.of(StreamEvent.Type.WRITER_START, "第 " + (r + 1) + " 轮改写…"));
                String rawJson = writerAgent.rewrite(jdText, originalProjectText,
                        buildFeedbackPrompt(r, feedback, scoreGuidance));
                WriterDraft draft = parseWriterDraft(rawJson);
                lastDraft = draft;
                onEvent.accept(StreamEvent.of(StreamEvent.Type.WRITER_DONE, "草稿生成完成", draft));

                onEvent.accept(StreamEvent.of(StreamEvent.Type.CRITIC_START, "Critic 审查中…"));
                CriticReport c = criticAgent.check(originalProjectText, formatBulletPoints(draft));
                onEvent.accept(StreamEvent.of(StreamEvent.Type.CRITIC_DONE,
                        c.approved() ? "审查通过" : "发现 " + c.violations().size() + " 条违规", c));

                if (c.approved()) {
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
        log.info("Quick score: overallScore={}, {}ms", score.overallScore(), elapsed);
        return OptimizationReport.scoreOnly(score, elapsed);
    }

    /**
     * 完整简历优化：解析 → 逐项目优化 → 回填拼接。
     */
    public OptimizationReport optimizeFullResume(String jdText, String fullResumeText) {
        long start = System.currentTimeMillis();
        log.info("optimizeFullResume: parsing resume...");

        ResumeParser.ResumeSections sections = ResumeParser.parse(fullResumeText);
        List<ResumeParser.ResumeSections.ProjectSection> projects = sections.projects();
        log.info("optimizeFullResume: found {} project(s)", projects.size());

        ScoreResult score = scoringAgent.score(jdText, fullResumeText);
        String scoreGuidance = buildScoreGuidance(score);

        List<String> rewrittenProjectTexts = new ArrayList<>();
        String rewrittenExperience = null;
        String rewrittenSkills = null;
        int totalRounds = 0;
        boolean allApproved = true;
        List<Violation> allViolations = new ArrayList<>();

        // 3a: 项目经历
        for (int i = 0; i < projects.size(); i++) {
            ResumeParser.ResumeSections.ProjectSection proj = projects.get(i);
            log.info("optimizeFullResume: project {}/{} — {}", i + 1, projects.size(), proj.title());
            RewriteResult rr = runActorCritic(jdText, proj.body(), scoreGuidance);
            if (rr.bullets() != null && !rr.bullets().isEmpty()) {
                rewrittenProjectTexts.add(String.join("\n", rr.bullets()));
            } else {
                rewrittenProjectTexts.add(proj.body());
            }
            totalRounds += rr.rounds();
            if (!rr.approved()) allApproved = false;
            if (rr.violations() != null) allViolations.addAll(rr.violations());
        }

        // 3b: 实习经历
        if (!sections.experience().isBlank()) {
            log.info("optimizeFullResume: rewriting experience...");
            String expRaw = writerAgent.rewrite(jdText, sections.experience(),
                    "【仅调整句式与JD关键词对齐，保留所有原有技术栈和量化数据。不得改变角色定位。】"
                    + (scoreGuidance.isBlank() ? "" : "\n" + scoreGuidance));
            WriterDraft expDraft = parseWriterDraft(expRaw);
            if (expDraft.rewrittenBulletPoints() != null && !expDraft.rewrittenBulletPoints().isEmpty()) {
                rewrittenExperience = String.join("\n", expDraft.rewrittenBulletPoints());
            }
        }

        // 3c: 技能列表
        if (!sections.skills().isBlank()) {
            log.info("optimizeFullResume: rewriting skills (conservative)...");
            String skillRaw = writerAgent.rewrite(jdText, sections.skills(),
                    "【严格保守：仅可调整技能排序和措辞，使JD关键词前置。严禁将'了解'升级为'熟悉'，将'熟悉'升级为'精通'。所有技能等级必须与原简历保持一致。】"
                    + (scoreGuidance.isBlank() ? "" : "\n" + scoreGuidance));
            WriterDraft skillDraft = parseWriterDraft(skillRaw);
            if (skillDraft.rewrittenBulletPoints() != null && !skillDraft.rewrittenBulletPoints().isEmpty()) {
                rewrittenSkills = String.join("\n", skillDraft.rewrittenBulletPoints());
            }
        }

        String finalResume = ResumeParser.reassembleFull(
                sections, rewrittenProjectTexts, rewrittenExperience, rewrittenSkills);

        long elapsed = System.currentTimeMillis() - start;
        log.info("optimizeFullResume complete: {} projects, exp={}, skills={}, score={}, rounds={}, {}ms",
                projects.size(),
                rewrittenExperience != null ? "rewritten" : "skipped",
                rewrittenSkills != null ? "rewritten" : "skipped",
                score.overallScore(), totalRounds, elapsed);

        return new OptimizationReport(
                score, null, null,
                allApproved, totalRounds,
                allViolations.isEmpty() ? null : allViolations,
                finalResume, elapsed);
    }

    private RewriteResult runActorCritic(String jdText, String originalProjectText) {
        return runActorCritic(jdText, originalProjectText, "");
    }

    /** Actor-Critic 循环：任一违规即触发重写 */
    private RewriteResult runActorCritic(String jdText, String originalProjectText,
                                          String scoreGuidance) {
        String feedback = "";
        WriterDraft lastDraft = null;
        String currentInput = originalProjectText;

        for (int round = 0; round < maxRounds; round++) {
            String prompt = buildFeedbackPrompt(round, feedback, scoreGuidance);
            log.info("Actor-Critic round={}/{}", round + 1, maxRounds);

            String rawJson = writerAgent.rewrite(jdText, currentInput, prompt);
            WriterDraft draft = parseWriterDraft(rawJson);
            lastDraft = draft;

            CriticReport c = criticAgent.check(originalProjectText, formatBulletPoints(draft));

            if (c.approved()) {
                return new RewriteResult(draft.rewrittenBulletPoints(),
                        draft.optimizationReasons(), round + 1, true, null);
            }
            feedback = c.feedback();
            currentInput = formatBulletPoints(draft);
        }

        CriticReport fc = criticAgent.check(originalProjectText, formatBulletPoints(lastDraft));
        return new RewriteResult(lastDraft.rewrittenBulletPoints(),
                lastDraft.optimizationReasons(), maxRounds, fc.approved(), fc.violations());
    }

    static String buildScoreGuidance(ScoreResult score) {
        if (score == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【评分引导】");
        if (score.jdMatch() != null) {
            sb.append("JD匹配度").append(score.jdMatch().score()).append("/100。");
            if (score.jdMatch().missingSkills() != null && !score.jdMatch().missingSkills().isEmpty()) {
                sb.append("缺失技能：").append(String.join("、", score.jdMatch().missingSkills()))
                        .append("。请在改写中自然融入相关经验描述。");
            }
        }
        if (score.contentQuality() != null
                && score.contentQuality().improvements() != null
                && !score.contentQuality().improvements().isEmpty()) {
            sb.append("改进方向：").append(String.join("；", score.contentQuality().improvements())).append("。");
        }
        if (score.improvementAdvice() != null && !score.improvementAdvice().isBlank()
                && sb.length() < 30) {
            sb.append(score.improvementAdvice());
        }
        return sb.length() > 15 ? sb.toString() : "";
    }

    /**
     * 解析 Writer Agent 返回的 JSON 字符串为 WriterDraft。
     *
     * <p>方案 B 核心——绕过 LangChain4j 结构化输出的 Gson 解析，在 Java 侧用 Jackson 做宽松解析。
     * 解析失败时记录原始输出日志，返回空 WriterDraft（不阻断请求）。
     */
    private WriterDraft parseWriterDraft(String rawJson) {
        try {
            // 第一层：剥 Markdown 包裹 / 噪声文本（复用 extractJsonObject 逻辑）
            String json = extractJsonObject(rawJson);

            // 第二层：字段名归一化（复用 FieldNameNormalizer）
            json = FieldNameNormalizer.normalize(json);

            // 第三层：Jackson 宽松解析（复用 JacksonConfig 的四宽松开关）
            return objectMapper.readValue(json, WriterDraft.class);
        } catch (Exception e) {
            log.error("Writer JSON 解析失败，原始响应: {}", rawJson.substring(0, Math.min(rawJson.length(), 300)), e);
            // 返回空对象，不阻断请求——后续 Critic 会发现 bullet 为空并标记不通过
            return new WriterDraft(Collections.emptyList(), Collections.emptyList());
        }
    }

    /** 从 LLM 输出中提取最外层 JSON 对象 */
    private static String extractJsonObject(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw; // 没有花括号，原样返回让 Jackson 尝试解析
    }

    private static String buildFeedbackPrompt(int round, String fb) {
        return buildFeedbackPrompt(round, fb, "");
    }

    private static String buildFeedbackPrompt(int round, String fb, String scoreGuidance) {
        if (round == 0 || fb == null || fb.isBlank()) {
            StringBuilder sb = new StringBuilder("【首次生成，请严格遵守约束，不得捏造任何数据或技术。】");
            if (scoreGuidance != null && !scoreGuidance.isBlank()) {
                sb.append('\n').append(scoreGuidance);
            }
            return sb.toString();
        }
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