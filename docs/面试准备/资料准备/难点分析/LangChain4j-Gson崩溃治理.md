# Gson 解析盲区问题排查与修复记录

> **时间**：2026-07-18
> **触发**：optimizeFull 端点返回 500 `Expected BEGIN_OBJECT but was STRING`
> **关联**：`00-项目拆解方法论-总驾驶舱.md` L3 数据流层

---

## 一、故障回溯链

```
POST /api/v1/resume/optimize/full (JD + 完整简历)
  → ResumeController.optimizeFull()
    → ResumeOptimizationService.optimizeFullResume()

[阶段0] ResumeParser.parse(fullResumeText)
  → 输出 projects[] = 9 个项目  ← ❌ 实际只有 2 个，编号行被误判为新项目
  → project[1] = "1. **多维评分驱动的双Agent改写体系**：设计了..."
  → （这只是一条 bullet point，不是完整项目）

[阶段2] runActorCritic(jd, project[1])
  → WriterAgent.rewrite(jd, "1. **多维评分驱动...")  ← 垃圾输入
  → DeepSeek 不理解怎么结构化输出，返回纯文本
  → LangChain4j 内部 Gson 解析 LLM 响应
  → 💥 Expected BEGIN_OBJECT but was STRING
  → 四层防御 Pipeline 未被执行（Gson 崩在框架内部）
```

## 二、技术组件链

| 组件 | 在该故障中的角色 |
|------|----------------|
| ResumeParser | 🔴 责任方——正则把 bullet point 编号行误判为新项目标题 |
| WriterAgent | 被动出错——收到不完整的简历片段，无法结构化输出 |
| LangChain4j Gson | 💥 崩溃点——期望 JSON 对象，收到纯文本 |
| 四层防御 Pipeline | 旁观者——Gson 崩在 Java 代码之前，Pipeline 没机会执行 |
| GlobalExceptionHandler | 兜底——返回 500 "AI 分析引擎暂时开小差了" |

## 三、已尝试的修复

| 修复 | 效果 |
|------|------|
| 在 FactCriticAgent SystemMessage 中添加 JSON 格式强制要求 | optimize 端点（单项目）✅ 通过 |
| 添加 `max-tokens: 8192` | 评分输出不再被截断 ✅ |
| 添加 `@UserMessage` 注解到 ResumeScoringAgent | 评分接口不再 500 ✅ |

## 四、修复方案探索

### 方案 A：try-catch 兜底
- 在 `runActorCritic()` 中外层包 try-catch，捕获 Gson 异常
- 降级策略：返回原文 + 标记 `approved=false` + 打 ERROR 日志
- 优点：一行改动，立刻生效
- 缺点：治标——垃圾输入 → 原样输出

### 方案 B：WriterAgent 返回 String + Jackson 解析

**思路**：绕过 `@AiService` 的结构化输出机制，让 WriterAgent 返回纯 String，解析步骤收回 Java 侧。

**具体做法**：
1. 将 `WriterAgent.rewrite()` 的返回类型从 `WriterDraft` 改为 `String`
2. 在 WriterAgent 的 SystemMessage 里写 JSON 输出格式要求（原来 LangChain4j 自动生成的 Schema 现在手动写在 Prompt 里）
3. 在 `runActorCritic()` 中拿到 String 后：`extractJsonObject()` → `FieldNameNormalizer.normalize()` → Jackson 宽松解析 → 得到 WriterDraft

**优点**：
- 彻底绕开 Gson——解析逻辑全部在你的 Java 代码里
- 复用现有四层防御 Pipeline（extractJsonObject + FieldNameNormalizer + Jackson 宽松开关 + Bean Validation）
- 解析失败时**你能看到原始文本**（可以打 ERROR 日志、重试、降级——方案 A 做不到）
- 改动范围小——只影响 `WriterAgent` 一个接口和 `runActorCritic()` 一个方法
- 面试可讲性强——「我在端到端验证时发现 LangChain4j 的 Gson 在四层防御之前就崩了，于是把解析步骤收回 Java 侧，复用四层防御」

**缺点**：
- 丢掉 `@AiService` 自动结构化输出的便利性——原来 LangChain4j 自动从 WriterDraft 生成 JSON Schema 注入 LLM 请求，现在你要在 Prompt 里手写格式要求
- 如果 Prompt 里写的格式要求和实际解析逻辑不一致，会出现「Prompt 要求输出 A 格式、Jackson 解析期望 B 格式」的错位

---

### 方案 C：通过 SPI 替换 LangChain4j 内置 Gson 为 Jackson

**技术原理**：

LangChain4j 0.36.2 的内部架构：
```
langchain4j-core-0.36.2.jar
└── dev/langchain4j/
    ├── internal/
    │   ├── GsonJsonCodec.class        ← 默认 JSON 解析器
    │   ├── Json.class                 ← 门面（所有内部代码通过它调用）
    │   └── Json$JsonCodec.class       ← SPI 接口
    └── spi/json/
        └── JsonCodecFactory.class     ← SPI 工厂（ServiceLoader 加载）
```

Gson 不是通过 Maven 依赖引入的——它被**直接打包在 langchain4j-core-0.36.2.jar 内部**。所以 pom.xml 里 exclude 没用。

**调用链路**（以 WriterAgent.rewrite 为例）：
```
writerAgent.rewrite()  ← 你的 Java 代码调用
  ↓
LangChain4j CGLIB 动态代理
  ↓ [1] 读返回类型 → WriterDraft
  ↓ [2] 从 WriterDraft 自动生成 JSON Schema
  ↓ [3] 调用 DeepSeek API（Schema 注入 response_format）
  ↓ [4] 收到 LLM 返回的文本
  ↓ [5] 🔴 GsonJsonCodec.fromJson(text, WriterDraft.class)
  ↓ [6] 返回 WriterDraft 对象给你的代码
```

