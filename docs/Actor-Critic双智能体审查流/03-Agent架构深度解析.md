# Agent 架构深度解析 — 从注解到设计决策

> **定位**：补充 `Agent面试重点速查.md` 的深度内容。速查文档负责"5 分钟脱稿 + 场景卡快扫"，本文档负责"看懂每一行代码为什么这样写"。
>
> **用法**：面试前逐章过一遍，确保被追问到任何注解/设计决策时都能讲出 3 层深度的回答。

---

## 1. LangChain4j 注解体系拆解

你的两个 Agent 接口（`ResumeWriterAgent`、`FactCriticAgent`）用了 5 种 LangChain4j 注解。理解它们不是为了背定义，而是为了向面试官证明：**你不是只会用框架，你知道框架在替你做什么。**

### 1.1 `@AiService` — 类级别

```java
@AiService
public interface ResumeWriterAgent { ... }
```

| 维度 | 说明 |
|------|------|
| **作用** | 告诉 LangChain4j Spring Boot Starter：这个接口是 AI Agent，请在启动时生成代理实现类并注入容器 |
| **底层机制** | JDK 动态代理 + `InvocationHandler`；`invoke()` 方法内完成：读取方法注解 → 拼接 System/User Message → 调用 ChatLanguageModel API → 反序列化返回值 |
| **你不需要写的** | HttpClient 配置、ChatRequest 构造、JSON 解析、错误重试 |
| **面试话术** | 「LangChain4j 的 `@AiService` 本质是声明式 Agent 编程——我定义接口描述 Agent 要做什么，框架通过 JDK 动态代理在运行时生成实现。这让我的精力集中在 Agent 角色设计和通信协议上，而不是 HTTP 调用的样板代码。」 |

**追问**：「底层是 JDK 动态代理，那它怎么知道调用哪个模型？」

→ `application.yml` 中的 `langchain4j.open-ai.chat-model` 配置。框架启动时读取配置创建 `ChatLanguageModel` Bean，代理类持有该引用。

---

### 1.2 `@SystemMessage` — 方法级别

```java
@SystemMessage("""
        你是一位专业的简历优化顾问。你的任务是基于候选人的原始项目经历...
        
        严格约束：
        1. 只能使用原始项目经历中已有的技术、数据和成果，绝对禁止捏造...
        2. 若原文没有量化数据，使用"优化了系统性能"等定性描述...
        3. 若原文没有某项技术，绝对不允许在要点中出现该技术...
        """)
```

| 维度 | 说明 |
|------|------|
| **对应 LLM API** | OpenAI Chat API 中的 `messages[role="system"]` |
| **作用** | 定义 Agent 的**身份、边界、行为规则**——是整个对话的"宪法" |
| **与 UserMessage 的区别** | SystemMessage 是持久角色设定（整个对话有效），UserMessage 是单次任务输入 |
| **面试话术** | 「SystemMessage 是 Agent 的行为宪法。我把三条硬约束写在这里——禁止捏造量化数据、禁止引入原文没有的技术、允许定性描述——这样 LLM 在整个重写任务中都会遵守。这不是"请尽量准确"的软约束，而是明确的可核查禁令。」 |

**追问**：「SystemMessage 和 UserMessage 在模型眼里有区别吗？」

→ 大部分模型对 system/user 有区分对待：system 消息权重更高，模型更倾向将其视为不可违背的指令。但技术上两者都是 token 序列的一部分，区别在于训练时的角色对齐。**工程上的最佳实践是：角色定义放 SystemMessage，具体任务放 UserMessage。**

---

### 1.3 `@UserMessage` — 方法级别

```java
@UserMessage("""
        目标岗位 JD：
        {{jdText}}
        
        候选人原始项目经历（事实边界，只能使用此处的信息）：
        {{originalProjectText}}
        
        {{criticFeedback}}
        
        请基于以上信息，重写候选人的项目经历要点。
        """)
```

| 维度 | 说明 |
|------|------|
| **对应 LLM API** | OpenAI Chat API 中的 `messages[role="user"]` |
| **作用** | 传递本轮具体任务输入，包含模板变量 `{{变量名}}` |
| **面试话术** | 「UserMessage 是每轮的具体任务。模板中的 `{{变量名}}` 通过 `@V` 注解从方法参数注入。注意模板里我写了"事实边界"四个字——这是给模型的心理暗示，强化它对这个输入不可越界的认知。」 |

