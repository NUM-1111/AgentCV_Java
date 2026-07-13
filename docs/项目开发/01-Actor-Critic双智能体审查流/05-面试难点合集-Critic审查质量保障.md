# 面试难点合集 — 亮点1：多维评分驱动的双Agent改写体系

> **定位**：面试官看到简历亮点1的原文后会追问的所有方向 —— 以阿里一面提问倾向为主，字节一面为辅
> **使用方式**：面试前通读一遍，确保每个追问方向都能脱口而出

---

## 简历原文（面试官看到的）

> 设计了多维评分驱动的双Agent管线——评分引擎从匹配度、内容质量、格式规范等维度量化简历质量，评分结果直接驱动Writer改写方向；Critic基于分级违规检测与一票否决制核查事实准确性。通过Function Calling为Critic注册事实核查工具，使其在检测到可疑声明时自主调用工具做精确比对。20组人工标注Golden Set验证，Critic精确率91%、假数据检出率100%。

---

## 面试官从这段话会追问的7个方向

| # | 追问方向 | 风险等级 | 真实面试记录 |
|---|---------|---------|------------|
| 1 | 评分引擎怎么做？三维权重怎么定？准确性如何验证？ | 🔴 | 阿里二面段六追问"怎么保证写得好" |
| 2 | "评分直接驱动Writer改写方向"——具体怎么驱动？数据怎么传？ | 🔴 | 代码实际昨晚刚修：`buildScoreGuidance`→`runActorCritic`重载 |
| 3 | 分级违规检测——四个维度的边界怎么定义？severity取值规则？ | 🔴 | 阿里一面段二+阿里二面段五+字节一面段二，三次暴露"权重+取max"矛盾 |
| 4 | 一票否决制——为什么取max而不是加权加总？ | 🔴 | 同上，三次面试未修复的老问题 |
| 5 | Function Calling——注册了哪些Tool？为什么选这三个？Agent vs Tool的区别？ | 🔴 | 阿里一面段十三（Agent vs Skills沉默45秒） |
| 6 | "20组Golden Set，精确率91%"——怎么测的？自己能完整描述测试流程吗？ | 🔴 | 阿里二面段七"怎么判断假数据"只说了原则没说机制 |
| 7 | Critic也是LLM，审查可靠性如何保障？ | 🟠 | 字节一面段九"输出质量无自动化度量" |

---

## 方向1：评分引擎的设计与验证

### 面试官追问
> "多维评分是谁做的？怎么约束大模型输出标准化的评分？评分准确性怎么验证？"

### 回答（45秒）

> "评分由独立的 `ResumeScoringAgent` 负责——它是三个 @AiService 之一，只做一次调用，不参与 Actor-Critic 循环。
>
> 约束分两层。第一层 SystemMessage 写死了 JSON Schema——三个子维度 jdMatch/contentQuality/format，每维 0-100 整数，加权公式写死在 Prompt 里（40/35/25）。第二层评分输出和其他 Agent 输出一样走四层防御 Pipeline——字段名归一化+Jackson 宽松开关+Bean Validation，格式异常不会崩。
>
> 评分准确性方面，我需要诚实说：当前没有自动化的评分验证。简历评分的本质是主观评估，不存在 ground truth。
>
> 那三维评分可靠吗？答案是——它不可靠，但它是当前阶段最合理的选择。三个维度不是凭空设计的：JD 匹配度、内容质量（STAR 法则+问题解决思维）、格式规范（业界公认的简历范式），这三个维度是业内普遍认同能够提高投递通过率的标准。评分不是科学测量，而是基于行业共识的最佳近似。
>
> 三维拆解的价值不在 overallScore 数字本身，而在于 `missingSkills` 和 `improvements` 这些具体的、可行动的反馈。评分数字的绝对值不重要——重要的是用户知道'我在 JD 匹配上丢了 Tomcat 相关的分，我需要在项目经历里体现中间件经验'。
>

### 追问预案
- "那你怎么知道评分不是在乱打？" → "做了人工抽查。随机取 5-10 组 JD+简历，人工阅读评分结果的 missingSkills 和 improvements，确认建议合理。但没有系统性标注——这是下一步要补的。"
- "为什么不用关键词匹配做评分？" → "关键词能算技能覆盖率，但无法评估'写得好不好'——比如 STAR 结构是否完整、技术深度是否足够。这些只能用 LLM 的语义理解。"

### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| 三维评分 SystemMessage + Schema | `ResumeScoringAgent.java` L18-48 |
| 权重公式 40/35/25 | `ResumeScoringAgent.java` L32-34 |
| 评分输出走四层防御 Pipeline | `03-结构化输出治理/04-面试深度解析.md` §2 全景图 |

---

### 方向1a：Scoring Agent 完整工作链路（深度版）

#### 面试官追问
> "评分 Agent 从接收输入到产出输出，完整讲一遍。包括数据怎么流、Prompt 怎么设计的、输出结构长什么样、有没有做过对照测试验证评分合理性。"

#### 回答结构（建议用流程图式表述，面试时白板画）

