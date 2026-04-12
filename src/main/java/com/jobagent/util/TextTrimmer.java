package com.jobagent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务层文本裁剪工具。
 *
 * <p>策略：从 JD / 简历纯文本中提取核心片段（职责、要求、经历、项目），
 * 丢弃页眉页脚、公司介绍、福利待遇等冗余内容，将输入压缩到合理长度。
 *
 * <p>不依赖任何外部库，纯正则 + 字符串操作，保证行为可预期、可测试。
 */
public final class TextTrimmer {

    /** 单段最大保留字符数（中文约等于 token 数）。 */
    private static final int SECTION_CHAR_LIMIT = 1500;

    /** 整体输出上限（字符数）。 */
    private static final int TOTAL_CHAR_LIMIT = 4000;

    private TextTrimmer() {}

    // -----------------------------------------------------------------------
    // JD 裁剪
    // -----------------------------------------------------------------------

    /**
     * 裁剪 JD 文本，只保留"岗位职责"和"任职要求"两类核心段落。
     * 若无法识别段落结构，则直接截断到 {@link #TOTAL_CHAR_LIMIT}。
     */
    public static String trimJd(String jdText) {
        if (jdText == null || jdText.isBlank()) {
            return "";
        }
        List<String> sections = extractSections(jdText,
                "职责|工作内容|工作职责|主要职责|岗位职责|job.?responsibilit",
                "要求|任职要求|岗位要求|技能要求|qualification|requirement");
        if (sections.isEmpty()) {
            return hardTruncate(jdText, TOTAL_CHAR_LIMIT);
        }
        return joinAndTruncate(sections, TOTAL_CHAR_LIMIT);
    }

    // -----------------------------------------------------------------------
    // 简历裁剪
    // -----------------------------------------------------------------------

    /**
     * 裁剪简历文本，只保留"工作经历"和"项目经历"两类核心段落。
     * 若无法识别段落结构，则直接截断到 {@link #TOTAL_CHAR_LIMIT}。
     */
    public static String trimResume(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return "";
        }
        List<String> sections = extractSections(resumeText,
                "工作经历|工作经验|职业经历|employment|work.?experience",
                "项目经历|项目经验|project.?experience|项目介绍");
        if (sections.isEmpty()) {
            return hardTruncate(resumeText, TOTAL_CHAR_LIMIT);
        }
        return joinAndTruncate(sections, TOTAL_CHAR_LIMIT);
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    /**
     * 从文本中提取匹配指定关键词的段落，每段截断到 {@link #SECTION_CHAR_LIMIT}。
     *
     * <p>段落识别规则：以关键词开头的行视为段落标题，直到下一个全大写/全中文标题行或文末。
     */
    static List<String> extractSections(String text, String... keywordPatterns) {
        // 将文本按行分割，找出所有"标题行"的位置
        String[] lines = text.split("\\r?\\n");
        List<String> result = new ArrayList<>();

        // 合并所有关键词为一个 pattern（不区分大小写）
        String combined = String.join("|", keywordPatterns);
        Pattern targetPattern = Pattern.compile("(?i)(" + combined + ")");
        // 通用标题行识别：短行（≤20字）且以中文/英文词开头，后跟冒号或换行
        Pattern headingPattern = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z\\s]{0,18}[：:：]?\\s*$");

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (targetPattern.matcher(line).find()) {
                // 找到目标段落标题，收集直到下一个标题行
                StringBuilder sb = new StringBuilder(line).append('\n');
                int j = i + 1;
                while (j < lines.length) {
                    String next = lines[j].trim();
                    // 遇到任何标题行（包括其他目标段落标题）则停止
                    if (!next.isEmpty() && headingPattern.matcher(next).matches()) {
                        break;
                    }
                    sb.append(lines[j]).append('\n');
                    j++;
                }
                String section = sb.toString().trim();
                result.add(hardTruncate(section, SECTION_CHAR_LIMIT));
                i = j;
            } else {
                i++;
            }
        }
        return result;
    }

    private static String joinAndTruncate(List<String> sections, int limit) {
        StringBuilder sb = new StringBuilder();
        for (String s : sections) {
            if (sb.length() + s.length() + 1 > limit) {
                int remaining = limit - sb.length() - 1;
                if (remaining > 0) {
                    sb.append(s, 0, remaining);
                }
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    static String hardTruncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…[已截断]";
    }
}
