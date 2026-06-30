package com.jobagent.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Critic Agent 的事实核查工具集。
 *
 * <p>为 FactCriticAgent 提供"精确比对"能力，弥补 LLM 在以下场景的天然弱点：
 * <ul>
 *   <li>数值精确比对——LLM 擅长语义理解，但不擅长"5000 ≠ 2000"这种精确数值判断</li>
 *   <li>技术栈差集运算——LLM 可能忽略某个技术的缺失或新增</li>
 *   <li>角色措辞升级检测——"参与→主导"这类关键词替换 LLM 判断不一致</li>
 * </ul>
 *
 * <h3>设计决策：为什么返回 String 而不是 boolean</h3>
 * <p>返回值会作为 {@code ToolExecutionResultMessage} 的 content 字段发回 LLM。
 * 返回 "不一致：原为 QPS 2000，改为 QPS 5000" 比返回 "false" 更有用——
 * LLM 不需要二次推理，直接拿到差异详情，可以据此写出更准确的 violation detail。
 *
 * <h3>注册方式</h3>
 * <p>本类声明为 {@code @Component("factCheckTool")}，由 {@link FactCriticAgent}
 * 通过 {@code @AiService(tools = {"factCheckTool"})} 引用。框架在代理生成时自动扫描
 * 所有 {@code @Tool} 方法，为每个方法生成 JSON Schema 并注册到 LLM 的 tools 字段。
 *
 * @see FactCriticAgent
 * @see dev.langchain4j.agent.tool.Tool
 */
@Component("factCheckTool")
public class FactCheckTool {

    private static final Logger log = LoggerFactory.getLogger(FactCheckTool.class);

    /** 匹配数值的模式：整数、小数、百分比、QPS/ms/s 等单位后缀 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "\\d+\\.?\\d*\\s*(?:%|倍|万|亿|QPS|ms|s|min|h|MB|GB|TB|次|条|个|人)?"
    );

    /** 角色夸大关键词映射：原文弱措辞 → 可能被夸大为 */
    private static final Set<String> MILD_WORDS = Set.of(
            "参与", "协助", "配合", "支持", "了解", "接触", "学习"
    );
    private static final Set<String> STRONG_WORDS = Set.of(
            "主导", "设计", "架构", "从零搭建", "独立负责", "全权负责", "领导", "Owner"
    );

