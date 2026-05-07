# Actor-Critic 架构设计与实现

## 1. 问题背景：为什么 rewrite 接口是幻觉重灾区

原 `ResumeRewriteService` 是一个纯 `@AiService` 单步调用，System Prompt 如下：

> 你是一位顶级互联网大厂的技术猎头兼简历辅导专家。你的拿手好戏是**化腐朽为神奇**……

「化腐朽为神奇」正是幻觉的来源——模型被激励去**发明数据**。典型表现：

- 原文没有量化数据 → 模型自行填入「QPS 提升 200%」「接口耗时降低 40ms」
- 原文没有 K8s 经验 → 模型主动写入「熟练运用 Kubernetes 编排」
- 没有任何事实核查机制，生成结果的真实性完全依赖模型自律

相比之下，`/match/evaluate` 接口的核心痛点是**格式漂移**，已被 Facade 链路解决；rewrite 接口的核心痛点是**语义幻觉**，需要另一层机制兜底。

---

## 2. 设计方案：Actor-Critic 双智能体审查流

### 2.1 核心思路

引入**事实边界**：`originalProjectText`（候选人原始项目经历）是唯一可信的事实来源。Writer 只能在这个边界内扩写，任何超出原文的技术或数据均视为幻觉。

设计一个独立的 Critic，专门做「原文 vs 草稿」的交叉比对，不依赖 Writer 的自我约束，而是以工程逻辑实现外部审查。

### 2.2 组件分工

```
ResumeController
    └── RewriteCoordinatorService（普通 @Service，负责编排）
            ├── ResumeWriterAgent（@AiService）
            │     职责：根据 JD 和原始经历生成简历要点草稿
            └── FactCriticAgent（@AiService）
                  职责：对照原始经历，逐条核查草稿中的 bullet points
                        返回 CriticReport（通过/打回 + 具体原因）
```

### 2.3 数据流与编排逻辑

```
round=1:  writerAgent.rewrite(jd, original, "【首次生成】")
              → RewriteReport(bulletPoints, reasons)
          criticAgent.check(original, formatBulletPoints(draft))
              → CriticReport(approved=false, feedback="第3条捏造了K8s...")
round=2:  writerAgent.rewrite(jd, original, "【上一版审查未通过，请根据以下反馈修正】\n第3条捏造了K8s...")
              → RewriteReport(bulletPoints', reasons')
          criticAgent.check(original, formatBulletPoints(draft'))
              → CriticReport(approved=true, feedback="")
              → 提前返回，rounds=2
```

核心代码（`RewriteCoordinatorService.evaluate`）：

```java
String criticFeedback = "";
RewriteReport lastDraft = null;

for (int round = 0; round < MAX_ROUNDS; round++) {
    String feedbackPrompt = buildFeedbackPrompt(round, criticFeedback);
    RewriteReport draft = writerAgent.rewrite(jdText, originalProjectText, feedbackPrompt);
    lastDraft = draft;

    String bulletPoints = formatBulletPoints(draft);
    CriticReport criticism = criticAgent.check(originalProjectText, bulletPoints);

    if (criticism.approved()) {
        return new RewriteResult(draft, round + 1);  // 提前返回
    }
    criticFeedback = criticism.feedback();
}

return new RewriteResult(lastDraft, MAX_ROUNDS);  // 超出轮次，返回最后一版
```

---

## 3. 关键设计决策（Trade-offs）

### 决策一：context 策略 — 只传 feedback，不传历史草稿

**问题**：打回重写时，要不要把之前的「错误草稿」也作为历史 context 传给 Writer？

**选择**：**不传历史草稿全文，只传 Critic 的 feedback 文字。**

每轮 Writer 接收的 context：

```
jdText + originalProjectText + criticFeedback（精华摘要）
```

**原因**：
- 历史草稿全文 = 重复传入大量 token，每轮 context 线性增长，3 轮后可能超出模型窗口
- Critic 的 feedback 本身是对错误的精华摘要，已包含 Writer 修正所需的全部信息
- context 大小可控，token 消耗可预测

### 决策二：响应时间劣化的缓解方案

**问题**：3 轮循环 × 每轮 2 次 LLM 调用 = 最多 6 次调用，单个 HTTP 请求可能长达 40+ 秒，网关或前端会超时。

**当前方案（面试阶段）**：
- 在响应头加 `X-Review-Rounds: N`，告知调用方实际循环了几轮
- 文档中明确说明 trade-off：这是用响应时间换取输出质量的主动设计选择
- 配置 `timeout: 60s` 防止永久挂起

**未来方案（工程化）**：
- 改为异步：先返回 `202 Accepted + taskId`，前端轮询结果
- 与 StageOne 中暂缓的异步化方向一致，留待后续实现

### 决策三：超出最大轮次后的行为

`MAX_ROUNDS = 3`，超出后返回最后一版草稿（不抛异常）。

