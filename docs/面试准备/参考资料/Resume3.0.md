蒋易武 18346023102 <2943603313@qq.com> 2027 届（本科）
求职意向：Agent 应用研发工程师 / 后端开发工程师

教育背景
哈尔滨工程大学（211） 2023.09 - 2027.06
软件工程 | 本科 | 学位：工学学士（在读）

项目经历

### 项目一：AgentCV（基于大模型的求职场景智能助手）

**岗位**：核心开发者 | 技术栈：Java 17, Spring Boot 3.2, LangChain4j 0.36, Spring AOP, Jackson, Jakarta Validation

#### 项目简介
通用大模型改写简历时会凭空虚构技术栈。针对这一核心痛点，设计并实现了基于 Actor-Critic 双 Agent 架构的事实边界控制系统，覆盖 JD 解析、人岗匹配评估、简历改写等完整链路。

#### 技术亮点

1. **双 Agent 事实对齐架构**：Writer 负责改写，Critic 按 4 类违规（severity 1-5 分级，max-severity 一票否决）逐条核查，3 轮迭代硬上限 + 降级兜底。全程自动化无人工介入，有效拦截虚构技术栈风险。

2. **大模型输出稳定性治理**：构建"JSON 提取→字段名归一化→Jackson 宽容反序列化→Bean Validation 校验"四层解析 Pipeline，结合 @JsonAlias 解决大模型格式漂移问题。输入侧基于 14 种正则裁剪 + 10,000 token 超限拦截，配合 TokenBudgetCalibrationTest 自动验证约束边界。

3. **Agent Function Calling 与自主决策**：基于 @Tool 为 Critic 注册事实核查工具（数值比对 / 技术栈差集 / 角色措辞检测），使模型可自主决策核查时机，推动架构从固定流程编排向 Agent 自主演进。

4. **AI 服务可观测性**：Spring AOP 双切面（HTTP 维度 + AI 业务维度）+ OncePerRequestFilter + MDC traceId，实现单次请求粒度的全链路日志追踪。

### 项目二：IntelliVault（多租户智能知识库与 RAG 问答平台）

**岗位**：核心开发者 | 技术栈：Java 17, Spring Boot 3, Spring AI, PostgreSQL, MongoDB, Milvus, Docker

#### 项目简介
面向企业多租户场景的 RAG 智能问答平台，解决大模型在垂直知识问答中编造事实的幻觉问题。

#### 技术亮点

1. **RAG 检索全链路**：基于 Spring AI 文档解析 + 智能重叠切片 + Milvus IVF_FLAT + COSINE 向量检索，实现低延迟语义检索。

2. **检索质量与幻觉控制**：5 条历史消息动态上下文窗口 + 双阈值降级检索（0.45 → 0.2）+ 严格 Prompt 约束，有效收敛无依据虚构。

3. **多租户隔离与生命周期管理**：基于 Milvus 标量过滤（JSON_EXTRACT）实现知识库级数据逻辑隔离；级联清理组件确保文档删除时向量同步清除。

技术能力

- 熟悉 Java 及 Spring Boot、Spring AI、LangChain4j 框架，能够利用其构建后端服务与 AI 应用。
- 熟悉 RAG 全链路设计与优化，能够结合业务场景对召回效果、响应质量进行针对性优化。
- 熟悉 Agent 基本原理，了解任务规划、工具调用、流程编排等核心机制，掌握 Agent 记忆系统的基础设计思路。
- 了解大模型底层原理，包括 Transformer 架构、Attention 机制、Tokenization（BPE）等核心技术；熟悉 LoRA 微调与模型量化（Quantization）的基本流程与方法。
- 熟悉 Claude Code、CLINE 等 Agent 的使用与架构设计原理，能够利用其完成代码理解、任务拆解与开发辅助工作。

证书与附加信息

- 语言能力：英语 CET-6，具备良好的英文技术文档阅读能力。