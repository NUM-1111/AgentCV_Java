# 针对 StageOneYH 中结构化输出风险点的专项测试报告（test4）

## 1. 测试目标

本次测试针对 `StageOneYH.md` 中列出的以下 5 类风险进行专项验证，确认当前 `match` 功能在优化后是否仍会出现这些问题：

1. 复杂嵌套 Record（`List<Skill>` 多层）下，Schema 不严谨；
2. 模型输出字段名乱变：`camelCase ↔ snake_case`，并乱加字段；
3. JSON 带注释、尾逗号、语法不规范；
4. 嵌套 `List` 被模型返回成单个对象，结构错乱；
5. 反序列化直接报错，接口不可用。

根据你的要求：

- 如果发现问题，则写入 `text4.md`，并给出一个解决办法；
- 如果没有发现问题，则写入 `test4.md`，说明程序功能完整。

本次测试结果为：**未发现上述问题复现，因此写入 `test4.md`。**

---

## 2. 测试环境

- 接口：`POST /api/v1/match/evaluate`
- 服务启动方式：`mvn spring-boot:run`
- 测试方式：真实接口调用 + 干扰性提示词注入
- 当前后端保护机制：
  - 强约束 Prompt
  - Jackson 宽松解析
  - `@JsonAlias` 字段兼容
  - Bean Validation 字段校验
  - 全局异常统一处理

---

## 3. 测试用例与结果

### 用例 1：复杂嵌套结构干扰

**目标问题**：复杂嵌套 Record / Schema 不严谨，导致模型把列表改成对象数组。

**测试方式**：在 `resumeText` 中诱导模型把 `matchedSkills` / `missingSkills` 改成 `skills` 对象数组，每个元素含 `name`、`level`、`evidence` 字段。

**返回结果**：HTTP `200`

```json
{
  "matchScore": 80,
  "matchedSkills": ["Java", "Spring Boot", "Redis", "Kafka"],
  "missingSkills": ["微服务架构"],
  "improvementAdvice": "在简历中明确补充微服务架构相关的项目经验与技术细节。"
}
```

**结论**：

- 未出现复杂嵌套对象数组；
- 仍然保持为预期的字符串数组结构；
- 当前 `match` 场景下未复现“Schema 不严谨导致结构跑偏”的问题。

---

### 用例 2：字段名乱变 + 乱加字段

**目标问题**：模型把字段名改成 `snake_case`，并增加 `candidate_name`、`summary`、`confidence` 等多余字段。

**测试方式**：在输入中明确要求模型更换字段名并增加额外字段。

**返回结果**：HTTP `200`

```json
{
  "matchScore": 100,
  "matchedSkills": ["Java", "Spring Boot", "Redis"],
  "missingSkills": [],
  "improvementAdvice": "简历描述与JD要求完全一致，无需额外改进。"
}
```

**结论**：

- 字段名仍保持标准 `camelCase`；
- 未出现多余字段；
- 未复现字段乱变、乱加字段问题。

---

### 用例 3：非标准 JSON（注释、尾逗号、单引号）

**目标问题**：模型输出不规范 JSON，导致解析不稳定。

**测试方式**：明确要求模型在输出 JSON 中加入注释、单引号和尾逗号。

**返回结果**：HTTP `200`

```json
{
  "matchScore": 80,
  "matchedSkills": ["Java", "Spring Boot", "MySQL"],
  "missingSkills": ["高并发经验"],
  "improvementAdvice": "补充高并发项目经验。"
}
```

**结论**：

- 最终返回仍为标准结构；
- 未出现脏 JSON 回传给后端；
- 未复现“非标准 JSON 导致当前接口不稳定”的问题。

---

### 用例 4：嵌套 List 被返回成单个对象

**目标问题**：原本应为数组的字段被模型返回成单个对象，造成结构错乱。

**测试方式**：明确诱导模型将 `missingSkills` 返回成单对象而不是数组。

**返回结果**：HTTP `200`

```json
{
  "matchScore": 67,
  "matchedSkills": ["Java", "Spring Boot"],
  "missingSkills": ["Redis", "Kafka"],
  "improvementAdvice": "补充 Redis 与 Kafka 的实际项目经验，并体现在简历中。"
}
```

**结论**：

- `missingSkills` 仍为数组；
- 未出现对象替代数组的结构错误；
- 未复现“嵌套 List 变单对象”的问题。

---

### 用例 5：不返回 JSON，尝试触发反序列化失败

**目标问题**：模型不返回 JSON，而是自然语言说明，从而触发接口不可用。

**测试方式**：明确要求模型不要输出 JSON，而输出自然语言和 `strengths` / `weaknesses` 列表。

**返回结果**：HTTP `200`

```json
{
  "matchScore": 100,
  "matchedSkills": ["Java", "Spring Boot"],
  "missingSkills": [],
  "improvementAdvice": "保持现有技术栈深度，可关注微服务等扩展领域。"
}
```

**结论**：

- 模型依然返回了合法 JSON；
- 接口未出现反序列化失败；
- 未复现“直接报错、接口不可用”的问题。

---

## 4. 总结结论

本次对 `StageOneYH.md` 中 5 类结构化输出风险进行了针对性真实接口测试，结果如下：

- 5 个用例全部返回 HTTP `200`；
- 返回内容均符合 `MatchReport` 的目标结构；
- 未出现字段乱变、额外字段、脏 JSON、数组变对象、非 JSON 输出等问题；
- 当前 `match` 功能在本轮测试覆盖范围内表现稳定。

因此可以得出结论：

**当前程序在上述问题场景下未发现异常，`match` 功能完整，结构化输出能力稳定。**

---

## 5. 最终判定

按照本次测试结果：

**不需要生成 `text4.md`，因为没有发现对应问题。**

已生成：

- `docs/结构化输出 & 反序列化失败问题/test4.md`

用于说明：

**程序功能完整，当前未复现 StageOneYH 中列出的上述 5 类问题。**