**方案 C 怎么做**：
1. 写一个 `JacksonJsonCodecFactory` 实现 `dev.langchain4j.spi.json.JsonCodecFactory`
2. 让它返回一个用 Jackson `ObjectMapper`（配置四个宽松开关）包装的 `JsonCodec` 实例
3. 在 `src/main/resources/META-INF/services/dev.langchain4j.spi.json.JsonCodecFactory` 中注册
4. LangChain4j 通过 `ServiceLoader` 发现你的实现并优先使用

**代码量估算**：约 80 行（实现 `JsonCodec` 接口的 `toJson()` / `fromJson()`，配置 Jackson）

**风险清单**：
| 风险 | 严重度 | 说明 |
|------|:--:|------|
| chat message 层仍用 Gson | 🔴 | `GsonChatMessageAdapter` / `GsonChatMessageJsonCodec` 也打包在 jar 里，处理底层请求响应序列化。SPI 只替换 structured output 层的解析器——chat message 层不受 SPI 控制，仍用 Gson |
| 双重 JSON 库共存 | 🟡 | classpath 同时有 Gson（jar 内置）和 Jackson（你引入），两个库行为差异可能导致边界不一致 |
| 升级兼容性 | 🟡 | LangChain4j 升级时 SPI 接口可能变化，你的 `JacksonJsonCodecFactory` 需要跟进 |
| 缺少社区先例 | 🟠 | 0.36.2 社区几乎没有 Jackson SPI 实现的公开案例——你是第一批吃螃蟹的人 |

---

### 三方案投入产出对比

| 维度 | 方案 A（try-catch） | 方案 B（返回 String） | 方案 C（SPI 替换） |
|------|:--:|:--:|:--:|
| 代码改动量 | ~5 行 | ~40 行 | ~80 行 + SPI 配置 |
| 影响范围 | 只改 runActorCritic | WriterAgent + runActorCritic | 全局所有 Agent |
| 解析失败时看到原始文本 | ❌ | ✅ | ❌ |
| 复用四层防御 | ❌ | ✅ | ✅ |
| 维护成本 | 极低 | 低 | 中高 |
| 落地的确定性 | 100% | 95% | 70%（有 SPI 兼容风险） |
| 面试可讲性 | 中 | 高 | 高 |

### 最终选型：方案 B

**选定方案 B**（WriterAgent 返回 String + Jackson 解析），理由：

1. **C 方案剔除**：LangChain4j 0.36.2 的 Gson 不仅用于 structured output 层，`GsonChatMessageAdapter` / `GsonChatMessageJsonCodec` 也在核心 jar 中处理 chat message 序列化。SPI 替换不完整，引入双重 JSON 库维护负担，且不能解决"输出不可观测"的核心问题。

2. **A 方案不足**：try-catch 兜底只能防止崩溃，不能让你看到 LLM 原始输出（异常被 Gson 吞掉，Java 代码收不到原始文本）。

3. **B 方案不等于放弃 LangChain4j**：只改变 WriterAgent 一个接口的返回类型，其他特性（`@AiService`、`@SystemMessage`、`@UserMessage`、`@Tool`）全部保留。

### 方案 B 设计摘要

| 改动文件 | 改动内容 | 行数 |
|---------|---------|:--:|
| `ResumeWriterAgent.java` | 返回类型 `WriterDraft` → `String`；SystemMessage 加入 JSON 格式要求 | ~12 行 |
| `ResumeOptimizationService.java` | `runActorCritic()` 和 `optimizeFullResume()` 中新增 `parseWriterDraft()` 解析调用 | ~25 行 |

核心逻辑：
```
writerAgent.rewrite() → String (LLM 原始输出)
  → extractJsonObject() (剥 Markdown)
  → FieldNameNormalizer.normalize() (字段名归一化)
  → Jackson 宽松解析 (四个宽松开关)
  → WriterDraft 对象
  → 解析失败 → catch → ERROR 日志 + 空 WriterDraft（服务不崩）
```

## 五、执行状态

- [x] 方案选型完成：方案 B
- [x] 实施：修改 ResumeWriterAgent（返回类型 String + Prompt JSON 格式要求）
- [x] 实施：修改 ResumeOptimizationService（ObjectMapper + parseWriterDraft + 4 调用点）
- [x] 编译通过 + 服务启动成功

## 六、验证结果（2026-07-18）

| 端点 | 结果 | 详情 |
|------|:--:|------|
| `/optimize`（单项目） | ✅ | approved=true, rounds=1, 98s |
| `/optimize/full`（完整简历） | ⚠️ 超时未崩溃 | ResumeParser 分出 9 个项目，只跑到第 4 个就 180s 超时。**无 parseWriterDraft ERROR 日志**——证明 Jackson 解析正常工作，Gson 崩溃已根治 |

**方案 B 核心目标达成**：LLM 输出在 Java 侧通过 extractJsonObject → FieldNameNormalizer → Jackson 宽松解析，解析失败时记录原始文本并降级为空 WriterDraft（不阻断请求）。

## 七、后续

- [ ] ResumeParser 正则过度分段（将 9 个假项目收敛为 2 个真实项目）
- [ ] optimizeFull 完整链路验证（需等分段修复后）
- [ ] 面试话术补充：Gson 盲区发现与方案 B 治理过程
