# CodeGrowth

> 一个使用 Java 21 构建的、本地优先的个人开发者 Agent，以软件工程为主要能力，并扩展了子 Agent、学习复习、个人记忆、Skills、MCP 和飞书日历。

CodeGrowth 不只是把问题发给模型。它维护了一条完整的 Agent 执行链：模型先理解任务并选择工具，运行时完成参数校验、权限审查和工具执行，再把结果交回模型继续判断，直到任务完成。

项目目前适合用来学习和验证 Coding Agent 的核心机制，也可以在受版本控制保护的代码仓库中完成小规模开发任务。它仍是持续迭代中的个人项目，尚未达到生产级沙箱、评测、可观测性和跨平台发布标准。

> 兼容性说明：仓库与项目展示名称已更新为 CodeGrowth；当前 CLI 命令、JAR、环境变量、配置目录和项目规则文件仍沿用 `codeagent`、`CODEAGENT_*`、`.codeagent` 与 `CODEAGENT.md`。

## 项目定位

CodeGrowth 面向个人开发者，当前包含三层能力：

1. **Coding Agent 核心闭环**：理解代码、搜索文件、修改实现、运行命令和验证结果。
2. **可持续工作的运行时**：权限控制、JSONL 会话、上下文压缩、项目规则、子 Agent 和扩展工具。
3. **开发者个人助手能力**：八股复习、个人记忆、计划查询，以及经确认后创建飞书日历事件。

编码仍然是主能力。学习、记忆和日历能力都复用同一套 Agent Runtime，而不是绕开权限与工具链单独执行。

## 核心能力

| 能力 | 当前实现 |
| --- | --- |
| 模型接入 | 通过 LangChain4j 低层 `ChatModel` 适配 Anthropic-compatible 与 OpenAI-compatible 服务；CodeGrowth 自己保留 AgentLoop 和工具执行语义 |
| 代码工具 | 文件读取、目录遍历、文本搜索、文件写入、精确编辑、批量补丁、命令执行 |
| 权限控制 | 路径、命令、文件修改、MCP 和外部写操作在执行前进入权限审查 |
| 会话 | append-only JSONL，支持列表、重命名、恢复和分叉 |
| 上下文治理 | 大工具结果外置、`microCompact`、自动压缩和手动 `/compact` |
| 子 Agent | `explore`、`plan`、`general-purpose` 三种角色，支持同步或后台执行 |
| 项目记忆 | 分层加载 `AGENTS.md`、`CODEAGENT.md` 和 `.codeagent/rules/*.md` |
| 个人记忆 | 可选的异步提取，记录用户信息、项目偏好与计划；默认关闭 |
| Study | 从 Markdown 导入题库，支持出题、追问、评分、恢复和复习统计 |
| Skills | 启动时发现 `SKILL.md`，模型按需加载完整工作流 |
| MCP | 支持 stdio 与 Streamable HTTP，远端工具进入统一注册表和权限链 |
| 飞书日历 | 通过 `lark-cli` 创建当前用户主日历事件，每次写入前确认 |
| 终端界面 | 全屏 Renderer TUI；终端能力不足时回退到普通行模式 |

## 运行架构

![CodeGrowth AgentLoop 架构](docs/images/codeagent-agentloop-architecture.png)

核心循环可以简化为：

```text
用户输入
  -> TUI
  -> ApplicationServices
  -> AgentLoop
       -> 刷新 System Prompt / Memory / Skills / Study 状态
       -> ModelAdapter 请求模型
       -> AssistantStep 或 ToolCallsStep
       -> ToolRegistry 校验并执行工具
       -> PermissionService 审查敏感操作
       -> ToolResultMessage 回到下一次模型请求
  -> SessionPersistenceRunner
  -> SessionStore 追加写入 JSONL
```

这里有两个重要边界：

- LangChain4j 只负责 Provider/模型适配，不接管 CodeGrowth 的 AgentLoop、权限、会话、上下文和工具执行。
- 子 Agent 拥有独立上下文、独立 AgentLoop 和经过角色过滤的工具表，但继续复用父级权限边界。

## 快速开始

### 1. 环境要求

- JDK 21
- Maven 3.9+

```bash
java -version
mvn -version
```

### 2. 构建

在源码目录执行：

```bash
mvn clean package
```

构建完成后会生成：

```text
target/codeagent.jar
target/dist/codeagent/lib/codeagent.jar
```

