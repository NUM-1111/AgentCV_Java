package com.jobagent.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextTrimmerTest {

    // -----------------------------------------------------------------------
    // trimJd — 段落识别
    // -----------------------------------------------------------------------

    @Test
    void trimJd_extractsResponsibilityAndRequirementSections() {
        String jd = """
                公司介绍：
                我们是一家优秀的互联网公司，致力于改变世界。
                
                岗位职责：
                1. 负责核心业务系统开发
                2. 参与技术选型
                
                任职要求：
                1. 本科及以上学历
                2. 熟练掌握Java
                
                薪资福利：
                五险一金，年终奖，弹性工作。
                """;

        String result = TextTrimmer.trimJd(jd);

        assertTrue(result.contains("岗位职责"), "应保留岗位职责段落");
        assertTrue(result.contains("任职要求"), "应保留任职要求段落");
        assertFalse(result.contains("公司介绍"), "应丢弃公司介绍");
        assertFalse(result.contains("薪资福利"), "应丢弃薪资福利");
    }

    @Test
    void trimJd_fallsBackToHardTruncateWhenNoSectionsFound() {
        // 无法识别段落结构的纯文本
        String jd = "这是一段没有任何标题结构的 JD 文本，直接描述岗位内容。".repeat(200);

        String result = TextTrimmer.trimJd(jd);

        assertTrue(result.length() <= TextTrimmer.hardTruncate(jd, 4000).length() + 10,
                "无结构时应硬截断到 4000 字以内");
    }

    @Test
    void trimJd_emptyInputReturnsEmpty() {
        assertEquals("", TextTrimmer.trimJd(null));
        assertEquals("", TextTrimmer.trimJd(""));
        assertEquals("", TextTrimmer.trimJd("   "));
    }

    @Test
    void trimJd_shortInputPassesThrough() {
        String jd = "岗位职责：负责开发。任职要求：会Java。";
        String result = TextTrimmer.trimJd(jd);
        assertFalse(result.isBlank(), "短文本不应被清空");
    }

    // -----------------------------------------------------------------------
    // trimResume — 段落识别
    // -----------------------------------------------------------------------

    @Test
    void trimResume_extractsWorkAndProjectSections() {
        String resume = """
                姓名：张三
                联系方式：138xxxx8888
                
                教育背景：
                2015-2019 北京大学 计算机科学
                
                工作经历：
                2022至今 某公司 高级工程师
                负责核心系统开发
                
                项目经历：
                分布式秒杀系统，Spring Boot + Redis
                
                技能清单：
                Java、Python
                """;

        String result = TextTrimmer.trimResume(resume);

        assertTrue(result.contains("工作经历"), "应保留工作经历段落");
        assertTrue(result.contains("项目经历"), "应保留项目经历段落");
        assertFalse(result.contains("姓名"), "应丢弃个人信息头部");
        assertFalse(result.contains("教育背景"), "应丢弃教育背景");
        assertFalse(result.contains("技能清单"), "应丢弃技能清单");
    }

    @Test
    void trimResume_emptyInputReturnsEmpty() {
        assertEquals("", TextTrimmer.trimResume(null));
        assertEquals("", TextTrimmer.trimResume(""));
    }

    // -----------------------------------------------------------------------
    // hardTruncate
    // -----------------------------------------------------------------------

    @Test
    void hardTruncate_shortTextPassesThrough() {
        String text = "短文本";
        assertEquals(text, TextTrimmer.hardTruncate(text, 100));
    }

    @Test
    void hardTruncate_longTextIsTruncatedWithMarker() {
        String text = "A".repeat(5000);
        String result = TextTrimmer.hardTruncate(text, 4000);

        assertEquals(4000 + "…[已截断]".length(), result.length());
        assertTrue(result.endsWith("…[已截断]"));
    }

    @Test
    void hardTruncate_exactLimitPassesThrough() {
        String text = "B".repeat(4000);
        assertEquals(text, TextTrimmer.hardTruncate(text, 4000));
    }

    // -----------------------------------------------------------------------
    // extractSections — 内部逻辑
    // -----------------------------------------------------------------------

    @Test
    void extractSections_returnsEmptyWhenNoMatch() {
        String text = "这段文字没有任何匹配的标题行。";
        List<String> sections = TextTrimmer.extractSections(text, "不存在的关键词");
        assertTrue(sections.isEmpty());
    }

    @Test
    void extractSections_capturesMultipleMatchingSections() {
        String text = """
                工作经历：
                2022 某公司 工程师
                
                项目经历：
                秒杀系统项目
                """;
        List<String> sections = TextTrimmer.extractSections(text,
                "工作经历|工作经验", "项目经历|项目经验");

        assertEquals(2, sections.size());
        assertTrue(sections.get(0).contains("工作经历"));
        assertTrue(sections.get(1).contains("项目经历"));
    }

    @Test
    void extractSections_singleSectionTruncatedToLimit() {
        // 构造一个超过 SECTION_CHAR_LIMIT(1500) 的段落
        String longContent = "工作经历：\n" + "负责开发工作。".repeat(300);
        List<String> sections = TextTrimmer.extractSections(longContent, "工作经历");

        assertFalse(sections.isEmpty());
        assertTrue(sections.get(0).length() <= 1500 + "…[已截断]".length());
    }
}
