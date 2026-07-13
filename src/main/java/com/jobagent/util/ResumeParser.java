package com.jobagent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历正则分段工具。
 *
 * <p>把完整简历文本拆成结构化片段：
 * <ul>
 *   <li>header — 个人信息（姓名、联系方式等，第一个标题之前的全部内容）</li>
 *   <li>education — 教育背景</li>
 *   <li>skills — 技能</li>
 *   <li>projects[] — 项目经历（每个项目 = title + body）</li>
 *   <li>experience — 实习/工作经历</li>
 *   <li>other — 其他（未匹配到上述标题的内容）</li>
 * </ul>
 */
public final class ResumeParser {

    private ResumeParser() {}

    /**
     * 简历结构片段。
     */
    public record ResumeSections(
            String header,
            String education,
            String skills,
            List<ProjectSection> projects,
            String experience,
            String other
    ) {
        /**
         * 单个项目段。
         */
        public record ProjectSection(
                String title,
                String body
        ) {}
    }

    /**
     * 常见简历标题关键词，按优先级排列。
     * key = 内部章节名, value = 匹配此标题的正则关键词组。
     */
    private static final LinkedHashMap<String, String> HEADING_PATTERNS = new LinkedHashMap<>();
    static {
        HEADING_PATTERNS.put("education",
                "教育背景|教育经历|学历|教育|学习经历|education|academic");
        HEADING_PATTERNS.put("skills",
                "技能|专业技能|核心技能|技术栈|个人技能|技能特长|技术能力|skill|技术|tech");
        HEADING_PATTERNS.put("projects",
                "项目经历|项目经验|项目介绍|项目|project|个人项目|主要项目");
        HEADING_PATTERNS.put("experience",
                "实习经历|工作经历|工作经验|职业经历|实习|工作|experience|employment|intern");
    }

    /**
     * 标题行正则：可选序号前缀（1. 一、 （一） 等），后跟关键词，可选冒号。
     */
    private static final Pattern HEADING_LINE = Pattern.compile(
            "^(?:[\\d一二三四五六七八九十]+[.、．]|[（(][\\d一二三四五六七八九十]+[)）][.、]?)?\\s*" +
            "([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9\\s/&]{0,18})" +
            "[：:：]?\\s*$"
    );

    /**
     * 解析完整简历文本，返回结构化分段。
     */
    public static ResumeSections parse(String fullResume) {
        if (fullResume == null || fullResume.isBlank()) {
            return new ResumeSections("", "", "", List.of(), "", "");
        }

        String text = fullResume.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = text.split("\n");

        // Step 1: 找出所有标题行及其位置
        record HeadingInfo(int lineIndex, String sectionKey, String rawLine) {}
        List<HeadingInfo> headings = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            Matcher m = HEADING_LINE.matcher(line);
            if (!m.matches()) continue;

            String candidate = m.group(1);
            for (Map.Entry<String, String> entry : HEADING_PATTERNS.entrySet()) {
                Pattern keyPattern = Pattern.compile("(?i)(" + entry.getValue() + ")");
                if (keyPattern.matcher(candidate).find()) {
                    headings.add(new HeadingInfo(i, entry.getKey(), line));
                    break;
                }
            }
        }

        // Step 2: 按标题位置切分段落
        String header = "";
        String education = "";
        String skills = "";
        List<ResumeSections.ProjectSection> projects = new ArrayList<>();
        String experience = "";
        StringBuilder other = new StringBuilder();

        if (headings.isEmpty()) {
            // 没有识别出任何标题，整文归入 header
            header = text.trim();
        } else {
            // 第一个标题之前的内容 → header
            int firstHeadingLine = headings.get(0).lineIndex;
            header = joinLines(lines, 0, firstHeadingLine).trim();

            for (int h = 0; h < headings.size(); h++) {
                HeadingInfo curr = headings.get(h);
                int contentStart = curr.lineIndex + 1;
                int contentEnd = (h + 1 < headings.size())
                        ? headings.get(h + 1).lineIndex
                        : lines.length;
                String content = joinLines(lines, contentStart, contentEnd).trim();

                switch (curr.sectionKey) {
                    case "education":
                        education = appendSection(education, content);
                        break;
                    case "skills":
                        skills = appendSection(skills, content);
                        break;
                    case "projects":
                        projects.addAll(splitProjects(content));
                        break;
                    case "experience":
                        experience = appendSection(experience, content);
                        break;
                    default:
                        other.append(content).append('\n');
                }
            }
        }

