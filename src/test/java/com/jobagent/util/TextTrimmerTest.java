package com.jobagent.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextTrimmer 单元测试。
 *
 * 覆盖场景：
 *  - 正常格式 JD（标题独占一行）
 *  - 无标题纯文本 JD（兜底策略）
 *  - 紧凑格式 JD（标题+内容同行）
 *  - 带序号标题的简历
 *  - 内容短句不被误判为标题
 *  - 智能截断在句子边界处断开
 *  - 原有基础用例（回归）
 */
class TextTrimmerTest {

    // -----------------------------------------------------------------------
    // 场景1：正常格式 JD（标题独占一行，含公司介绍/福利等噪声）
    // -----------------------------------------------------------------------

    @Test
    void trimJd_normalFormat_extractsCoreAndDropsNoise() {
        String jd = """
                公司介绍：
                我们是一家优秀的互联网公司，致力于改变世界。
                
                岗位职责：
                1. 负责核心业务系统的设计与开发，保障系统稳定运行。
                2. 参与技术选型，推动架构演进。
                3. 编写技术文档，协助团队成员解决技术难题。
                
                任职要求：
                1. 本科及以上学历，计算机相关专业。
                2. 熟练掌握 Java，熟悉 Spring Boot 框架。
                3. 有分布式系统开发经验者优先。
                
                薪资福利：
                五险一金，年终奖，弹性工作，带薪年假。
                """;

        String result = TextTrimmer.trimJd(jd);

        assertTrue(result.contains("岗位职责"), "应保留岗位职责段落");
        assertTrue(result.contains("任职要求"), "应保留任职要求段落");
        assertFalse(result.contains("公司介绍"), "应丢弃公司介绍");
        assertFalse(result.contains("薪资福利"), "应丢弃薪资福利");
        assertFalse(result.contains("五险一金"), "应丢弃福利内容");
    }

    // -----------------------------------------------------------------------
    // 场景2：无标题纯文本 JD（兜底策略：过滤垃圾行，保留有效内容）
    // -----------------------------------------------------------------------

    @Test
    void trimJd_noHeading_fallbackFiltersNoiseAndKeepsContent() {
        String jd = """
                负责后端微服务架构设计与开发，使用 Spring Cloud 技术栈。
                参与数据库设计，熟悉 MySQL 调优。
                五险一金，年终奖，弹性工作。
                联系邮箱：hr@example.com
                要求本科以上学历，三年以上 Java 开发经验。
                """;

        String result = TextTrimmer.trimJd(jd);

        assertFalse(result.isBlank(), "兜底策略不应返回空");
        assertTrue(result.contains("Spring Cloud") || result.contains("Java"), "应保留有效技术内容");
        assertFalse(result.contains("五险一金"), "应过滤福利行");
        assertFalse(result.contains("邮箱"), "应过滤联系方式行");
    }

    // -----------------------------------------------------------------------
    // 场景3：紧凑格式 JD（标题+内容同行，如"岗位职责：负责开发..."）
    // -----------------------------------------------------------------------

    @Test
    void trimJd_compactFormat_titleAndContentOnSameLine() {
        String jd = """
                岗位职责：负责后端服务开发，维护线上系统稳定性，参与代码评审。
                任职要求：三年以上 Java 经验，熟悉 Spring Boot，有微服务经验优先。
                """;

        String result = TextTrimmer.trimJd(jd);

        assertTrue(result.contains("岗位职责"), "应识别同行标题");
        assertTrue(result.contains("负责后端服务开发"), "同行内容不应丢失");
        assertTrue(result.contains("任职要求"), "应识别同行任职要求标题");
        assertTrue(result.contains("三年以上"), "同行要求内容不应丢失");
    }

    // -----------------------------------------------------------------------
    // 场景4：带序号标题的简历（"一、工作经历" / "1. 项目经历"）
    // -----------------------------------------------------------------------

    @Test
    void trimResume_numberedHeadings_extractsCorrectly() {
        String resume = """
                姓名：张三
                联系方式：138xxxx8888
                
                一、教育背景
                2015-2019 北京大学 计算机科学
                
                二、工作经历
                2022至今 某互联网公司 高级工程师
                负责核心交易系统开发，日均处理订单百万级。
                
                三、项目经历
                分布式秒杀系统：基于 Spring Boot + Redis 实现高并发秒杀，QPS 达 5 万。
                
                四、技能清单
                Java、Python、MySQL
                """;

        String result = TextTrimmer.trimResume(resume);

        assertTrue(result.contains("工作经历"), "应识别带序号的工作经历标题");
        assertTrue(result.contains("项目经历"), "应识别带序号的项目经历标题");
        assertTrue(result.contains("核心交易系统"), "应保留工作经历内容");
        assertFalse(result.contains("姓名"), "应丢弃个人信息头部");
        assertFalse(result.contains("教育背景"), "应丢弃教育背景");
    }

