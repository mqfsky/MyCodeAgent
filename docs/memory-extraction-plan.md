# CodeAgent 个人记忆提取与加载功能执行计划

状态：已按最终计划实现  
日期：2026-07-24

验证：`mvn clean test`（821 tests，0 failures，0 errors，3 skipped）；
`mvn -DskipTests package` 与可运行 JAR 启动检查通过。

## 1. 目标

在不阻塞主对话的前提下，每个包含新用户消息的主 AgentLoop 结束并完成会话持久化后，
异步调用一次专用记忆 subagent，尝试从最近对话中提取长期有用的信息。

记忆分为三类：

- `user`：用户明确表达的身份、职责、长期目标和知识背景。
- `feedback`：用户对 Agent 工作方式的明确纠正、偏好或希望继续保持的做法。
- `plan`：用户明确表达的未来日程和计划。

提取目标不是生成对话摘要，而是保存“未来对话仍然有用，并且无法从仓库现状重新获得”的信息。

明确不提取：

- 可以从源码推导的架构、文件路径和代码规范。
- Git 历史和近期修改。
- 调试步骤、问题原因和修复方案。
- 当前任务进度、临时待办和其他短期上下文。
- Assistant 的推测、泛泛认可、引用、假设和粘贴的第三方内容。
- 密码、Token、API Key、私钥等秘密。

只记录用户明确表达的事实，不根据提问难度、代码表现等推断用户身份或知识水平。

本地 `plan.md` 与飞书日历相互独立：

- “明天九点复习算法”可以被提取为本地计划。
- 只有用户明确要求创建飞书日程时，才调用飞书工具。
- 飞书创建成功、失败或外部修改不会自动改变 `plan.md`。

## 2. 配置与固定参数

自动记忆默认关闭，只允许通过用户级 `~/.codeagent/settings.json` 开启：

```json
{
  "memory": {
    "enabled": true,
    "timezone": "Asia/Shanghai"
  }
}
```

项目级配置不能擅自启用后台记忆提取或改变用户时区。

v1 固定参数：

- 提取模型：复用主 Agent 当前使用的模型。
- 对话范围：当前 Turn 加前 4 个 Turn。
- subagent 最大模型步骤：6。
- 对话快照最大字符数：24,000。
- 单条对话消息最大字符数：8,000。
- 单个记忆文件最大大小：128 KiB。
- `user` 和 `feedback` 单文件提示词注入预算：8,000 字符。
- CodeAgent 退出时等待记忆任务的最长时间：10 秒。

## 3. 文件路径和 Markdown 格式

### 3.1 固定路径

- `user`：`~/.codeagent/memory/user.md`
- `plan`：`~/.codeagent/memory/plan.md`
- `feedback`：`<项目根目录>/.codeagent/memory/feedback.md`

新增公共 `ProjectRootLocator`：

- 从真实 `cwd` 开始向上寻找最近的 `.git` 文件或目录。
- 找不到 Git 项目时，以启动时的 `cwd` 作为项目根。
- 现有分层记忆加载器和新个人记忆组件共用该解析器，避免读写位置不一致。

### 3.2 user.md

```markdown
# User

## 身份

- Java 后端开发

## 职责

- 负责服务端功能开发

## 长期目标

- 转向 Agent 开发岗位

## 知识背景

- 熟悉 Java 和 Spring
```

规则：

- 一级标题固定为 `# User`。
- 二级标题只能是“身份”“职责”“长期目标”“知识背景”。
- 空分类可以省略。
- 每条 bullet 只表达一个事实。
- 用户明确纠正旧事实时，替换旧内容，不同时保留冲突版本。

### 3.3 feedback.md

```markdown
# Feedback

- 解释代码时先讲整体职责，再讲关键判断和调用链
- 修改代码后需要运行相关测试
```

规则：

- 一级标题固定为 `# Feedback`。
- 每条 bullet 表达一个可以跨未来 Turn 复用的工作方式。
- “不错”“可以”等没有说明具体做法的认可不保存。
- 明确的新偏好与旧偏好冲突时，删除或改写旧偏好。

### 3.4 plan.md

```markdown
# Plan

## 2026-07-25

- [ ] [09:00] 看八股文
- [ ] [时间待定] 完善简历
- [x] [全天] 参加技术大会
- [-] [20:00] 已取消的数据库复习

## 日期待定

- [ ] 整理 Agent 学习路线
```

状态约定：

- `[ ]`：未完成。
- `[x]` 或 `[X]`：已完成。
- `[-]`：已取消。