检查可运行 JAR：

```bash
java -jar target/codeagent.jar --version
java -jar target/codeagent.jar --help
```

### 3. 配置模型

推荐把个人配置写入：

```text
~/.codeagent/settings.json
```

OpenAI-compatible 示例：

```json
{
  "provider": "openai-compatible",
  "model": "your-model",
  "baseUrl": "https://your-provider.example/v1",
  "apiKey": "your-api-key",
  "maxOutputTokens": 8192,
  "contextWindow": 128000
}
```

Anthropic-compatible 示例：

```json
{
  "provider": "anthropic-compatible",
  "model": "your-model",
  "baseUrl": "https://your-provider.example",
  "authToken": "your-auth-token"
}
```

支持的 `provider`：

| 配置值 | 协议 |
| --- | --- |
| `anthropic`、`anthropic-compatible` | Anthropic Messages API compatible |
| `openai`、`openai-compatible` | OpenAI Chat Completions compatible |
| `mock` | 本地测试模式，不访问真实模型 |

也可以通过环境变量配置：

```bash
export CODEAGENT_PROVIDER="openai-compatible"
export CODEAGENT_MODEL="your-model"
export ANTHROPIC_BASE_URL="https://your-provider.example/v1"
export ANTHROPIC_API_KEY="your-api-key"
```

`ANTHROPIC_BASE_URL`、`ANTHROPIC_API_KEY` 和 `ANTHROPIC_AUTH_TOKEN` 是当前保留的兼容变量名，对 OpenAI-compatible Provider 同样生效。

配置优先级：

```text
进程环境变量
  > 当前项目 .codeagent/settings.json
  > 用户目录 ~/.codeagent/settings.json
  > 内置默认值
```

请不要把真实密钥提交到仓库。

### 4. 在目标项目中启动

进入希望 CodeGrowth 操作的项目目录：

```bash
cd /path/to/your/project
java -jar /path/to/codeagent/target/codeagent.jar
```

也可以显式指定工作区：

```bash
java -jar /path/to/codeagent/target/codeagent.jar --cwd /path/to/your/project
```

启动后直接描述任务：

```text
解释这个项目的启动流程
定位当前失败测试的原因
给这个接口增加参数校验并补充测试
重构这段逻辑，保持现有行为不变
```

## CLI

下表使用 `codeagent` 作为命令名简写。未安装 launcher 时，请替换为 `java -jar /path/to/codeagent.jar`。

| 命令 | 作用 |
| --- | --- |
| `codeagent` | 在当前工作区创建新会话 |
| `codeagent --cwd <path>` | 指定工作区 |
| `codeagent --resume <id>` | 恢复当前工作区下的会话 |
| `codeagent --fork <id>` | 基于已有会话创建新会话 |
| `codeagent session list` | 列出当前工作区的会话 |
| `codeagent session rename <id> <title>` | 重命名会话 |
| `codeagent --max-steps <n>` | 设置单轮最大步骤数，范围 1–100 |
| `codeagent --version` | 显示版本 |
| `codeagent --help` | 显示帮助 |

示例：

```bash
java -jar target/codeagent.jar session list
java -jar target/codeagent.jar --resume <session-id>
java -jar target/codeagent.jar --fork <session-id>
```

## 对话内命令

这些命令由 TUI 本地处理，不会作为普通用户消息发给模型：

| 命令 | 作用 |
| --- | --- |
| `/init` | 检测项目结构并生成适用的项目规则文件 |
| `/memory` | 查看项目记忆、个人记忆路径和提取队列状态，不打印个人记忆正文 |
| `/study` | 查看题库、事件文件和累计答题概况 |
| `/study <文件名.md>` | 从固定导入目录录入或更新题库 |
| `/skill` | 列出本次启动发现的 Skills |
| `/compact` | 手动压缩当前会话上下文 |
| `exit`、`quit` | 退出 CodeGrowth |

## 关键机制

### 代码工具与权限

内置 Coding 工具统一注册到 `ToolRegistry`：

```text
read_file       list_files      grep_files
write_file      edit_file       patch_file
modify_file     run_command      ask_user
load_skill      agent
```

模型输出始终被视为不可信输入。敏感路径访问、命令执行、文件修改、MCP 调用和外部写入会进入 `PermissionService`，用户可以：

