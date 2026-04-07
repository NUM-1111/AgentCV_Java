package com.jobagent.controller;

import com.jobagent.model.MatchReport;
import com.jobagent.service.MatchEvaluationFacadeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/match")
public class MatchController {

    public record MatchRequest(String jdText, String resumeText) {}

    private final MatchEvaluationFacadeService matchEvaluationFacadeService;

    public MatchController(MatchEvaluationFacadeService matchEvaluationFacadeService) {
        this.matchEvaluationFacadeService = matchEvaluationFacadeService;
    }

    @PostMapping("/evaluate")
    public MatchReport evaluate(@RequestBody MatchRequest request) {
        if (request.jdText() == null || request.jdText().isBlank()) {
            throw new IllegalArgumentException("jdText 不能为空");
        }
        if (request.resumeText() == null || request.resumeText().isBlank()) {
            throw new IllegalArgumentException("resumeText 不能为空");
        }
        return matchEvaluationFacadeService.evaluate(request.jdText(), request.resumeText());
    }
}