时间约定：

- `[HH:mm]`：明确的 24 小时时间。
- `[时间待定]`：日期确定，但没有具体时间；不能转换成 `00:00`。
- `[全天]`：只有用户明确表示“全天”时才能使用。
- `## 日期待定`：存在未来计划，但无法确定日期。

其他规则：

- 日期标题必须是合法的 `## YYYY-MM-DD`，相对日期在写入时根据配置时区转换为绝对日期。
- 有确定日期的条目必须带时间、`时间待定` 或 `全天` 标签。
- 日期待定条目不强制时间标签。
- 时间过去不代表完成，只有用户明确说已完成时才能改为 `[x]`。
- 只有用户明确取消计划时才能改为 `[-]`。
- 完成和取消的计划在 v1 中继续保留，不自动归档或删除。
- 修改日期或时间时移动原条目，不额外创建重复计划。
- 无法确定用户指的是哪条计划时不猜测、不修改。

写入使用严格格式校验；读取、查询和启动展示使用宽容解析，跳过非法行并返回诊断，
不能因用户手动损坏一行 Markdown 而阻止 CodeAgent 启动。

## 4. 受限记忆文件工具

记忆 subagent 的 ToolRegistry 从空注册表创建，只注册以下两个工具。

不得注册普通文件工具、命令、MCP、Skill、飞书、`ask_user`、`agent` 或任何其他工具。

### 4.1 read_memory_file

输入：

```json
{
  "type": "user"
}
```

`type` 只允许：

```text
user
feedback
plan
```

返回：

```json
{
  "type": "user",
  "exists": true,
  "hash": "<sha256>",
  "markdown": "<原始 Markdown>"
}
```

文件不存在时：

```json
{
  "type": "user",
  "exists": false,
  "hash": "MISSING",
  "markdown": ""
}
```

### 4.2 write_memory_file

输入：

```json
{
  "type": "user",
  "expectedHash": "<read_memory_file 返回的 hash>",
  "markdown": "<完整的新 Markdown>"
}
```

返回：

```json
{
  "type": "user",
  "changed": true,
  "hash": "<新 sha256>"
}
```

约束：

- 模型不能提供路径，`type` 由宿主映射到三份固定文件。
- subagent 必须先读取对应文件，再使用读取到的 hash 写入。
- `expectedHash` 与当前文件不一致时拒绝写入，允许 subagent 重新读取并重试一次。
- `expectedHash` 只用于避免 subagent 读取后、写入前用户手动修改 Markdown 导致旧内容覆盖新内容。
- 本计划不处理多个 CodeAgent 进程同时写入，不使用跨进程文件锁。
- 同一 CodeAgent 内的记忆写入由单 worker 串行执行，不会出现两个记忆 subagent 同时写文件。
- 内容没有变化时返回 `changed=false`，不重写文件、不发送通知。
- 写入使用同目录唯一临时文件、刷盘和原子替换，避免异常中断留下半份文件。
- 不支持原子替换时写入失败，不退化成直接覆盖。
- 逐级拒绝符号链接、非普通文件、NUL、非法控制字符、超限和格式非法内容。
- 开启自动记忆功能即授权写入这三份固定文件，不再弹普通文件修改权限确认。

### 4.3 feedback 的 Git 本地属性

首次准备 `feedback.md` 时，幂等追加以下规则到仓库本地 exclude：

```gitignore
/.codeagent/memory/feedback.md
```

要求：

- 不修改受版本控制的 `.gitignore`。
- 普通仓库写入 `.git/info/exclude`。
- Git worktree 正确解析 `.git` 指针和 `commondir`。
- 文件已经被 Git 跟踪时拒绝自动写入并输出诊断，不自动执行 `git rm --cached`。
- 无 Git 仓库时正常保存文件，但返回无法配置本地忽略的非致命诊断。

## 5. 每轮“尝试提取”的行为

每个包含新用户消息的主 AgentLoop 结束并持久化后，都会异步运行一次记忆 subagent。

“每轮运行”只表示调用模型尝试判断，不表示每轮都调用记忆文件工具。

subagent 第一轮模型判断必须遵守：

1. 先只分析对话快照。
2. 判断是否存在 `user`、`feedback` 或 `plan` 候选。
3. 没有候选时直接结束，不调用任何工具。
4. 有候选时只读取相关类型的记忆文件。
5. 读取后发现内容已经存在或无需变化时直接结束，不调用写工具。
6. 只有确实需要新增、纠正、完成、取消或删除记忆时，才调用写工具。

