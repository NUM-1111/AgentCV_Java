package com.jobagent.controller;

import com.jobagent.model.RewriteReport;
import com.jobagent.service.ResumeRewriteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resume")
public class ResumeController {

    public record RewriteRequest(String jdText, String originalProjectText) {}

    private final ResumeRewriteService resumeRewriteService;

    public ResumeController(ResumeRewriteService resumeRewriteService) {
        this.resumeRewriteService = resumeRewriteService;
    }

    @PostMapping("/rewrite")
    public RewriteReport rewrite(@RequestBody RewriteRequest request) {
        if (request.jdText() == null || request.jdText().isBlank()) {
            throw new IllegalArgumentException("jdText 不能为空");
        }
        if (request.originalProjectText() == null || request.originalProjectText().isBlank()) {
            throw new IllegalArgumentException("originalProjectText 不能为空");
        }
        return resumeRewriteService.rewrite(request.jdText(), request.originalProjectText());
    }
}
