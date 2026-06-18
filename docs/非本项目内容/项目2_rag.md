# RAG 项目面试准备稿 —— IntelliVault（模块4：RAG翻盘关）

> 基于项目代码真实提取，所有参数均与 `application.yml`、`MilvusService.java`、`ChatService.java`、`KnowledgeBaseService.java`、`MilvusConfig.java` 一致。

---

## 阶段A：RAG维度信息表（已填完）

| 维度 | 答案 |
|------|------|
| **Embedding模型** | **qwen2.5:7b**（Ollama 本地部署，同一模型同时做 Chat + Embedding） |
| **向量维度** | **3584**（qwen2.5:7b 原生输出维度，代码有 `EmbeddingDimensionValidator` 做启动时维度校验） |
| **向量数据库** | **Milvus**（`localhost:19530`，通过 `milvus-sdk-java` 直连，非 Spring AI VectorStore 封装） |
| **索引类型** | **IVF_FLAT** + **COSINE** 余弦相似度，`nlist=1024`（IVF 聚类加速检索，FLAT 保证精确搜索） |
| **向量数量** | 取决于用户上传的文档量，每个文档被 TokenTextSplitter 切分为约 N 个 chunk，无硬编码上限 |
| **Chunk大小** | Spring AI `TokenTextSplitter` 默认值：**~800 token/chunk**（默认 chunkSize=800, minChunkSizeChars=350） |
| **Chunk重叠** | `TokenTextSplitter` 默认：无显式字符重叠（Spring AI TokenTextSplitter 按 token 边界切分，默认无 overlap 参数配置） |
| **存储的具体文档类型** | **Word（doc/docx）、Excel（xls/xlsx）、PPT（ppt/pptx）、PDF、TXT、Markdown、Image（jpg/png/gif/bmp）**——见 `FileType.java` 枚举 |
| **检索Top-K** | **Top-8**，主要阈值 **0.45**，兜底阈值 **0.2**（见 `application.yml` 第117-119行） |
| **元数据字段** | docId, baseId, fileName, chunkIndex, isEnabled（写入 `metadata_json` JSON字段，支持 `JSON_EXTRACT` 过滤） |

---

## 阶段B：2分钟脱稿话术（定制版——背诵用）

> 这个RAG项目名叫 IntelliVault，解决的是**专业领域AI对话的幻觉问题**——当用户在知识库场景下问"这个工程标准的具体要求是什么"，直接用大模型可能编造标准内容。通过 RAG 先检索真实文档再生成回答，把回答锚定在用户自己上传的知识库文档上，大幅降低幻觉率。
>
> **Embedding 用的是 qwen2.5:7b，3584维**。选它的理由是：项目整体用 Ollama 本地部署 qwen2.5:7b 做 Chat，复用同一个模型做 Embedding 可以避免引入额外的模型依赖，同时 3584 维在语义表达能力和检索效率之间取得了较好的平衡。代码里专门写了 `EmbeddingDimensionValidator`，启动时自动校验 Milvus collection 的维度和配置是否一致，不一致会重建 collection。
>
> **向量数据库用 Milvus，索引类型 IVF_FLAT**——IVF 做聚类倒排加速检索，FLAT 保证聚类单元内的精确搜索，配合 COSINE 余弦相似度做语义匹配。nlist 设置为 1024，聚类质心数量适中。**和常见的 Spring AI VectorStore 封装不同，这里直接用 milvus-sdk-java 直连 Milvus**，原因是我们需要在检索时做 metadata JSON 字段的条件过滤——比如按 baseId 隔离不同知识库、按 isEnabled 过滤已禁用的文档，这些用 Spring AI 的标准 API 做不到。
>
> **文档入库流程**：文件上传 → Apache Tika 解析提取文本 → Spring AI TokenTextSplitter 切分 chunk（~800 token/chunk）→ 为每个 chunk 注入 metadata（docId、baseId、fileName、chunkIndex、isEnabled）→ 经过 MilvusDocumentSanitizer 清洗保证无空字段 → 调用 Ollama Embedding 生成 3584 维向量 → Milvus 入库。
>
> **检索流程**：用户 Query → Ollama Embedding 向量化 → Milvus ANN 检索，filter 表达式同时过滤 baseId 和 isEnabled=true → Top-8 召回，阈值 0.45；如果 Top-8 结果为空，自动用兜底阈值 0.2 重试一次 → 拼接检索到的文档片段到 System Prompt → 传给 qwen2.5:7b 生成回答。System Prompt 里专门设计了**幻觉防护规则**：严格限制只能基于上下文回答、不允许编造、不允许推测、不确定时明确告知用户。
>
> **全链路向量清理**也是一个亮点。删除文档时同步删 Milvus 向量、删除知识库时级联清理所有关联文档的向量、用户注销账号时清理所有向量数据——三条路径都做了覆盖，防止孤立向量造成隐私泄露和检索污染。
>
> 下一步方向：引入 **Reranker 做检索结果重排序**、对 **Chunking 策略做 A/B 评估**（比如对比 Token split vs RecursiveCharacter split）、考虑增量更新 Milvus metadata 以支持已入库文档的 isEnabled 状态变更实时生效。

---

## 阶段C：技术亮点 & 常见追问 Q&A

### 1. 为什么不用 Spring AI 的 VectorStore，而是直接写 MilvusService？

Spring AI 的 VectorStore 接口只提供了基础的 `similaritySearch(query, topK)` 方法，**不支持在检索时做 metadata 条件过滤**。但我们的业务需要：
- 按 **baseId** 隔离不同知识库（不能让用户A的query检索到用户B的文档）
- 按 **isEnabled** 过滤禁用的文档