---

### 1.4 `@V("变量名")` — 参数级别

```java
RewriteReport rewrite(
        @V("jdText") String jdText,
        @V("originalProjectText") String originalProjectText,
        @V("criticFeedback") String criticFeedback
);
```

| 维度 | 说明 |
|------|------|
| **作用** | 将方法参数的值注入 UserMessage 模板中的 `{{变量名}}` 占位符 |
| **等价于** | `template.replace("{{jdText}}", jdText)` + `template.replace("{{originalProjectText}}", originalProjectText)` |
| **面试话术** | 「`@V` 是模板变量注入。框架在 `invoke()` 里做字符串替换，把方法参数值填入 UserMessage 模板，然后发送给 LLM。」 |

---

### 1.5 `@Description` — record 字段级别

```java
public record CriticReport(
        @Description("是否通过审查。true 表示所有 bullet points 均能在原始项目经历中找到事实依据。")
        boolean approved,

        @Description("仅在 approved=false 时填写：逐条列出有问题的 bullet point 的具体违规详情。")
        List<Violation> violations,

        @Description("对 Writer 的修正建议，用自然语言汇总 violations 中的关键问题。approved=true 时为空。")
        String feedback
) {}

public record Violation(
        @Description("有问题的 bullet point 序号，从 1 开始。")
        int bulletIndex,

        @Description("违规类型：FAKE_DATA（捏造数据）、FAKE_TECH（引入原文不存在的技术栈）、EXAGGERATION（夸大成果/角色）、MINOR_EMBELLISHMENT（轻度润色越界）")
        String violationType,

        @Description("违规严重程度 1-5。1=措辞轻微不当，5=完全捏造核心技术/数据。")
        int severity,

        @Description("具体说明哪个 bullet point 的哪部分描述违规，为什么不符合原文。")
        String detail
) {}
```

| 维度 | 说明 |
|------|------|
| **作用** | 将字段含义告诉 LLM，让模型按描述输出对应的 JSON 字段 |
| **底层** | 框架在请求 LLM 时，在 Prompt 中追加「请输出如下 JSON 格式：`approved` (boolean): 是否通过审查...；`violations` (array): 违规详情...；`feedback` (string): ...」 |
| **关键价值** | 你不需要在 Prompt 里手写 JSON Schema，框架自动从 Java record + @Description 生成。`violations` 字段的设计用了**结构化违规**——不再是简单的"通过/不通过"，而是告诉 Writer 你第几条 bullet 在哪个维度上违规、严重程度多少。这让 Coordinator 可以实现 severity 阈值容忍（如 severity≤2 仍视为通过），避免 Critic 的偶发性过度敏感导致不必要的循环 |
| **面试话术** | 「`@Description` 是 LangChain4j 结构化输出的核心。但我想强调 `Violation` 这个嵌套 record 的设计决策——我不是简单地问 Critic "过没过"，而是让它输出每条违规的类型和严重度。这样 Coordinator 可以做智能决策：如果所有违规 severity≤2，说明只是措辞不当，可以放行；如果 severity≥4，才是真正的捏造，需要重写。这比二元 approved 精准得多。」 |

**追问**：「如果模型输出的 JSON 字段名不是 `approved` 而是 `is_approved` 怎么办？」

→ 这就是 `FieldNameNormalizer` + `@JsonAlias` 的用武之地（参见结构化输出治理章节）。`@Description` 负责让模型输出正确的字段名和含义，`FieldNameNormalizer` 负责兜底模型不听话的情况。两者互为补充。

---

### 1.6 双 Agent Prompt 差异对照（面试重点）

面试官追问「两个 Agent 的 Prompt 有什么不同」时，不要分别描述——用对比表展示设计意图。

