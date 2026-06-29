# 蒋毅武

> **求职意向**：Agent应用研发工程师 / 后端开发工程师
> **电话 / 邮箱**：18346023102 · 2943603313@qq.com
> **教育背景**：哈尔滨工程大学 · 软件工程 · 2027届
> **英语能力**：CET-6，具备良好的英文技术文档阅读、翻译与理解能力

---

## 项目经历

### AgentCV / Job Agent（基于大模型的求职场景智能助手）

> **核心开发者** · Java 17, Spring Boot 3.2, LangChain4j 0.36, Spring AOP, Jackson, Jakarta Validation

面向求职场景设计并实现的AI-Native后端服务，支持JD解析、人岗匹配评估、简历项目经历重写等核心能力。系统以LangChain4j @AiService为核心编排层，通过精密Prompt工程建立数据契约，将非结构化求职文本转化为结构化的匹配报告与改写建议。

**个人工作：**

- **输入侧工程化治理 (Reliability)**：针对长文本上下文溢出问题，在匹配链路中实现基于14种标题关键词正则的业务层文本裁剪（职责/要求/工作经历/项目经历），配合本地保守Token预估算与超限前置拦截机制，并建立Token预算约束校准管道（`TokenBudgetCalibrationTest`）自动验证裁剪上限与拦截阈值间的数学约束，有效控制调用成本。
- **输出鲁棒性与漂移治理 (Resilience)**：针对大模型结构化输出格式漂移问题，设计并实现"原始响应JSON提取 → 字段名归一化 → Jackson宽容反序列化 → Bean Validation校验"的标准化解析链路，结合@JsonAlias注解提升转换稳定性。
- **事实边界控制**：设计并实现基于双Agent协同的重写与校验工作流。由ResumeWriterAgent生成改写草稿，FactCriticAgent基于原始经历进行逐条合规核查（4类违规+severity 1-5分级），并在业务层配置3轮迭代硬上限与降级兜底机制，有效拦截虚构技术栈的风险。
- **Agent Function Calling 能力集成**：基于LangChain4j @Tool注解为审查智能体注册结构化事实核查工具，使模型可自主决策核查时机与参数，推动架构从"固定流程编排"向"Agent自主决策"演进。
- **输入通道拓展与Token策略化**：基于Apache Tika实现PDF/DOCX等真实文件格式的自动解析与纯文本提取；将Token预估模块重构为策略模式，支持启发式算法与模型原生tokenizer的配置化切换，为多模型适配预留基础。
- **单服务可观测性 (Observability)**：基于Spring AOP技术实现核心业务链路的统一切面监控；利用OncePerRequestFilter配合MDC机制注入唯一traceId并透传至响应头，实现单次请求粒度的日志闭环追踪与问题快速复盘。

---

### IntelliVault（多租户智能知识库与RAG问答平台）

> **核心开发者** · Java 17, Spring Boot 3, Spring AI, PostgreSQL, MongoDB, Milvus, Docker

面向企业多租户场景的RAG（检索增强生成）智能问答平台。系统以Spring AI为AI编排框架、Milvus为向量检索引擎，实现从多格式文档自动解析、智能切片、向量化入库到语义检索与流式生成的全链路闭环，支持租户级数据隔离与知识库生命周期管理。

**个人工作：**

- **RAG检索增强生成全链路**：主导基于Spring AI的文档解析、智能重叠切片（Chunking）与高性能嵌入向量化方案落地。依托Milvus IVF_FLAT + COSINE向量索引算法搭建垂直知识库检索闭环，实现海量文档的低延迟语义检索。
- **检索召回质量调优与幻觉控制**：精细化调优RAG生成效果，引入5条历史消息动态上下文窗口机制，设计双阈值降级检索策略（0.45→0.2）进行意图补偿；配合严格Prompt工程（前置角色定义、背景约束与未召回兜底话术），显著收敛大语言模型的无依据虚构与幻觉风险。
- **多租户隔离与向量全生命周期管理**：针对企业多租户场景，深入应用Milvus标量过滤（Scalar Filtering）机制，通过JSON_EXTRACT实现多租户知识库ID与文档状态的严密数据逻辑隔离；设计向量清理联级组件，确保文档删除时对应向量索引得到彻底同步清理，消除孤立数据残留。

---

## 技术能力

- **AI 应用开发**：熟练掌握RAG与Agent Workflow全流程开发；具备任务分解、数据契约设计、Prompt Engineering与大模型结构化输出约束的工程落地经验；熟悉基于双Agent协同的事实校验机制设计，正在推进Agent Tool/Function Calling的架构升级

- **工程化治理（LLM侧）**：具备大模型应用可靠性与防御性编程意识；熟练掌握本地Token预估、动态上下文规则裁剪、Jackson宽容反序列化及请求级MDC日志追踪等生产环境常见问题的治理方案

- **Java 后端开发**：Java基础扎实，熟悉线程池参数调优与并发编程；熟练使用Spring Boot 3、Spring AI、LangChain4j快速构建AI-Native后端服务，具备将LLM调用、向量检索、Agent编排等AI能力工程化落地的Java侧实践经验

- **数据存储与架构**：掌握PostgreSQL、MongoDB、Milvus等异构数据库的分层存储建模；具备向量数据库标量过滤（Scalar Filtering）及基于知识库ID的数据逻辑隔离与生命周期管理实战经验

- **工程素养**：熟悉Linux常用操作及Git协作规范；能够熟练利用Docker / Docker Compose进行服务的容器化环境搭建与本地快速部署

  ---

## 证书与附加信息

- **语言能力**：英语CET-6，具备良好的英文技术文档阅读、翻译与理解能力