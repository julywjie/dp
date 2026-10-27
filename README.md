# DP Agent — JavaFX × LangGraph 桌面智能助手

JavaFX 桌面程序：内部以 **LangGraph4j** 编排 Agent（ReAct 循环：`agent 调用模型 → 条件边 → action 执行工具 → 回环 → END`），支持**多提供商大模型随时切换**，API Key 以 **AES-GCM 加密**保存在本机。对话区为气泡式界面，助手消息按 Markdown 渲染，支持**浅色 / 深色主题**一键切换。内置 **JSON / XML 数据格式化工具**，也可由 Agent 调用。

**会话与安全**：左侧历史会话栏（切换 / 删除 / 重启恢复）；上下文超限时自动生成摘要并保留近期消息；工具调用按风险确认（智能模式 / 全部确认），每次调用写入本地审计日志。

**AI 热榜**：启动默认进入「发现」页，分两个标签——「AI 热榜」展示 Hacker News 与 Hugging Face 各自按真实热度排序的 20 条；「中文资讯」按时间列出 Google News 中文内容。点击条目在应用内**新选项卡**中阅读（WebView），也可一键改用系统浏览器。

## 技术栈

| 层 | 组件 | 版本 |
|---|---|---|
| UI | JavaFX Controls / WebView | 21.0.12 (LTS) |
| 消息渲染 | commonmark-java（原生 JavaFX 节点，非 WebView） | 0.28.0 |
| LLM 接入 | LangChain4j（open-ai / anthropic 模块） | 1.22.0 |
| Agent 编排 | LangGraph4j AgentExecutor | 1.9.6 |
| 构建 | Maven / JDK | 3.9+ / **17** |

## 运行

```bash
mvn javafx:run
```

要求 `JAVA_HOME` 指向 JDK 17+（Maven 使用的 JVM）。由于使用 WebView，推荐始终通过 `mvn javafx:run` 启动，由插件处理 JavaFX 模块路径；classpath 方式需要自行补上 `javafx-web` / `javafx-media`。classpath 启动示例：

```bash
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
%JAVA17_HOME%\bin\java -cp "target/classes;<target/cp.txt 内容>" com.dp.agent.Launcher
```

## 快速开始

1. 启动默认进入 **AI 热榜**：Hacker News（10 条）与 Hugging Face（10 条）分两组按真实热度排序；切到 **中文资讯** 按时间查看中文内容。点条目在应用内新建网页选项卡阅读，选项卡顶部支持前进 / 后退 / 刷新，右上角可改用系统浏览器。点左侧「新会话」或任意历史会话回到聊天
2. 点击右上角 **⚙ 设置**
3. 左侧选择提供商（内置预设：DeepSeek / 通义千问 / 智谱 GLM / Kimi / OpenAI / OpenRouter / Ollama 本地 / Anthropic Claude），右侧填入 **API Key**，按需修改模型名与**上下文窗口**，点 **保存**
4. 顶部下拉框切换模型，输入消息回车即对话
5. 对话中可观察 Agent 轨迹：`🔧 决定调用工具 …` → `✅ 工具 … 返回 …` → 最终回答
6. **左侧「新会话」** 开始新对话；历史会话可点击切换、悬停删除，重启后自动恢复最近会话
7. 执行中可点 **停止**（在途 HTTP 请求需等到自身超时才中断）
8. 右上角 **🌙 / ☀** 切换深浅主题，选择会自动保存到本机配置
9. 输入框右下角 **`<>` 图标**打开数据格式化工具：粘贴 JSON / XML 自动识别，一键格式化或压缩 JSON，结果可复制；Ctrl+Enter 快捷格式化。对模型说「格式化下面这段 JSON」也会调用同一套工具

### 新增自定义提供商

设置 → **➕ 新增**：任何 OpenAI 兼容端点（自建网关、vLLM、One-API 等）填 `Base URL + 模型名 + Key` 即可；Anthropic 原生协议选「Anthropic」类型。Ollama 本地端点无需 Key。

## 界面细节

- **执行过程折叠**：同一轮的工具轨迹合并成一行「执行过程 (N)」，默认收起，点击展开
- **自适应正文宽度**：消息宽度随窗口变化（约 480–760px），Markdown 表格列多时横向滚动而不是撑破气泡
- **文章深色阅读**：深色主题下打开文章默认注入深色阅读样式，工具栏按钮可随时切换回原色
- **发现页来源时间**：热榜状态分别显示 HN / HF 的最近成功时间，失败来源标注「缓存」；两个标签页的按钮统一为「刷新」
- **内联 AI 总结**：每个 Tab 顶部有「总结」，用当前模型生成简洁要点，结果以可折叠卡片直接展示在列表上方，支持复制与收起
- **文章级总结**：每条热榜 / 中文资讯右侧有「总结」按钮，抓取正文后生成单篇总结；Google News 包装链接会先解析为发布方地址再抓正文。正文不可用时自动降级为「基于标题」的谨慎概括，卡片会标注「仅标题」、显示失败原因并提供「打开原文」；降级结果不缓存，可重试。正文会发送给当前配置的模型提供商，抓取仅允许公网 http/https
- **会话搜索**：左侧历史会话支持按标题搜索，并保留今天 / 昨天 / 更早分组
- **消息操作**：助手消息悬停可复制；最后一条回答可重新生成（复用原问题，不重复插入用户消息）
- **设置页测试连接**：保存前可用当前 URL / Key / 模型发一条最小请求，验证连通性；Key 输入框下方显示「已保存 / 未配置 / 将保存新 Key」
- **网络代理**：设置页支持 HTTP / SOCKS 全局代理（暂不支持代理认证）；保存后新请求生效，必要时重启应用