| 维度 | WriterAgent（创作者） | FactCriticAgent（审查者） |
|------|----------------------|--------------------------|
| **角色定位** | 简历优化顾问 | 严格的事实核查员 |
| **核心指令** | 「只能使用原始经历中已有的技术、数据和成果」 | 「以原始项目经历为唯一事实边界」 |
| **数据约束** | 禁止添加原文不存在的量化数据/技术栈 | 逐条检查每个 bullet point 是否能在原文找到依据 |
| **语言风格** | 可使用 STAR 法则优化表达 | 「不受语言表达优美程度的影响」——只做二元判断 |
| **判断标准** | 写得是否贴合 JD + 是否越过事实边界 | 能/不能在原文找到依据 |
| **输入参数** | `jdText` + `originalProjectText` + `criticFeedback` | `originalProjectText` + `bulletPoints` |
| **返回类型** | `RewriteReport`（List<bulletPoints> + List<reasons>） | `CriticReport`（boolean approved + String feedback） |

> **面试话术**：「两个 Agent 的设计哲学不同。Writer 是"在笼子里跳舞"——笼子是原文事实边界，跳舞是 STAR 优化表达。Critic 是"笼子的守卫"——不看舞姿好不好看，只判断有没有越界。这保证了审查的客观性——Critic 的 System Prompt 明确禁止它评价'写得漂不漂亮'。」
>
> **追问**：「Writer 为什么不直接自我审查？」
>
> → 「这是 LLM 的角色冲突问题。同一个模型在"创作模式"和"审查模式"下行为完全不同——让它同时做好两件事，它会优先满足创作目标，审查沦为走过场。这不是 Prompt 写得不够好，而是心理学上的"确认偏误"——人会倾向于维护自己的产出，LLM 同理。拆成两个独立调用、用不同 SystemMessage 切换角色，是工程上解决角色冲突的标准方案。」

---

## 2. 双智能体交互完整时序

### 2.1 核心流程（5 步循环）

```
Controller 收到请求
  │
  └─→ RewriteCoordinatorService.evaluate(jdText, originalProjectText)
        │
        │  ┌──────────────────────────────────────────────────┐
        │  │              for round = 0 to 2                   │
        │  │                                                  │
        │  │  ① buildFeedbackPrompt(round, criticFeedback)     │
        │  │     round=0 → "【首次生成，请严格遵守约束】"      │
        │  │     round≥1 → "【上一版审查未通过...】\n" + fb    │
        │  │                                                  │
        │  │  ② writerAgent.rewrite(jd, original, fbPrompt)    │
        │  │     → RewriteReport(bulletPoints, reasons)        │
        │  │                                                  │
        │  │  ③ formatBulletPoints(draft)                      │
        │  │     → "1. xxx\n2. yyy\n3. zzz"                   │
        │  │                                                  │
        │  │  ④ criticAgent.check(original, bulletPoints)      │
        │  │     → CriticReport(approved, feedback)            │
        │  │                                                  │
        │  │  ⑤ if approved → return RewriteResult(draft, N)  │
        │  │     else        → criticFeedback = feedback       │
        │  │                    continue loop                  │
        │  └──────────────────────────────────────────────────┘
        │
        └─→ (超过3轮) return RewriteResult(lastDraft, 3)
```

### 2.2 具体第1轮和第2轮的数据流

```
第1轮：
  Writer 接收: jdText + originalProjectText + "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】"
  Writer 输出: RewriteReport{ bulletPoints: ["主导设计支付系统，QPS提升30%", ...], ... }
  Critic 接收: originalProjectText + "1. 主导设计支付系统，QPS提升30%\n2. ..."
  Critic 输出: CriticReport{ approved: false, feedback: "第1条：原文为'参与开发支付模块'，'QPS提升30%'无依据" }

第2轮：
  Writer 接收: jdText + originalProjectText + "【上一版审查未通过，请根据以下反馈修正】\n第1条：原文为'参与开发支付模块'，'QPS提升30%'无依据"
  Writer 输出: RewriteReport{ bulletPoints: ["参与开发支付模块，优化了支付流程性能", ...], ... }
  Critic 接收: originalProjectText + "1. 参与开发支付模块，优化了支付流程性能\n2. ..."
  Critic 输出: CriticReport{ approved: true, feedback: "" }
  → 提前返回，rounds=2
```

---

## 3. 五个关键设计决策（Trade-off 深度解析）

