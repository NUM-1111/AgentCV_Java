package com.jobagent.controller;

import com.jobagent.model.RewriteReport;
import com.jobagent.service.RewriteCoordinatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resume")
public class ResumeController {

    public record RewriteRequest(String jdText, String originalProjectText) {}

    private final RewriteCoordinatorService rewriteCoordinatorService;

    public ResumeController(RewriteCoordinatorService rewriteCoordinatorService) {
        this.rewriteCoordinatorService = rewriteCoordinatorService;
    }

    @PostMapping("/rewrite")
    public ResponseEntity<RewriteReport> rewrite(@RequestBody RewriteRequest request) {
        if (request.jdText() == null || request.jdText().isBlank()) {
            throw new IllegalArgumentException("jdText 不能为空");
        }
        if (request.originalProjectText() == null || request.originalProjectText().isBlank()) {
            throw new IllegalArgumentException("originalProjectText 不能为空");
        }
        if (request.jdText().length() > 3000) {
            throw new IllegalArgumentException("jdText 超过 3000 字符限制");
        }
        if (request.originalProjectText().length() > 3000) {
            throw new IllegalArgumentException("originalProjectText 超过 3000 字符限制");
        }

        RewriteCoordinatorService.RewriteResult result =
                rewriteCoordinatorService.evaluate(request.jdText(), request.originalProjectText());

        return ResponseEntity.ok()
                .header("X-Review-Rounds", String.valueOf(result.rounds()))
                .body(result.report());
    }
}