- 仅允许一次；
- 在当前 turn 内允许；
- 始终允许；
- 仅拒绝一次；
- 始终拒绝；
- 拒绝并向 Agent 提供反馈。

长期允许或拒绝规则保存在：

```text
~/.codeagent/permissions.json
```

权限控制不是操作系统级沙箱。请在 Git 仓库或可恢复副本中运行，并在提交前审阅 diff。

### 会话与上下文

会话默认保存在：

```text
~/.codeagent/sessions/
```

每个 session 使用 append-only JSONL 记录用户消息、模型回复、工具调用、工具结果、压缩边界和元数据。会话按工作区绝对路径隔离，因此恢复时需要回到原工作区，或提供相同的 `--cwd`。

长对话通过三层机制控制体积：

1. 大型工具结果写入 `~/.codeagent/tool-results/`，上下文只保留引用与预览。
2. `microCompact` 不调用模型，优先清理历史中的旧工具结果。
3. 压力仍然较高时执行自动语义压缩，也可以手动输入 `/compact`。

### 子 Agent

父 Agent 可以通过 `agent` 工具委派独立任务：

| 角色 | 用途 | 工具边界 | 最大步骤 |
| --- | --- | --- | --- |
| `explore` | 代码搜索和证据收集 | 只读工具与只读命令 | 30 |
| `plan` | 架构分析和实施计划 | 只读工具与只读命令 | 15 |
| `general-purpose` | 聚焦的实现任务 | 读取、写入和命令 | 200 |

三种角色都支持同步与后台执行。子 Agent：

- 继承当前 Provider 和模型；
- 不复制父会话历史，只接收专用提示词和委派任务；
- 拥有独立上下文、工具表和 AgentLoop；
- 禁止再次调用 `agent` 或 `ask_user`；
- 文件、命令和 MCP 操作仍经过父级权限链；
- 不创建隐藏 session，也不把内部消息写入父 session。

后台任务使用 Java 21 虚拟线程并保存在当前进程内。任务状态和通知不会落盘，应用退出或崩溃后不能恢复；它不是跨进程、多节点的协作系统。

更完整的设计边界见 [多 Agent 迁移规范](docs/multi-agent-migration-spec.md)。

### 项目记忆

项目记忆是显式维护的 Markdown 规则，不会因为用户说“记住”就自动改写。CodeGrowth 会在每次模型请求前重新加载：

```text
AGENTS.md
CODEAGENT.md
.codeagent/rules/*.md
```

输入 `/init` 后，CodeGrowth 会检测 Java、Maven 和 Gradle 项目，并在对应文件不存在时生成：

```text
CODEAGENT.md
.codeagent/
└── rules/
    ├── project.md
    ├── java.md
    ├── maven.md
    └── gradle.md
```

它还兼容用户级、项目级和子目录级规则，以及 `.mini-code/rules/*.md`。更具体目录下的规则优先级更高。

### 自动个人记忆

自动个人记忆默认关闭，只能由用户级 `~/.codeagent/settings.json` 开启：

```json
{
  "memory": {
    "enabled": true,
    "timezone": "Asia/Shanghai"
  }
}
```

开启后，主 turn 完成并持久化后会异步提交一次受限记忆提取。专用 Agent 只能使用 `read_memory_file` 和 `write_memory_file`，不能调用普通文件、命令、MCP、飞书、Skill 或公开子 Agent 工具。

记忆分为：

| 类型 | 路径 | 用途 |
| --- | --- | --- |
| `user` | `~/.codeagent/memory/user.md` | 用户身份、职责、长期目标和知识背景 |
| `feedback` | `<project>/.codeagent/memory/feedback.md` | 当前项目中的工作偏好和明确纠正 |
| `plan` | `~/.codeagent/memory/plan.md` | 待办、完成、取消和日期待定计划 |

`user.md` 和当前项目的 `feedback.md` 会作为可能过期的参考信息注入主 Agent；当前请求和显式项目规则始终优先。`plan.md` 不整体注入 Prompt，主 Agent 通过只读 `query_plan` 查询。

记忆写入使用固定路径、Markdown 结构校验、原子替换和基于哈希的并发检查。主对话不等待提取完成；只有存储确认文件实际变化后，界面才会显示更新通知。

实现方案见 [个人记忆提取与加载计划](docs/memory-extraction-plan.md)。

### Study：八股复习