    /**
     * 核查简历改写中的某项声明是否与原文一致。
     *
     * <p>比对内容：数值（QPS、百分比等）+ 定性描述。这是 FactCheckTool 最核心的方法，
     * 也是 {@code @Tool} 描述最需要精准措辞的方法——描述质量直接决定 LLM 的调用率。
     *
     * @param originalSegment 原文中的相关段落（LLM 从原始项目经历中提取）
     * @param rewrittenClaim  改写后的声明（LLM 从 bullet point 中提取）
     * @return 比对结果字符串，供 LLM 作为后续判断的上下文。格式：
     *         <ul>
     *           <li>"一致" — 原文和改写声明在数值和定性描述上一致</li>
     *           <li>"不一致：原为 [原文数值]，改为 [改写数值]" — 数值不一致</li>
     *           <li>"不确定：原文无具体数值，改写声称 [改写数值]" — 原文无数据但改写编造了</li>
     *         </ul>
     */
    @Tool("精确比对原文段落和改写声明中的数值是否一致。当你发现 bullet point 中的数字与原文不符时，调用此工具做精确比对，而不是凭感觉判断。")
    public String checkClaim(
            @P("原文中的相关段落内容") String originalSegment,
            @P("改写后简历中的声明（可能是句子或片段）") String rewrittenClaim
    ) {
        log.debug("checkClaim called: original='{}' rewritten='{}'", 
                trimForLog(originalSegment), trimForLog(rewrittenClaim));

        Set<String> originalNumbers = extractNumbers(originalSegment);
        Set<String> rewrittenNumbers = extractNumbers(rewrittenClaim);

        // 原文和改写都无数字 → 一致（纯定性描述，LLM 自行判断语义）
        if (originalNumbers.isEmpty() && rewrittenNumbers.isEmpty()) {
            return "一致：双方均无具体数值，为纯定性描述";
        }

        // 原文无数字，但改写声称有数字 → 疑似捏造数据
        if (originalNumbers.isEmpty() && !rewrittenNumbers.isEmpty()) {
            return String.format("不一致：原文无具体数值，但改写声称了 [%s]。原文仅有定性描述，不应编造数值。",
                    String.join(", ", rewrittenNumbers));
        }

        // 原文有数字，改写无数字 → 可能遗漏（较轻微）
        if (!originalNumbers.isEmpty() && rewrittenNumbers.isEmpty()) {
            return String.format("注意：原文包含数值 [%s]，但改写中未体现。可能是合理省略，需人工判断。",
                    String.join(", ", originalNumbers));
        }

        // 双方都有数字 → 逐项比对
        Set<String> onlyInOriginal = new HashSet<>(originalNumbers);
        onlyInOriginal.removeAll(rewrittenNumbers);
        Set<String> onlyInRewritten = new HashSet<>(rewrittenNumbers);
        onlyInRewritten.removeAll(originalNumbers);

        if (onlyInOriginal.isEmpty() && onlyInRewritten.isEmpty()) {
            return "一致：数值完全匹配";
        }

        StringBuilder result = new StringBuilder("不一致：");
        if (!onlyInOriginal.isEmpty()) {
            result.append(String.format("原文有但改写遗漏 [%s]；", String.join(", ", onlyInOriginal)));
        }
        if (!onlyInRewritten.isEmpty()) {
            result.append(String.format("改写出现原文没有的数值 [%s]", String.join(", ", onlyInRewritten)));
        }
        return result.toString();
    }

    /**
     * 核查改写中引用的技术栈是否全部在原文中出现过。
     *
     * <p>做集合差集运算：rewritten_tech - original_tech = 新增的技术。
     * LLM 不擅长对列表做精确差集，此 Tool 填补这个能力空白。
     *
     * @param originalTechList 原文中出现的所有技术名称，逗号或空格分隔（LLM 从原文中提取）
     * @param rewrittenTechList 改写后 bullet point 中提到的所有技术名称，逗号或空格分隔
     * @return 比对结果：
     *         <ul>
     *           <li>"技术栈一致" — 改写未引入原文没有的技术</li>
     *           <li>"新增技术：Kafka（原文未出现）" — 存在原文没有的技术</li>
     *           <li>"遗漏技术：RocketMQ（原文有但改写未提）" — 原文技术被删除</li>
     *         </ul>
     */
    @Tool("核查改写后简历中引用的技术栈是否全部在原文中出现过。做集合差集运算，发现原文中未出现的'新增技术'。")
    public String checkTechStack(
            @P("原文中出现的所有技术名称，逗号或空格分隔") String originalTechList,
            @P("改写后 bullet point 中提到的所有技术名称，逗号或空格分隔") String rewrittenTechList
    ) {
        log.debug("checkTechStack called: original='{}' rewritten='{}'", originalTechList, rewrittenTechList);

        Set<String> originalTechs = parseTechNames(originalTechList);
        Set<String> rewrittenTechs = parseTechNames(rewrittenTechList);

        if (originalTechs.isEmpty() && rewrittenTechs.isEmpty()) {
            return "技术栈一致：双方均无技术栈信息";
        }

        Set<String> added = new HashSet<>(rewrittenTechs);
        added.removeAll(originalTechs);

        Set<String> removed = new HashSet<>(originalTechs);
        removed.removeAll(rewrittenTechs);

        if (added.isEmpty() && removed.isEmpty()) {
            return "技术栈一致";
        }

        StringBuilder result = new StringBuilder();
        if (!added.isEmpty()) {
            result.append(String.format("新增技术：%s（原文未出现→疑似捏造）；",
                    String.join(", ", added)));
        }
        if (!removed.isEmpty()) {
            result.append(String.format("遗漏技术：%s（原文有但改写未提）；",
                    String.join(", ", removed)));
        }
        return result.toString();
    }