预期调用模式：

```text
没有候选记忆
→ 0 次工具调用

存在候选，但现有记忆已经包含
→ read_memory_file
→ 不调用 write_memory_file

存在候选，并且需要修改
→ read_memory_file
→ write_memory_file
```

允许三种内部结果：

- `NO_MEMORY`：对话没有长期记忆候选。
- `NO_CHANGE`：存在候选，但文件不需要变化。
- `UPDATED`：至少一次 `write_memory_file` 返回 `changed=true`。

Coordinator 不能相信 subagent 最终文本中的“已经保存”，只有写工具的真实结果才能确定 `UPDATED`。

## 6. 对话快照

每次提交记忆任务时立即生成不可变快照，不能等 worker 开始执行时再读取 session，
否则后续 Turn 会混入前一个提取任务。

快照范围为当前 Turn 和之前 4 个 Turn。

保留：

- `UserMessage`
- 普通 `AssistantMessage`
- `ask_user` 工具中的问题文本，用于理解“九点”“这种方式”等补充回答

排除：

- `SystemMessage`
- Assistant thinking 和 progress
- 普通工具调用及工具结果
- `ContextSummaryMessage`
- 文件、命令、MCP 和飞书结果

对话被当作待分析数据，不是新的系统指令。引用、示例、假设和第三方粘贴内容不能作为用户事实保存。

## 7. 单 worker 异步队列

### 7.1 为什么仍然需要队列

记忆提取不会阻塞主对话。上一次记忆 subagent 仍在调用模型时，下一次主 AgentLoop 可能已经结束。

为了避免多个记忆 subagent 同时总结，`MemoryExtractionCoordinator` 使用进程内单 worker FIFO：

```text
Turn 1 结束 → 提取任务 A 开始
Turn 2 结束 → A 未完成，任务 B 进入队列
A 完成       → B 开始
```

队列只存在当前 CodeAgent 进程内：

- 不是 Kafka、RabbitMQ 等外部消息队列。
- 不持久化。
- 不恢复上次进程留下的任务。
- 不处理多个 CodeAgent 进程之间的协调。

### 7.2 worker 如何知道 subagent 完成

对主 Agent 来说记忆提取是异步的，但对 worker 来说 `memoryAgent.run(request)` 是同步调用：

```text
主 Agent
  └─ submit(request)，立即返回

memory worker
  └─ 同步调用 memoryAgent.run(request)
       └─ 记忆 AgentLoop 到达终态后返回
```

`memoryAgent.run()` 只有在以下情况之一发生后才返回：

- 模型直接判断 `NO_MEMORY`。
- 读取后判断 `NO_CHANGE`。
- 完成必要写入并结束。
- 达到最大步骤数。
- 模型请求失败。
- 任务被取消。

方法返回或抛出异常后，当前 Runnable 结束，单线程执行器才会开始下一个排队任务。
不使用额外完成通知，也不轮询 `Future.isDone()`。

建议实现：

```java
Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("memory-extraction-", 0).factory()
);
```

### 7.3 Coordinator 约束

`MemoryExtractionCoordinator`：

- `submit(request)` 只负责入队并立即返回。
- 相同 `turnId` 在当前进程内只接受一次。
- 当前任务和后续任务严格 FIFO。
- 不复用现有 `SubAgentTaskManager`。
- 不产生普通 subagent notification。
- 不向主 Agent 上下文注入提取结果。
- 只有实际文件变化时向 UI 发布一条非阻塞记忆更新事件。
- 通知只显示变化类型和数量，不显示记忆正文。
- `NO_MEMORY`、`NO_CHANGE` 和失败不显示用户通知，只写内部诊断。

退出时：

1. 停止接收新任务。
2. 调用 executor shutdown。
3. 最多等待 10 秒。
4. 超时后请求当前记忆 Agent 的 CancellationToken 取消并中断 worker。
5. 丢弃尚未开始的任务。
6. 不触发父 Agent continuation Turn。

## 8. 主 AgentLoop 后的统一触发点

目前普通 `MiniTui` 和 Renderer 分别实现“读取历史、运行 AgentLoop、持久化结果”。
实现时新增统一的 `ConversationTurnService`，避免两条 UI 路径重复提交记忆任务。

用户输入 Turn 的固定顺序：

