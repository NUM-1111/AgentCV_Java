# 面试难点合集：Critic 审查质量保障

> **定位**：应对面试官追问「你怎么保证 Critic 的审查质量？」的攻防手册。
> **用法**：面试前通读一遍，确保任何角度追问都能接住。

---

## 难点全景

| 难点 | 面试官会怎么问 | 应对核心 |
|------|-------------|---------|
| 1. 「Critic 自己也是 LLM，怎么能保证它审得准？」 | 质疑可靠性 | "从二元判断升级到结构化违规 + 精确数值审查规则" |
| 2. 「精确数据（QPS/百分比）怎么确保不篡改？」 | 追问细节 | "Prompt 级数值逐字比对 + dirty draft 验证 100% 召回率" |
| 3. 「模糊描述（"优化了性能"）被改成 STAR 句式，Critic 会不会误判？」 | 边界场景 | "定性描述容忍规则 + severity 阈值区分捏造和润色" |
| 4. 「你怎么量化验证这些保障是有效的？」 | 要数据 | "3 类 dirty draft × 3 次采样 = 100% 召回率 + 4 场景 E2E 测试" |

---

## 难点一：Critic 作为 LLM，审查可靠性如何保障？

### 面试官问法
> 「CriticAgent 本质上也是调 LLM，你怎么保证它的审查结果准确？万一把对的判错了、把错的放过了呢？」

### 回答拆解（三层递进）

**第一层：任务不对称——审查比生成简单**

我的 Critic 任务从设计上就比 Writer 简单得多。Writer 要做的是"在事实边界内进行创意性语言生成"——这是个开放域问题。Critic 做的是"这个数字/技术栈在原文中出现过吗"——这是个封闭域事实匹配问题。LLM 在封闭域任务上的准确率天然高于开放域，这是认知科学上的不对称性。

**第二层：不是二元判断，是分级判定**

早期版本我只用了 `approved: boolean`，验证实验发现边界场景下有约 40% 的随机翻转率。所以我把审查输出改成**结构化分级**：

```
CriticReport {
    approved: boolean;                    // 快速终止标记
    violations: [{
        bulletIndex: int;                 // 哪条 bullet 有问题
        violationType: "FAKE_DATA" |      // 捏造数据
                       "FAKE_TECH" |      // 引入不存在的技术栈
                       "EXAGGERATION" |   // 夸大成果/角色
                       "MINOR_EMBELLISHMENT"; // 轻度润色越界
        severity: 1-5;                    // 严重程度
        detail: "原文中QPS为2000，bullet写了50000" // 引用原文证据
    }];
    feedback: string;                     // 修正建议汇总
}
```

去每个Violations的severity的最大值 -- maxSeverity
这样做的好处是 Coordinator 可以做智能决策：
- `maxSeverity ≤ 2`：仅 MINOR_EMBELLISHMENT → 直接通过，不需要重写
- `maxSeverity ≥ 4`：FAKE_DATA/FAKE_TECH → 必须重写
- `severity = 3`：EXAGGERATION → 根据场景判断

**（已落地，2026-06-07）**

Coordinator 中具体实现（`RewriteCoordinatorService.evaluate()` 第 110-140 行）：
- `maxSeverity ≤ 2` → `log.warn("MINOR_EMBELLISHMENT only, treating as passed")` + 直接 return，避免不必要循环
- `maxSeverity ≥ 3` → `log.info("entering rewrite loop")` + `criticFeedback = criticism.feedback()` + 进入下一轮
- `maxSeverity == 0` + `approved=true` → 快速通过（无违规）

循环次数验证实验中已观测到运行时效果：
- MAX_ROUNDS=3 变体 C2-1：round=2 时 `maxSeverity=2` → WARN 日志 → 视为通过，避免不必要循环
- 多组用例中 `maxSeverity=3/4/5` → INFO 日志 → 正确触发重写