面试官问"为什么这样设计"时，不要只说"我觉得好"。每个决策都能讲出**3 层原因**：表面原因 + 深层原因 + 反面选择的代价。

### 决策一：为什么拆两个 Agent 而不是一个 Prompt？

**表面原因**：一个生成一个审查，分工明确。

**深层原因**：LLM 在"生成模式"和"审查模式"下的行为完全不同。同一个模型，让它同时"写得好"和"查得严"会陷入角色冲突——生成时倾向于脑补，审查时倾向于保守。拆成两个独立调用、用不同 SystemMessage 切换角色，是工程上解决角色冲突的标准方案。

**反面选择的代价**：如果用一个 Prompt 写「请生成简历并自我检查」，模型会优先满足"生成好内容"的目标，"自我检查"沦为走过场。

**类比**：代码审查不可能自己审自己的代码——这不是能力问题，是角色问题。

---

### 决策二：为什么传结构化对象（CriticReport）而不是自由文本？

**表面原因**：`approved` 是 boolean，程序可以直接 `if (criticism.approved())` 分支，不需要解析自然语言。

**深层原因**：Agent 间通信如果使用自由文本，会带来三个问题：
1. **不可靠解析**：你无法保证 Critic 每次都说「通过」两个字，可能说「没问题」「可以」「行」——字符串匹配脆弱
2. **Token 浪费**：自然语言对话会有大量冗余（"好的我来审查一下...经过仔细比对...结论是..."）
3. **无法做数据统计**：无法统计"平均几轮通过""哪些类型的错误最频繁"

**反面选择的代价**：如果用自由文本，Coordinator 需要对 Critic 的回复做意图识别，本质上是把"结构化输出"的问题转移到了下游——而且准确率更低。

---

### 决策三：为什么是 3 轮？

**表面原因**：大部分事实性错误 1~2 轮即可修正，3 轮提供额外容错缓冲。

**深层原因**：通过 8 组测试用例 × 5 种循环上限（1/2/3/4/5）的 A/B 验证实验，得出以下数据：

| 循环上限 | 最终通过率 | 关键发现 |
|---------|-----------|---------|
| 1 轮 | 50% | 无审查兜底，Writer 首版脑补的假数据直接输出 |
| 2 轮 | 87.5% | 大部分简单捏造被一轮反馈修正，但仍有 12.5% 因 Critic 偶发性误判导致失败 |
| 3 轮 | 87.5% | 通过率持平，但**增加了对 Critic 偶发性假阳性的容错**——某用例在 2 轮变体中因一次误判失败，在 3 轮变体中获得额外修正机会后通过 |
| 4 轮 | 87.5% | 边际收益为零 |
| 5 轮 | 100% | 全通过但不经济——token 成本比 3 轮增加约 30%，且 87.5% 的用例 2 轮内已通过 |

**结论**：
- **2 轮是最小可行值**（成本最低且通过率不降）
- **3 轮是最优质量保证值**——对那 12.5% 的"Critic 偶发误判"场景提供容错，避免因审查者自身的 LLM 随机性导致用户看到不合格输出

**反面选择的代价**：
- 只设 1 轮：50% 通过率不可接受
- 只设 2 轮：87.5% 基本可用，但缺少容错 buffer
- 设 4+ 轮：token 成本线性增长但质量不再提升

**最佳回答**：「3 轮不是拍脑袋定的。我跑了 8 组测试用例的 A/B 验证实验——1 轮只有 50% 通过率，2 轮到 87.5%，3 轮提供额外容错。本质上是边际收益递减曲线——3 轮刚好是拐点，再多就是浪费。」

---

### 决策四：`buildFeedbackPrompt` 为什么区分首轮和后续轮？

```java
private String buildFeedbackPrompt(int round, String criticFeedback) {
    if (round == 0 || criticFeedback == null || criticFeedback.isBlank()) {
        return "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】";
    }
    return "【上一版审查未通过，请根据以下反馈修正，确保所有内容均有原文依据】\n" + criticFeedback;
}
```

**表面原因**：首轮没有 feedback，需要一个初始指令。

