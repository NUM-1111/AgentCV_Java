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
public class MatchEvaluationFacadeService {

    private final MatchEvaluatorService matchEvaluatorService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MatchEvaluationFacadeService(MatchEvaluatorService matchEvaluatorService, ObjectMapper objectMapper, Validator validator) {
        this.matchEvaluatorService = matchEvaluatorService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public MatchReport evaluate(String jdText, String resumeText) {
        String raw = matchEvaluatorService.evaluate(jdText, resumeText);
        String json = extractJsonObject(raw);
        try {
            MatchReport report = objectMapper.readValue(json, MatchReport.class);
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
