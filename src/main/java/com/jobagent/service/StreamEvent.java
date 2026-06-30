package com.jobagent.service;

/**
 * SSE 流式事件模型——Service 层只依赖此枚举和记录，不接触 SseEmitter。
 *
 * <p>设计意图：Service 层发出业务事件，Controller 层负责将事件翻译成 SSE 格式。
 * 哪天要换成 WebSocket，只改 Controller。</p>
 */
public record StreamEvent(
        Type type,
        String message,
        Object data
) {
    public enum Type {
        /** Phase 1 评分开始 */
        SCORING_START,
        /** Phase 1 评分完成，data=ScoreResult */
        SCORING_DONE,

        /** Phase 2 某轮改写开始 */
        WRITER_START,
        /** Phase 2 某轮改写草稿完成，data=WriterDraft */
        WRITER_DONE,

        /** Phase 3 审查开始 */
        CRITIC_START,
        /** Phase 3 审查完成，data=CriticReport */
        CRITIC_DONE,

        /** 全部完成，data=OptimizationReport */
        COMPLETE,

        /** 发生错误，data=Throwable message */
        ERROR
    }

    public static StreamEvent of(Type type, String message) {
        return new StreamEvent(type, message, null);
    }

    public static StreamEvent of(Type type, String message, Object data) {
        return new StreamEvent(type, message, data);
    }
}