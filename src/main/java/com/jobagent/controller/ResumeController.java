package com.jobagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.model.OptimizationReport;
import com.jobagent.service.ResumeOptimizationService;
import com.jobagent.service.StreamEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/resume")
public class ResumeController {

    public record OptimizeRequest(String jdText, String originalProjectText) {}

    private final ResumeOptimizationService optimizationService;
    private final ObjectMapper objectMapper;

    public ResumeController(ResumeOptimizationService optimizationService, ObjectMapper objectMapper) {
        this.optimizationService = optimizationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 完整三阶段优化：评分 + 改写 + 审查。
     */
    @PostMapping("/optimize")
    public ResponseEntity<OptimizationReport> optimize(@RequestBody OptimizeRequest request) {
        validate(request);
        OptimizationReport report = optimizationService.optimize(
                request.jdText(), request.originalProjectText());
        return ResponseEntity.ok()
                .header("X-Review-Rounds", String.valueOf(
                        report.reviewRounds() != null ? report.reviewRounds() : 0))
                .body(report);
    }

    /**
     * 仅快速评分（跳过改写+审查）。
     */
    @PostMapping("/score")
    public OptimizationReport score(@RequestBody OptimizeRequest request) {
        validate(request);
        return optimizationService.scoreOnly(request.jdText(), request.originalProjectText());
    }

    /**
     * 完整简历优化：解析分段 → 逐项目优化 → 回填拼接。
     */
    @PostMapping("/optimize/full")
    public ResponseEntity<OptimizationReport> optimizeFull(@RequestBody OptimizeRequest request) {
        validate(request);
        OptimizationReport report = optimizationService.optimizeFullResume(
                request.jdText(), request.originalProjectText());
        return ResponseEntity.ok().body(report);
    }

    /**
     * SSE 流式优化——逐步推送评分、改写、审查进度。
     */
    @PostMapping(value = "/optimize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter optimizeStream(@RequestBody OptimizeRequest request) {
        validate(request);
        SseEmitter emitter = new SseEmitter(120_000L); // 2 分钟超时

        // 每 10 秒发一次 SSE 心跳，防止浏览器/代理因长时间静默断开连接
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                heartbeat.shutdown();
            }
        }, 10, 10, TimeUnit.SECONDS);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                optimizationService.optimizeStreaming(
                        request.jdText(), request.originalProjectText(),
                        event -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(event.type().name())
                                        .data(objectMapper.writeValueAsString(event)));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        });
                heartbeat.shutdown();
                emitter.complete();
            } catch (Exception e) {
                heartbeat.shutdown();
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void validate(OptimizeRequest request) {
        if (request.jdText() == null || request.jdText().isBlank()) {
            throw new IllegalArgumentException("jdText 不能为空");
        }
        if (request.originalProjectText() == null || request.originalProjectText().isBlank()) {
            throw new IllegalArgumentException("originalProjectText 不能为空");
        }
        if (request.jdText().length() > 10000) {
            throw new IllegalArgumentException("jdText 超过 10000 字符限制");
        }
        if (request.originalProjectText().length() > 10000) {
            throw new IllegalArgumentException("originalProjectText 超过 10000 字符限制");
        }
    }
}