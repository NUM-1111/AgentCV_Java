package com.jobagent.util;

import com.jobagent.exception.ContextWindowExceededException;

/**
 * 轻量级 token 数量预估工具。
 *
 * <p>不依赖真实 tokenizer，采用保守估算规则：
 * <ul>
 *   <li>中文字符：1字 ≈ 1 token（保守，实际 DeepSeek 约 1.5~2字/token）</li>
 *   <li>ASCII 单词：平均 1 词 ≈ 1.3 token</li>
 *   <li>Prompt 模板固定开销：约 400 token（来自 @SystemMessage + @UserMessage 模板文本）</li>
 * </ul>
 *
 * <p>保守估算的目的是宁可误报（拦截实际安全的请求）也不漏报（放行实际超长的请求）。
 */
public final class TokenEstimator {

    /**
     * Prompt 模板固定开销（token 数）。
     * 来源：MatchEvaluatorService @SystemMessage + @UserMessage 模板文本约 400 字。
     */
    public static final int PROMPT_TEMPLATE_OVERHEAD = 400;

    /**
     * 安全阈值：留给模型输出的 token 预算（约 1000 token）。
     * DeepSeek-chat 上下文窗口 ~64K，减去输出预算后的输入安全上限。
     */
    public static final int MAX_INPUT_TOKENS = 10_000;

    private TokenEstimator() {}

    /**
     * 估算单段文本的 token 数。
     *
     * <p>规则：中文字符按 1:1，其余字符（英文单词、数字、标点）按字符数 / 4 估算。
     */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chineseCount = 0;
        int otherCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fa5') {
                chineseCount++;
            } else {
                otherCount++;
            }
        }
        // 中文 1:1，其他字符 4个 ≈ 1 token（英文单词平均长度约 5，加空格约 4字符/token）
        return chineseCount + (otherCount / 4) + 1;
    }

    /**
     * 估算 JD + 简历 + 模板固定开销的总 token 数。
     */
    public static int estimateTotal(String jdText, String resumeText) {
        return estimate(jdText) + estimate(resumeText) + PROMPT_TEMPLATE_OVERHEAD;
    }

    /**
     * 检查总 token 数是否超过安全阈值，超过则抛出 {@link ContextWindowExceededException}。
     *
     * <p>应在调用模型前调用此方法，确保输入可控。
     *
     * @throws ContextWindowExceededException 若估算 token 数超过 {@link #MAX_INPUT_TOKENS}
     */
    public static void assertWithinLimit(String jdText, String resumeText) {
        int total = estimateTotal(jdText, resumeText);
        if (total > MAX_INPUT_TOKENS) {
            throw new ContextWindowExceededException(total, MAX_INPUT_TOKENS);
        }
    }
}
