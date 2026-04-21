Now I have a thorough understanding of the entire project. Let me give you a structured and honest analysis.

---

## 项目现状诊断：哪里扎实，哪里存在空白

先把现有的工程质量实事求是地摸一遍，这是讨论方向的基础。

### 已经做得很好的地方

**match 接口工程化最完整**，这条链路有完整的防御层次：

```49:81:src/main/java/com/jobagent/service/MatchEvaluationFacadeService.java
public MatchReport evaluate(String jdText, String resumeText) {
    // Step 1: 业务层裁剪，只保留核心片段，丢弃冗余内容
    String trimmedJd = TextTrimmer.trimJd(jdText);
    String trimmedResume = TextTrimmer.trimResume(resumeText);
    // Step 2: Token 预估，超过阈值主动拦截
    TokenEstimator.assertWithinLimit(trimmedJd, trimmedResume);
    // Step 3: 调用模型
    String raw = matchEvaluatorService.evaluate(trimmedJd, trimmedResume);
    // Step 4: 提取 JSON，Step 5: 字段名归一化，Step 6: Bean Validation
    ...
}
```

这条 Facade 链路（裁剪 → Token 预估 → 原始文本返回 → JSON 提取 → 字段归一化 → Bean 校验）是项目的技术含量核心，面试可以直接拿来讲。

---

### 当前明确存在的不足（按优先级）

**高优先级：**

| 问题 | 具体证据 | 影响 |
|------|----------|------|
| **jd/rewrite 缺乏防御** | `JdAnalyzeService` 和 `ResumeRewriteService` 是纯 `@AiService`，没有任何 Token 预估、JSON 提取、字段归一化 | 两个接口对超长输入和幻觉完全裸奔，与 match 的防御水平差距极大 |
| **`ResumeRewriteService` 有幻觉无兜底** | prompt 里的 `"你是顶级互联网大厂技术猎头"` 会主动激励模型"化腐朽为神奇"，但没有任何事实核查机制 | 模型会捏造量化数据（QPS、性能提升百分比）、虚构技术栈 |
| **IllegalArgumentException 被映射为 500** | 项目说明文档自己也指出："若未单独映射，会由全局异常处理统一转为 HTTP 500 风格响应" | 参数错误应该是 400，返回 500 是语义错误，对接前端或联调会很混乱 |

**中优先级：**

| 问题 | 说明 |
|------|------|
| 测试只覆盖工具类 | 3 个测试文件全是 `TextTrimmer`、`TokenEstimator`、`MatchEvaluationFacadeService` 的单元测试，没有 Controller 集成测试，没有 Mock LLM |
| 无超时/重试配置 | LangChain4j 调用 LLM 无任何 timeout 设置，一旦模型侧慢响应，HTTP 请求会永久挂起 |
| 无 OpenAPI 文档 | 面试演示时没有直观的接口文档，增加沟通成本 |

---

## 关于 StageOne 的双智能体方案：直接评估

StageOne 提出的方向是对的，但**做在哪个接口上**很关键。

### 建议：**不要先做 match 的双智能体，先做 rewrite 的双智能体**

原因如下：

**1. match 接口本身不需要双智能体**

`/match/evaluate` 的职责是"打分 + 列出技能"，它的输出是结构化 JSON，已经有 Bean Validation 做约束。match 的痛点不是幻觉，而是格式漂移——这个问题已经被现有的 Facade 链路解决了。给它再套一层 Critic Agent 审查"matchScore 是否公平"，审查者自己也是 LLM，主观性更大，没有客观基准。

**2. rewrite 接口才是幻觉的重灾区**

看现有的 `ResumeRewriteService`：

```12:22:src/main/java/com/jobagent/service/ResumeRewriteService.java
@SystemMessage("你是一位顶级互联网大厂的技术猎头兼简历辅导专家。你的拿手好戏是化腐朽为神奇...")
@UserMessage("""
        目标岗位 JD：
        {{jdText}}

        候选人原始项目经历：
        {{originalProjectText}}

        请严格根据 JD 的技术偏好，重写候选人的项目经历。
        """)
RewriteReport rewrite(...);
```