```java
// 落地代码（RewriteCoordinatorService.evaluate()）：
int maxSeverity = criticism.violations() != null && !criticism.violations().isEmpty()
        ? criticism.violations().stream()
            .mapToInt(Violation::severity)
            .max()
            .orElse(0)
        : 0;

if (criticism.approved()) {
    return new RewriteResult(draft, round + 1);
}

if (maxSeverity <= 2) {
    log.warn("Critic not approved but maxSeverity={} (MINOR_EMBELLISHMENT only), "
            + "treating as passed. round={}/{}", maxSeverity, round + 1, maxRounds);
    return new RewriteResult(draft, round + 1);
}

// maxSeverity >= 3：需要修正，进入下一轮
log.info("Critic not approved, maxSeverity={}, entering rewrite loop. round={}/{}",
        maxSeverity, round + 1, maxRounds);

criticFeedback = criticism.feedback();
```

**第三层：暴力测试验证**

我用 3 组刻意注入违规的 dirty draft（数字膨胀、技术栈替换、捏造百分比），每组采样 3 次，Critic 的命中率是 9/9 = 100%。同时跑了 4 场景端到端测试（精确数据/信息稀疏/零交集/模糊描述），全部通过。

### 防御话术速记
> 「审查比生成简单——Critic 做的是封闭域事实匹配，天然比 Writer 的开放域创意生成准确。但我没有停留在二元判断——我用结构化违规（类型+严重度+原文证据），让 Coordinator 能根据 severity 做灰度决策，severity≤2 直接放行、≥3 触发重写。这段逻辑已经落地在 Coordinator 代码里，测试日志可完整还原决策链。最后用 dirty draft 暴力测试验证——数字膨胀、技术栈替换、百分比捏造各测 3 次，100% 命中。」

---

## 难点二：精确数据如何保证不篡改？

### 面试官问法
> 「你说的"事实边界"具体怎么落地？比如原文 QPS 是 2000，你怎么保证 Writer 不会写成 50000？」

### 回答拆解

**多层防护机制**：

| 防护层 | 位置 | 机制 |
|--------|------|------|
| L1: Writer SystemMessage | ResumeWriterAgent | "绝对禁止捏造或推测任何数字" + "只能使用原文已有的数据" |
| L2: Writer @Description | RewriteReport | "只能使用原文已有数据，不得推测或编造任何量化指标" |
| L3: Critic 精确数值规则 | FactCriticAgent | "数字逐字比对——原文QPS=2000，bullet QPS≠2000 → 不通过" |
| L4: Violation 证据链 | Violation.detail | "原文中QPS提升至2000，但bullet point 1写为50000，数值严重不符" |

**验证数据**：

| 测试 | 注入违规 | 3次采样 | 命中率 |
|------|---------|---------|--------|
| 数字膨胀 | QPS 2000→50000 | 3/3 FAKE_DATA (sev=5) | 100% |
| 技术替换 | RocketMQ→Kafka | 3/3 FAKE_TECH (sev=3-4) | 100% |
| 捏造百分比 | 编造"降低60%" | 3/3 FAKE_DATA (sev=4) | 100% |

**E2E 验证**：
```
原文: QPS从500提升至2000, 使用了RocketMQ
Writer 输出: 主导订单系统优化，采用Redis分布式缓存与RocketMQ异步处理，
           将系统QPS从500提升至2000，显著增强高并发处理能力。
✓ QPS 2000 原样保留 ✓ RocketMQ 未被替换 ✓ 无 Kafka 引入
```

### 防御话术速记
> 「四层防护：Writer 的 SystemMessage + @Description 约束源头，Critic 的精确数值审查规则做逐字比对，Violation 的 detail 字段提供原文证据。验证实验 3 类违规 × 3 次采样 = 100% 命中——数字膨胀、技术替换、捏造百分比全部召回。」

---

## 难点三：模糊描述被润色后，Critic 会不会误判？

### 面试官问法
> 「如果原文是"优化了系统性能"，Writer 用 STAR 法则写成"主导性能优化工作，提升了系统响应速度"——这算不算捏造？Critic 会怎么判？」

### 回答拆解

**这是整个系统最微妙的设计决策——区分"合理润色"和"捏造夸大"。**

**规则设计**：