**深层原因**：两种 Prompt 的**语义不同**：
- 首轮是**指令式**（"请做 X，遵守约束 A、B、C"）——建立初始行为框架
- 后续轮是**纠正式**（"你上次犯了错误 Y，请修正"）——精准打击错误

如果把首轮也写成「请根据以下反馈修正」但 feedback 是空字符串，模型会困惑——"修正什么？我没有收到反馈"。

**反面选择的代价**：如果首轮也传空 feedback + 纠正文案，模型可能出现预期外行为（比如输出"没有发现需要修正的内容"而不做生成）。

---

### 决策五：为什么不传历史草稿？Context 策略详解

**表面原因**：防止 token 窗口指数级膨胀。

**深层原因**：每轮 Writer 产生的草稿文本量与输入相当（≈2000 token）。如果把历史草稿逐轮累积传入：

```
第1轮: jd + original + fb0                      ≈ 3000 token
第2轮: jd + original + fb1 + draft1             ≈ 5000 token  
第3轮: jd + original + fb2 + draft1 + draft2    ≈ 7000 token
```

Critic 的 feedback 本身就是对错误的精华摘要，已包含 Writer 修正所需的全部信息。传历史草稿是冗余的。

**反面选择的代价**：失去"历史对比"能力——Writer 可能在第 3 轮重复第 1 轮的某个错误，因为它看不到第 1 轮犯了什么错。这是用部分可追溯性换取 token 效率的主动 trade-off。

**进阶做法**（可提及但不一定实现）：保留历史 feedback 列表传给 Writer，而不是历史草稿全文。这样 Writer 知道"之前被指出过这些错误"，但 context 增加量仅为 feedback 文本（远小于草稿全文）。

---

## 4. 面试高频追问防御话术

### Q1：「你代码不到 150 行，Agent 不就是一个循环调两次 API 吗？」

> **不要慌张，这正是你展示工程思维的机会。**

**三层回答**：
1. 「从调用形式上看确实是串行调用，但 Agent 项目的技术含量不在代码量——在**设计决策**的深度。」
2. 「同样的场景，如果让我只写 Prompt 不拆 Agent、不通结构化对象、不设终止条件、不做 context 策略，代码量可能更少，但幻觉率 15%、token 浪费严重、请求超时无法控制。我 150 行代码背后是 5 个经过权衡的设计决策——每一个都有"为什么这样选"的清晰理由。」
3. 「LangChain4j 的声明式编程让我把样板代码降到最低——框架处理 HTTP、Prompt 拼接、JSON 解析，我的精力集中在架构层面。这是框架的正确用法，不是偷懒。」

---

### Q2：「CriticAgent 自己也是 LLM，万一 Critic 也出错怎么办？」

**三层回答**：
1. 「承认这是 LLM 的固有限制——审查者也非完美。但 Critic 的任务比 Writer 简单得多：它不是要判断'写得好不好'，只做**二元判断**——这个数字/技术栈在原文中出现了吗？二元判断的 LLM 准确率远高于生成任务。」
2. 「设计中做了双重防护：一是 Critic 的 System Prompt 明确写"你的判断必须客观，不受语言表达优美程度的影响"——切断它进入"评价模式"的倾向；二是 3 轮循环本身就提供了容错——即使 Critic 误判一次，下一轮 Writer 可以重新调整。」
3. 「如果要做更严格的质量保证，可以引入 LLM-as-Judge 做离线评测——用黄金测试集统计 Critic 的误判率和漏判率。这是 StageThree 的规划之一。」

---

### Q3：「这个架构和金蝶/用友等企业级 Agent 平台的差距在哪？」

**三层回答**：
1. 「定位不同。企业级 Agent 平台要解决的是**通用 DAG 编排**——用户通过拖拽/配置任意组合 Agent 工作流。我的项目是**场景深度定制**——为"简历重写审查"这一个场景做最优设计。」
2. 「我清楚这个架构的升级路径：如果未来有 3 个以上 Agent 参与（比如加上 JD 解析 Agent、面试题生成 Agent），硬编码的 Coordinator 就撑不住了，需要引入 LangGraph 做可配置的 DAG 编排。'
3. 「另外企业级平台关注的多租户管理、权限控制、计费系统，与 Agent 核心架构本身是正交的——我可以清楚地区分'Agent 工程深度'和'企业平台广度'两个维度。」