Study 使用用户自己的 Markdown 笔记作为题目和标准答案来源。先准备固定导入目录：

```bash
mkdir -p ~/.codeagent/study/imports
cp "/path/to/Java interview notes.md" ~/.codeagent/study/imports/
```

题库采用严格的两级标题：

```md
# JVM

## JVM 的运行时数据区有哪些

这里填写标准答案正文。

## 类加载过程分为哪几个阶段

这里填写另一道题的标准答案。
```

一级标题是章节，二级标题是题目，二级标题后的正文是标准答案。答案不能为空，也不需要额外编写 `### 答案`。

进入 CodeGrowth 后导入：

```text
/study Java interview notes.md
/study
```

然后可以使用自然语言：

```text
给我出 3 道 JVM 八股题
回答第 1 题：……
追问第 1 题：这里具体分哪几步？
我不会第 2 题
查看我的复习进度和重点方向
```

当前 Study 具有以下边界：

- 单次默认 3 题，最多 20 题；
- 题目和标准答案必须来自本地题库；
- 回答、追问、跳过、评分和题组完成都通过强类型工具推进；
- 待评分答案必须先保存评审，Agent 才能结束当前 turn；
- 抽题优先考虑新题、答案版本变化、历史跳过、低分和较久未复习的题；
- 题库使用原子快照，答题过程使用追加事件；
- 题库和历史在不同工作区间共享，进行中题组按 session 隔离并可恢复。

数据统一位于：

```text
~/.codeagent/study/
├── imports/
├── bank.json
└── events.jsonl
```

重新导入会把该 Markdown 文件视为完整、权威的当前题库；旧题库中缺失的题不会再进入新题组，但既有答题历史和已经开始的题组快照会保留。

### Skills

Skill 是一个带有工作流说明的 `SKILL.md`。启动时只加载名称和简介，匹配任务后再通过 `load_skill` 读取完整内容。

推荐结构：

```text
.codeagent/
└── skills/
    └── code-review/
        └── SKILL.md
```

示例：

```md
---
description: 审查 Java 修改，并检查测试、异常处理和资源释放。
---

# Code Review

1. 检查修改范围和真实调用链。
2. 检查行为变化是否有测试覆盖。
3. 运行与改动相关的验证命令。
```

发现顺序：

1. `<workspace>/.codeagent/skills/`
2. `~/.codeagent/skills/`
3. `<workspace>/.mini-code/skills/`
4. `~/.mini-code/skills/`
5. `<workspace>/.claude/skills/`
6. `~/.claude/skills/`

同名 Skill 只保留优先级最高的一个。新增或修改后需要重启 CodeGrowth。

### MCP

CodeGrowth 支持 stdio 与 Streamable HTTP。MCP 工具会以 `mcp__<server>__<tool>` 注册到 `ToolRegistry`，并继续经过输入校验和权限检查。

stdio 示例：

```json
{
  "mcpServers": {
    "local": {
      "command": "node",
      "args": ["/absolute/path/to/server.js"],
      "cwd": ".",
      "env": {
        "EXAMPLE_ENV": "value"
      },
      "enabled": true
    }
  }
}
```

Streamable HTTP 示例：

```json
{
  "mcpServers": {
    "remote": {
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${MCP_TOKEN}"
      },
      "enabled": true
    }
  }
}
```

每个 Server 必须在 `command` 和 `url` 中二选一。项目级配置会覆盖并合并用户级同名 Server；切换传输类型时不会继承另一种传输的专属字段。

启动时 CodeGrowth 会执行 MCP 初始化、读取 Server Instructions 和工具列表。单个 Server 失败不会阻止其他 Server 启动。远端 Instructions 会作为受限、不可信内容注入，不能覆盖用户指令、项目规则或权限决定。

### 飞书日历

飞书集成只从用户级配置读取，项目不能替换个人身份、CLI 路径和目标日历：

```json
{
  "integrations": {
    "feishuCalendar": {
      "enabled": true,
      "cliPath": "/opt/homebrew/bin/lark-cli",
      "timezone": "Asia/Shanghai",
      "defaultDurationMinutes": 30,
      "defaultReminderMinutes": 5,
      "timeoutSeconds": 30
    }
  }
}
```

飞书应用至少需要：

```text
calendar:calendar:readonly
calendar:calendar.event:create
offline_access
```

授权示例：