原因：即使 Critic 认为草稿仍有问题，最后一版也是经过多轮修正后「最接近合格」的版本，返回它比直接报错更有业务价值。调用方可以通过 `X-Review-Rounds` 响应头判断是否达到了最大轮次。

---

## 4. 新增文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `model/CriticReport.java` | Record | 审查结果：`approved`（boolean）+ `feedback`（String） |
| `service/ResumeWriterAgent.java` | `@AiService` 接口 | Writer：生成简历要点草稿，接受 `criticFeedback` 参数 |
| `service/FactCriticAgent.java` | `@AiService` 接口 | Critic：事实交叉比对，返回 `CriticReport` |
| `service/RewriteCoordinatorService.java` | `@Service` | 编排层：Actor-Critic 循环，暴露 `RewriteResult evaluate(...)` |

### `CriticReport` 设计

```java
public record CriticReport(
    @Description("是否通过审查。true 表示所有 bullet points 均能在原始项目经历中找到事实依据")
    boolean approved,
    @Description("仅在 approved=false 时填写：逐条列出有问题的 bullet point 及违规原因")
    String feedback
) {}
```

### `ResumeWriterAgent` System Prompt 与原版对比

| | 原 `ResumeRewriteService` | 新 `ResumeWriterAgent` |
|--|--------------------------|------------------------|
| **核心指令** | 「化腐朽为神奇」，鼓励激进包装 | 「只能使用原始经历中已有的技术、数据和成果」 |
| **数量数据** | 「尽可能推测或预留量化指标」 | 「若原文没有量化数据，使用定性描述，不得编造数值」 |
| **Critic 反馈** | 无 | 接受 `criticFeedback` 参数，首轮为「首次生成」提示 |

### `FactCriticAgent` 核心约束

```
核查规则：
1. 以"原始项目经历"为唯一事实边界
2. 逐条检查每个 bullet point 的技术栈、数据指标、工作成果
3. 合理的语言润色视为通过；原文没有的技术名词/捏造数据视为不通过
4. 不受语言表达优美程度的影响
```

---

## 5. 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `controller/ResumeController.java` | 注入 `RewriteCoordinatorService`（替换 `ResumeRewriteService`），返回 `ResponseEntity<RewriteReport>`，添加 `X-Review-Rounds` 响应头，增加 3000 字符长度上限校验 |
| `exception/GlobalExceptionHandler.java` | 新增 `IllegalArgumentException → HTTP 400` handler |
| `resources/application.yml` | 新增 `timeout: 60s` |
| `service/ResumeRewriteService.java` | 保留原接口（不删除），不再被 Controller 直接使用 |

---

## 6. 面试话术

### Q：你说「双智能体」，为什么不直接在一个 Agent 里约束好 prompt，还要专门加一个 Critic？

**A**：单靠 prompt 约束是「自我承诺」，没有外部校验机制。模型在面对「匹配高阶 JD」和「不得捏造」两个相互冲突的指令时，往往会优先满足生成质量目标。Critic 是用**工程手段**把「事实核查」从 prompt 指令提升为**独立的执行步骤**——它不关心 bullet point 写得好不好，只做二元判断：能/不能在原文找到依据。这个任务有明确的客观输入，不是让模型主观评判另一个模型，工程上更可靠。

### Q：Critic 每次核查的是什么，具体怎么做的？

**A**：Critic 接收两个输入：`originalProjectText`（候选人原始项目经历，事实边界）和 `bulletPoints`（Writer 输出的要点列表，格式化为 `1. xxx\n2. yyy`）。它的 System Prompt 明确禁止它评价「写得漂不漂亮」，只判断每条要点里的技术名词和量化数据能不能在原文找到。返回结构化的 `CriticReport`，包含 `approved`（boolean）和 `feedback`（具体违规原因）。

### Q：打回重写时，有没有把上一轮的草稿也传进去？为什么？

**A**：没有，只传 Critic 的 feedback 文字。主要考虑两点：第一是 context 爆炸——把历史草稿全文传入，3 轮后 context 会线性增长，可能超出模型窗口；第二是没有必要——Critic 的 feedback 已经是对错误的精华摘要，Writer 修正只需要知道「哪里错了、错在哪」，不需要看完整草稿。

### Q：响应时间怎么处理？最多 6 次 LLM 调用不会超时吗？

**A**：这是有意识的 trade-off，当前方案通过三个手段缓解：一是 3000 字符的输入上限，控制单次 LLM 调用时长；二是 `timeout: 60s` 防止单次 LLM 调用永久挂起；三是在响应头加 `X-Review-Rounds: N`，让调用方感知实际循环轮次。长期方案是改为异步（202 + taskId 轮询），但面试阶段同步实现已足够展示架构思路，而且把这个 trade-off 主动说出来本身就是工程成熟度的体现。
