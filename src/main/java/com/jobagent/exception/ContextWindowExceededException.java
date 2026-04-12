package com.jobagent.exception;

/**
 * 输入文本超过模型上下文窗口安全阈值时抛出。
 * 由业务层在调用模型前主动检测并抛出，避免依赖框架/模型的黑盒截断行为。
 */
public class ContextWindowExceededException extends RuntimeException {

    private final int estimatedTokens;
    private final int maxTokens;

    public ContextWindowExceededException(int estimatedTokens, int maxTokens) {
        super(String.format(
                "输入文本过长：估算 token 数 %d 超过安全阈值 %d，请精简 JD 或简历后重试。",
                estimatedTokens, maxTokens));
        this.estimatedTokens = estimatedTokens;
        this.maxTokens = maxTokens;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public int getMaxTokens() {
        return maxTokens;
    }
}