```bash
/opt/homebrew/bin/lark-cli auth login \
  --scope "calendar:calendar:readonly calendar:calendar.event:create offline_access" \
  --no-wait \
  --json
```

只有明确的创建请求才会进入日历流程，例如：

```text
帮我创建日程：明天九点复习 JVM
添加到飞书日历：9 月 10 日下午三点整理面试笔记
```

普通计划陈述不会自动创建日程。创建前会展示标题、起止时间、时区和提醒信息，并且只允许本次操作，不能永久放行。

当前只支持向当前用户主日历创建私密单次事件；不支持重复日程、参与人、修改、删除和双向同步。支持的日期表达以今天、明天、后天和明确月日为主，复杂或含糊时间会先询问。

## 配置项

常用顶层配置：

| 字段 | 作用 |
| --- | --- |
| `provider` | 模型协议类型 |
| `model` | 模型名称，真实运行必填 |
| `baseUrl` | Provider API 地址 |
| `apiKey` / `authToken` | Provider 鉴权 |
| `maxOutputTokens` | 单次模型输出上限 |
| `contextWindow` | 模型上下文窗口 |
| `maxSteps` | 单轮 Agent 最大步骤数 |
| `providerTimeoutSeconds` | Provider 请求超时，默认 300 秒 |
| `mcpServers` | MCP Server 配置 |
| `integrations.feishuCalendar` | 用户级飞书日历配置 |
| `memory` | 用户级自动个人记忆配置 |

`memory` 和 `integrations` 只接受用户级配置；MCP 和模型设置支持用户级与项目级合并。

## 本地数据

```text
~/.codeagent/
├── settings.json
├── permissions.json
├── sessions/
├── skills/
├── tool-results/
├── agent-tool-results/
├── memory/
│   ├── user.md
│   └── plan.md
└── study/
    ├── imports/
    ├── bank.json
    └── events.jsonl
```

项目内数据：

```text
<workspace>/
├── AGENTS.md
├── CODEAGENT.md
└── .codeagent/
    ├── settings.json
    ├── memory/
    │   └── feedback.md
    ├── rules/
    └── skills/
```

## 源码结构

核心代码位于 `src/main/java/minicode/`：

| 目录 | 职责 |
| --- | --- |
| `app`、`config` | CLI 参数、配置加载和应用装配 |
| `core` | AgentLoop、消息、step、turn 和事件 |
| `model` | LangChain4j Provider 适配与协议兼容 |
| `tools` | 工具接口、元数据、注册表、内置工具和结果处理 |
| `permissions` | 权限请求、作用域、交互和持久化规则 |
| `session` | JSONL 会话、恢复、重命名和 fork |
| `context` | Token 统计、工具结果预算和上下文压缩 |
| `agent` | 子 Agent 角色、运行时、任务和通知 |
| `memory`、`init` | 分层项目记忆、个人记忆和规则初始化 |
| `study` | 题库快照、答题事件、状态恢复和统计 |
| `skills` | Skill 发现、摘要注册和按需加载 |
| `mcp` | stdio / Streamable HTTP Client 和远端工具适配 |
| `integrations` | 飞书日历等外部集成 |
| `tui` | Renderer TUI、行模式、输入和终端渲染 |

## 开发与验证

```bash
# 完整测试
mvn test

# 构建可运行 JAR
mvn package

# 清理后完整构建
mvn clean package
```

改动 Agent 行为时，建议至少覆盖：

- 普通文本回复和工具调用是否能正确结束 turn；
- 工具结果是否进入下一次模型上下文；
- 权限允许、拒绝和长期规则是否符合预期；
- session 恢复、fork 和 compact 边界是否正确；
- 父子 Agent 的工具隔离和取消传播是否正确；
- Renderer TUI 与行模式行为是否一致；
- Study 待评分、保存评审和恢复状态是否闭环。

## 当前边界

CodeGrowth 已覆盖一个本地 Coding Agent 的主要运行链路，但仍需继续完善：

- 系统化离线评测和回归基准；
- 更强的操作系统级隔离与命令沙箱；
- 后台子任务的持久化、崩溃恢复和容量治理；
- Provider 故障、限流和长时间任务的可观测性；
- 跨平台安装、升级和发布流程；
- 外部集成更细粒度的权限模型。

在重要仓库中使用时，请保留版本控制、审查权限请求、检查最终 diff，并运行项目自己的测试。
