package com.jobagent.util;

import com.jobagent.exception.ContextWindowExceededException;

/**
 * 轻量级 token 数量预估工具。
 *
 * <p>不依赖真实 tokenizer，采用保守估算规则：
 * <ul>
 *   <li>中文字符：1字 ≈ 1 token（保守，实际 DeepSeek 约 1.5~2字/token，高估约30-50%）</li>
 *   <li>ASCII 单词：平均 1 词 ≈ 1.3 token（按 4字符≈1token 实现）</li>
 *   <li>Prompt 模板固定开销：约 400 token（来自 @SystemMessage + @UserMessage 模板文本）</li>
 * </ul>
 *
 * <p>保守估算的目的是宁可误报（拦截实际安全的请求）也不漏报（放行实际超长的请求）。
 *
 * <h3>与 TextTrimmer 的耦合关系</h3>
 * <p>本类的 MAX_INPUT_TOKENS 和 TextTrimmer.TOTAL_CHAR_LIMIT 之间存在约束关系，
 * 由 {@code TokenBudgetCalibrationTest} 自动验证：
 * <pre>
 *   TOTAL_CHAR_LIMIT(8000) × 保守系数(1.0) + PROMPT_OVERHEAD(400) = 8400 ≤ MAX_INPUT_TOKENS(10000)
 * </pre>
 * 修改任一常量后必须重跑 TokenBudgetCalibrationTest 确认约束仍成立。
 */
public final class TokenEstimator {

    /**
     * Prompt 模板固定开销（token 数）。
     * 来源：MatchEvaluatorService @SystemMessage + @UserMessage 模板文本约 400 字。
     * 若模板文本变更，必须重新统计字数并同步更新此常量，然后重跑 TokenBudgetCalibrationTest。
     */
    public static final int PROMPT_TEMPLATE_OVERHEAD = 400;

    /**
     * Token 拦截安全阈值。
     *
     * <p><b>推导公式</b>（由 TokenBudgetCalibrationTest 自动验证）：
     *
     * <p>阈值是从 TextTrimmer 结构上限反推的，不是从样本统计出来的：
     * <pre>
     *   Trimmer 理论最大输出: JD 2段×1500 + 简历 3段×1500 = 7500 → 设为 8000(留 fallback 余量)
     *   TOTAL_CHAR_LIMIT(8000) × 保守系数(1.0字/token) + PROMPT_TEMPLATE_OVERHEAD(400) = 8400
     *   ← 这是阈值下限。低于8400，约束A断裂。取整到 10000，留 1600 token 业务增长余量。
     * </pre>
     *
     * <p><b>误杀验证</b>：Golden Set 9组用例经 TextTrimmer→TokenEstimator 完整链路后，
     * 实测token分布 486~604，最大604（TokenBudgetCalibrationTest.constraintB 自动报告）。
     * 504 ≪ 10000——正常用户不会被拦。604 是用来<b>验证不误杀</b>，不是用来推导阈值的。
     *
     * <p><b>修改指引</b>：修改此常量或 TextTrimmer.TOTAL_CHAR_LIMIT 时，
     * 必须运行 {@code mvn test -Dtest=TokenBudgetCalibrationTest}
     * 确认约束A(阈值下限)、约束B(误杀验证)、约束C(英文覆盖) 全部通过。
     * 失败信息会给出修复建议（改到多少）。
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
