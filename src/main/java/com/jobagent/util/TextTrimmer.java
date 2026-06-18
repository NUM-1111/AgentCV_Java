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
    private static final int TOTAL_CHAR_LIMIT = 8000;

    /**
     * 标题行判定：
     * - 可选序号前缀（1. 一、 （一） 等）
     * - 主体为中文/英文词，长度 2-20 字
     * - 可选尾部冒号（中英文均可）
     * - 不含句子标点（。，；！？…），避免把内容短句误判为标题
     */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:[\\d一二三四五六七八九十]+[.、．]|[（(][\\d一二三四五六七八九十]+[)）][.、]?)?\\s*" +
            "[\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9\\s/&]{1,18}" +
            "[：:：]?\\s*$"
    );

    /** 垃圾行过滤：福利、公司介绍、联系方式等无效信息。 */
    private static final Pattern NOISE_LINE_PATTERN = Pattern.compile(
            "(?i)(福利|五险|社保|公积金|年终奖|弹性|餐补|交通补|带薪|假期|" +
            "公司介绍|公司简介|关于我们|企业文化|我们提供|" +
            "电话|手机|邮箱|地址|微信|qq|招聘联系|投递|简历发送)"
    );

    private TextTrimmer() {}

    // -----------------------------------------------------------------------
    // JD 裁剪
    // -----------------------------------------------------------------------

    /**
     * 裁剪 JD 文本，只保留"岗位职责"和"任职要求"两类核心段落。
     * 若无法识别段落结构，则过滤垃圾行后截断到 {@link #TOTAL_CHAR_LIMIT}。
     */
    public static String trimJd(String jdText) {
        if (jdText == null || jdText.isBlank()) {
            return "";
        }
        String cleaned = cleanText(jdText);
        List<String> sections = extractSections(cleaned,
                "职责|工作内容|工作职责|主要职责|岗位职责|工作任务|主要任务|job.?responsibilit",
                "要求|任职要求|岗位要求|技能要求|任职资格|必备条件|专业要求|qualification|requirement");
        if (sections.isEmpty()) {
            return fallbackTrim(cleaned, TOTAL_CHAR_LIMIT);
        }
        return joinAndTruncate(sections, TOTAL_CHAR_LIMIT);
    }

    // -----------------------------------------------------------------------
    // 简历裁剪
    // -----------------------------------------------------------------------

    /**
     * 裁剪简历文本，只保留"工作经历"和"项目经历"两类核心段落。
     * 若无法识别段落结构，则过滤垃圾行后截断到 {@link #TOTAL_CHAR_LIMIT}。
     */
    public static String trimResume(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return "";
        }
        String cleaned = cleanText(resumeText);
        List<String> sections = extractSections(cleaned,
                "工作经历|工作经验|职业经历|employment|work.?experience",
                "项目经历|项目经验|project.?experience|项目介绍",
                "核心技能|专业技能|技术栈|个人技能|技能特长|skill|技术能力");
        if (sections.isEmpty()) {
            return fallbackTrim(cleaned, TOTAL_CHAR_LIMIT);
        }
        return joinAndTruncate(sections, TOTAL_CHAR_LIMIT);
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    /**
     * 格式清洗：合并连续空行、清理行内多余空格。
     */
    static String cleanText(String text) {
        // 统一换行符
        String s = text.replace("\r\n", "\n").replace("\r", "\n");
        // 清理行内连续空格（保留单个空格）
        s = s.replaceAll("[ \t]{2,}", " ");
        // 合并超过 2 个连续空行为 1 个
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    /**
     * 从文本中提取匹配指定关键词的段落，每段截断到 {@link #SECTION_CHAR_LIMIT}。
     *
     * <p>段落识别规则：
     * <ul>
     *   <li>关键词可出现在行首（标题独占一行），也可出现在行内（标题+内容同行）。</li>
     *   <li>段落结束于下一个合法标题行，或文末。</li>
     *   <li>标题判定使用严格的 {@link #HEADING_PATTERN}，避免内容短句被误判。</li>
     * </ul>
     */
    static List<String> extractSections(String text, String... keywordPatterns) {
        String[] lines = text.split("\n");
        List<String> result = new ArrayList<>();

        String combined = String.join("|", keywordPatterns);
        // 关键词必须出现在行首（可带序号/空格前缀），后跟冒号或行尾
        Pattern targetPattern = Pattern.compile(
                "(?i)^(?:[\\d一二三四五六七八九十]+[.、．]|[（(][\\d一二三四五六七八九十]+[)）][.、]?)?\\s*(" + combined + ")[：:：]?"
        );

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            Matcher m = targetPattern.matcher(line);
            if (m.find()) {
                StringBuilder sb = new StringBuilder();
                // 支持"标题+内容同行"：把整行都纳入段落
                sb.append(line).append('\n');
                int j = i + 1;
                while (j < lines.length) {
                    String next = lines[j].trim();
                    // 遇到非空的合法标题行则停止（但不能是目标关键词行，那会在外层循环处理）
                    if (!next.isEmpty() && isHeading(next)) {
                        break;
                    }
                    sb.append(lines[j]).append('\n');
                    j++;
                }
                String section = sb.toString().trim();
                result.add(smartTruncate(section, SECTION_CHAR_LIMIT));
                i = j;
            } else {
                i++;
            }
        }
        return result;
    }

    /**
     * 判断一行是否为合法标题行（严格模式，避免内容短句误判）。
     * 必须同时满足：
     * 1. 符合 HEADING_PATTERN（长度、格式）
     * 2. 不含句子标点（。，；！？…）
     */
    static boolean isHeading(String line) {
        if (line == null || line.isBlank()) return false;
        // 含句子标点的一定不是标题
        if (line.matches(".*[。，；！？…].*")) return false;
        return HEADING_PATTERN.matcher(line).matches();
    }

    /**
     * 智能截断：优先在句子结束符（。！？\n）处截断，保证语义完整。
     * 若在 [limit*0.7, limit] 范围内找不到合适断点，则硬切。
     */
    static String smartTruncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        // 在 [70%limit, limit] 窗口内向前找最近的句子结束符
        int searchFrom = (int) (limit * 0.7);
        int bestBreak = -1;
        for (int k = limit - 1; k >= searchFrom; k--) {
            char c = text.charAt(k);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '!' || c == '?') {
                bestBreak = k + 1;
                break;
            }
        }
        if (bestBreak > 0) {
            return text.substring(0, bestBreak).stripTrailing() + "\n…[已截断]";
        }
        return hardTruncate(text, limit);
    }

    /**
     * 兜底策略：找不到目标模块时，过滤垃圾行后保留长内容行，再截断。
     * 保证 AI 仍能读到有效信息，而不是直接硬截断原始文本。
     */
    static String fallbackTrim(String text, int limit) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // 过滤垃圾行
            if (NOISE_LINE_PATTERN.matcher(line).find()) continue;
            // 过滤纯标题行（短且无实质内容）且长度 <= 6 字的行
            if (isHeading(line) && line.replaceAll("[：:：\\s]", "").length() <= 6) continue;
            sb.append(line).append('\n');
        }
        String filtered = sb.toString().trim();
        // 若过滤后内容太少，退回原文
        if (filtered.length() < 50) {
            filtered = text;
        }
        return smartTruncate(filtered, limit);
    }

    /**
     * 将多个段落拼接成一个字符串，并截断到指定长度。
     */
    private static String joinAndTruncate(List<String> sections, int limit) {
        StringBuilder sb = new StringBuilder();
        for (String s : sections) {
            if (sb.length() + s.length() + 1 > limit) {
                int remaining = limit - sb.length() - 1;
                if (remaining > 100) {
                    sb.append(smartTruncate(s, remaining));
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

    /**
     * 硬截断：直接按字符切，添加截断标记。
     */
    static String hardTruncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…[已截断]";
    }
}
