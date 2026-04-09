# 结构化输出容错改造记录（本次）

## 1. 变更背景

在 `POST /api/v1/match/evaluate` 的测试中，出现了两类典型问题：

- 模型返回非标准 JSON（注释、尾逗号）导致解析异常；
- 字段命名漂移（`camelCase` -> `snake_case`）导致数据静默丢失（`0` / `null`）。

原链路由 LangChain4j 内部 Gson 直接反序列化，无法复用项目中的 Jackson 宽松配置与字段别名能力。

## 2. 改造目标

- 将 AI 输出解析链路改为“可控反序列化”；
- 引入字段级完整性校验，避免“解析成功但业务无效”；
- 保持对前端的统一错误响应格式。

## 3. 变更内容

### 3.1 解析链路改造（核心）

- `MatchEvaluatorService` 从返回 `MatchReport` 改为返回 `String`（原始模型文本）。
- 新增 `MatchEvaluationFacadeService`：
  - 统一提取 JSON 主体（去除前后噪声）；
  - 使用全局 `ObjectMapper` 执行宽松反序列化；
  - 解析失败时抛出统一异常信息。

### 3.2 Jackson 宽松配置

新增 `JacksonConfig`，包含以下配置：

- 允许注释：`ALLOW_JAVA_COMMENTS`、`ALLOW_YAML_COMMENTS`
- 允许尾逗号：`ALLOW_TRAILING_COMMA`
- 允许单引号：`ALLOW_SINGLE_QUOTES`
- 忽略未知字段：`FAIL_ON_UNKNOWN_PROPERTIES = false`

### 3.3 字段别名与校验

`MatchReport` 增强：

- 别名映射：
  - `matchScore` <- `match_score` / `score`
  - `matchedSkills` <- `matched_skills`
  - `missingSkills` <- `missing_skills`
  - `improvementAdvice` <- `improvement_advice` / `advice`
- 校验约束：
  - `matchScore`: `@Min(0)` + `@Max(100)`
  - `matchedSkills`: `@NotNull`
  - `missingSkills`: `@NotNull`
  - `improvementAdvice`: `@NotBlank`

### 3.4 校验异常专用兜底

- 新增 `AiOutputValidationException`。
- `MatchEvaluationFacadeService` 在反序列化成功后，手动调用 `Validator` 做 Bean Validation。
- 校验失败抛出 `AiOutputValidationException`，并在 `GlobalExceptionHandler` 中单独拦截，返回统一 500 JSON。

### 3.5 依赖补充

`pom.xml` 新增：

- `spring-boot-starter-validation`

## 4. 受影响文件

- `src/main/java/com/jobagent/service/MatchEvaluatorService.java`
- `src/main/java/com/jobagent/service/MatchEvaluationFacadeService.java`
- `src/main/java/com/jobagent/controller/MatchController.java`
- `src/main/java/com/jobagent/config/JacksonConfig.java`
- `src/main/java/com/jobagent/model/MatchReport.java`
- `src/main/java/com/jobagent/exception/AiOutputValidationException.java`
- `src/main/java/com/jobagent/exception/GlobalExceptionHandler.java`
- `pom.xml`

## 5. 验证结果

- 项目编译通过（`mvn compile`）。
- 非标准 JSON 和字段命名漂移场景已从“框架黑盒解析”切换为“可控解析 + 校验”路径。
- 对字段缺失/空值场景已可通过校验层拦截并统一返回错误结构。

## 6. 后续建议

- 可增加列表元素级校验（如 `List<@NotBlank String>`）；
- 可增加业务语义校验（如技能列表最小长度、建议文案最小字数）；
- 可补充该链路的集成测试与回归用例，覆盖异常输入矩阵。