```
★ 定性描述容忍规则（Critic SystemMessage）：
  - 原文说"优化了系统性能"，bullet 说"提升了系统响应速度" → 通过（同义表达，无假数字）
  - 原文说"优化了系统性能"，bullet 说"系统响应时间降低60%" → 不通过（捏造了原文没有的百分比）
  - 原文说"负责/参与"，bullet 说"主导/设计" → 判 EXAGGERATION (severity=3)
```

**关键设计**：我把"定性→定性"和"定性→定量"做了区分。前者是合理语言润色，后者是捏造假数据。Critic 被明确告知这条边界——"无假数字的定性同义表达 → 通过"。

**验证数据**：

早期验证实验中最能说明问题的是 C3-1 这个用例——原文"优化了系统性能"，5 个变体中 2 次被误判为 EXAGGERATION。发现根本原因是当时的 Critic SystemMessage 没有定性容忍规则，LLM 在"合理润色 vs 轻微夸大"之间随机翻转。

加上规则后，E2E-4 验证：
```
原文: "负责系统性能优化工作，改善了系统响应速度"
E2E 输出: 2轮通过, Critic approved=true, 0 violations
```

### 防御话术速记
> 「这是最难的设计决策——区分"合理润色"和"捏造夸大"。我的解法是在 Critic 的 SystemMessage 中写明确切规则：定性→定性=通过，定性→定量=不通过。"优化了性能"改成"提升了响应速度"算润色，但加上"降低60%"就是捏造。这个规则经 E2E 验证有效——模糊描述场景 2 轮通过，0 违规。」

---

## 难点四：你怎么量化验证这些保障是有效的？

### 面试官问法
> 「你说的这些规则、验证，有数据支撑吗？」

### 回答拆解

**验证金字塔（从底层到顶层）**：

| 层级 | 测试名称 | 覆盖范围 | 规模 | 结果 |
|------|---------|---------|------|------|
| **L1: Critic 精确度** | CriticPrecisionTest | 3 类已知违规 × 3 次采样 | 9 次调用 | **100% 召回率** |
| **L2: Critic 惩罚度** | CT-BP3 连带检测 | 同一调用中识别违规 + MINOR_EMBELLISHMENT | 定性验证 | **精确数据不影响其他维度审查** |
| **L3: Actor-Critic 全链路** | EndToEndValidationTest | 4 场景（精确数据/稀疏/零交集/模糊）× 2 轮 | ~20 次调用 | **4/4 通过** |
| **L4: 循环次数对比** | RoundOptimizationValidatorTest | 8 用例 × 5 种 MAX_ROUNDS | ~240 次调用 | **3 轮为最优拐点** |

**具体测试代码和结果见**：
- `src/test/java/com/jobagent/service/CriticPrecisionTest.java`
- `src/test/java/com/jobagent/service/EndToEndValidationTest.java`
- `src/test/java/com/jobagent/service/RoundOptimizationValidatorTest.java`

### 防御话术速记
> 「我建了三层验证金字塔。底层——Critic 精确度测试：3 类已知违规 × 3 采样 = 100% 召回。中层——端到端全链路：4 场景覆盖精确数据/信息稀疏/零交集/模糊描述，全部通过。顶层——循环次数对比：8 用例 × 5 变体 = 240 次调用，确认 3 轮最优。这不是"我觉得好"，是数据驱动的。」

---

## 追问合集（快速索引）

| 追问 | 短答 | 详见解 |
|------|------|--------|
| Critic 误判怎么办？ | severity 阈值容忍 + 多次采样投票（演进方向） | 难点一 |
| 怎么确保 Writer 不脑补 JD 中的技术？ | Writer SystemMessage 双重约束 + Critic 技术栈差集比对 | 难点二 |
| "参与"改成"主导"算不算违规？ | 判 EXAGGERATION (severity=3)，但不定性捏造 (severity=5) | 难点三 |
| 为什么不用代码规则而用 Prompt？ | LLM 做语义理解（"优化了性能"和"提升了响应速度"是同义表达）、规则做兜底（精确数字提取）——两者互补 | 难点一 |
| 有考虑过多次采样降低随机性吗？ | 已验证 3 次采样的稳定性，对模糊场景可引入投票机制（演进方向） | 难点一 |