1. 读取当前 session 历史。
2. 持久化当前 `UserMessage`。
3. 构造并执行主 `AgentTurnRequest`。
4. 持久化 `AgentTurnResult.persistencePlan()`。
5. 构造不可变记忆对话快照。
6. 非阻塞提交 `MemoryExtractionRequest`。
7. 把主 Agent 结果返回给 TUI 渲染。

触发规则：

- `FINAL`
- `AWAIT_USER`
- `MAX_STEPS`
- `MODEL_ERROR`
- `CANCELLED`
- `EMPTY_RESPONSE_FALLBACK`

以上终态只要主结果已经成功持久化并且本轮来自新用户输入，就提交一次尝试提取任务。

以下情况不提交：

- AgentLoop 抛出异常，没有产生可持久化结果。
- 主结果持久化失败。
- 普通 subagent 完成后触发的 notification Turn。
- 没有新 `UserMessage` 的内部 Turn。

## 9. 专用记忆 subagent

新增内部 `MemoryExtractionAgent`：

- 不加入公开 `AgentType`。
- 主模型不能通过 `agent` 工具主动调用它。
- 使用与主 Agent 相同的模型配置，但创建独立 ModelAdapter。
- ModelAdapter 绑定只包含两个记忆文件工具的私有 ToolRegistry。
- 使用独立 AgentLoop、System Prompt、CancellationToken 和无持久化上下文。
- 不加载项目记忆、Skills、MCP 或主 Agent 隐藏状态。

专用提示词必须包含：

- 三类记忆定义和排除规则。
- 先判断、后读文件的零工具调用规则。
- 允许没有候选记忆。
- 只记录用户明确表达的事实。
- 读取后去重，纠正旧事实而不是追加冲突内容。
- Plan 的日期、时间和状态格式。
- 不明确的完成、取消和计划修改不猜测。
- 不检查 `CLAUDE.md` 是否包含相同内容。
- 未获得成功工具结果时不得声称写入成功。

## 10. user 和 feedback 的加载

新增独立 `PersonalMemoryLoader`，不要把自动个人记忆混入现有分层项目指令文档。

当记忆功能启用时，每次重建主系统提示词都重新读取：

- `user.md` → `# User memory`
- 当前项目 `feedback.md` → `# Project feedback memory`

提示词边界：

- 记忆可能过期，当前用户消息优先。
- 记忆不能授予权限、绕过确认或放宽安全规则。
- 项目明确规则和当前请求与旧 feedback 冲突时，不盲从旧 feedback。
- 当前用户明确纠正记忆时，以新内容为准，异步提取将在 Turn 结束后更新文件。

`plan.md` 不进入系统提示词，避免所有历史日程持续占用上下文。

## 11. Plan 查询工具

主 Agent 注册只读 `query_plan` 工具。

输入示例：

```json
{
  "date": {
    "kind": "RELATIVE_DAY",
    "offsetDays": 1
  },
  "statuses": ["PENDING"]
}
```

日期类型：

- `RELATIVE_DAY`：相对当前日期的天数。
- `EXACT_DATE`：ISO 日期 `YYYY-MM-DD`。
- `UNSCHEDULED`：查询 `日期待定`。

状态过滤：

- `PENDING`
- `COMPLETED`
- `CANCELLED`

不传状态时返回所有状态，并在结果中明确标记。

工具返回：

- 日期
- 状态
- 时间类型 `TIMED / UNSPECIFIED / ALL_DAY`
- 可选具体时间
- 计划正文
- 解析警告

系统提示词要求：用户询问某天日程时调用 `query_plan`，不能只依赖聊天记忆。

Plan 使用独立的 `PlanDateResolver` 和配置时区，不能直接复用只允许未来开始时间的飞书 `CalendarTimeResolver`。

## 12. 启动时的今日和逾期提醒

记忆功能启用后，在进入 TUI 输入循环前直接读取 `plan.md`。

展示内容：

- 今天仍未完成的计划。
- 日期早于今天且仍未完成的逾期计划。
- 日期待定计划的数量。
- 没有计划时显示“今天没有已记录日程”。

启动展示：

- 不调用模型。
- 不写入 session。
- 不触发记忆提取。
- 不自动改变任何完成状态。
- 普通 TUI 和 Renderer 共同使用一个 `StartupPlanSummary` 服务。

## 13. /memory 命令

扩展现有 `/memory` 报告：

- 自动记忆功能是否启用。
- 三类记忆文件的实际路径。
- 文件是否存在、大小和解析状态。
- 当前记忆提取队列的运行中/等待数量。
- Plan 中今日、逾期、日期待定、完成和取消的数量。