## 长期记忆 / 通用记忆

- **入口**：顶栏星形按钮，或快捷键 `Ctrl+M`
- **长期记忆**：每轮成功回答后，用当前模型异步提取（最多 5 条，低温、不重试）；去重、裁剪后保存。下次对话按相关度召回（中文二元组 + 英文单词 + 重要度/新鲜度），注入系统提示
- **通用记忆**：手动维护，对所有会话生效，每次对话优先注入（受约 1000 字符预算限制）
- **召回预算**：相关长期记忆优先约 60% 字符预算，通用记忆占用剩余空间（至少保留一条），避免通用记忆把相关内容挤出上下文
- **合并与冲突**：相似记忆去重时保留信息更完整的文本；注入时附带更新时间，并提示模型以较新的记忆为准
- **检索上下文**：使用「上一轮回答 + 上一轮问题 + 当前问题」作为 query，改善追问场景
- **管理**：支持添加、编辑、删除、置顶、搜索、按类型清空；可分别关闭「启用记忆」和「自动提取长期记忆」
- **存储**：`%APPDATA%\dp-agent\memory\memories.json`（明文，仅保存在本机）
- **边界**：检索目前是本地关键词/二元组匹配，未使用 embedding；自动提取会额外调用一次当前模型（按 token 计费）；记忆内容可能包含对话中的事实，建议定期在记忆库中检查/删除

## Key 存储说明（坦率的边界）

- 配置位于 `%APPDATA%\dp-agent\config.json`，Key 以 **AES-128-GCM** 加密（`gcm:` 前缀 Base64）
- 加密密钥在同目录 `secret.key`，首次运行随机生成
- **能防**：配置文件被单独拷贝 / 误发他人 / 明文落盘被扫盘
- **防不了**：对本机有完整控制权的攻击者（密钥与密文同机）。这是桌面工具的常规取舍
- 泄露应急：删除该提供商重新录入 Key 即可（会生成新密文）

## 会话、上下文与审计

- **会话文件**：`%APPDATA%\dp-agent\sessions\<id>.json`，明文 JSON（含对话内容，不含 API Key）；启动时恢复最近会话
- **上下文预算**：取「上下文窗口 − 最大输出 − 固定余量」作为输入预算；估算超过 90% 时，用当前模型把较早消息压缩为摘要，保留最近约一半预算的消息。摘要失败自动降级为截断，摘要进度随会话保存，重启不会重复压缩
- **工具确认**：设置 →「安全与工具」可选「智能」或「全部确认」。智能模式下，`ToolRegistry` 白名单内的只读工具直接执行，新增/未知工具先弹窗确认；120 秒未响应按拒绝处理
- **审计文件**：`%APPDATA%\dp-agent\audit\tool-calls.jsonl`，每行包含时间、会话、工具名、参数（截断 800 字符）、审批结果、耗时与执行结果

## AI 热榜

- **AI 热榜标签（真实热度）**：Hacker News 近 7 天 AI 热帖（points + 2×评论，时间衰减）与 Hugging Face Daily Papers（upvotes），各取前 10 条，分两组呈现，**不做跨来源分数比较**
- **中文资讯标签（时间序）**：Google News 中文「人工智能」RSS 最新 20 条，明确标注“按发布时间排序 · 不代表热度”；过滤涨停/概念股等行情噪音
- **刷新**：启动时先渲染缓存，再后台刷新；同一会话 5 分钟内重复进入不重复请求；单个来源失败不影响其他来源，状态栏会标注
- **阅读**：点击条目在应用内新选项卡打开，使用 JavaFX WebView；同一 URL 复用选项卡，最多保留 8 个网页标签；提供前进/后退/刷新与「在浏览器中打开」兜底
- **缓存**：`%APPDATA%\dp-agent\discover\hot.json`（30 分钟 TTL，热榜与中文资讯分开存储）
- **边界**：依赖公网访问；HN / Google News 在部分网络环境可能不可用。WebView 是 JavaFX WebKit 内核（非 Chromium），少数站点可能渲染不佳，可用工具栏按钮转到系统浏览器。Google News RSS 按其声明仅用于个人阅读

## 行情

窗口右下角提供「行情」按钮，**点击才展开**行情卡（按标题栏的收起按钮可再次收缩）：**上证指数 / 恒生科技 / 富时中国A50 / 日经225 / 韩国KOSPI / 沪深300 / 创业板指 / 科创50 / 中证2000 / 美元离岸人民币（USD/CNH）**。聊天页贴在输入框上方，发现页贴窗口底部。