所以用 `milvus-sdk-java` 直连，通过 `SearchParam` 传入 `filterExpr`——Milvus 支持 `JSON_EXTRACT` 表达式，语法如 `metadata_json["baseId"] == "123" && metadata_json["isEnabled"] == "true"`。同时直连还能控制更多参数：一致性级别设为 STRONG、检索失败自动 retry 3次 + 自动 load collection。

### 2. 向量维度 3584 是怎么确定的？如果换 Embedding 模型怎么办？

qwen2.5:7b 的 Embedding 输出就是 3584 维。代码里 `MilvusConfig` 在启动时会：
1. 检查 collection 是否存在
2. 如果存在，用反射读取 `DescribeCollection` 返回的 embedding 字段维度
3. 和 `application.yml` 里的 `embedding-dimension: 3584` 对比
4. **维度不匹配 → 自动 drop 并重建 collection + 创建索引**
5. 维度未知（读取失败）→ 不操作，保护已有数据

### 3. 检索阈值 0.45 / 0.2 是怎么定的？

0.45 是主要阈值，目的是过滤掉语义不相关的结果（余弦相似度太低说明检索到的chunk和query无关）。0.2 是兜底阈值——当主阈值无结果时自动降级重试，提升**召回率**。

实际代码逻辑（`ChatService.java:148-159`）：
```java
List<Document> similarDocuments = milvusService.similaritySearchWithBaseId(
    query, effectiveBaseId, retrievalTopK, retrievalThreshold);  // 0.45

if (similarDocuments.isEmpty() && fallbackThreshold < retrievalThreshold) {
    similarDocuments = milvusService.similaritySearchWithBaseId(
        query, effectiveBaseId, retrievalTopK, fallbackThreshold);  // 0.2
}
```

### 4. 如果 Milvus 挂了或检索失败，聊天还能用吗？

能。`ChatService` 里用 try-catch 包住了整个检索逻辑：
```java
try {
    List<Document> similarDocuments = milvusService.similaritySearchWithBaseId(...);
    // ...
} catch (Exception e) {
    log.error("Vector retrieval failed, continuing chat without context", e);
    context = ""; // 降级为无上下文的纯LLM对话
}
```
同时 `MilvusService` 内部也有 **3次重试 + auto load collection** 的容错机制。

### 5. 向量清理的覆盖面是什么？

三条清理路径全部覆盖：
- **删除单个文档**：`DocumentService.deleteDocument()` → `MilvusService.deleteChunksByDocId()`
- **删除整个知识库**：`KnowledgeBaseController.deleteKnowledgeBase()` → 遍历所有文档 → 每个调 `deleteChunksByDocId()`
- **用户注销账号**：`UserSettingsController.deleteAccount()` → 遍历所有知识库 → 遍历所有文档 → 级联删除

每条路径都有异常处理和日志，单个文档删除失败不影响其他文档。

### 6. 文档切分用的什么策略？为什么没设 overlap？

用的是 Spring AI 的 `TokenTextSplitter`（默认构造），因为它在**中文文本的处理上表现稳定**——按 token 边界切分而非粗暴按字符数截断，能更好地保持语义完整性。默认无字符级 overlap，因为 TokenTextSplitter 本身在 token 级别已有一定的语义边界感知能力。

### 7. System Prompt 怎么设计来防止幻觉？

（见 `ChatService.buildSystemPrompt()` 第406-465行）关键规则：
- **STRICTLY base your answer ONLY on the provided context** —— 严格限缩
- **DO NOT make up information, speculate, or use knowledge outside the context**
- 上下文不足时**明确告知**用户"无法找到足够信息"，并建议用户重新表述问题或上传更多文档
- 如果有部分相关、部分不相关，**区分告知**哪些能回答、哪些不能
- 建议引用原文：**"According to the context..."**

---

## 附：全链路架构速记图（面试时可画）

```
┌─────────────────────────────────────────────────────────┐
│                    Write Path（入库）                      │
│                                                         │
│  File ──→ Tika Parser ──→ TokenTextSplitter              │
│    │                         │                          │
│    │                    ~800 token/chunk                 │
│    │                         │                          │
│    │              ┌──────────▼──────────┐               │
│    │              │ Inject Metadata:     │               │
│    │              │ docId, baseId,        │               │
│    │              │ fileName, chunkIndex, │               │
│    │              │ isEnabled             │               │
│    │              └──────────┬──────────┘               │
│    │                         │                          │
│    └──→ Ollama Embedding ──→ Milvus Insert              │
│              (3584d)                                     │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    Read Path（检索）                       │
│                                                         │
│  Query ──→ Ollama Embedding (3584d)                      │
│                │                                        │
│                ▼                                        │
│       Milvus ANN Search                                  │
│       filter: baseId + isEnabled=true                    │
│       Top-8, threshold=0.45→0.2 fallback                 │
│                │                                        │
│                ▼                                        │
│       Build System Prompt (hallucination prevention)     │
│                │                                        │
│                ▼                                        │
│       qwen2.5:7b ChatModel → Streaming SSE Response      │
│                │                                        │
│                ▼                                        │
│       Persist to MongoDB (conversation history)           │
└─────────────────────────────────────────────────────────┘
```

---

> **文件生成时间**: 2026-06-17  
> **数据来源**: 项目代码 `application.yml`、`MilvusConfig.java`、`MilvusService.java`、`ChatService.java`、`KnowledgeBaseService.java`、`FileType.java`  
> **使用说明**: ①先背维度表 → ②背2分钟话术（朗读5遍）→ ③过一遍Q&A → ④找人模拟面试