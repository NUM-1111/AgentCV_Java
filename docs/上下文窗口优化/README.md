# 上下文窗口优化

本目录记录"长文本上下文溢出"问题（P0）的探测、分析与解决过程。

## 文档列表

| 文件 | 内容 |
|------|------|
| [01-长文本溢出问题探测报告](./01-长文本溢出问题探测报告.md) | 针对 `/api/v1/match/evaluate` 接口的 7 个测试场景，验证当前代码无任何 token 保护机制 |

## 问题优先级

**P0** — 真实简历一长就崩，结果不可信，无法面对真实业务场景

## 核心结论

当前 `MatchEvaluationFacadeService` 对输入长度完全无感知，`jdText` 和 `resumeText` 原样透传给 DeepSeek API，没有：
- token 预估
- 业务层裁剪
- 主动拦截
- 超长错误提示

详见 [探测报告](./01-长文本溢出问题探测报告.md)。

---

## 已实现的优化措施（2026-04-12）

### 措施一：业务层裁剪 — `TextTrimmer`

新增 `src/main/java/com/jobagent/util/TextTrimmer.java`。

- `trimJd(jdText)`：提取"岗位职责"和"任职要求"段落，单段上限 1500 字，总输出上限 4000 字
- `trimResume(resumeText)`：提取"工作经历"和"项目经历"段落，同等限制
- 若无法识别段落结构，直接硬截断到 4000 字并追加 `…[已截断]` 标记
- 纯正则 + 字符串操作，无外部依赖，行为可预期、可测试

### 措施二：Token 长度预估 — `TokenEstimator`

新增 `src/main/java/com/jobagent/util/TokenEstimator.java`。

- 保守估算规则：中文 1字 ≈ 1 token，其他字符 4个 ≈ 1 token
- 固定叠加 Prompt 模板开销 400 token（来自 `@SystemMessage` + `@UserMessage`）
- 安全阈值：`MAX_INPUT_TOKENS = 10_000`
- `assertWithinLimit(jdText, resumeText)`：超过阈值抛出 `ContextWindowExceededException`

### 措施三：主动拦截 + 明确错误 — `ContextWindowExceededException`

新增 `src/main/java/com/jobagent/exception/ContextWindowExceededException.java`。

- 携带 `estimatedTokens` 和 `maxTokens` 字段，错误信息明确告知用户原因
- `GlobalExceptionHandler` 新增对应处理器，返回 **HTTP 400**（区别于模型内部错误的 500）
- 错误响应示例：
  ```json
  {
    "code": 400,
    "message": "输入文本过长：估算 token 数 12500 超过安全阈值 10000，请精简 JD 或简历后重试。"
  }
  ```

### 集成点 — `MatchEvaluationFacadeService`

`evaluate()` 方法新增两个前置步骤（在调用模型前执行）：

```
Step 1: TextTrimmer.trimJd / trimResume  ← 业务层裁剪
Step 2: TokenEstimator.assertWithinLimit ← token 预估拦截（不依赖框架截断）
Step 3: matchEvaluatorService.evaluate   ← 调用模型（输入已可控）
```

### 优化效果对比

| 维度 | 优化前 | 优化后 |
|------|--------|--------|
| 超长 JD（8000字） | 原样透传，黑盒截断风险 | 裁剪到核心段落，≤4000字 |
| 超长简历（8000字） | 原样透传，关键信息丢失 | 裁剪到经历/项目段落，≤4000字 |
| 双超长（各25000字） | API 报错或静默截断 | 裁剪后 token 预估，超限返回 400 |
| 错误信息 | "模型输出解析失败"（误导） | "输入文本过长，请精简后重试"（明确） |
| 框架依赖 | 依赖 LangChain4j 黑盒截断 | 业务层完全可控 |