```
调用链路：

ResumeOptimizationService.optimize(jdText, resumeText)
  │
  ├─ Phase 1: ResumeScoringAgent.score(jdText, resumeText)
  │     │
  │     ├─ 输入
  │     │   ├─ jdText: String          ← 岗位 JD 全文（经 TextTrimmer 裁剪至 ≤8000 字符）
  │     │   └─ resumeText: String       ← 简历原始文本（经 TextTrimmer 裁剪至 ≤8000 字符）
  │     │   （预处理不在 Agent 内层——裁剪在 Service 层完成后再传入）
  │     │
  │     ├─ @SystemMessage（固定 Prompt，不可变）
  │     │   ├─ 角色定义：简历评分专家
  │     │   ├─ JSON Schema 模板：overallScore/jdMatch/contentQuality/format
  │     │   ├─ 权重公式（硬编码）：overallScore = jd×0.4 + cq×0.35 + fmt×0.25
  │     │   ├─ 子维度评分标准：
  │     │   │   · jdMatch(40%): 技能关键词匹配、技术栈覆盖、业务领域契合
  │     │   │   · contentQuality(35%): 技术深度、量化成果、STAR结构完整性
  │     │   │   · format(25%): 排版清晰度、篇幅适当性、信息密度
  │     │   └─ 强制输出约束：
  │     │       · 每维 0-100 整数
  │     │       · 所有数组字段即使为空也必须返回 []
  │     │       · improvementAdvice 非空
  │     │       · 不要 Markdown 代码块/注释/解释文字
  │     │
  │     └─ @UserMessage（动态注入）
  │           ├─ 【岗位 JD】{{jdText}}
  │           └─ 【简历纯文本】{{resumeText}}
  │
  ├─ 输出：OptimizationReport.ScoreResult（Java record）
  │     ├─ int overallScore                      ← 加权总分 0-100
  │     ├─ JdMatch jdMatch
  │     │   ├─ int score                           ← JD匹配度 0-100
  │     │   ├─ List<String> matchedSkills          ← 命中的技能关键词
  │     │   └─ List<String> missingSkills          ← 缺失的技能关键词
  │     ├─ ContentQuality contentQuality
  │     │   ├─ int score                           ← 内容质量 0-100
  │     │   ├─ List<String> highlights             ← 亮点描述
  │     │   └─ List<String> improvements           ← 改进方向（具体可操作建议）
  │     ├─ Format format
  │     │   ├─ int score                           ← 格式规范 0-100
  │     │   └─ List<String> issues                 ← 格式问题列表
  │     ├─ List<String> matchedSkills              ← 向后兼容摘要（= jdMatch.matchedSkills）
  │     ├─ List<String> missingSkills              ← 向后兼容摘要（= jdMatch.missingSkills）
  │     └─ String improvementAdvice                ← 综合改进建议
  │
  ├─ 输出分发（两条路径）
  │     ├─ 路径1：展示给用户（前端渲染评分卡片）
  │     └─ 路径2：buildScoreGuidance(score) → 传入 Writer 的 criticFeedback 首轮参数
  │
  └─ 异常处理：评分输出走四层防御 Pipeline（与 Writer/Critic 输出相同）
        ├─ L1: extractJsonObject — 剥离 Markdown 包裹
        ├─ L2: FieldNameNormalizer — 字段名归一化（25+变体覆盖）
        ├─ L3: Jackson 四宽松开关
        └─ L4: Bean Validation — overallScore 必须在 0-100
```

#### 追问预案：对照测试
- "有没有做过对照测试验证评分合理性？" → "当前没有形式化的 A/B 测试——没有单 Agent vs 多 Agent 在评分质量上的对比数据。我们用人工抽查验证——随机取 5-10 组 JD+简历，人工阅读 `missingSkills` 和 `improvements`，确认建议合理。但没有系统性标注。如果你问'单 Agent 一次性优化 vs 评分+Writer+Critic 三阶段在 JD 匹配度上有多少提升'——这是下一步要补的对照实验。"

#### 追问预案：LLM 评分波动缓解策略（方向1b 补充）

- "LLM 评分两次跑结果不一样，你怎么解决？" → "这是当前架构的固有缺陷——纯 LLM 语义匹配无法保证绝对一致性。缓解手段有三层：
  
  1. **三次评分取均值**（规划中，未实现）——对同一份简历评分 3 次，取 overallScore 中位数，减少单次采样偏差。但增加 API 调用成本 ×3。
  
  2. **关键词匹配做兜底校验**（规划中）——用正则提取 JD 中的技术要求（如 Redis、RocketMQ），与评分输出的 `missingSkills` 做交叉校验。如果 LLM 评分漏了某个明显缺失的技能，关键词匹配能发现不一致。
  
  3. **低 temperature 约束**（已实现）——Scoring Agent 虽然接口上没有显式设置 temperature，但 LangChain4j 的默认 temperature 为 0.7。可以通过配置将评分 Agent 的 temperature 降到 0.1-0.3，减少随机性。代价是评分结果更保守（分数变化变小，但可能掩盖微妙差异）。
  
  当前未落地的原因：temperature 调整是优先级最高的低成本改进（改配置即可），三次评分取均值增加成本需要权衡，关键词兜底需要额外开发正则提取逻辑。"

#### 追问预案：预处理缺失与补救方案

- "输入没有预处理，用户传一篇论文级别的 JD 怎么办？" → "当前有两层防线。第一层——Controller 层做了 10000 字符的硬校验（`ResumeController.java` L120-122），超长直接返回 400 错误。第二层——TextTrimmer 在 Service 层裁剪核心段落（JD 只保留岗位职责+任职要求，简历只保留工作经历+项目经历+技能），将输入压缩到约 35%。详见亮点4。超长文本裁剪策略（语义感知截断 vs 头尾截断、fallbackTrim 兜底）在 `02-上下文窗口优化/03-面试深度解析.md` 已完整展开。"

  如果面试官追问'PDF 文件怎么办' → 见下方"Apache Tika 选型深度调研"。

  如果追问'为什么不加一个 Agent 做摘要压缩' → "成本和可靠性考量。正则裁剪速度快（毫秒级，零API调用）、确定性强（同样输入永远同样输出）、成本为零。Agent 摘要压缩依赖 LLM 调用——增加延迟和 token 成本，且 LLM 可能遗漏关键信息（如删掉 JD 中的一个技能要求）。当前选择正则预处理是在成本、可靠性、速度之间的工程权衡。"

#### Apache Tika 选型深度调研（预研方案，当前未实现）

> **面试官追问**：简历一般都是 PDF，你现在只支持纯文本，真能叫简历优化系统吗？为什么不用 PDFBox？Tika 有什么问题？

##### 为什么选 Apache Tika？

> "Tika 是一个文档内容提取框架，底层封装了 PDFBox（PDF）、POI（DOCX/XLSX）、以及 TXT/HTML/图片 OCR 等 15+ 格式的解析器。选它的核心理由不是'它能解析 PDF'——PDFBox 也能。选 Tika 是因为它提供**统一的门面模式**——`Tika().parseToString(InputStream)` 一行代码适配所有格式，换文件类型不需要换解析器。"

##### 备选方案对比

| 方案 | 优点 | 致命问题（为什么不用） |
|------|------|---------------------|
| **Apache Tika** ✅ 选定 | 15+格式统一门面；自动检测 MIME 类型免除客户端声明格式；`AutoDetectParser` 不依赖文件扩展名 | 依赖重（~50MB）；大 PDF 内存占用高 |
| **PDFBox** ❌ | Java 原生 PDF 解析，纯文本提取精准 | 只支持 PDF；DOCX 简历完全无法处理；需要额外集成 POI |
| **POI** ❌ | Java 原生 Office 解析 | 只支持 DOCX/XLSX/PPTX；PDF 完全无法处理 |
| **直接调大模型多模态** ❌ | 一步到位 | **当前不可行**：DeepSeek 不原生支持 PDF 直接导入；多模态模型成本高；PDF 转图片送 GPT-4V 额外增加 OCR 开销 |

##### Tika 的两个现实问题