"化腐朽为神奇"正是幻觉的来源——模型被激励去发明数据。而 StageOne 里 P0 的核心痛点描述的就是这个："单步模型为了匹配高阶 JD，极易产生'发明数据（如乱写QPS）'和'捏造技能（如强行写精通K8s）'的幻觉"。

**3. rewrite 的双智能体有明确的交叉比对基准**

`FactCriticAgent` 的审查逻辑很清晰：**原始项目经历文本是事实边界**，Critic 只需要对照 `originalProjectText`，检查 Writer 生成的每一条 bullet point 是否能在原文中找到对应依据。这个任务有客观输入，不是让 LLM 主观评判另一个 LLM，工程上是可靠的。

---

## 建议的开发方向（具体且分阶段）

### 第一步：修补当前的明显短板（1-2天，面试前必做）

这些修改工作量小但价值高，面试时不能有明显的"我知道有问题但没修"：

1. **`GlobalExceptionHandler` 里把 `IllegalArgumentException` 映射到 400** 而不是 500
2. **给 rewrite 接口加输入校验**：在 `ResumeController` 里对 `originalProjectText` 做长度上限限制，防止无边界输入
3. **给 `@AiService` 调用加 timeout**（在 `application.yml` 里配置 `langchain4j.open-ai.chat-model.timeout`）

### 第二步：实现 rewrite 双智能体审查流（核心特色，1周）

这是面试最有含金量的部分，具体架构：

```
ResumeController
    └── RewriteCoordinatorService（新建，Java 普通 Bean，负责编排）
            ├── ResumeWriterAgent（改造现有 ResumeRewriteService）
            │     职责：根据 JD 重写简历，返回 RewriteReport
            └── FactCriticAgent（新建 @AiService 接口）
                  职责：对照 originalProjectText，
                        逐条检查 RewriteReport 中的 bullet points，
                        返回 CriticReport（通过/打回+具体理由）
```

`RewriteCoordinatorService` 的核心逻辑（伪代码）：

```java
for (int round = 0; round < MAX_ROUNDS; round++) {
    RewriteReport draft = writerAgent.rewrite(jdText, originalProjectText);
    CriticReport criticism = criticAgent.check(originalProjectText, draft);
    if (criticism.approved()) {
        return draft;  // 通过，直接返回
    }
    // 打回，把 Critic 的意见传给下一轮 Writer
    feedbackForNextRound = criticism.feedback();
}
return lastDraft; // 超过最大轮次，返回最后一版
```

### 第三步：解决 StageOne 里明确提出的两个 Trade-off（设计决策必须先想清楚）

StageOne 文档里已经非常诚实地列出了两个待决问题：

**问题1：上下文爆炸**——打回重写时要不要把历史草稿传给模型？

建议：**不传历史草稿全文，只传 Critic 的 feedback 文字**。每轮 Writer 接收的 context 是 `(jdText + originalProjectText + criticFeedback)`，而不是把之前的草稿也塞进去。这样 context 大小可控，且 Critic 的 feedback 本身就是精华摘要。

**问题2：响应时间劣化**——3轮循环可能 40 秒超时

**面试阶段的缓解方案**是：在响应头里加一个 `X-Review-Rounds: 2` 字段告知实际循环了几轮，并在文档里写清楚这是 trade-off 的设计决策。**未来的工程方案**是改成异步（先返回 `202 Accepted + taskId`，轮询结果），但 StageOne 文档已经说了暂缓异步，所以面试期间直接在技术说明里把这个 trade-off 摆出来讲即可——这本身就是工程成熟度的体现。

---

## 总结

**开发优先级建议：**

1. **立即**：修掉 `IllegalArgumentException → 400`，给 rewrite 加输入上限，加 LLM 超时配置
2. **核心**：在 **rewrite 接口**（不是 match）上实现 Actor-Critic 双智能体流，配合 `FactCriticAgent` 的事实核查
3. **加分**：实现 P2 的 Eval-Driven（构建小型黄金测试集，跑一次单步 vs 双智能体的对比数据），这是面试中最有说服力的技术证明

match 接口的双智能体方案目前没有明确的业务场景支撑，而且它的主要痛点（格式漂移）已经被现有 Facade 解决了。把资源聚焦在 rewrite 的幻觉问题上，工程价值和面试故事都会更清晰。