        return new ResumeSections(header, education.trim(), skills.trim(),
                projects, experience.trim(), other.toString().trim());
    }

    /**
     * 将项目段内容进一步拆分：按子标题或分隔符识别多个项目。
     */
    private static List<ResumeSections.ProjectSection> splitProjects(String content) {
        if (content.isBlank()) return List.of();

        List<ResumeSections.ProjectSection> result = new ArrayList<>();

        // 尝试按"项目一""项目N""项目名称"等拆分
        String[] blocks = content.split("(?m)^(?=[\\d一二三四五六七八九十]+[.、．)\\s]|项目[\\d一二三四五六七八九十]|[●■◆▸▪◦])");

        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;

            // 尝试提取标题（第一行）
            String[] lines = trimmed.split("\n", 2);
            String title = lines[0].trim();
            String body;
            if (lines.length > 1) {
                body = lines[1].trim();
            } else {
                // 单行：整个作为 title，body 为空
                body = "";
                title = trimmed;
            }

            // 如果 title 太短（< 4 字），可能是误分割，合并到上一个
            if (title.length() < 4 && !result.isEmpty()) {
                ResumeSections.ProjectSection last = result.remove(result.size() - 1);
                String mergedBody = last.body().isEmpty()
                        ? (last.title() + "\n" + trimmed)
                        : last.body() + "\n" + trimmed;
                result.add(new ResumeSections.ProjectSection(last.title(), mergedBody));
            } else {
                result.add(new ResumeSections.ProjectSection(title, body));
            }
        }

        return result;
    }

    /**
     * 拼接指定行范围内的文本。
     */
    private static String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 追加片段（处理重复标题：如两个"教育背景"标题）。
     */
    private static String appendSection(String existing, String newContent) {
        if (existing == null || existing.isEmpty()) return newContent;
        return existing + "\n" + newContent;
    }

    /**
     * 回填拼接：将改写后的项目经历替换回原始简历结构，生成完整简历文本。
     *
     * @param sections        原始简历分段
     * @param rewrittenTexts  改写后的项目段文本（顺序与 sections.projects() 一致）
     * @return 回填后的完整简历文本
     */
    public static String reassemble(ResumeSections sections, List<String> rewrittenTexts) {
        return reassembleFull(sections, rewrittenTexts, null, null);
    }

    /**
     * 完整回填拼接：支持改写后的项目经历 + 实习经历 + 技能列表。
     *
     * @param sections             原始简历分段
     * @param rewrittenProjectTexts 改写后的项目段文本（顺序与 sections.projects() 一致），可为 null 使用原文
     * @param rewrittenExperience   改写后的实习/工作经历文本，null 表示使用原文
     * @param rewrittenSkills       改写后的技能列表文本，null 表示使用原文
     * @return 回填后的完整简历文本
     */
    public static String reassembleFull(ResumeSections sections,
                                         List<String> rewrittenProjectTexts,
                                         String rewrittenExperience,
                                         String rewrittenSkills) {
        StringBuilder sb = new StringBuilder();

        // 个人信息
        if (!sections.header().isBlank()) {
            sb.append(sections.header()).append("\n\n");
        }

        // 教育背景
        if (!sections.education().isBlank()) {
            sb.append("教育背景\n").append(sections.education()).append("\n\n");
        }

        // 技能（优先使用改写版本）
        if (rewrittenSkills != null && !rewrittenSkills.isBlank()) {
            sb.append("技能\n").append(rewrittenSkills).append("\n\n");
        } else if (!sections.skills().isBlank()) {
            sb.append("技能\n").append(sections.skills()).append("\n\n");
        }

        // 项目经历（用改写后的内容替换）
        if (!sections.projects().isEmpty() && rewrittenProjectTexts != null) {
            sb.append("项目经历\n");
            for (int i = 0; i < sections.projects().size(); i++) {
                ResumeSections.ProjectSection proj = sections.projects().get(i);
                if (!proj.title().isBlank()) {
                    sb.append(proj.title()).append('\n');
                }
                if (i < rewrittenProjectTexts.size() && rewrittenProjectTexts.get(i) != null
                        && !rewrittenProjectTexts.get(i).isBlank()) {
                    sb.append(rewrittenProjectTexts.get(i));
                } else {
                    sb.append(proj.body());
                }
                sb.append("\n\n");
            }
        }

        // 实习/工作经历（优先使用改写版本）
        if (rewrittenExperience != null && !rewrittenExperience.isBlank()) {
            sb.append("工作经历\n").append(rewrittenExperience).append("\n\n");
        } else if (!sections.experience().isBlank()) {
            sb.append("工作经历\n").append(sections.experience()).append("\n\n");
        }

        // 其他
        if (!sections.other().isBlank()) {
            sb.append(sections.other()).append('\n');
        }

        return sb.toString().trim() + '\n';
    }

    // ========== 调试入口 ==========

    public static void main(String[] args) {
        String sample = """
                张三
                手机 138xxxx | email@xxx.com

                教育背景
                2023-2027 某大学 计算机科学 本科

                技能
                Java, Spring Boot, Redis, MySQL

                项目经历
                项目一：电商订单系统（2024.06-2024.12）
                负责订单系统性能优化，通过缓存和异步处理，QPS从500提升至2000。
                使用了Redis和RocketMQ。

                项目二：博客平台（2024.01-2024.05）
                基于Spring Boot开发个人博客，实现了文章管理、评论系统。
                部署在Docker容器中。

                实习经历
                2025.07-2025.09 某公司 Java 开发实习生
                参与内部管理系统开发，负责数据库设计和API接口编写。
                """;

        ResumeSections sections = parse(sample);
        System.out.println("=== Header ===");
        System.out.println(sections.header());
        System.out.println("\n=== Education ===");
        System.out.println(sections.education());
        System.out.println("\n=== Skills ===");
        System.out.println(sections.skills());
        System.out.println("\n=== Projects (" + sections.projects().size() + ") ===");
        for (var p : sections.projects()) {
            System.out.println("  TITLE: " + p.title());
            System.out.println("  BODY:  " + p.body().replace("\n", "\n  "));
        }
        System.out.println("\n=== Experience ===");
        System.out.println(sections.experience());
        System.out.println("\n=== Other ===");
        System.out.println(sections.other());

        // 测试回填
        System.out.println("\n=== Reassembled ===");
        List<String> rewritten = List.of(
                "项目一改写内容：大幅优化了订单系统，QPS提升至5000。",
                "项目二改写内容：博客平台重构，增加了全文搜索功能。"
        );
        System.out.println(reassemble(sections, rewritten));
    }
}