- A 股与港股指数每 **3 秒**后台刷新；美元离岸人民币、涨跌家数与富时中国A50 / 日经225 / 韩国KOSPI 每 **30 秒**刷新；源站异常时自动指数退避，避免触发风控
- 每个指标一行五列：**名称 / 量比 / 成交额 / 点位 / 涨跌幅**（带 ↑↓ 箭头）；成交量（股数）、成交额单位等细节收进悬停提示；汇率行显示四位小数的即期报价（如 6.6720）；底部显示沪深北三市**涨跌家数**
- 量比与成交额列缺失时显示「—」：恒生科技与美元离岸人民币无成交额；富时中国A50 / 日经225 源站不提供成交数据；韩国KOSPI 有量比但成交量单位与 A 股不一致，不展示以免误导
- 标题栏显示**行情源时间**，超过 5 分钟标为琥珀色；行悬停可看各自的数据时间；右侧 ⟳ 可手动立即刷新（2 秒节流）
- 数据源：腾讯财经为主、新浪财经兜底（上证指数、沪深300、恒生科技指数、创业板指、科创50）；东方财富多节点自动切换并记住上次成功节点（中证2000、富时中国A50、日经225、韩国KOSPI、涨跌家数）；美元离岸人民币以新浪财经为主、东方财富兜底，以兼容部分代理/TUN 网络下节点被断连的情况
- 富时中国A50 / 日经225 / 韩国KOSPI 为盘中实时快照，非开市时段显示收盘值；腾讯/新浪不提供这几个指数的稳定代码，仅东方财富可用
- 涨跌配色遵循 A 股习惯：红涨绿跌；单个数据源失败时保留其余指标，全部失败时保留上一份数据并标记「离线」
- 行情仅供参考，不构成投资建议；接口为公开免费接口，可能受网络环境影响

## 扩展 Agent 工具

在 `core/AgentTools.java` 中新增一个 `@Tool` 方法即可被自动扫描注册，无需改编排层：

```java
@Tool("查询指定城市的天气")
public String weather(String city) { ... }
```

新工具默认属于「未知工具」：智能模式下会先弹窗确认。确认它确实只读且无副作用后，可把方法名加入 `core/tool/ToolRegistry.java` 的 `SAFE_TOOLS` 白名单。

## 工程结构

```
com.dp.agent
├─ App / Launcher          入口与启动器
├─ config/                 AppConfig · ProviderConfig · ConfigStore · CryptoUtil · ContextDefaults
├─ core/                   ModelFactory · AgentService(LangGraph 编排) · AgentTools · DataFormatter
├─ core/context/           TokenEstimator · ContextPlanner(预算决策) · ContextCompactor · LlmSummarizer
├─ core/tool/              ToolRegistry(扫描注册) · GuardedToolExecutor(审批/审计) · ToolAuditLog · ToolApprovalGate
├─ session/                SessionStore(原子读写) · SessionManager(生命周期) · SessionData
├─ discover/               DiscoverService(聚合/异步) · ArticleRanker(配额混排) · HN/HF/GoogleNews 源 · DiscoverCache
├─ chat/                   Conversation(双轨 + 摘要状态) · UiMessage
├─ ui/                     ChatView · TopBar · ThemeManager · ChatController · SessionSidebar · DiscoverView · ArticleTab(WebView) · MessageBubbles · ToolApprovalCoordinator · SettingsDialog · FormatDialog
├─ ui/markdown/            MarkdownRenderer(块级) · InlineRenderer(行内)
└─ resources/.../          app.css · controls.css · markdown.css · settings.css · format.css · icon/
```

## 运行稳定性与指标

- **工具异常隔离**：除用户取消外，工具抛出的异常会转换成 `isError` 工具结果返回给模型，ReAct 可继续自我恢复，不再终止整轮
- **工具执行超时**：每个工具调用最多等待 30 秒，超时返回错误结果并写入审计日志
- **停止 watchdog**：点击停止后若 20 秒仍在等待在途 HTTP 退出，会给出明确反馈、结束 UI 忙碌态并释放提交锁，允许开始新请求；旧任务继续在后台退出，但其回调会被丢弃，后台线程不会被强制杀死
- **每轮指标**：「执行过程」末尾记录耗时、步数、工具调用次数、记忆命中数和估算 tokens，同时写入运行日志
- token 为启发式估算（模型响应未暴露 usage），用于趋势观察，不等同于提供商计费

## 已知边界（v1）

- 输出默认 **token 级流式**（打字机效果）；顶部「更多」菜单中的「流式输出」可关闭，退化为按 agent/action 步骤回报的节点级轨迹
- 「停止」对已发出的 HTTP 请求依赖其超时返回；界面与审计会立即反映中断
- 工具调用循环有 60 步熔断保护，防止模型陷入工具死循环
- 会话内容与工具参数以**明文**保存在 `%APPDATA%\dp-agent`（API Key 仍单独加密）
- 不支持多实例同时写同一会话；不做会话搜索与云同步