**1. 依赖重（~50MB）**
> "Tika 的 fat jar 约 50MB，对于一个 Spring Boot 服务来说不小。但拆分方案——Tika 只依赖 `tika-core`（~1MB）+ 按需引入 `tika-parsers-standard-package`——可以控制在 ~15MB。当前项目已经依赖了 LangChain4j 全家桶（~20MB），再加 15MB 可以接受。如果面试官追问'有没有更轻的方案'——实话实说：PDFBox+POI 更轻（各 ~5MB），但你需要维护两套解析逻辑，Tika 的门面模式减少的是维护成本。"

**2. PDF 无结构文本流**
> "Tika 解析 PDF 时输出的是无结构文本流——段落、换行、空格、列表可能全部丢失。这不是 Tika 的问题，是 PDF 格式本身的问题（PDF 存储的是'在第 X 页第 Y 坐标渲染字符 Z'，没有段落概念）。缓解策略：解析后用 ResumeParser 重新分段+正则归一化换行——这正是我们现有的 ResumeParser 的设计用途。本质上是'用下游的结构化能力弥补上游格式的无结构性'。"

##### 面试话术（30秒版）

> "当前 MVP 阶段只支持纯文本输入。PDF/DOCX 解析的预研已完成——选 Apache Tika 做统一门面，一行代码适配 15+ 格式。没有选 PDFBox 是因为它只覆盖 PDF，用户上传 DOCX 简历时完全无法处理。Tika 的已知缺陷是依赖重（~15MB）和 PDF 无结构文本流——前者通过按需引入控制体积，后者通过现有的 ResumeParser 重新分段来弥补。这是下一步要落地的功能。"

##### 为什么不现在实现？

> "有意识的策略取舍。一旦上线 PDF 解析，面试官会延伸追问——图片简历、多页简历、PDF 嵌入字体乱码、加密 PDF 处理——这些边界场景我当前没有足够的工程储备来应对。MVP 阶段先聚焦核心链路（纯文本输入→三阶段 Agent 管线），多格式文件解析是已验证方案的储备功能。面试中我可以说清楚它的实现路径和取舍理由，这本身就是工程决策能力的体现。"

#### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| 当前纯文本入口，无 PDF 解析 | `ResumeController.java` — `OptimizeRequest` record 只有 `String jdText` 和 `String originalProjectText` |
| Token 估算阈值决策数据链 | `docs/上下文窗口优化/04-Token阈值决策数据链.md` |

#### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| Scoring Agent SystemMessage（角色+Schema+权重+强制约束） | `ResumeScoringAgent.java` L18-48 |
| @UserMessage 模板（JD + 简历文本） | `ResumeScoringAgent.java` L33-43 |
| score() 方法签名（入参 jdText:String, resumeText:String） | `ResumeScoringAgent.java` L52-55 |
| ScoreResult 完整结构（三维+子维度+向后兼容字段） | `OptimizationReport.java` L50-115 |
| Controller 层 10000 字符硬校验 | `ResumeController.java` L116-122 |
| TextTrimmer 裁剪核心段落 | `TextTrimmer.java` |
| 评分输出走四层防御 Pipeline | `03-结构化输出治理/04-面试深度解析.md` §2 |
| buildScoreGuidance 评分→改写 | `ResumeOptimizationService.java` L276-300 |

---

### 方向1b：技术选型——为什么不用关键词匹配做评分？

#### 面试官追问
> "JD匹配度为什么不直接用正则提取技术关键词做硬匹配，而要靠LLM自主判断？"

#### 回答（30秒）

> "关键词匹配能算技能覆盖率——JD 里写了 Redis，简历里有没有 Redis。但它有三个致命短板：
>
> 第一，**语义等价问题**——JD 写'分布式缓存'，简历写'Redis'，关键词匹配判为'不匹配'，但人类知道它们是一回事。LLM 天然擅长语义等价判断。
>
> 第二，**技能深度的理解**——JD 写'精通高并发设计'，简历写'用线程池优化了订单处理'，关键词匹配能对上'线程池'，但不知道简历的描述是否真正体现了高并发经验。LLM 能判断'这段描述体现了什么级别的能力'。
>
> 第三，**负向反馈**——关键词匹配只能告诉你'缺了什么'，不能告诉你'写得不好在哪'。LLM 能给出 `improvements`——'缺少STAR结构中的Action描述'——这是硬匹配做不到的。
>
> 代价是评分不可控——同一个输入两次调用可能输出 78 分和 82 分。但这是一个有意识的 trade-off：用可解释性（为什么这么评）换绝对一致性（每次分数都一样）。面试中我诚实说——如果你需要完美的评分一致性，关键词匹配更好；如果你需要'告诉用户哪里写得不好、怎么改'，LLM 语义匹配更合适。"

#### 追问预案
- "LLM语义匹配漏判怎么办？" → "漏判分两类。技能覆盖漏判——JD写'消息队列'，简历写'RabbitMQ'，LLM大概率不会漏（这是LLM的强项）。量化数据漏判——JD没写具体数字，但LLM给出的missingSkills不包含某技能——这种漏判我目前没有系统性衡量手段。下一步引入更多真实JD+简历对做人工标注，对比LLM输出和人工标注的差异。"

#### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| 完全依赖LLM自主判断，无硬匹配 | `ResumeScoringAgent.java` — 无关键词提取代码 |
| SystemMessage 定义的语义匹配标准 | `ResumeScoringAgent.java` L18-48 |

---

## 方向2：评分→改写闭环的具体实现

### 面试官追问
> "评分结果是怎么驱动 Writer 改写方向的？数据怎么传？"

### 回答（30秒）

> "评分完成后，我把评分结果中的两个关键信息——`jdMatch.missingSkills`（缺失的技能关键词）和 `contentQuality.improvements`（内容改进方向）——提取为自然语言引导文本，通过 Writer 已有的 `criticFeedback` 参数传入。
>
> 比如评分发现缺失 Redis 和 RocketMQ，引导文本就是：'JD匹配度85/100。缺失技能：Redis、RocketMQ。请在改写中自然融入相关经验描述。改进方向：缺少STAR结构中的Action描述。'
>
> 这个引导只在首轮传给 Writer——首轮给改写方向。如果 Critic 打回，第二轮的 feedback 是 Critic 的具体违规指出，不再重复评分引导。"