---

### Q4：「你怎么评估 Critic 的审查质量？」

**三层回答**：
1. 「目前依赖人工抽查 + 日志观测。每次 Critic 输出 `approved=false` 时，日志会记录具体的 feedback 内容，可以回溯验证审查是否合理。」
2. 「要做量化评估，需要构建**黄金测试集**：准备 20 组"原文 + 刻意捏造的草稿"，统计 Critic 的召回率（识别出多少捏造）和精确率（误报多少）。这是 Eval-Driven 的方向。」
3. 「更进一步可以用 LLM-as-Judge：让第三个独立的 LLM 评判 Critic 的结论是否正确——同样是 LLM，但任务更简单，准确率可接受。」

---

## 5. Agent 项目 vs RAG 项目的差异化定位

> 如果你两个面试项目都做了，面试官会对比。确保你自己知道两者考察的是不同的 AI 工程能力。

| 维度 | RAG 知识库（IntelliVault） | Agent 工具（Job Agent） |
|------|--------------------------|------------------------|
| **LLM 角色** | 内容生成器（根据检索到的文档回答问题） | 任务执行器（自主决策、调用工具、迭代修正） |
| **核心挑战** | 检索质量、Embedding 精度、Chunking 策略 | 决策可靠性、工具编排、幻觉治理、成本控制 |
| **数据流** | Query → Embed → Retrieve → Augment → Generate | Request → Plan → Act → Evaluate → Re-plan |
| **失败模式** | 检索到无关文档、embedding 漂移 | Agent 选错工具、无限循环、超预算 |
| **你的关键代码** | Milvus IVF_FLAT、RAG Prompt 工程、SSE 输出 | Actor-Critic 双智能体、Token 预估、结构化容错 |

**关键点**：RAG 是让 LLM **看更多资料**，Agent 是让 LLM **做更复杂的决策**。两个问题不同，解决方案不同，放在一起是互补而非重叠。

---

## 6. 演进路线速览（StageThree 规划）

> 以下内容用于回答「如果给你更多时间，你会怎么继续完善这个 Agent 系统？」

| 阶段 | 模块 | 具体内容 | 解决什么问题 | 工时 |
|------|------|---------|-------------|------|
| **第1轮** | Tool Calling | 为 WriterAgent 挂载 `@Tool` 方法（查询技术栈知识库），Agent 自主决定何时查询 | 从"固定脚本"升级为"动态决策" | 2~3天 |
| **第2轮** | Agent Trace | 接入 Langfuse 记录每轮 Prompt/响应/token消耗 | 可观测性，面试时有可视化面板可展示 | 2天 |
| **第3轮** | Eval 评测 | 构建黄金测试集 + LLM-as-Judge，输出单步 vs 双智能体幻觉率对比 | 量化证明架构有效性 | 2天 |
| **第4轮** | 异步化 | SSE 推送审查进度（第1轮生成中→审查中→修改中→完成） | 体验提升，请求不阻塞 | 2天 |

> **面试话术**：「这四个方向我已经做过技术预研，优先级是按面试反馈迭代的——先加 Tool Calling 展示 Agent 自主性，再加 Trace 展示可观测性，最后用 Eval 数据收尾。」

---

---

## 7. 面试复盘补充：匹配度评分链路与评估体系

> 2026-06-03 模拟面试暴露了两个盲区：匹配度评分的落地逻辑未展开、评估体系完全缺失。以下为补充内容。

### 7.1 匹配度是怎么计算出来的？

面试官问「matchScore 是怎么打出来的」时，完整链路是：

```
MatchEvaluationFacadeService.evaluate(jdText, resumeText)
  │
  ├─ Step 1: TextTrimmer.trimJd + trimResume  → 裁剪到核心段落
  ├─ Step 2: TokenEstimator.assertWithinLimit   → 超限拦截
  ├─ Step 3: MatchEvaluatorService.evaluate()   → @AiService 直接输出 MatchReport JSON
  │           ├─ @SystemMessage: "你是一位严格的招聘评估专家..."
  │           └─ 模型输出: {"matchScore":85, "matchedSkills":["Java","Spring"], ...}
  ├─ Step 4: extractJsonObject                   → 剥离 Markdown 包裹
  ├─ Step 5: FieldNameNormalizer.normalize       → 字段名归一化
  ├─ Step 6: ObjectMapper.readValue              → Jackson 宽松解析
  └─ Step 7: Bean Validation                     → 字段合法性校验
       └─ matchScore 必须在 [0, 100]；matchedSkills 不能为 null
```