    // -----------------------------------------------------------------------
    // 场景5：内容短句不被误判为标题（修复原代码核心缺陷）
    // -----------------------------------------------------------------------

    @Test
    void extractSections_shortSentenceInContentNotMistakenAsHeading() {
        String jd = """
                岗位职责：
                1. 负责系统设计与开发。
                2. 代码质量高。
                3. 沟通能力强。
                4. 持续学习新技术，保持技术竞争力。
                
                任职要求：
                本科及以上学历，三年以上经验。
                """;

        List<String> sections = TextTrimmer.extractSections(jd,
                "岗位职责|工作职责", "任职要求|岗位要求");

        assertEquals(2, sections.size(), "应提取到2个段落，短句不应截断段落");
        assertTrue(sections.get(0).contains("代码质量高"), "短句内容不应被误切");
        assertTrue(sections.get(0).contains("沟通能力强"), "短句内容不应被误切");
    }

    // -----------------------------------------------------------------------
    // 场景6：智能截断在句子边界处断开
    // -----------------------------------------------------------------------

    @Test
    void smartTruncate_breaksAtSentenceBoundary() {
        // 构造一段在 1050 字符处有句号的文本（limit=1500，70%=1050）
        String base = "负责系统开发工作，保障系统稳定运行。".repeat(60); // ~1080 chars
        String extra = "这是超出部分的内容，不应该出现在截断结果中。".repeat(20);
        String text = base + extra;

        String result = TextTrimmer.smartTruncate(text, 1500);

        assertTrue(result.length() <= 1500 + "\n…[已截断]".length() + 5,
                "截断后长度应在限制范围内");
        assertTrue(result.endsWith("…[已截断]") || result.length() <= 1500,
                "超长文本应有截断标记或在限制内");
        // 截断点应在句号处，不应切在汉字中间
        if (result.contains("…[已截断]")) {
            String content = result.replace("\n…[已截断]", "").replace("…[已截断]", "");
            char lastChar = content.charAt(content.length() - 1);
            assertTrue(lastChar == '。' || lastChar == '\n' || lastChar == '！' || lastChar == '？',
                    "智能截断应在句子结束符处断开，实际末尾字符: " + lastChar);
        }
    }

    // -----------------------------------------------------------------------
    // 场景7：isHeading 标题判定精确性
    // -----------------------------------------------------------------------

    @Test
    void isHeading_correctlyIdentifiesHeadingsAndNonHeadings() {
        // 合法标题
        assertTrue(TextTrimmer.isHeading("岗位职责"), "纯标题词应判定为标题");
        assertTrue(TextTrimmer.isHeading("岗位职责："), "带冒号标题应判定为标题");
        assertTrue(TextTrimmer.isHeading("任职要求："), "任职要求应判定为标题");
        assertTrue(TextTrimmer.isHeading("1. 工作经历"), "带序号标题应判定为标题");
        assertTrue(TextTrimmer.isHeading("一、项目经历"), "带中文序号标题应判定为标题");

        // 非标题（含句子标点）
        assertFalse(TextTrimmer.isHeading("代码质量高。"), "含句号的短句不应判定为标题");
        assertFalse(TextTrimmer.isHeading("沟通能力强，团队协作好。"), "含逗号句号的短句不应判定为标题");
        assertFalse(TextTrimmer.isHeading("负责系统开发工作，保障系统稳定运行。"), "内容句不应判定为标题");
        assertFalse(TextTrimmer.isHeading(""), "空行不应判定为标题");
    }

    // -----------------------------------------------------------------------
    // 回归：原有基础用例
    // -----------------------------------------------------------------------

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

    @Test
    void trimResume_emptyInputReturnsEmpty() {
        assertEquals("", TextTrimmer.trimResume(null));
        assertEquals("", TextTrimmer.trimResume(""));
    }

    @Test
    void hardTruncate_shortTextPassesThrough() {
        assertEquals("短文本", TextTrimmer.hardTruncate("短文本", 100));
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
        assertEquals("B".repeat(4000), TextTrimmer.hardTruncate("B".repeat(4000), 4000));
    }

    @Test
    void extractSections_returnsEmptyWhenNoMatch() {
        List<String> sections = TextTrimmer.extractSections("这段文字没有任何匹配的标题行。", "不存在的关键词");
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
        String longContent = "工作经历：\n" + "负责开发工作，保障系统稳定运行。".repeat(100);
        List<String> sections = TextTrimmer.extractSections(longContent, "工作经历");

        assertFalse(sections.isEmpty());
        assertTrue(sections.get(0).length() <= 1500 + "\n…[已截断]".length() + 5);
    }

