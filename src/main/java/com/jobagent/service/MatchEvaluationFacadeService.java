package com.jobagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.exception.AiOutputValidationException;
import com.jobagent.exception.ContextWindowExceededException;
import com.jobagent.model.MatchReport;
import com.jobagent.util.FieldNameNormalizer;
import com.jobagent.util.TextTrimmer;
import com.jobagent.util.TokenEstimator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 负责封装"岗位-简历匹配评估"的完整流程：
 * 1) 业务层裁剪：只保留核心片段（职责、要求、经历、项目）；
 * 2) Token 预估：超过安全阈值主动拦截，返回明确错误；
 * 3) 调用模型获取原始文本；
 * 4) 从文本中提取 JSON 主体；
 * 5) 反序列化为 {@link MatchReport}；
 * 6) 执行 Bean Validation 约束校验。
 */
@Service
public class MatchEvaluationFacadeService {

    private static final Logger log = LoggerFactory.getLogger(MatchEvaluationFacadeService.class);

    private final MatchEvaluatorService matchEvaluatorService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MatchEvaluationFacadeService(MatchEvaluatorService matchEvaluatorService,
            ObjectMapper objectMapper,
            Validator validator) {
        this.matchEvaluatorService = matchEvaluatorService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * 对外统一入口：裁剪输入 → token 预估拦截 → 调用模型 → 结构化输出。
     *
     * @throws ContextWindowExceededException 若裁剪后 token 数仍超过安全阈值
     */
    public MatchReport evaluate(String jdText, String resumeText) {
        // Step 1: 业务层裁剪，只保留核心片段，丢弃冗余内容
        String trimmedJd = TextTrimmer.trimJd(jdText);
        String trimmedResume = TextTrimmer.trimResume(resumeText);

        log.debug("裁剪前 JD={}字 简历={}字，裁剪后 JD={}字 简历={}字",
                jdText == null ? 0 : jdText.length(),
                resumeText == null ? 0 : resumeText.length(),
                trimmedJd.length(), trimmedResume.length());

        // Step 2: Token 预估，超过阈值主动拦截，不依赖框架/模型黑盒截断
        TokenEstimator.assertWithinLimit(trimmedJd, trimmedResume);

        // Step 3: 调用模型（输入已可控）
        String raw = matchEvaluatorService.evaluate(trimmedJd, trimmedResume);

        // Step 4: 提取最外层 JSON 对象，降低反序列化失败率
        String json = extractJsonObject(raw);

        // Step 5: 归一化字段名，覆盖 @JsonAlias 无法预设的漂移变体
        String normalized = FieldNameNormalizer.normalize(json);

        try {
            MatchReport report = objectMapper.readValue(normalized, MatchReport.class);
            // Step 6: 将"可解析"进一步提升为"满足业务约束"
            validateReport(report);
            return report;
        } catch (AiOutputValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("模型输出解析失败: " + summarize(raw), ex);
        }
    }

    private void validateReport(MatchReport report) {
        Set<ConstraintViolation<MatchReport>> violations = validator.validate(report);
        if (violations.isEmpty()) {
            return;
        }
        String detail = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        throw new AiOutputValidationException("模型输出字段校验失败: " + detail);
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String summarize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "<empty>";
        }
        String oneLine = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }
}