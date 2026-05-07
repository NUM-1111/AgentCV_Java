# Actor-Critic 双智能体审查流

本目录记录「消灭简历重写幻觉」模块的架构设计、实现细节与面试话术。

## 文档列表

| 文件 | 内容 |
|------|------|
| [01-三处短板补丁](./01-三处短板补丁.md) | 本次同步修复的三个明确工程短板：异常映射语义错误、无输入上限、无超时配置 |
| [02-Actor-Critic架构设计与实现](./02-Actor-Critic架构设计与实现.md) | 双智能体审查流的设计动机、组件分工、编排逻辑、Trade-off 决策与面试话术 |

## 问题优先级

**P0（核心特色）** — 对应 `StageTwo.md`：「单步模型为了匹配高阶 JD，极易产生"发明数据"和"捏造技能"的幻觉」

## 核心结论

原 `ResumeRewriteService` 是纯 `@AiService` 单步调用，System Prompt 中的「化腐朽为神奇」主动激励模型捏造量化数据（QPS、性能提升百分比）和虚构技术栈，且没有任何事实核查机制。

本次通过 Actor-Critic 双智能体审查流解决：

- **WriterAgent**：按 JD 偏好扩写简历要点，System Prompt 明确禁止捏造数据和技术
- **FactCriticAgent**：以原始项目经历为唯一事实边界，逐条核查 Writer 输出的 bullet points
- **RewriteCoordinatorService**：Java 普通 Bean，串联两者，实现最多 3 轮的审查-重写循环

## 学习建议

| 顺序 | 核心文件 | 学习目标 |
|------|---------|---------|
| 1 | `model/CriticReport.java` | 理解审查结果的数据结构设计（approved + feedback） |
| 2 | `service/ResumeWriterAgent.java` | 对比旧 `ResumeRewriteService`，理解 System Prompt 约束对模型行为的影响 |
| 3 | `service/FactCriticAgent.java` | 理解以"原始文本"作为事实边界的 Critic 设计 |
| 4 | `service/RewriteCoordinatorService.java` | 理解 context 策略选择（只传 feedback 不传历史草稿）和最大轮次兜底 |
| 5 | `controller/ResumeController.java` | 观察 `X-Review-Rounds` 响应头如何暴露内部轮次信息 |
