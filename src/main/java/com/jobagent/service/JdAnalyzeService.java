package com.jobagent.service;

import com.jobagent.model.JdInfo;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

// 这个注解告诉 Spring：请帮我实现这个接口，并把它变成一个 Bean
@AiService
public interface JdAnalyzeService {

    @SystemMessage({
            "你是一个资深的 HR 和技术架构师。",
            "你的任务是精准地解析用户发来的岗位描述 (JD)，并提取出最核心的信息。"
    })
    @UserMessage("请解析以下岗位描述：\n\n{{jdText}}")
    JdInfo analyzeJd(String jdText);
    // 魔法就在这里：你告诉它入参是 String，返回是 JdInfo，剩下的框架全包了！
}