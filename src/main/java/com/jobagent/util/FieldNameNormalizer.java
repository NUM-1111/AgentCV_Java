package com.jobagent.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 在 Jackson 反序列化之前，对 JSON 字符串中的 key 做字段名归一化。
 *
 * <p>为什么在反序列化之前用正则处理，而不是解析成 JsonNode 再改 key？
 * 因为此时 JSON 可能还带有注释、单引号、尾逗号等非标准语法，
 * Jackson 尚未介入，正则直接操作字符串不依赖 JSON 合法性，能更早拦截漂移。
 *
 * <p>与 {@code @JsonAlias} 的分工：
 * <ul>
 *   <li>本类负责"别名范围外"的模糊变体（大小写、语义相近词、缺少分隔符等）</li>
 *   <li>{@code @JsonAlias} 作为 Jackson 层的精确兜底，处理已知的固定别名</li>
 * </ul>
 */
public final class FieldNameNormalizer {

    private FieldNameNormalizer() {}

    /**
     * 单条归一化规则：pattern 匹配到的 key 替换为 replacement。
     * replacement 已包含引号和冒号，例如 {@code "matchScore":}。
     */
    private record NormalizeRule(Pattern pattern, String replacement) {}

    /**
     * 归一化规则表。
     *
     * <p>规则设计原则：
     * <ol>
     *   <li>只匹配 JSON key（被双引号包裹，后跟可选空白和冒号），避免误替换 value 中的字符串</li>
     *   <li>用 {@code [_\s]?} 覆盖有无分隔符的变体（如 match_score / matchscore / match score）</li>
     *   <li>用 {@code (?i)} 大小写不敏感，覆盖 MatchScore / MATCHSCORE 等变体</li>
     *   <li>语义相近词（gaps / suggestion / improvement）也纳入同一规则</li>
     * </ol>
     */
    private static final List<NormalizeRule> RULES = List.of(

        // overallScore：覆盖 overall_score / overallscore / overall score / OverallScore / matchScore / match_score 等
        new NormalizeRule(
            Pattern.compile("(?i)\"(overall[_\\s]?score|overallscore|match[_\\s]?score|matchscore)\"\\s*:"),
            "\"overallScore\":"
        ),

        // matchedSkills：覆盖 matched_skills / matchedskills / skills_matched / matched 等
        new NormalizeRule(
            Pattern.compile("(?i)\"(matched[_\\s]?skills?|skills?[_\\s]?matched|matchedskills?)\"\\s*:"),
            "\"matchedSkills\":"
        ),

        // missingSkills：覆盖 missing_skills / missingskills / skill_gaps / gaps / missing_requirements 等
        new NormalizeRule(
            Pattern.compile("(?i)\"(missing[_\\s]?skills?|missingskills?|skill[_\\s]?gaps?|gaps?|missing[_\\s]?requirements?)\"\\s*:"),
            "\"missingSkills\":"
        ),

        // improvementAdvice：覆盖 improvement_advice / improvementadvice / advice_text / suggestion / improvement 等
        new NormalizeRule(
            Pattern.compile("(?i)\"(improvement[_\\s]?advice|improvementadvice|advice[_\\s]?texts?|suggestions?|improvements?)\"\\s*:"),
            "\"improvementAdvice\":"
        )
    );

    /**
     * 对 JSON 字符串执行字段名归一化，返回 key 已统一为标准 camelCase 的字符串。
     * 若输入为 null，原样返回 null。
     */
    public static String normalize(String json) {
        if (json == null) {
            return null;
        }
        String result = json;
        for (NormalizeRule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }
}
