package com.jobagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.JacksonConfig;
import com.jobagent.exception.AiOutputValidationException;
import com.jobagent.model.MatchReport;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchEvaluationFacadeServiceTest {

    private MatchEvaluationFacadeService facadeService;
    private StubMatchEvaluatorService stubService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new JacksonConfig().objectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        stubService = new StubMatchEvaluatorService();
        facadeService = new MatchEvaluationFacadeService(stubService, objectMapper, validator);
    }

    @Test
    void shouldParseJsonWrappedByNoiseAndComments() {
        stubService.output = "分析如下：\n```json\n{\n  // comment\n  'match_score': 82,\n  'matched_skills': ['Java', 'Spring Boot'],\n  'missing_skills': ['Redis'],\n  'improvement_advice': '补充 Redis 项目经验',\n}\n```\n请参考上述结果。";

        MatchReport report = facadeService.evaluate("jd", "resume");

        assertEquals(82, report.matchScore());
        assertEquals(2, report.matchedSkills().size());
        assertEquals("补充 Redis 项目经验", report.improvementAdvice());
    }

    @Test
    void shouldAcceptAliasFields() {
        stubService.output = "{\"score\":76,\"matched_skills\":[\"Java\"],\"missing_skills\":[\"Kafka\"],\"advice\":\"补充 Kafka 实战经验\",\"extra_field\":true}";

        MatchReport report = facadeService.evaluate("jd", "resume");

        assertEquals(76, report.matchScore());
        assertEquals("Java", report.matchedSkills().get(0));
        assertEquals("Kafka", report.missingSkills().get(0));
        assertEquals("补充 Kafka 实战经验", report.improvementAdvice());
    }

    @Test
    void shouldRejectOutOfRangeScore() {
        stubService.output = "{\"matchScore\":120,\"matchedSkills\":[\"Java\"],\"missingSkills\":[\"Redis\"],\"improvementAdvice\":\"补充 Redis 经验\"}";

        AiOutputValidationException ex = assertThrows(AiOutputValidationException.class, () -> facadeService.evaluate("jd", "resume"));

        assertTrue(ex.getMessage().contains("matchScore"));
    }

    @Test
    void shouldRejectBlankAdvice() {
        stubService.output = "{\"matchScore\":80,\"matchedSkills\":[\"Java\"],\"missingSkills\":[\"Redis\"],\"improvementAdvice\":\"   \"}";

        AiOutputValidationException ex = assertThrows(AiOutputValidationException.class, () -> facadeService.evaluate("jd", "resume"));

        assertTrue(ex.getMessage().contains("improvementAdvice"));
    }

    @Test
    void shouldRejectMissingSkillsWhenNull() {
        stubService.output = "{\"matchScore\":80,\"matchedSkills\":[\"Java\"],\"missingSkills\":null,\"improvementAdvice\":\"补充 Redis 经验\"}";

        AiOutputValidationException ex = assertThrows(AiOutputValidationException.class, () -> facadeService.evaluate("jd", "resume"));

        assertTrue(ex.getMessage().contains("missingSkills"));
    }

    @Test
    void shouldFailFastWhenModelReturnsWrongSchema() {
        stubService.output = "{'matchScore': 0.8, // 这是一个测试注释 'strengths': ['A'], 'weaknesses': ['B'],}";

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> facadeService.evaluate("jd", "resume"));

        assertTrue(ex.getMessage().contains("模型输出解析失败"));
        assertInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class, ex.getCause());
    }

    private static final class StubMatchEvaluatorService implements MatchEvaluatorService {
        private String output;

        @Override
        public String evaluate(String jdText, String resumeText) {
            return output;
        }
    }
}