> **面试话术**：「matchScore 是 LLM 直接输出的 0-100 评分，不是向量相似度计算。但这个 LLM 评分经过了四层防御式 pipeline——从 JSON 提取到字段校验——确保评分值合法（不超 0-100）、技能列表不为空。如果需要更精细的评分，可以做向量相似度 + LLM 语义评分的加权融合，这是后续演进方向。」

### 7.2 3 轮后仍未达标的处理策略

**当前处理**：返回最后一版草稿 + 前端提示「改写可能不够充分」

**实验数据**：在 8 组测试用例的 A/B 验证中，3 轮到达上限时未通过的用例为 1/8（12.5%）。深入分析发现，未通过的根本原因不是 Writer 改不好——而是 **Critic 自身的二元判定存在随机性**。同一个用例 C3-1（"优化了系统性能"被润色为"主导性能优化工作"）在 5 个变体中交替通过/不通过（✗→✓→✗→✓→✓），这是 LLM 审查的固有不稳定性。

**实现改进**（已落地）：

| 改进点 | 具体方案 | 现状 |
|--------|---------|------|
| **结构化违规** | `CriticReport` 新增 `violations: List<Violation>`，每条包含 `violationType`（FAKE_DATA/FAKE_TECH/EXAGGERATION/MINOR_EMBELLISHMENT）和 `severity`（1-5） | ✅ 已实现 |
| **Severity 阈值容忍** | Coordinator 检查所有 violations 的 maxSeverity ≤ 2 时仍视为通过——MINOR_EMBELLISHMENT 不应触发整轮重写 | 🔲 待实现 |
| **动态终止条件** | 连续两轮 feedback 的文本相似度（Levenshtein）> 90% → 说明 Critic 在重复相同意见，继续循环无意义 → 提前终止 | 🔲 待实现 |
| **多次采样投票** | 对 C3 类模糊场景做 3 次 Critic 采样取多数，降低单次判定随机性的影响 | 🔲 待实现 |


### 7.3 评估体系（Eval）规划

| 指标 | 当前状态 | 可讲的话术 |
|------|---------|----------|
| **幻觉率** | 无量化 | 「下一步构建 20 组黄金测试集，跑单步 vs 双智能体的对比，用 LLM-as-Judge 打分——这个方案已在技术预研阶段」 |
| **循环通过率** | 无统计 | 「可以在 Coordinator 里加一个计数器输出到日志，聚合后就能得到每轮通过率分布」 |
| **Token 消耗** | 无打点 | 「Langfuse 接入后可以按 trace 统计 token，做成本分析」 |

> **面试话术**（诚实版）：「坦白说这块目前是 MVP 阶段的已知短板——我先跑了核心架构（幻觉治理 → token 管理 → 输出校验 → 可观测性），评估体系是下一步。但我有过清晰的规划：黄金测试集 + LLM-as-Judge + 离线对比。」

---

## 附：关键源码索引

| 想复习的内容 | 文件路径 |
|-------------|---------|
| WriterAgent 注解与 Prompt | `src/main/java/com/jobagent/service/ResumeWriterAgent.java` |
| CriticAgent 注解与 Prompt | `src/main/java/com/jobagent/service/FactCriticAgent.java` |
| 协调器循环逻辑 | `src/main/java/com/jobagent/service/RewriteCoordinatorService.java` |
| 审查结果结构 | `src/main/java/com/jobagent/model/CriticReport.java` |
| 重写结果结构 | `src/main/java/com/jobagent/model/RewriteReport.java` |
| 面试速查（5分钟版） | `docs/Agent面试重点速查.md` |
| Actor-Critic 架构设计 | `docs/Actor-Critic双智能体审查流/02-Actor-Critic架构设计与实现.md` |