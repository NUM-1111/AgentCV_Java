# 结构化输出 & 反序列化失败问题 — 治理总览

本目录记录 `POST /api/v1/match/evaluate` 接口在接入 AI 后遇到的三类核心问题及其完整治理过程。

---

## 问题速览

| # | 问题 | 根因 | 解决方案 | 详细文档 |
|---|------|------|---------|---------|
| 1 | 非标准 JSON 导致解析硬崩溃 | 模型输出含注释、尾逗号、单引号，框架 Gson 无法解析 | 切换为 Jackson 宽松配置 + Facade 层可控解析 | [01-非标准JSON解析失败.md](01-非标准JSON解析失败.md) |
| 2 | 字段漂移导致静默错误 | 模型输出 `snake_case` 或语义相近词，反序列化"成功"但字段丢失 | `@JsonAlias` 精确兜底 + `FieldNameNormalizer` 模糊归一化 | [02-字段漂移.md](02-字段漂移.md) |
| 3 | 解析成功但业务无效 | 无字段级约束，`null`/空值/越界分值静默进入下游 | Bean Validation + 统一异常出口 | [03-业务字段校验.md](03-业务字段校验.md) |

---

## 整体治理链路

```
模型原始输出（String）
    │
    ▼
extractJsonObject()        ← 截取 { ... } 主体，去除 Markdown 包裹和前后噪声
    │
    ▼
FieldNameNormalizer        ← 正则归一化字段名，覆盖 @JsonAlias 范围外的漂移变体
    │
    ▼
Jackson 宽松反序列化        ← 允许注释/尾逗号/单引号，@JsonAlias 精确别名兜底
    │
    ▼
Bean Validation            ← 字段级约束：范围、非空、非空白
    │
    ▼
MatchReport（业务有效）
```

每层只做自己该做的事，不互相替代。

---

## 最终效果

- 非标准 JSON 不再导致不可控崩溃
- 字段漂移（已知别名 + 未知变体）均被归一化处理
- `null`/空值/越界分值被显式拦截，不再静默污染业务结果
- 所有异常统一走 `GlobalExceptionHandler`，前端拿到结构化错误而非堆栈

**测试结果：** `Tests run: 9, Failures: 0, Errors: 0`（含 6 个原有场景 + 3 个字段漂移归一化新场景）