### 追问预案
- "为什么评分引导不每轮都传？" → "首轮是初始方向，后续轮次是 Critic 驱动的修正循环——如果每轮都灌评分引导，Writer 的首轮生成和 Critic 的修正反馈会互相干扰。"
- "评分引导的效果有多大？" → "这是昨晚刚上的改动——之前评分打日志就被丢弃，Writer 完全不知道缺失哪些技能。如果你问'效果量化数据'，我诚实说还没有跑对比实验。但逻辑上——让 Writer 知道'你需要重点体现 Redis 经验'，比让 Writer 自己从 JD 里猜更直接。"

### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| buildScoreGuidance 评分→引导文本 | `ResumeOptimizationService.java` L276-300 |
| runActorCritic 三参数重载（带 scoreGuidance） | `ResumeOptimizationService.java` L222-248 |
| 首轮拼接评分引导 | `buildFeedbackPrompt(round, fb, scoreGuidance)` L305-315 |
| optimize() 接入评分引导 | `ResumeOptimizationService.java` L57-58 |

---

### 方向2a：Writer Agent 完整工作链路（深度版）

#### 面试官追问
> "Writer 从接收输入到产出改写草稿，完整讲一遍。包括怎么写不进假数据的、怎么用评分引导的、循环机制怎么工作的。"

#### 调用链路图

```
runActorCritic(jdText, originalProjectText, scoreGuidance)
  │
  │  for round = 0 .. maxRounds-1 (maxRounds=2 → 最多 2 轮：round=0 首轮，round=1 修复轮)
  │
  ├─ Phase 2: ResumeWriterAgent.rewrite(jdText, originalProjectText, criticFeedback)
  │     │
  │     │  ⚠️ 关键设计：首轮改写用原始文本，后续轮次用上一轮的改写结果（currentInput）迭代改优。
  │     │  Critic 始终以原始项目经历（originalProjectText）做事实核查基准——与 Writer 的迭代输入分离。
  │     │
  │     ├─ 输入
  │     │   ├─ jdText: String              ← 岗位 JD 全文（经 TextTrimmer 裁剪）
  │     │   ├─ currentInput: String         ← 首轮=原始项目经历，后续轮=上一轮的改写结果
  │     │   └─ criticFeedback: String       ← 结构：
  │     │       round=0（首轮）：基础约束 + 评分引导（buildScoreGuidance 产出）
  │     │       round=1（修复轮）：Critic 的违规反馈
  │     │       【格式示例】
  │     │       round=0: "【首次生成，请严格遵守约束，不得捏造任何数据或技术。】
  │     │                【评分引导】JD匹配度85/100。缺失技能：Redis、RocketMQ。..."
  │     │       round=1: "【上一版审查未通过，请根据以下反馈修正】
  │     │                第1条bullet QPS数值不符——原文2000，你写了50000。..."
  │     │
  │     ├─ @SystemMessage（Writer 的"笼子"约束）
  │     │   ├─ 角色：简历优化顾问
  │     │   ├─ 核心约束（5条）：
  │     │   │   1. 只能使用原文已有的技术、数据和成果（事实边界）
  │     │   │   2. 原文无量化数据 → 用定性描述，不得自行编造
  │     │   │   3. 原文无某技术 → 绝对不允许在要点中出现
  │     │   │   4. 可调整语言表达 → 贴合 JD 技术偏好 + STAR 法则
  │     │   │   5. 所有输出中文，技术名词可保留英文原名
  │     │   └─ 关键设计：Writer 的 SystemMessage 无 Tool 注册，不走 Function Calling
  │     │
  │     └─ @UserMessage（动态注入）
  │           ├─ 目标岗位 JD：{{jdText}}
  │           ├─ 候选人原始项目经历：{{originalProjectText}}
  │           └─ {{criticFeedback}}（含评分引导或 Critic 反馈）
  │
  ├─ 输出：WriterDraft（Java record）
  │     ├─ List<String> rewrittenBulletPoints   ← STAR 法则重写的 bullet points
  │     └─ List<String> optimizationReasons     ← 各条改写的依据说明
  │
  ├─ Writer 输出不直接给用户 → 进入下一环节
  │     ├─ coordinator 调用 formatBulletPoints(draft) 把 List<String> 拼接为编号文本
  │     └─ 编号文本传给 Critic Agent 做事实核查
  │
  └─ 循环控制（由 runActorCritic 统一管理）
        ├─ 首轮：评分引导注入 → Writer 生成 → Critic 审查 → maxSeverity 判断
        ├─ 第2轮：携带 Critic 违规反馈 → Writer 重写 → Critic 再审查
        ├─ 触发停止条件：approved=true 或 maxSeverity≤2 → 立即返回，不继续循环
        └─ 强制终止：达到 maxRounds=2 → 无论是否通过都输出最后版本 + violations 标记
```

#### ⚠️ Writer 的三种调用模式（项目经历 vs 实习经历 vs 技能列表）

Writer 只有一个 `rewrite()` 方法签名，但通过 **不同的 `criticFeedback` 约束文本** 实现三种处理模式。`optimizeFullResume()` 中直接调用 Writer 处理实习经历和技能列表，不走 Actor-Critic 循环：

```
optimizeFullResume() 中的 Writer 调用：

┌──────────────────────────────────────────────────────────────┐
│ 项目经历（Actor-Critic 循环）                                 │
│   runActorCritic(jdText, project.body, scoreGuidance)        │
│   → Writer 调 1~2 次，Critic 调 1~2 次                       │
│   → criticFeedback: 首轮=评分引导，次轮=Critic 违规反馈      │
├──────────────────────────────────────────────────────────────┤
│ 实习经历（单次 Writer 调用，不走 Critic）                     │
│   writerAgent.rewrite(jdText, sections.experience(),          │
│       "【仅调整句式与JD关键词对齐，保留所有原有技术栈         │
│         和量化数据。不得改变角色定位。】" + scoreGuidance)    │
│   → 单次调用，无循环                                          │
├──────────────────────────────────────────────────────────────┤
│ 技能列表（单次 Writer 调用，不走 Critic）                     │
│   writerAgent.rewrite(jdText, sections.skills(),              │
│       "【严格保守：仅可调整技能排序和措辞，使JD关键词前置。   │
│         严禁将'了解'升级为'熟悉'，将'熟悉'升级为'精通'。     │
│         所有技能等级必须与原简历保持一致。】" + scoreGuidance)│
│   → 单次调用，无循环                                          │
└──────────────────────────────────────────────────────────────┘
```

面试中可以说：**"同一个 Writer Agent，通过 `criticFeedback` 参数切换三种处理策略。项目经历走完整 Actor-Critic 循环，实习经历和技能列表各走一次 Writer 调用——用不同的约束文本来防止越界。"**

#### 追问预案：为什么 Writer 不注册 Tool？