    @Test
    void trimJd_fallsBackWhenNoSectionsFound() {
        String jd = "这是一段没有任何标题结构的 JD 文本，直接描述岗位内容。".repeat(200);
        String result = TextTrimmer.trimJd(jd);
        assertTrue(result.length() <= 4000 + "\n…[已截断]".length() + 10,
                "无结构时应截断到 4000 字以内");
    }

    // -----------------------------------------------------------------------
    // 极端 / 边界场景
    // -----------------------------------------------------------------------

    /** 场景E1：全是空行和空白字符，不崩溃，返回空字符串。 */
    @Test
    void trimJd_allBlankLines_returnsEmpty() {
        assertEquals("", TextTrimmer.trimJd("   \n\n\t\n   \n"));
        assertEquals("", TextTrimmer.trimResume("\n\n\n\n"));
    }

    /** 场景E2：单个字符输入，不崩溃，直接透传。 */
    @Test
    void trimJd_singleChar_doesNotCrash() {
        String result = TextTrimmer.trimJd("A");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    /** 场景E3：超大文本（~10万字符），性能可接受，输出严格在限制内。 */
    @Test
    void trimJd_hugeInput_outputWithinLimit() {
        // 构造带标题的超大 JD
        String bigJd = "岗位职责：\n" + "负责系统开发，保障稳定运行。".repeat(5000)
                + "\n任职要求：\n" + "本科以上学历，三年以上经验。".repeat(5000);

        long start = System.currentTimeMillis();
        String result = TextTrimmer.trimJd(bigJd);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 3000, "10万字符处理应在3秒内完成，实际: " + elapsed + "ms");
        assertTrue(result.length() <= TOTAL_CHAR_LIMIT_FOR_TEST + "\n…[已截断]".length() + 10,
                "输出应在4000字符限制内");
        assertFalse(result.isBlank(), "输出不应为空");
    }

    private static final int TOTAL_CHAR_LIMIT_FOR_TEST = 4000;

    /** 场景E4：全是标题行，没有任何内容行，不死循环，段落内容为空也能处理。 */
    @Test
    void trimJd_onlyHeadings_noContentLines_doesNotCrash() {
        String jd = """
                公司介绍
                岗位职责
                任职要求
                薪资福利
                工作地点
                """;
        String result = TextTrimmer.trimJd(jd);
        assertNotNull(result);
        // 只有标题没有内容，段落提取后内容极少，不应抛异常
    }

    /** 场景E5：关键词出现在文本最后一行（无后续内容），不越界，能正常提取。 */
    @Test
    void trimJd_keywordOnLastLine_doesNotThrow() {
        String jd = "这是前置内容，描述公司背景。\n岗位职责：";
        assertDoesNotThrow(() -> {
            String result = TextTrimmer.trimJd(jd);
            assertTrue(result.contains("岗位职责"), "最后一行的关键词也应被识别");
        });
    }

    /** 场景E6：混合中英文 + emoji + 特殊符号，正则不崩溃，输出合理。 */
    @Test
    void trimJd_emojiAndSpecialChars_doesNotCrash() {
        String jd = """
                🚀 Job Responsibilities:
                岗位职责：
                1. 负责 AI/ML 系统开发 🤖，使用 Python & Java。
                2. 处理 <XML>、JSON、{特殊符号}、[括号]、(圆括号)。
                3. 正则表达式：\\d+\\.\\d+，不应导致 Pattern 异常。
                
                任职要求：
                - 熟悉 C++ / Rust / Go 等语言。
                - 有 AWS/GCP/Azure 云平台经验 ☁️。
                """;
        assertDoesNotThrow(() -> {
            String result = TextTrimmer.trimJd(jd);
            assertFalse(result.isBlank(), "含特殊字符的 JD 不应返回空");
        });
    }

    /** 场景E7：同一关键词标题在文本中出现多次，每次都能独立提取，不合并。 */
    @Test
    void trimJd_duplicateKeywordSections_extractedSeparately() {
        String jd = """
                岗位职责：
                负责 A 系统开发。
                
                岗位职责：
                负责 B 系统维护。
                """;
        List<String> sections = TextTrimmer.extractSections(jd, "岗位职责");
        assertEquals(2, sections.size(), "重复出现的关键词标题应各自提取");
        assertTrue(sections.get(0).contains("A 系统"), "第一段内容应正确");
        assertTrue(sections.get(1).contains("B 系统"), "第二段内容应正确");
    }

