# 行动项：Coordinator 中落地 maxSeverity 阈值逻辑

> **预计耗时**：25min | **优先级**：P0 | **关联文件**：`RewriteCoordinatorService.java`

---

## 一、当前状态（问题）

`RewriteCoordinatorService.evaluate()` 第 115 行，Coordinator 只检查 `criticism.approved()` 布尔值做决策：

```java
if (criticism.approved()) {
    return new RewriteResult(draft, round + 1);
}
```

`Violation.java` 的 Javadoc 和 `FactCriticAgent.java` 的 SystemMessage 中定义了 severity 阈值容忍的**设计意图**，但从未落地为代码：
- severity ≤ 2 → 轻度润色，仍视为通过
- severity ≥ 4 → 必须重写
- severity = 3 → 当前处于逻辑真空

---

## 二、目标

修改 `evaluate()` 方法，在 Critic 返回 `approved=false` 时，不再直接进入下一轮循环，而是**先计算 `maxSeverity`**，根据阈值决定是否提前通过。

**决策矩阵**：

| 条件 | 行为 |
|------|------|
| `approved == true` | 直接通过（不变） |
| `approved == false` 且 `maxSeverity ≤ 2` | 视为通过，只打 WARN 日志（设计意图：MINOR_EMBELLISHMENT 不触发重写） |
| `approved == false` 且 `maxSeverity ≥ 4` | 必须重写（不变，进入下一轮） |
| `approved == false` 且 `maxSeverity == 3` | **新增**：打 WARN 日志提示 EXAGGERATION，但仍进入重写循环（由 Writer 在下一轮自行判断是否修正） |

> **设计说明**：severity=3 的处理选择"循环重写"而非"直接通过"，因为 EXAGGERATION 仍属于应修正的范畴。真正需要"灵活性"的只有 MINOR_EMBELLISHMENT（severity≤2）。

---

## 三、具体改动

### 改动位置：`RewriteCoordinatorService.java` 第 110-119 行

**现代码**（第 110-119 行）：

```java
            CriticReport criticism = criticAgent.check(originalProjectText, bulletPoints);

            log.info("Critic result: approved={}, round={}/{}", criticism.approved(), round + 1, maxRounds);

            if (criticism.approved()) {
                return new RewriteResult(draft, round + 1);
            }

            criticFeedback = criticism.feedback();
```

**改为**：

```java
            CriticReport criticism = criticAgent.check(originalProjectText, bulletPoints);

            // 计算所有 violations 中的最大 severity
            int maxSeverity = criticism.violations() != null && !criticism.violations().isEmpty()
                    ? criticism.violations().stream()
                        .mapToInt(com.jobagent.model.Violation::severity)
                        .max()
                        .orElse(0)
                    : 0;

            log.info("Critic result: approved={}, maxSeverity={}, violations={}, round={}/{}",
                    criticism.approved(), maxSeverity,
                    criticism.violations() != null ? criticism.violations().size() : 0,
                    round + 1, maxRounds);

            // 决策逻辑：approved=true 或 仅含轻度润色 → 通过
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

---

## 四、验证清单（改完后自测）

- [ ] 改动后编译通过（`mvn compile`）
- [ ] 阅读 `Violation.java` 确认 `severity()` 方法的返回类型是 `int`
- [ ] 思考场景：如果 `criticism.violations()` 返回 `null`，代码会不会 NPE？（答案：已用三元表达式防御）
- [ ] 检查 `log.warn` 中的字符串拼接是否可读
- [ ] 确认新增的 `log.info` 中 `violations` 数量能帮你在排查时快速判断严重程度

---

## 五、时间预算

| 阶段 | 分钟 | 做什么 |
|------|------|--------|
| 理解现状 | 0-5 | 阅读 `evaluate()` 方法 + `Violation.java`，理解当前 approved 逻辑 |
| 编码 | 5-12 | 在 `evaluate()` 中插入 maxSeverity 计算和阈值判断 |
| 编译验证 | 12-15 | `mvn compile`，修编译错误（如有） |
| 逻辑走查 | 15-20 | 对照第四节验证清单逐条走查 |
| 测跑现有测试 | 20-25 | `mvn test -pl . -Dtest="*Critic*,*EndToEnd*,*RoundOptimization*"` 确保不破坏现有测试 |

---

## 六、完成后

1. 截图 `mvn test` 通过结果
2. 更新 `docs/面试准备/复习任务推进表.md` 中行动项 1 为 `[x]`
3. 把 `evaluate()` 方法的新逻辑记入面试话术：「Coordinator 的决策现在有两条路径：approved 布尔值做快速通过，maxSeverity 阈值做兜底判决——severity≤2 直接通过，≥3 触发重写」