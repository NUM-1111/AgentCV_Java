# 人岗匹配（Match）功能说明

## 概述

Match 功能根据**岗位 JD 纯文本**与**简历纯文本**，调用大语言模型生成结构化的人岗匹配报告。评估风格在系统提示中设定为「严苛的技术面试官与 HR」视角，强调客观性，避免奉承。

实现上基于 **LangChain4j** 的 `@AiService`，将模型输出约束为 Java 记录类型 `MatchReport`（结构化输出）。

## 相关代码位置

| 角色 | 路径 |
|------|------|
| HTTP 接口 | `src/main/java/com/jobagent/controller/MatchController.java` |
| AI 服务接口 | `src/main/java/com/jobagent/service/MatchEvaluatorService.java` |
| 响应模型 | `src/main/java/com/jobagent/model/MatchReport.java` |

## API

### `POST /api/v1/match/evaluate`

对 JD 与简历做一次匹配评估，返回 `MatchReport`。

**Content-Type:** `application/json`

### 请求体

| 字段 | 类型 | 说明 |
|------|------|------|
| `jdText` | string | 岗位 JD 全文（必填，不能为空或纯空白） |
| `resumeText` | string | 候选人简历全文（必填，不能为空或纯空白） |

示例：

```json
{
  "jdText": "岗位要求……",
  "resumeText": "个人简历……"
}
```

### 响应体（`MatchReport`）

| 字段 | 类型 | 说明 |
|------|------|------|
| `matchScore` | int | 0–100 的整数；设计上偏严格，80+ 表示非常匹配，低于 60 表示整体不匹配感强；重点考察简历是否覆盖 JD 中的硬性技能 |
| `matchedSkills` | string[] | 与 JD 要求相符的技能/要点，短词组形式（如 `Java`、`Spring Boot`），避免长句 |
| `missingSkills` | string[] | 相对 JD 缺失或薄弱的技能/要点，短词组形式 |
| `improvementAdvice` | string | 给候选人的修改建议，约 100 字以内，语气专业、直指问题 |

字段上的语义说明同时通过 LangChain4j 的 `@Description` 注解提供给模型，以稳定结构化输出质量。

### 校验与错误

- `jdText` 为 `null` 或仅空白：抛出 `IllegalArgumentException`，提示「jdText 不能为空」。
- `resumeText` 为 `null` 或仅空白：抛出 `IllegalArgumentException`，提示「resumeText 不能为空」。

（若项目配置了全局异常处理，实际 HTTP 状态码与错误体格式以全局处理器为准。）

## 模型与提示词要点

- **系统消息**：角色为严苛的资深技术面试官与 HR，要求客观、冷酷评估匹配度，不奉承。
- **用户消息模板**：将 `jdText`、`resumeText` 嵌入固定模板，要求「严格对照」并输出结构化人岗匹配报告。

具体文案见 `MatchEvaluatorService` 中的 `@SystemMessage` 与 `@UserMessage`。

## 运行配置

Match 与项目中其他 LangChain4j AI 能力共用 OpenAI 兼容 Chat 模型配置，见 `src/main/resources/application.yml`：

| 配置项 | 环境变量 | 说明 |
|--------|----------|------|
| API Key | `OPENAI_API_KEY` | 访问模型服务的密钥 |
| Base URL | `OPENAI_BASE_URL` | 兼容 OpenAI API 的服务地址 |
| 模型名 | `OPENAI_MODEL_NAME` | 使用的模型标识 |

服务端口可通过 `SERVER_PORT` 覆盖，默认 `8081`。

调试时可关注 `dev.langchain4j` 的 DEBUG 日志（含请求/响应体，具体以当前 `application.yml` 为准）。

## 依赖关系简述

- Spring Web：`MatchController` 暴露 REST 接口。
- LangChain4j Spring：`MatchEvaluatorService` 由框架生成实现，调用配置的 Chat Model，并将输出解析为 `MatchReport`。