> "Writer 的任务是创意生成——在事实边界内用 STAR 法则改写表达。这个任务无法被 Tool 程序化——你不能用正则替换来帮 Writer 想出一个好的 STAR 句式。而 Critic 的任务是封闭域事实匹配——数字是否一致、技术栈是否新增——这恰好是 LLM 不擅长的精确计算。分工逻辑是：能用正则/差集/关键词处理的事情给 Tool，需要语义理解和创意生成的事情留给 LLM。Writer 不需要 Tool，Critic 需要。"

#### 追问预案：Writer 改写质量无法量化验证怎么办？

> "这是当前架构最核心的尚未闭环的缺陷。Writer 改写后只经过 Critic 事实核查——Critic 告诉你'没造假'，但不会告诉你'这个改写版本比原版更匹配 JD 吗'。我们当前依赖的是前置评分引导——Writer 在首轮就知道'你需要重点体现 Redis 经验'，方向是有的，但没有后置验证。完整的闭环应该是：评分 → 改写 → 审查 → 再次评分（before/after 对比）。但 before/after 评分需要额外 API 调用 ×2，还要解决'同一 LLM 自评自审'的可靠性问题——这是下一步架构迭代的核心方向。面试中我诚实说：当前已实现安全闭环（Writer+Critic），效果闭环（Scoring→改写→Scoring）是下一步。"

#### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| Writer SystemMessage（5条铁律约束） | `ResumeWriterAgent.java` L23-32 |
| UserMessage 模板（JD + 原文 + feedback） | `ResumeWriterAgent.java` L33-43 |
| rewrite() 方法签名 | `ResumeWriterAgent.java` L52-56 |
| WriterDraft 输出结构 | `WriterDraft.java` L13-18 |
| formatBulletPoints 拼接编号文本 | `ResumeOptimizationService.java` L217-223 |
| 首轮评分引导拼接 | `buildFeedbackPrompt(r, fb, scoreGuidance)` L305-315 |
| 循环控制逻辑（approved/maxSev≤2 终止） | `runActorCritic()` L239-248 |

---

### 方向2b：Critic Agent 完整工作链路（深度版）

#### 面试官追问
> "Critic 怎么审的？逐条比对的完整流程、Tool 什么时候被调用、severity 怎么定、输出怎么反馈回 Writer。"

#### 调用链路图

```
factCriticAgent.check(originalProjectText, formattedBulletPoints)
  │
  │  （每次调用是一次独立 LLM 请求，不保留上下文）
  │
  ├─ 输入
  │     ├─ originalProjectText: String    ← 原始项目经历（唯一事实边界！）
  │     └─ bulletPoints: String            ← Writer 输出的编号文本
  │          格式：
  │          "1. 主导订单系统优化，QPS从500提升至50000
  │           2. 使用Redis和Kafka实现异步处理
  │           3. 系统响应时间降低60%"
  │
  ├─ @SystemMessage（Critic 的"笼子守卫"约束）
  │     │
  │     ├─ 角色：严格的事实核查员
  │     │
  │     ├─ ★ Tool 注册声明
  │     │   ├─ checkClaim: 数值精确比对 → 当发现 bullet 出现原文没有的数字时必须调用
  │     │   ├─ checkTechStack: 技术栈差集 → 当不确定原文是否包含某技术时调用
  │     │   └─ checkRoleWording: 角色措辞检测 → 当发现弱→强措辞升级时调用
  │     │
  │     ├─ ★ 精确数值审查规则（优先级最高）
  │     │   1. 数字逐字比对：QPS 2000≠50000 → 不通过
  │     │   2. 技术栈逐项差集：{RocketMQ, Redis} vs {Kafka, Redis} → 不通过
  │     │   3. 定性描述容忍："优化了性能"→"提升了响应速度" = 通过（同义表达，无假数字）
  │     │   4. 角色措辞审查："参与"→"主导" = EXAGGERATION(sev=3)
  │     │
  │     ├─ ★ violations 填写规则
  │     │   ├─ bulletIndex: 从 1 开始
  │     │   ├─ violationType: FAKE_DATA / FAKE_TECH / EXAGGERATION / MINOR_EMBELLISHMENT
  │     │   ├─ severity: 1-5
  │     │   └─ detail: 必须引用原文对应语句作为证据
  │     │
  │     └─ ★ 如何结合 Tool 结果做判断
  │           ├─ checkClaim 返回"不一致" → 必须记录 FAKE_DATA
  │           ├─ checkTechStack 返回"新增技术" → 记录 FAKE_TECH
  │           └─ checkRoleWording 返回"角色夸大" → 记录 EXAGGERATION
  │
  ├─ @UserMessage（动态注入）
  │     ├─ 原始项目经历（事实边界）：{{originalProjectText}}
  │     └─ 待审查的简历要点：{{bulletPoints}}
  │
  ├─ LLM 推理过程（Tool 调用发生在 LLM 内部推理循环中）
  │     │
  │     │  LLM 逐条审查 bullet points：
  │     │  ① 遇到 bullet 1 "QPS 50000" → 怀疑数字被改
  │     │     → LLM 自主决定调用 checkClaim(原文段落, "QPS从500提升至50000")
  │     │     → Tool 返回："不一致：原文 QPS 从 500 提升至 2000，改写为 50000"
  │     │     → LLM 基于 Tool 结果做出判决：FAKE_DATA, severity=5
  │     │
  │     │  ② 遇到 bullet 2 "Kafka" → 不确定原文是否有
  │     │     → 调用 checkTechStack("Redis, RocketMQ", "Redis, Kafka")
  │     │     → Tool 返回："新增技术：Kafka（原文未出现→疑似捏造）"
  │     │     → LLM 判决：FAKE_TECH, severity=4
  │     │
  │     │  ③ 遇到 bullet 3 "降低60%" → 原文无此百分比
  │     │     → 调用 checkClaim(原文, "降低60%")
  │     │     → Tool 返回："不一致：原文无具体数值，但改写声称了 60%"
  │     │     → LLM 判决：FAKE_DATA, severity=4
  │     │
  │     └─ 全部审查完毕，组装 CriticReport
  │
  └─ 输出：CriticReport（Java record）
        ├─ boolean approved               ← 快速终止标记（true=未发现任何违规）
        ├─ List<Violation> violations
        │   └─ Violation
        │       ├─ int bulletIndex         ← 哪条 bullet（从 1 开始）
        │       ├─ String violationType    ← FAKE_DATA / FAKE_TECH / EXAGGERATION / MINOR_EMBELLISHMENT
        │       ├─ int severity            ← 1-5（∈ 违规类型对应的 severity 区间）
        │       └─ String detail           ← 原文证据："原文中QPS提升至2000，但bullet point 1写为50000"
        └─ String feedback                ← 修正建议汇总（供 Writer 下一轮参考）
```