默认不打印完整记忆正文，避免在终端意外暴露个人信息。

## 14. 实现顺序

1. 增加 `MemoryConfig`、公共项目根解析和三类固定路径映射。
2. 实现 user、feedback、plan 的 Markdown 校验器和 Plan 宽容解析器。
3. 实现安全读取、expectedHash 校验和原子文件替换。
4. 实现 Git 本地 exclude 管理。
5. 实现 `read_memory_file`、`write_memory_file` 和私有工具注册表。
6. 实现专用记忆 subagent 及其提示词。
7. 实现对话快照构造、单 worker Coordinator、去重提交和 10 秒关闭。
8. 收敛 MiniTui 和 Renderer 的用户 Turn 执行/持久化入口。
9. 接入持久化后的异步提取触发。
10. 实现 user/feedback 热加载。
11. 实现 `query_plan` 和启动今日/逾期提醒。
12. 扩展 `/memory` 和 README。
13. 运行定向测试、完整 Maven 测试并重新构建可运行 JAR。

## 15. 测试计划

### 配置和路径

- 默认关闭，只有用户级配置可以开启。
- 时区合法性和默认值。
- user、plan、feedback 三类路径。
- 子目录启动、项目根目录和无 Git 目录。
- feedback 本地 exclude 幂等，已跟踪文件拒绝写入。

### Markdown 和文件安全

- 三种文件的合法格式。
- Plan 的具体时间、时间待定、全天、日期待定。
- 未完成、完成、取消状态。
- 非法日期、非法时间、未知状态和损坏行。
- 缺失文件的 `MISSING` hash。
- 正常更新、相同内容 no-op、手动修改后的 hash 冲突。
- 符号链接、非普通文件、控制字符和大小超限。
- 写入失败时原文件保持完整。

不增加多进程并发写和跨进程文件锁测试。

### subagent 工具调用

- 没有候选记忆时，模型直接结束，工具调用次数为 0。
- 有候选但内容已存在时，只调用 read。
- 确实需要修改时调用 read 和 write。
- 未读取文件直接写入时被拒绝。
- 工具表精确等于两个记忆文件工具。
- 普通文件、命令、MCP、飞书、ask_user 和 agent 均不可见。
- 模型口头声称保存但没有成功写工具结果时，不产生更新通知。

### 队列和生命周期

- 同一时间最多一个记忆 subagent 正在运行。
- A 未完成时 B 进入队列，不并发执行。
- `memoryAgent.run(A)` 返回后才开始 B。
- submit 不阻塞主 AgentLoop。
- 请求入队后快照不会被后续 Turn 修改。
- 相同 turnId 只执行一次。
- 六种主 Turn 终态各提交一次。
- notification Turn 和无用户消息 Turn 不提交。
- 持久化失败不提交。
- 正常退出在 10 秒内排空队列。
- 卡住任务超时后取消，未开始任务被丢弃。

### 提取规则

- 明确的用户身份、职责、长期目标和知识背景写入 user。
- 明确工作偏好和纠正写入 feedback。
- 泛泛认可不写入。
- 未来计划写入 plan。
- 仓库架构、代码路径、Git 修改、调试过程和当前任务进度不写入。
- 引用、假设、第三方内容和秘密不写入。
- 明确纠正替换旧事实。
- 计划完成、取消和改期正确更新；歧义时不修改。

### 加载和使用

- user/feedback 在写入完成后的下一轮系统提示词生效。
- plan 永远不进入系统提示词。
- query_plan 支持相对日期、精确日期、日期待定和状态过滤。
- 启动展示今日未完成项、逾期项和日期待定数量。
- 启动展示不写 session、不调用模型、不触发记忆提取。
- 只有实际文件变化才显示非阻塞通知。
- 记忆功能未配置时不改变现有 CodeAgent 行为。

### 回归验证

- 保留当前未提交的飞书功能修改，不重置或覆盖无关工作区内容。
- 运行相关定向测试。
- 运行完整 `mvn clean test`。
- 运行 `mvn -DskipTests package`，确认生成可运行 JAR。

## 16. v1 明确不做

- 多个 CodeAgent 进程之间的记忆写入协调。
- 持久化记忆提取任务和下次启动恢复。
- 单独配置更便宜的记忆模型。
- 自动同步或合并飞书日历。
- 自动归档或删除历史 Plan。
- 根据代码仓库推导长期记忆。
- 根据用户表现推断身份或知识水平。
- 与 `CLAUDE.md`、`CODEAGENT.md` 等项目指令做语义查重。
