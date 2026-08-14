# ResumeParser 分段过度拆分修复记录

> **时间**：2026-07-21
> **触发**：optimizeFull 端到端测试发现 ResumeParser 将 2 个项目拆分为 9 个假项目
> **性质**：针对当前测试用例（Markdown 格式简历）的**针对性修复**，非完整永久方案
> **关联**：`src/main/java/com/jobagent/util/ResumeParser.java`

---

## 一、问题现象

测试简历（Resume3.2.1.md）中有 2 个项目经历，每个项目内部用 `1. **xxx**` 格式描述技术亮点：

```markdown
### 项目一：AgentCV
1. **多维评分驱动的双Agent改写体系**：...
2. **简历结构感知的局部改写管线**：...
...
### 项目二：IntelliVault
1. **RAG 检索全链路**：...
2. **检索质量与幻觉控制**：...
```

ResumeParser 却输出了 **9 个项目**——5 条 bullet point 各被当成一个独立项目。

---

## 二、根因定位

`splitProjects()` 方法的切分正则：

```java
// ResumeParser.java line 160（原）
String[] blocks = content.split(
    "(?m)^(?=[\\d一二三四五六七八九十]+[.、．)\\s]|项目[\\d一二三四五六七八九十]|[●■◆▸▪◦])"
);
```

第一条规则 `[\\d一二三四五六七八九十]+[.、．)\\s]` 将 **所有数字+标点开头** 的行视为项目边界——包括 `1.` `2.` `3.` 这类项目内部的 bullet point 编号。

---

## 三、修复（两层）

### 修复 1：BULLET_POINT_LINE 过滤

新增正则，识别 `数字. **xxx**` 格式行，判定为 bullet point 而非项目标题，合并回上一个项目 body：

```java
private static final Pattern BULLET_POINT_LINE = Pattern.compile(
    "^[\\d一二三四五六七八九十]+[.、．)]\\s*\\*\\*"
);
```

### 修复 2：Markdown 标题预切分

在 `splitProjects()` 开头优先按 `^#{1,3}\s` 切分——正确分离 `### 项目一` 和 `### 项目二`。每块内部再用子切分逻辑处理，BULLET_POINT_LINE 保护在子切分中生效。

```java
// 优先按 Markdown 标题预切分
String[] mdBlocks = content.split("(?m)^(?=#{1,3}\\s)", -1);
if (mdBlocks.length > 1) {
    // 有 Markdown 标题，逐块处理...
}
// 无 Markdown 标题，退回到原有逻辑
```

### 关键设计

- **有 Markdown 标题时**：预切分 → 每块取首行为标题 → body 走子切分  
- **无 Markdown 标题时**：完全退回到原有 `splitByDelimiter()` 逻辑  
- 纯文本简历的分段行为不受任何影响

---

## 四、验证结果

| 修复前 | 修复后 |
|--------|--------|
| Projects = **9** 个 | Projects = **2** 个 ✅ |

```
[0] TITLE: ### 项目一：AgentCV（基于大模型的求职场景智能助手）
    BODY:  含全部 5 条技术亮点 ✅
[1] TITLE: ### 项目二：IntelliVault（多租户智能知识库与 RAG 问答平台）
    BODY:  含全部 3 条技术亮点 ✅
```

---

## 五、已知局限性（⚠️ 待完整解决）

| # | 局限 | 说明 | 触发条件 |
|---|------|------|---------|
| 1 | **纯数字编号无 `**` 包裹** | BULLET_POINT_LINE 只匹配 `数字. **xxx**`，不匹配 `1. 普通文本` | 简历用 `1. 负责开发...` 格式而非 `1. **xxx**` |
| 2 | **无 Markdown 标题的纯文本简历** | 退回到原有逻辑后，bullet point 可能仍被误判——需要 `splitByDelimiter` 自身增强 | 纯文本简历用 `项目一：` 配合 `1. xxx` 的格式 |
| 3 | **项目标题格式多样** | 当前只识别 `^#{1,3}\s`，不识别 `###` 以外标题（如 `**项目一**` 加粗格式） | 非标准 Markdown 写法 |
| 4 | **标题层级缺失的简历** | `项目经历` 章节下只有一个大段，无项目子标题——全部内容被当做一个项目 | 无子标题拆分点的简历 |

---

## 六、后续规划

> ⚠️ 当前修复是**针对特定测试用例**（Markdown + `1. **xxx**` 格式）的补丁，非完整解决方案。

后续完整方案应在 `docs/项目开发/` 下新建文件统一规划，方向包括：

- [ ] 多级切分策略：Markdown 层级 → 编号前缀 → 空行分隔 → LLM 兜底  
- [ ] `splitByDelimiter` 增强：增加更多 bullet point 识别特征（长度、有无 `**`、下一行格式）  
- [ ] 单元测试覆盖：基于 Golden Set 的简历样本做分段结果回归  
- [ ] 评估 LLM 兜底的可行性与成本