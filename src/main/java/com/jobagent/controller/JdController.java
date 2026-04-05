package com.jobagent.controller;

import com.jobagent.model.JdInfo;
import com.jobagent.service.JdAnalyzeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/jd")
public class JdController {

    private final JdAnalyzeService jdAnalyzeService;

    // 依赖注入，把 AI 服务拿过来用
    public JdController(JdAnalyzeService jdAnalyzeService) {
        this.jdAnalyzeService = jdAnalyzeService;
    }

    @PostMapping("/analyze")
    public JdInfo analyze(@RequestBody Map<String, String> request) {
        String jdText = request.get("jdText");
        if (jdText == null || jdText.isBlank()) {
            throw new IllegalArgumentException("jdText 不能为空");
        }

        // 直接调用，像调用普通 Java 方法一样调用大模型！
        return jdAnalyzeService.analyzeJd(jdText);
    }
}