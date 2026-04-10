package com.jobagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.exception.AiOutputValidationException;
import com.jobagent.model.MatchReport;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
/**
 * 负责封装“岗位-简历匹配评估”的完整流程：
 * 1) 调用模型获取原始文本；
 * 2) 从文本中提取 JSON 主体；
 * 3) 反序列化为 {@link MatchReport}；
 * 4) 执行 Bean Validation 约束校验。
 */
public class MatchEvaluationFacadeService {

    private final MatchEvaluatorService matchEvaluatorService;
    private final ObjectMapper objectMapper;//jackson的objectmapper，用于将json字符串转换为java对象
    private final Validator validator;//bean validation的validator，用于校验java对象的约束

    public MatchEvaluationFacadeService(MatchEvaluatorService matchEvaluatorService, ObjectMapper objectMapper, Validator validator) {
        this.matchEvaluatorService = matchEvaluatorService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * 对外统一入口：将模型输出转换为结构化且通过校验的匹配报告。
     * 若模型输出不符合结构或字段约束，会抛出带上下文的异常，便于排查。
     */
    public MatchReport evaluate(String jdText, String resumeText) {
        // 原始返回可能包含解释性文本、Markdown 包裹等非 JSON 内容。
        String raw = matchEvaluatorService.evaluate(jdText, resumeText);
        // 尽量截取最外层 JSON 对象，降低反序列化失败率。
        String json = extractJsonObject(raw);
        try {
            MatchReport report = objectMapper.readValue(json, MatchReport.class);
            // 将“可解析”进一步提升为“满足业务约束”。
            validateReport(report);
            return report;
        } catch (AiOutputValidationException ex) {
            // 业务校验异常直接透传，保留明确错误语义。
            throw ex;
        } catch (Exception ex) {
            // 其他异常统一包装，附带精简后的原始输出用于定位问题。
            throw new IllegalStateException("模型输出解析失败: " + summarize(raw), ex);
        }
    }

    /**
     * 基于 Bean Validation 校验反序列化后的对象，聚合错误后一次性抛出。
     */
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

    /**
     * 从模型原始文本中提取最外层 JSON 对象。
     * 这里使用轻量策略（首个 '{' 到最后一个 '}'），避免引入额外解析成本。
     */
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

    /**
     * 生成用于日志/异常的摘要文本，避免把超长输出直接打入错误信息。
     */
    private String summarize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "<empty>";
        }
        String oneLine = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }
}