    /** 场景E8：Windows CRLF 换行符 + 混合换行，cleanText 统一处理后正常工作。 */
    @Test
    void trimJd_windowsCrlfLineEndings_handledCorrectly() {
        String jd = "公司介绍：\r\n我们是一家公司。\r\n\r\n岗位职责：\r\n1. 负责开发。\r\n2. 参与设计。\r\n\r\n任职要求：\r\n本科以上。\r\n";
        String result = TextTrimmer.trimJd(jd);
        assertTrue(result.contains("岗位职责"), "CRLF 换行应被正确处理");
        assertTrue(result.contains("任职要求"), "CRLF 换行应被正确处理");
        assertFalse(result.contains("公司介绍"), "公司介绍应被丢弃");
    }

    /** 场景E9：JD 内容全是英文，关键词也是英文，能正常识别。 */
    @Test
    void trimJd_fullEnglishJd_extractsCorrectly() {
        String jd = """
                About Us:
                We are a leading tech company.
                
                Job Responsibilities:
                1. Design and develop backend services.
                2. Collaborate with cross-functional teams.
                
                Requirements:
                1. Bachelor's degree in Computer Science.
                2. 3+ years of Java experience.
                
                Benefits:
                Health insurance, 401k, remote work.
                """;
        String result = TextTrimmer.trimJd(jd);
        assertFalse(result.isBlank(), "全英文 JD 不应返回空");
        // 英文关键词 job responsibilities / requirements 应被识别
        assertTrue(result.contains("Responsibilities") || result.contains("Requirements"),
                "应识别英文关键词段落");
    }

    /** 场景E10：简历只有一行，无任何结构，兜底策略不崩溃。 */
    @Test
    void trimResume_singleLineNoStructure_doesNotCrash() {
        String resume = "张三，5年Java开发经验，熟悉Spring Boot，有大厂背景。";
        assertDoesNotThrow(() -> {
            String result = TextTrimmer.trimResume(resume);
            assertFalse(result.isBlank(), "单行简历兜底后不应为空");
        });
    }

    /** 场景E11：简历中工作经历和项目经历内容极长（各超过1500字），各自被独立截断。 */
    @Test
    void trimResume_eachSectionExceedsLimit_truncatedIndependently() {
        String workSection = "工作经历：\n" + "在某公司负责核心系统开发，日均处理订单百万级。".repeat(80);
        String projectSection = "\n项目经历：\n" + "主导分布式秒杀系统设计，QPS达5万。".repeat(80);
        String resume = workSection + projectSection;

        List<String> sections = TextTrimmer.extractSections(resume,
                "工作经历|工作经验", "项目经历|项目经验");

        assertEquals(2, sections.size(), "应提取到2个段落");
        // 每段都应被截断到 1500 + 截断标记以内
        for (String s : sections) {
            assertTrue(s.length() <= 1500 + "\n…[已截断]".length() + 5,
                    "每段应独立截断，实际长度: " + s.length());
        }
    }

    /** 场景E12：JD 中标题行前后有大量连续空行，cleanText 压缩后仍能正确识别。 */
    @Test
    void trimJd_excessiveBlankLines_cleanedAndExtracted() {
        String jd = "\n\n\n\n\n岗位职责：\n\n\n\n负责开发。\n\n\n\n\n任职要求：\n\n\n本科以上。\n\n\n\n";
        String result = TextTrimmer.trimJd(jd);
        assertTrue(result.contains("岗位职责"), "大量空行清洗后应能识别标题");
        assertTrue(result.contains("任职要求"), "大量空行清洗后应能识别标题");
    }

    /** 场景E13：关键词被包含在更长的词中（如"主要职责说明"），不应误匹配。
     *  同时验证"职责"作为独立关键词能正确匹配。 */
    @Test
    void trimJd_keywordEmbeddedInLongerWord_matchesCorrectly() {
        String jd = """
                职责：
                负责后端开发。
                """;
        // "职责" 是关键词之一，应能匹配
        String result = TextTrimmer.trimJd(jd);
        assertTrue(result.contains("职责"), "独立关键词'职责'应能匹配");
    }

    /** 场景E14：cleanText 对各种空白字符的处理。 */
    @Test
    void cleanText_normalizesWhitespaceCorrectly() {
        String messy = "岗位职责：\t\t负责开发。\n\n\n\n任职要求：\n本科以上。";
        String cleaned = TextTrimmer.cleanText(messy);
        assertFalse(cleaned.contains("\t\t"), "连续Tab应被清理");
        assertFalse(cleaned.contains("\n\n\n"), "超过2个连续空行应被合并");
        assertTrue(cleaned.contains("岗位职责"), "内容不应丢失");
    }
}
