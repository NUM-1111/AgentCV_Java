package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

// Record 是 Java 17 的新特性，特别适合做这种纯数据载体
public record JdInfo(
    @Description("岗位的标准名称，例如：Java高级开发工程师、产品经理等")
    String jobTitle,
    
    @Description("该岗位最核心的 3 到 5 项工作职责")
    List<String> coreResponsibilities,
    
    @Description("候选人必须具备的硬性技能点，例如：Java, Spring Boot, MySQL等。提取独立的词，不要句子")
    List<String> requiredSkills
) {}