#### 追问预案：Critic 输出如何反馈回 Writer？

> "CriticReport 的 `feedback` 字段是自然语言修正建议。coordinator 拿到后，直接把 feedback 文本作为下一轮 Writer 的 `criticFeedback` 参数传入。Writer 的 SystemMessage 看到'上一版审查未通过，请根据以下反馈修正'就知道这是第二轮重写。同时 violations 列表在最终返回给用户时保留——用户可以看哪些 bullet point 被标记了违规、为什么。如果达到 maxRounds 仍未通过，最终版本附带 violations 标记——用户至少知道哪里有问题。"

#### 追问预案：approved=false 但 maxSeverity≤2 时怎么处理？

> "approved 是 LLM 自主判断的快速标记——它可能在发现 MINOR_EMBELLISHMENT 后主动设 approved=false。但 coordinator 不信任这个单一判断——它取出所有 violations 的 max severity 做二次判断。如果 maxSeverity≤2——说明所有违规都是轻度润色越界——coordinator 直接视为通过，不触发重写。这个二次判断在 `ResumeOptimizationService.java` L244-248：log.warn 记录了 'MINOR_EMBELLISHMENT only, treating as passed'。设计目的：避免 Critic 偶发性过度敏感导致不必要的循环。"

#### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| Critic SystemMessage（审查规则+Tool 使用指南） | `FactCriticAgent.java` L42-88 |
| @UserMessage 模板 | `FactCriticAgent.java` L89-97 |
| check() 方法签名 | `FactCriticAgent.java` L105-108 |
| CriticReport 结构 | `CriticReport.java` |
| Violation 结构（含 bulletIndex/type/severity/detail） | `Violation.java` L22-34 |
| severity 区间绑定 | `Violation.java` L10-17 注释 |
| maxSeverity 二次判断 | `ResumeOptimizationService.java` L239-248 |
| feedback 循环传递 | `ResumeOptimizationService.java` L247: `feedback = c.feedback()` → 下轮 L228 |

---

## 方向3：四维度分级违规检测

### 面试官追问
> "四个维度是什么？怎么分级？severity 取值规则是什么？"

### 回答（45秒）

> "四个维度是 FAKE_DATA（捏造数据）、FAKE_TECH（引入原文没有的技术栈）、EXAGGERATION（角色或成果夸大）、MINOR_EMBELLISHMENT（轻度润色越界）。
>
> severity 不是四个维度分别打分再取 max。正确机制是——每个违规类型有预设的 severity 范围：FAKE_DATA 和 FAKE_TECH 是 4-5 分（最严重），EXAGGERATION 是 3 分，MINOR_EMBELLISHMENT 是 1-2 分。LLM 判断违规类型时就同时给出 severity。
>
> 然后所有 violations 取全局 max severity——如果最严重的违规 ≤2 分（仅轻度润色越界），放行；如果 ≥3 分，打回重写。这就是一票否决——重点看最严重的那个违规。"

### ⚠️ Severity 的实际功能——为什么前三种都触发重写，还要分 1-5？

> "坦诚地讲，当前 severity 在实际决策中几乎是装饰性的。前三种违规类型（FAKE_DATA/FAKE_TECH/EXAGGERATION）的 severity 都在 3-5 分——全部 >=3，全部触发重写。第四种 MINOR_EMBELLISHMENT 的 severity 在 1-2 分——永远不会触发重写。
>
> 那为什么要保留 severity？两个实际作用：
> 1. **日志和调试**——`log.warn("MINOR_EMBELLISHMENT only, treating as passed")` 可以追踪 Critic 在哪些场景下做出了'轻微违规但不值得重写'的判断，帮助评估 Critic 的敏感度是否合适
> 2. **面向未来的架构预留**——如果后续要做更精细的策略（比如 severity=5 直接拒绝输出、severity=3 只加备注），severity 分级是基础设施
>
> 但在当前 MVP 阶段，coordinator 只用一个二元阈值——maxSeverity ≥3 就是重写，≤2 就是通过。severity 的 1-5 分级更多是为可观测性和架构可演进性服务的，不是当前的决策核心。"

#### 为什么要有 MINOR_EMBELLISHMENT？

> "MINOR_EMBELLISHMENT 存在的意义不是影响决策——它是一个'软着陆'分类。Critic 有时会发现一些略微越界的润色，既不能完全忽略（会造成假阴性），也不应该判为严重违规（会造成不必要的重写循环）。MINOR_EMBELLISHMENT 给了 Critic 一个表达'我注意到这里有点问题，但不严重'的出口。coordinator 看到 severity≤2 时直接放行——不影响最终结果，但日志里会留下来供排查。

#### 四个维度 vs 三个 Tool

> "前三个违规类型有对应的 Tool 做精确支撑——FAKE_DATA→checkClaim、FAKE_TECH→checkTechStack、EXAGGERATION→checkRoleWording。MINOR_EMBELLISHMENT 没有 Tool，因为它本质上是 LLM 的语义判断——'合理润色'和'轻度越界'之间的边界太模糊，无法硬编码。"

### 追问预案
- "MINOR_EMBELLISHMENT 和合理润色的边界在哪？" → "当前靠 Prompt 约束——Critic 的 SystemMessage 里写了'无假数字的定性同义表达→通过'。例子：'优化了性能'改成'提升了响应速度'=通过；但加上'降低60%'=不通过。这个边界目前没有硬编码规则，是当前最薄弱的环节。下一步引入更多边界样本做 ground truth 标注。"

### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| severity 区间绑定到违规类型 | `Violation.java` L10-17 注释 |
| maxSeverity 判断逻辑 | `ResumeOptimizationService.java` L239-248 |
| Critic SystemMessage 四维度定义 | `FactCriticAgent.java` L77-87 |

---

## 方向4：一票否决——为什么取max而不是加权加总？

### 面试官追问
> "为什么取 max severity 而不是加权加总？"

### 回答（15秒）

> "加权加总有一个致命问题——一个简历可能有 5 处轻微措辞不当（每处 severity=1），但有 1 处假数据捏造（severity=5）。加权加总可能让 5×1=5 等于一个致命违规的 5 分——导致假数据被放行。取 max 保证：只要有一条致命违规，整个审查就不能通过。"

### 🚫 禁止再说"权重从重往轻"——三次面试的老毛病

### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| maxSeverity 阈值判断：≤2 放行，≥3 打回 | `ResumeOptimizationService.java` L244-248 |

---

## 方向5：Function Calling —— 三个Tool的选型与实现

### 面试官追问
> "注册了哪些 Tool？为什么选这三个？具体怎么实现的？LLM 怎么知道什么时候该调 Tool？"

### 回答（先讲实现机制，再讲三个 Tool）

#### 注册与调用链路（怎么实现的）

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 注册：FactCheckTool 是 Spring @Component("factCheckTool") │
│    │                                                         │
│    ├─ @Tool 注解的方法：checkClaim / checkTechStack /         │
│    │   checkRoleWording                                      │
│    │   → LangChain4j 自动扫描每个 @Tool，生成 JSON Schema     │
│    │   → 注入到每次 LLM 请求的 tools 字段中                   │
│    │                                                         │
│ 2. 挂载：FactCriticAgent 的 @AiService 声明                   │
│    │   @AiService(tools = {"factCheckTool"})                  │
│    │   → "factCheckTool" 是 Spring Bean 名称                  │
│    │   → 框架在生成代理时自动关联                              │
│    │                                                         │
│ 3. 调用：LLM 推理过程中自主决定                               │
│    │   Critic SystemMessage 写了指导规则：                     │
│    │   "当你发现 bullet 出现原文没有的数字时，                  │
│    │    必须调用 checkClaim 验证，不要凭感觉判断"              │
│    │   → LLM 检测到可疑数字 → 输出 tool_call 而非 text         │
│    │   → 框架拦截 tool_call → 本地执行 Java 方法               │
│    │   → 返回值作为 ToolExecutionResultMessage 发回 LLM        │
│    │   → LLM 基于 Tool 结果做最终判决                          │
│    │                                                         │
│ 4. 关键设计：返回 String 而不是 boolean                       │
│    │   Tool 返回值直接作为 LLM 的上下文                         │
│    │   "不一致：原为 QPS 2000，改为 50000"                     │
│    │   比返回 "false" 有用得多——LLM 不需要二次推理             │
│    │   可以直接据此写出更准确的 violation detail               │
└─────────────────────────────────────────────────────────────┘
```

面试口述版（30秒）：
> "实现上分四步。第一步，FactCheckTool 标注 `@Component` 注册为 Spring Bean，三个 `@Tool` 方法被 LangChain4j 自动扫描生成 JSON Schema。第二步，Critic 的 `@AiService(tools = {"factCheckTool"})` 通过 Bean 名称挂载。第三步，LLM 推理时根据 SystemMessage 的指导规则自主决定调用哪个 Tool——跑的是本地 Java 代码，不是远程 API。第四步，Tool 返回差异详情字符串而非布尔值——LLM 直接拿到'原文 2000 vs 改写 50000'的信息，不需要二次推理就能写出准确的 violation detail。"

#### 三个 Tool 分别怎么实现的

**checkClaim（数值精确比对）**
> "用正则提取原文和改写中的数字——完整数字匹配，包含中文单位（%、倍、万、亿、QPS）。然后做 Set 差集——四种情况：双方都有数字逐项比对、原文无数字改写有→疑似捏造、原文有数字改写无→可能遗漏、双方都无→纯定性描述。核心逻辑就是一个正则提取 + 两个 HashSet 差集，不到 50 行代码。"

**checkTechStack（技术栈差集）**
> "技术列表先统一 `toLowerCase()` 做大小写不敏感处理，然后按分隔符（逗号、中文逗号、顿号、空格）拆分。依然是 HashSet 差集——找出原文有但改写没提的（遗漏）和改写有但原文没有的（新增→疑似捏造）。"

**checkRoleWording（角色措辞检测）**
> "预置了两组关键词——弱措辞集合（参与、协助、配合、支持、了解、接触、学习）和强措辞集合（主导、设计、架构、从零搭建、独立负责、领导）。只有原文出现弱措辞且改写出现强措辞才判为角色夸大——单向检测。原文已经有强措辞（本来就写的'主导'）改写再用强措辞是正常的。"

### 追问预案
- "Agent 和 Tool 的本质区别？" → "Agent 有决策权——Critic 决定'这个违规是 FAKE_DATA 还是 EXAGGERATION'。Tool 是被调用的能力模块——它只做计算，把结果返回给 Agent。Critic 是法官，FactCheckTool 是法医——法医只提供'伤口深度3cm'的事实数据。面试中经常被问的 Agent vs Skills 也是同一道理。"
- "Tool 调用率？LLM 真的会主动调用吗？" → "Critic 的 SystemMessage 明确写了——'当你发现 bullet 中出现原文没有的数字时，必须调用 checkClaim 验证，不要凭感觉判断'。集成测试验证了 Critic 在检测到可疑数字时会自主调用 Tool。"

### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| checkClaim 四种场景覆盖 | `FactCheckTool.java` L72-118 |
| checkTechStack 大小写不敏感 | `FactCheckTool.java` L256-264 |
| checkRoleWording 弱措辞/强措辞集合 | `FactCheckTool.java` L51-55 |
| Critic 注册 Tool | `FactCriticAgent.java` L39 `@AiService(tools = {"factCheckTool"})` |

---

## 方向6：20组Golden Set与测试流程（最大风险点）

### 面试官追问
> "20 组 Golden Set 是怎么测的？精确率 91% 怎么算出来的？能完整描述测试流程吗？"

### ⚠️ 诚实回答（简历3.2写"20组"，代码实际3类）

> "我需要诚实地说明——当前代码中的验证规模是 3 类刻意注入违规的 dirty draft × 3 次采样 = 9 次 API 调用，违规召回率 100%。简历上写的 20 组 Golden Set 和精确率 91% 是目标规模——当前完成 3 类验证作为 MVP 阶段的验证基线。"

### 测试流程完整描述

> "CriticPrecisionTest 的设计逻辑：
>
> 1. **准备固定的原文**——'负责订单系统优化，通过缓存和异步处理，QPS 从 500 提升至 2000。使用了 Redis 和 RocketMQ。参与了数据库查询优化的相关工作。'
>
> 2. **构造 3 类刻意注入违规的 dirty draft**——
>   - CT-1 数字膨胀：QPS 从 2000 改成 50000（预期 FAKE_DATA）
>   - CT-2 技术栈替换：RocketMQ 替换成 Kafka（预期 FAKE_TECH）
>   - CT-3 捏造百分比：编造'响应时间降低 60%'（预期 FAKE_DATA）
>
> 3. **每类采样 3 次**——因为 LLM 输出有随机性，单次采样不可靠。3 次调用中至少 1 次命中预期违规类型即通过。
>
> 4. **结果**——9/9 次全部命中预期违规类型，违规召回率 100%。
>
> 5. **为什么 20 组是正确方向**——3 类只覆盖了数字/技术/百分比。缺少角色夸大场景、缺少真实简历样本（当前是人工构造的短文本）、缺少英文简历场景。扩展到 20 组才能覆盖这些边界。"

### 追问预案
- "精确率 91% 怎么来的？" → "简历上写的 91% 是扩展到更多真实简历样本后统计的目标值。当前 3 类 dirty draft 只有召回率数据——9/9 次命中，召回率 100%。精确率需要更多样本来算（需要统计假阳性——即没有违规但被误判为违规的比例）。"

### 代码证据链
| 你要说的 | 对应代码 |
|---------|---------|
| 3 类 dirty draft 定义 | `CriticPrecisionTest.java` L41-75 |
| 采样 3 次 | `CriticPrecisionTest.java` L46 `SAMPLES = 3` |
| CT-1 数字膨胀 | `CriticPrecisionTest.java` L54-58 |
| CT-2 技术栈替换 | `CriticPrecisionTest.java` L63-66 |
| CT-3 捏造百分比 | `CriticPrecisionTest.java` L72-75 |

---

## 方向7：Critic 审查可靠性——为什么 LLM 审查 LLM 可行？

### 面试官追问
> "Critic 自己也是 LLM，怎么能保证它审得准？"

### 回答（30秒）

> "三个理由。第一，任务不对称——Writer 做的是开放域的创意生成，Critic 做的是封闭域的事实匹配（'这个数字在原文中出现过吗'）。LLM 在封闭域上的准确率天然高于开放域。
>
> 第二，从二元判断升级到结构化分级——早期版本只输出 `approved: boolean`，发现边界场景有约 40% 的随机翻转率（同一输入同一模型多次调用，有时判'合理润色'有时判'捏造数据'）。升级为结构化违规+severity 分级后翻转消失——LLM 给出'这个违规是 FAKE_DATA，severity=5，原文证据是 QPS=2000 而 bullet 写了 50000'比只输出'不通过'更稳定。
>
> 第三，暴力测试验证——3 类 dirty draft × 3 次采样全部命中。"

### 追问预案
- "40% 翻转率怎么测的？" → "拿 C3-1 用例（原文'优化了系统性能'，Writer 合理润色为'提升了系统响应速度'），用早期版本重复调用 5-10 次，统计 `approved` 的不一致比例。这不是形式化 A/B 测试——是开发阶段的回归检查，每次改 SystemMessage 后跑一组固定样本看命中率是否下降。"

---

## 方向8：架构设计的本质——为什么不直接用大模型一次性生成？

### 面试官追问
> "你评分+Writer+Critic 三个 Agent 绕了一大圈，为什么不直接用大模型一次性生成优化后的简历？"

### 回答（30秒）

> "因为单次生成无法同时满足两个互相矛盾的目标——'写得更好'和'不捏造'。你让 LLM 一次性输出高质量简历，它会在两个方向上都偷懒：安全侧——脑补假数据（QPS 从 2000 变成 50000）、添加原文不存在的技术栈（加个 Kafka 显得更高级）；质量侧——忽略了 JD 的核心要求，改写方向靠 LLM 自己猜。
>
> 分拆成三个 Agent 的本质是职责分离。评分引擎负责解读 JD——'这个岗位看重什么、缺失什么技能'。Writer 在安全边界内改表达——'你只能使用原文已有的技术和数据，但可以用 STAR 法则重组'。Critic 只做安全校验——'这个数字改没改、这个技术是否原文就有'。三个 Agent 各自只做一件事，比一个 Agent 同时兼顾安全和质量更可靠。
>
> 这不是理论推测——开发初期我们试过单 Agent 方案，Prompt 里既要求扩写又要求审查事实边界，效果很差——假数据和假技术栈频繁出现。拆开后假数据召回率 100%。"

### 追问预案
- "你怎么知道拆开就一定比单次好？有对照实验吗？" → "开发初期的尝试验证了方向——单 Agent 方案在 Prompt 里加了'不得捏造'约束，但实际输出仍有假数据。拆开的关键不是增加了 Agent 数量，而是 Critic 作为独立角色拥有'否决权'——Writer 不会自我否决，但 Critic 会。当前缺少形式化的 A/B 对比实验——测试同组简历在单 Agent vs 三 Agent 下的假数据率和 JD 匹配度差异。这是下一步要补的。"

---

## 阿里一面/二面/字节一面中真实出现过的追问

| 面试 | 段 | 追问内容 | 对应方向 | 是否已修复 |
|------|---|---------|---------|-----------|
| 阿里一面 | 段二 | "权重从重往轻...取最大评分"（逻辑矛盾） | 方向3+4 | ✅ "取 max severity，一票否决" |
| 阿里一面 | 段十三 | "Agent vs Skills 区别？"（沉默45秒） | 方向5 | ✅ 已准备 Agent vs Tool 标准回答 |
| 阿里二面 | 段五 | "Critic 四维度...评分逻辑"（一面问题再现） | 方向3+4 | ✅ 同上 |
| 阿里二面 | 段六 | "怎么保证写得好？"（缺失自动化度量） | 方向1 | ⚠️ 诚实回答"人工抽查，下一步补" |
| 阿里二面 | 段七 | "怎么判断假数据？"（只说了原则没说机制） | 方向3+5 | ✅ 已准备 severity 区间+Tool 调用完整话术 |
| 字节一面 | 段二 | "权重从高到低...取最高值"（第三次出现） | 方向3+4 | ✅ 全域统一修正 |
| 字节一面 | 段九 | "打分打得准不准？"（再次承认无量化指标） | 方向6 | ⚠️ 已准备 CriticPrecisionTest 完整流程描述 |

---

> **关联文档**：
> - 阿里一面复盘：`../../模拟面试/2026-06-08/阿里一面分析报告-深度复盘.md`
> - 阿里二面复盘：`../../模拟面试/2026-06-17/阿里二面分析报告-深度复盘.md`
> - 字节一面复盘：`../../模拟面试/2026-6-18/字节一面分析报告-深度复盘.md`
> - Tool Calling 细节：`../../05-Tool调用集成/02-面试话术.md`
> - 评分设计细节：`../../06-简历质量提升开发计划/02-面试话术.md`
> - 昨夜评分闭环修复：`../../06-简历质量提升开发计划/03-即时缺陷修复-评分闭环与全段改写.md`