# 结构化输出容错改造变更记录

## 1. 变更背景

`POST /api/v1/match/evaluate` 在测试中暴露两类问题：

- 模型返回非标准 JSON（注释、尾逗号、单引号）导致 Gson 解析硬崩溃（HTTP 500）；
- 字段命名漂移（`camelCase` → `snake_case`）导致反序列化静默丢值（HTTP 200 + `0` / `null`）。

原链路由 LangChain4j 内部 Gson 直接反序列化，无法复用项目 Jackson 宽松配置，也无字段级校验。

## 2. 改造目标

- 将 AI 输出解析链路改为后端可控反序列化；
- 引入字段级完整性校验，避免"解析成功但业务无效"的静默错误；
- 保持对前端的统一错误响应格式。

## 3. 变更内容

### 3.1 解析链路改造（核心）

- `MatchEvaluatorService` 返回值从 `MatchReport` 改为 `String`（原始模型文本）；
- 新增 `MatchEvaluationFacadeService`，统一负责：
  - 提取 JSON 主体（去除前后噪声文本）；
  - 使用全局 `ObjectMapper` 执行宽松反序列化；
  - 解析失败时抛出统一异常。

### 3.2 Jackson 宽松配置

新增 `JacksonConfig`：

| 配置项 | 作用 |
|---|---|
| `ALLOW_JAVA_COMMENTS` / `ALLOW_YAML_COMMENTS` | 允许 JSON 中含注释 |
| `ALLOW_TRAILING_COMMA` | 允许尾逗号 |
| `ALLOW_SINGLE_QUOTES` | 允许单引号 |
| `FAIL_ON_UNKNOWN_PROPERTIES = false` | 忽略未知字段 |

### 3.3 字段别名与校验

`MatchReport` 增强：

**别名映射（`@JsonAlias`）：**

| 字段 | 兼容别名 |
|---|---|
| `matchScore` | `match_score`, `score` |
| `matchedSkills` | `matched_skills` |
| `missingSkills` | `missing_skills` |
| `improvementAdvice` | `improvement_advice`, `advice` |

**校验约束（Bean Validation）：**

| 字段 | 约束 |
|---|---|
| `matchScore` | `@Min(0)` + `@Max(100)` |
| `matchedSkills` | `@NotNull` |
| `missingSkills` | `@NotNull` |
| `improvementAdvice` | `@NotBlank` |

### 3.4 异常处理

- 新增 `AiOutputValidationException`，专用于字段校验失败场景；
- `MatchEvaluationFacadeService` 反序列化成功后手动触发 Bean Validation；
- `GlobalExceptionHandler` 统一拦截，返回标准 500 JSON 错误结构。

### 3.5 依赖补充

`pom.xml` 新增 `spring-boot-starter-validation`。

## 4. 受影响文件

- `service/MatchEvaluatorService.java`
- `service/MatchEvaluationFacadeService.java`（新增）
- `controller/MatchController.java`
- `config/JacksonConfig.java`（新增）
- `model/MatchReport.java`
- `exception/AiOutputValidationException.java`（新增）
- `exception/GlobalExceptionHandler.java`
- `pom.xml`

## 5. 后续建议

- 补充列表元素级校验（如 `List<@NotBlank String>`）；
- 区分解析失败与字段校验失败的错误码，便于前端区分处理；
- 对未知字段漂移增加告警日志，提升可观测性。
