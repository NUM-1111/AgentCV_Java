package com.jobagent.util;

import com.jobagent.exception.ContextWindowExceededException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {

    // -----------------------------------------------------------------------
    // estimate — 单段估算
    // -----------------------------------------------------------------------

    @Test
    void estimate_nullOrBlankReturnsZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate("   "));
    }

    @Test
    void estimate_pureChineseCountsOnePerChar() {
        // 100 个中文字符，预期 ≈ 100 token（+ 1 基础值）
        String text = "中".repeat(100);
        int result = TokenEstimator.estimate(text);
        assertTrue(result >= 100 && result <= 105,
                "100 个中文字符应估算为约 100 token，实际: " + result);
    }

    @Test
    void estimate_pureAsciiCountsFourCharsPerToken() {
        // 400 个 ASCII 字符，预期 ≈ 100 token（400/4 + 1）
        String text = "a".repeat(400);
        int result = TokenEstimator.estimate(text);
        assertTrue(result >= 100 && result <= 105,
                "400 个 ASCII 字符应估算为约 100 token，实际: " + result);
    }

    @Test
    void estimate_mixedTextIsReasonable() {
        // 50 中文 + 100 ASCII = 50 + 25 + 1 = 76
        String text = "中".repeat(50) + "a".repeat(100);
        int result = TokenEstimator.estimate(text);
        assertTrue(result >= 70 && result <= 85,
                "混合文本估算应在合理范围内，实际: " + result);
    }

    // -----------------------------------------------------------------------
    // estimateTotal — 含模板开销
    // -----------------------------------------------------------------------

    @Test
    void estimateTotal_includesTemplateOverhead() {
        // 空输入时，estimate("") 返回 0（isBlank 早返回），总 token 数等于模板固定开销
        int total = TokenEstimator.estimateTotal("", "");
        assertEquals(TokenEstimator.PROMPT_TEMPLATE_OVERHEAD, total,
                "空输入时总 token 数应等于模板开销");
    }

    @Test
    void estimateTotal_sumsBothTextsAndOverhead() {
        String jd = "中".repeat(1000);
        String resume = "中".repeat(1000);
        int total = TokenEstimator.estimateTotal(jd, resume);
        // 1000 + 1000 + 400 = 2400，加上各自的 +1 基础值
        assertTrue(total >= 2400 && total <= 2410,
                "总 token 数应为两段文本之和加模板开销，实际: " + total);
    }

    // -----------------------------------------------------------------------
    // assertWithinLimit — 拦截逻辑
    // -----------------------------------------------------------------------

    @Test
    void assertWithinLimit_doesNotThrowForShortInput() {
        String jd = "中".repeat(100);
        String resume = "中".repeat(100);
        // 100 + 100 + 400 = 600，远低于 10000
        assertDoesNotThrow(() -> TokenEstimator.assertWithinLimit(jd, resume));
    }

    @Test
    void assertWithinLimit_throwsWhenExceedingThreshold() {
        // 构造超过 MAX_INPUT_TOKENS(10000) 的输入
        String jd = "中".repeat(5000);
        String resume = "中".repeat(5000);
        // 5000 + 5000 + 400 = 10400 > 10000

        ContextWindowExceededException ex = assertThrows(
                ContextWindowExceededException.class,
                () -> TokenEstimator.assertWithinLimit(jd, resume));

        assertTrue(ex.getEstimatedTokens() > TokenEstimator.MAX_INPUT_TOKENS,
                "estimatedTokens 应超过阈值");
        assertEquals(TokenEstimator.MAX_INPUT_TOKENS, ex.getMaxTokens());
        assertTrue(ex.getMessage().contains("超过安全阈值"),
                "错误信息应明确说明超过阈值");
    }

    @Test
    void assertWithinLimit_exceptionMessageContainsActualNumbers() {
        String jd = "中".repeat(5000);
        String resume = "中".repeat(5000);

        ContextWindowExceededException ex = assertThrows(
                ContextWindowExceededException.class,
                () -> TokenEstimator.assertWithinLimit(jd, resume));

        String msg = ex.getMessage();
        assertTrue(msg.contains(String.valueOf(ex.getEstimatedTokens())),
                "错误信息应包含实际 token 数");
        assertTrue(msg.contains(String.valueOf(TokenEstimator.MAX_INPUT_TOKENS)),
                "错误信息应包含阈值");
    }

    @Test
    void assertWithinLimit_exactlyAtThresholdDoesNotThrow() {
        // 构造恰好等于阈值的输入（不超过）
        // MAX_INPUT_TOKENS = 10000，模板开销 400，两段各贡献 1
        // 需要 jd + resume ≈ 9598 中文字符
        int charsNeeded = TokenEstimator.MAX_INPUT_TOKENS - TokenEstimator.PROMPT_TEMPLATE_OVERHEAD - 2;
        String jd = "中".repeat(charsNeeded / 2);
        String resume = "中".repeat(charsNeeded / 2);

        assertDoesNotThrow(() -> TokenEstimator.assertWithinLimit(jd, resume),
                "恰好在阈值内不应抛出异常");
    }
}
