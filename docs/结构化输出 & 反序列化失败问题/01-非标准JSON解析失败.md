# 问题一：非标准 JSON 导致解析硬崩溃

## 问题是什么

`POST /api/v1/match/evaluate` 接口在真实调用中，模型偶发返回如下格式的内容：

```
分析如下：
```json
{
  // 这是匹配结果
  'match_score': 82,
  'matched_skills': ['Java', 'Spring Boot'],
  'missing_skills': ['Redis'],
  'improvement_advice': '补充 Redis 项目经验',
}
```
请参考上述结果。
```

这段内容有四类问题：
- 前后包裹了自然语言和 Markdown 代码块
- JSON 内含 `//` 注释
- 使用单引号而非双引号
- 最后一个字段后有尾逗号

原链路由 LangChain4j 内部的 Gson 直接反序列化，Gson 对以上任何一种情况都会直接抛异常，接口返回 HTTP 500。

---

## 为什么有这个问题

根本原因有两层：

**1. 模型输出本质上不可完全约束**

即使 Prompt 里写了"只输出标准 JSON"，模型仍然有概率：
- 在 JSON 前后加解释性文字（它认为这样更"友好"）
- 输出类 JavaScript 风格的注释（训练数据里大量代码含注释）
- 用单引号（Python 风格，训练数据里很常见）
- 在最后一个字段后加逗号（很多语言允许这样写）

这是语言模型的概率性特征，不能靠 Prompt 完全消除。

**2. 原链路没有自己的解析层**

原来的 `MatchEvaluatorService` 返回值类型是 `MatchReport`，LangChain4j 框架内部用 Gson 做反序列化，项目代码完全不介入这个过程。这意味着：
- 无法使用项目自己配置的宽松 Jackson
- 无法在解析前做任何预处理（比如截取 JSON 主体）
- 出错时只能拿到框架抛出的异常，无法附加上下文信息

---

## 用什么方法解决

### 第一步：把返回值改为原始字符串

`MatchEvaluatorService` 的返回类型从 `MatchReport` 改为 `String`，让框架只负责调用模型、拿回文本，不再做任何解析。

```java
// 改造前：框架内部 Gson 反序列化，项目无法介入
MatchReport evaluate(@V("jdText") String jdText, @V("resumeText") String resumeText);

// 改造后：拿原始文本，解析权交给项目自己
String evaluate(@V("jdText") String jdText, @V("resumeText") String resumeText);
```

### 第二步：新增 Facade 层统一处理

新增 `MatchEvaluationFacadeService`，集中负责"原始文本 → 业务对象"的完整链路：

```java
public MatchReport evaluate(String jdText, String resumeText) {
    String raw = matchEvaluatorService.evaluate(jdText, resumeText);
    String json = extractJsonObject(raw);          // 截取 JSON 主体
    String normalized = FieldNameNormalizer.normalize(json); // 字段名归一化（见问题二）
    MatchReport report = objectMapper.readValue(normalized, MatchReport.class);
    validateReport(report);                        // 业务校验（见问题三）
    return report;
}
```

`extractJsonObject` 的逻辑：找第一个 `{` 和最后一个 `}`，截取中间内容，去掉前后的自然语言和 Markdown 包裹。

### 第三步：配置 Jackson 宽松解析

新增 `JacksonConfig`，开启对非标准 JSON 的容错：

```java
mapper.configure(JsonParser.Feature.ALLOW_JAVA_COMMENTS, true);   // 允许 // 注释
mapper.configure(JsonParser.Feature.ALLOW_YAML_COMMENTS, true);   // 允许 # 注释
mapper.configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);  // 允许尾逗号
mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);   // 允许单引号
mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // 忽略多余字段
```

这个 `ObjectMapper` 作为 Spring Bean 注入，整个项目统一使用。

---

## 测试情况

**自动化测试（`MatchEvaluationFacadeServiceTest`）：**

```java
@Test
void shouldParseJsonWrappedByNoiseAndComments() {
    stubService.output = "分析如下：\n```json\n{\n  // comment\n  'match_score': 82,\n  'matched_skills': ['Java', 'Spring Boot'],\n  'missing_skills': ['Redis'],\n  'improvement_advice': '补充 Redis 项目经验',\n}\n```\n请参考上述结果。";

    MatchReport report = facadeService.evaluate("jd", "resume");

    assertEquals(82, report.matchScore());
    assertEquals(2, report.matchedSkills().size());
}
```

**真实接口回归（6 组干扰场景）：**

| 干扰类型 | 结果 |
|---------|------|
| 强制加注释和尾逗号 | HTTP 200，结构正确 |
| 强制用 snake_case | HTTP 200，字段正确映射 |
| 强制加额外字段 | HTTP 200，多余字段被忽略 |
| 强制把数组改成字符串 | HTTP 200，模型未被带偏 |
| 强制不返回 JSON | HTTP 200，模型仍输出合法 JSON |
| 强制把列表改成单个对象 | HTTP 200，结构保持稳定 |

---

## 前后效果对比

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 非标准 JSON | 直接 HTTP 500 崩溃 | 宽松解析，正常返回 |
| 解析过程 | 框架黑盒，项目无法介入 | Facade 层完全可控 |
| 错误信息 | 框架堆栈，无上下文 | 附带原始输出摘要，便于定位 |
| 可扩展性 | 换模型/换框架需大改 | 只改 Facade 层即可 |

---

## 拓展：还有哪些方案

**方案 A：JSON 修复库（json-repair / jsonrepair）**

有专门的库可以尝试"修复"不合法 JSON，比如自动补全缺失的引号、括号。适合对容错要求极高的场景，但引入了额外依赖，且修复结果不总是符合预期。

**方案 B：让模型输出前先做格式校验（Function Calling / Structured Output）**

部分模型（如 GPT-4o）支持 Structured Output，强制模型输出符合指定 JSON Schema 的内容，从源头消除格式问题。但这依赖模型能力，不是所有模型都支持，且仍需后端兜底。

**方案 C：正则提取 + 二次解析**

先用正则从原始文本里提取 JSON 片段，再解析。比 `indexOf('{')` 更精确，能处理嵌套 JSON 字符串的边界情况。当前 `extractJsonObject` 用的是简单的首尾截取，对于模型输出里包含嵌套 JSON 字符串的极端情况可能误判。