    /**
     * 核查改写中的角色措辞是否夸大了候选人的实际定位。
     *
     * <p>检测模式：原文用"参与/协助/配合" → 改写用"主导/设计/从零搭建"。
     * 这不是严格的字符串匹配——原文可能出现"参与核心模块开发"，
     * 改写可能出现"主导核心模块开发"，通过关键词检测来发现升级。
     *
     * @param originalRoleDescription 原文中描述候选人角色的句子
     * @param rewrittenRoleClaim      改写后 bullet point 中对角色的描述
     * @return 比对结果：
     *         <ul>
     *           <li>"措辞一致" — 无夸大</li>
     *           <li>"角色夸大：原文使用[参与]，改写使用[主导]" — 存在措辞升级</li>
     *         </ul>
     */
    @Tool("核查改写中的角色措辞是否被夸大，例如原文'参与开发'被改为'主导设计'。比对角色关键词的强弱程度。")
    public String checkRoleWording(
            @P("原文中描述候选人角色的句子或短语") String originalRoleDescription,
            @P("改写后 bullet point 中对角色的描述") String rewrittenRoleClaim
    ) {
        log.debug("checkRoleWording called: original='{}' rewritten='{}'",
                trimForLog(originalRoleDescription), trimForLog(rewrittenRoleClaim));

        // 找出原文中的弱措辞
        Set<String> mildFound = MILD_WORDS.stream()
                .filter(originalRoleDescription::contains)
                .collect(Collectors.toSet());

        // 找出改写中的强措辞
        Set<String> strongFound = STRONG_WORDS.stream()
                .filter(rewrittenRoleClaim::contains)
                .collect(Collectors.toSet());

        // 原文有弱措辞 且 改写有强措辞 → 疑似夸大
        if (!mildFound.isEmpty() && !strongFound.isEmpty()) {
            return String.format(
                    "角色夸大：原文使用[%s]，改写使用[%s]。原文的语气仅为%s，但改写升级为%s，可能存在夸大。",
                    String.join(", ", mildFound),
                    String.join(", ", strongFound),
                    mildFound.size() == 1 ? "参与者" : "参与者",
                    strongFound.size() == 1 ? "负责人" : "负责人"
            );
        }

        // 仅有弱措辞无强措辞 → 可能正常
        if (!mildFound.isEmpty()) {
            return String.format("注意：原文使用弱措辞[%s]，改写未出现对应的强措辞，可能已经做了合理弱化。",
                    String.join(", ", mildFound));
        }

        // 仅有强措辞无弱措辞 → 可能是原文本身就有较强定位
        if (!strongFound.isEmpty()) {
            return String.format("注意：改写使用强措辞[%s]，原文未出现对应的弱措辞。如果原文本身已使用类似措辞（如'主导'），则无问题；否则需要人工判断。",
                    String.join(", ", strongFound));
        }

        return "措辞一致：未检测到角色夸大";
    }

    // ========== 私有辅助方法 ==========

    /**
     * 从文本中提取所有数值。
     *
     * <p>匹配模式：整数、小数、带单位的数字（QPS、百分比、MB等）。
     * 使用正则而非 NLP，保证确定性——同一输入永远返回同一结果。
     */
    private Set<String> extractNumbers(String text) {
        Set<String> numbers = new HashSet<>();
        if (text == null || text.isBlank()) {
            return numbers;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group().trim());
        }
        return numbers;
    }

    /**
     * 从技术列表字符串中解析技术名称。
     *
     * <p>支持逗号、中文逗号、顿号、空格分隔，自动去空、去重。
     * 对每个技术名做规范化：去首尾空格、统一大小写。
     */
    private Set<String> parseTechNames(String techList) {
        if (techList == null || techList.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(techList.split("[,，、\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)  // 大小写不敏感：RocketMQ == rocketmq
                .collect(Collectors.toSet());
    }

    /** 截断日志输出，避免打印过长文本 */
    private static String trimForLog(String text) {
        if (text == null) return "null";
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}