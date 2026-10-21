# AI Git Commit

[![Release](https://img.shields.io/github/v/release/anjia0532/ai-git-commit)](https://github.com/anjia0532/ai-git-commit/releases)
[![Release workflow](https://github.com/anjia0532/ai-git-commit/actions/workflows/release.yml/badge.svg)](https://github.com/anjia0532/ai-git-commit/actions/workflows/release.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

一个 JetBrains IDE 插件：在「提交」面板里点一下，就用 AI 根据当前选中的改动生成 Git 提交信息。

支持 IntelliJ IDEA、PyCharm、GoLand、WebStorm 等所有基于 IntelliJ Platform 的 IDE（2024.2 及以上）。

默认使用 **OpenCode Zen 免费模型**，无需安装网关、无需 Docker、无需单独运行服务；也支持任意 OpenAI 兼容接口（OpenAI、DeepSeek、OpenRouter、自建服务等）。

仓库：<https://github.com/anjia0532/ai-git-commit>

---

## 功能

- **提交框一键生成**：操作注册在提交工具栏（`Vcs.MessageActionGroup`），选中改动后点星标图标即可；快捷键 `Ctrl+Alt+Shift+G`。
- **默认免费模型**：内置 OpenCode Zen 引擎，启动时拉取免费模型列表，默认在多个免费模型之间轮换。
- **未跟踪文件先跟踪**：如果提交面板里勾选了未跟踪文件，插件会先把它们加入默认变更列表（由 VCS 执行 `git add` 等），等 VCS 注册成 Change 后再读取 diff 生成提交信息；添加失败会提示并停止。
- **开放给其他工具**：设置页可一键复制「外部工具端点」，作为其他 OpenAI 兼容工具的 Base URL；端点只监听本机，模型列表只返回免费且探测可用的模型。
- **模型参数自动读取**：从 `/v1/models` 读取上下文窗口、输入/输出上限、推理档位和默认档位，不需要逐个模型手填；生成时自动按模型限制收紧预算。
- **模型可用性探测**：打开项目时后台预热，提交前再校验；能区分地区受限、模型下架、临时限流/超时，并优先跳过不可用模型。
- **模型轮换 + 失败切换**：轮询 / 失败切换 / 随机 / 固定四种策略；遇到 429、超时、5xx、模型下线会自动换下一个模型。
- **完全可配置的模型接入**：端点、API Key、模型列表、推理级别、输入 token 预算、输出 token 上限、温度、超时、重试次数、输出上限字段。
- **中英文提交信息**：自动跟随 IDE 语言，也可以固定英文或简体中文。
- **中英文界面**：插件界面、按钮、提示、校验错误支持「自动 / English / 简体中文」。
- **可编辑默认提示词**：系统提示词模板完全可改，支持占位符；另有「额外项目规则」。
- **智能裁剪 diff**：按 token 预算裁剪，自动去掉常见生成文件/二进制/锁文件；不会把整份大 diff 塞进上下文。

---

## 为什么内置一个原生引擎？

OpenCode 的免费层只接受「看起来像官方 OpenCode 客户端」的请求：入口会校验 Bun 运行时的 TLS ClientHello 和 HTTP/1.1 头布局。普通 Java HTTP 客户端（以及 curl）会直接收到：

```json
{"type":"error","error":{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode."}}
```

参考实现 [opencode2api](https://github.com/jasonxu114514/opencode2api) 使用 Go + uTLS 复刻 Bun 的握手与请求头。为了让插件保持「装一个插件就能用」，本插件把该引擎的精简构建（无 WebUI、仅本机回环）随插件分发：

- 插件首次使用内置提供方时，把对应平台的引擎解压到 IDE 系统目录；
- 以随机端口、随机本地 Key 启动，只监听 `127.0.0.1`；
- 插件退出时自动结束进程；
- 用户不需要安装、配置或管理任何网关。

如果你不想使用内置引擎，可以在设置里切换到「自定义 OpenAI 兼容接口」，此时插件只用 JDK 的 HTTP 客户端，不会启动任何本地进程。

> 内置引擎来自 opencode2api（GPL-3.0-or-later），固定提交 `0bb23c7`，源码与许可证见文末。为了在只读配置目录、受限杀毒环境里也能启动，内置构建额外打了一个容错补丁：轮转状态写入失败不再阻止引擎启动；补丁见 `scripts/patches/0001-rotation-persistence-non-fatal.patch`，构建脚本会自动应用。

---

## 安装

### 直接下载安装包

从 [GitHub Releases](https://github.com/anjia0532/ai-git-commit/releases) 下载最新的 `ai-git-commit-<version>.zip`，然后在 IDE 中打开 `Settings | Plugins`，点齿轮 → `Install Plugin from Disk...`，选择 zip 并重启 IDE。

### 从源码构建（WSL + Docker，不污染宿主机）

前置条件：WSL2、Docker（WSL 内可用即可）。宿主机不需要安装 JDK / Gradle / Go。

```bash
# 在 WSL 中，进入仓库目录
cd <repo>

# 可选：如果访问 Maven / Gradle / JetBrains 仓库较慢，传入代理
PROXY=http://127.0.0.1:7890 scripts/build-in-docker.sh buildPlugin
```

产物：

```text
build/distributions/ai-git-commit-1.0.0.zip
```

在 IDE 中打开 `Settings | Plugins`，点齿轮 → `Install Plugin from Disk...`，选择上面的 zip，重启 IDE。

### 构建缓存

构建脚本使用两个 **Docker named volume**，容器重建、重启都不会丢：

| Volume | 用途 |
| --- | --- |
| `ai-git-commit-gradle` | Gradle 依赖、IntelliJ Platform、Gradle 发行版 |
| `ai-git-commit-go` | Go module 缓存与编译缓存（重建内置引擎时使用） |

Gradle 容器镜像固定为 `gradle:8.10-jdk21`，JDK 21 toolchain 在容器内解析。

### 重新生成内置引擎

内置引擎的二进制在 `src/main/resources/native/<os>-<arch>/`。仓库已包含 Windows x64、Linux x64/arm64、macOS x64/arm64 五个构建。更新时：

```bash
# 把 opencode2api 源码克隆到任意目录（不要提交进本仓库）
git clone https://github.com/jasonxu114514/opencode2api <opencode2api-src>

# 在 WSL 中
OPENCODE2API_SRC=<opencode2api-src> scripts/build-native-engine.sh

# 只重建 Windows x64
TARGETS=windows/amd64 OPENCODE2API_SRC=<opencode2api-src> scripts/build-native-engine.sh
```

脚本会：

1. 切到固定提交 `OPENCODE2API_PIN`（默认 `0bb23c7`）；
2. 用 `golang:1.24-bookworm` 交叉编译，Go 缓存写入 `ai-git-commit-go` 卷；
3. 把二进制写入 `src/main/resources/native/<os>-<arch>/`；
4. 复制 GPL 许可证到 `src/main/resources/META-INF/third-party/opencode2api/LICENSE`。

如访问 `proxy.golang.org` 慢，脚本默认使用 `https://goproxy.cn,direct`，可用 `GOPROXY_VALUE` 覆盖。

### 运行测试

```bash
scripts/build-in-docker.sh test
```

测试覆盖：SSE 解析、请求体构造、HTTP 错误、模型目录解析（含上下文/推理档位）、模型可用性探测与状态分类、模型轮换、diff 渲染、提示词模板、提交信息清洗、配置边界、中英文资源包，以及 IntelliJ 平台内的插件注册、设置页创建、启动 Activity 加载与内置引擎生命周期。

可选的真实引擎端到端测试（需要能访问 OpenCode，Linux 容器内执行）：

```bash
docker run --rm \
  -v "$PWD":/workspace -v ai-git-commit-gradle:/home/gradle/.gradle \
  -w /workspace -e AI_COMMIT_E2E=1 \
  gradle:8.10-jdk21 ./gradlew --no-daemon test \
  --tests dev.aicommit.core.EngineEndToEndTest \
  --tests dev.aicommit.bridge.BridgeEndToEndTest
```

它会启动内置 Linux 引擎、拉取免费模型目录，并真实生成一条提交信息。

---

## 使用

1. 打开「提交」工具窗口（`Alt+0` / `View | Tool Windows | Commit`）。
2. 勾选要提交的改动。
3. 点提交信息框上方工具栏的 **AI 星标按钮**，或按 `Ctrl+Alt+Shift+G`。
4. 生成过程中可以在进度条取消；结果会直接写入提交信息框，可继续手动编辑。

> 首次使用内置提供方时，插件需要 3–20 秒启动引擎并刷新免费模型列表；之后同一 IDE 会话内复用。

---

## 设置

`Settings | Tools | AI Git Commit`。

### 服务提供方

| 设置 | 说明 |
| --- | --- |
| Provider | `OpenCode Zen free models (built-in)`、OpenAI、DeepSeek、OpenRouter、自定义 |
| Base URL | 自定义接口的 OpenAI 兼容根地址，例如 `https://api.example.com/v1` |
| API Key | 保存在 IDE 凭据库（PasswordSafe），不写入 XML；留空表示沿用已保存的 Key |
| Upstream proxy | 仅内置引擎使用，例如 `http://127.0.0.1:7890`；留空直连 |
| Test connection | 在后台请求 `/v1/models`，显示模型数与免费模型数 |
| Refresh models | 重新拉取模型列表 |
| Restart engine | 重启内置引擎（仅内置提供方） |
| Endpoint for other tools | 内置引擎的对外 OpenAI 兼容端点 |
| Copy AI endpoint | 复制 `http://127.0.0.1:<port>/v1` 到剪贴板；其他工具 Base URL 填这个，API Key 可留空或任意 |
| Endpoint hint | 端点只监听本机；`/v1/models` 只返回免费且探测可用的模型 |

### 模型

| 设置 | 说明 |
| --- | --- |
| Models | 模型 ID 列表，第一项为主模型；可增删 |
| Also use every free model | 自动把接口返回的全部免费模型追加到轮换列表（去重） |
| Model parameters | 只读表格：上下文、输入/输出上限、推理档位、可用性；全部从上游 `/v1/models` 自动读取 |
| Check availability | 立即探测模型可用性并刷新状态（会绕过缓存） |
| Selection strategy | 轮询 / 失败切换 / 随机 / 固定 |
| Maximum attempts | 一次生成最多尝试几个模型 |
| Probe model availability when a project opens | 打开项目时后台预热模型目录和可用性（默认开启；同一 IDE 会话 10 分钟内不重复） |

可用性状态含义：

- **可用**：最小请求成功；
- **地区受限**：上游返回地区/国家不可用；
- **已下架**：模型不存在或已退役；
- **临时不可用**：限流、5xx、超时；
- **未知**：探测无结论（网络问题等），仍会作为候选尝试。

探测结果会缓存：可用 10 分钟、限流 5 分钟、地区受限/下架 6 小时；提交时只补探过期的模型，通常不会增加明显等待。每次探测会发送一个 `max_tokens=64` 的最小请求，会消耗少量免费额度；如果不需要项目启动时预热，可以关闭上面的开关。

### 提交信息

| 设置 | 说明 |
| --- | --- |
| Message language | 自动（跟随 IDE 语言）/ English / 简体中文 |
| Format | Conventional Commits 或纯主题行 |
| Include a body | 非平凡改动是否生成正文 |
| Subject limit / Body wrap width | 主题长度、正文换行宽度 |
| Plugin UI language | 自动 / English / 简体中文 |

> 切换界面语言后，插件按钮和通知会立即使用新语言；设置页已渲染的标签需要关闭再打开设置页刷新。服务端返回的原始错误文本保持原文，其余界面提示、校验信息和错误前缀均支持中英文。

### 提示词

- **System prompt template**：默认提示词全文可编辑。占位符：
  - `{format_rules}`：Conventional Commits / 纯主题行的规则
  - `{body_rules}`：正文规则
  - `{subject_limit}`、`{body_limit}`、`{language}`
- **Restore default prompt**：恢复内置模板。
- **Additional project rules**：追加到系统提示词末尾，例如「引用 issue 时使用 `#123`」。

### 模型参数

| 设置 | 说明 |
| --- | --- |
| Reasoning effort | `auto` / `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max`；`auto` 不发送字段 |
| Input token budget | diff 的输入预算；会再按模型声明的 `max_input` 收紧 |
| Max output tokens | 输出上限；会再按模型声明的 `max_output` 收紧 |
| Temperature | 推理模型（o1/o3/o4/gpt-5/gpt-6）会自动省略该字段 |
| Timeout | 单次请求超时（秒） |
| Output limit field | `auto` 对 o 系列/gpt-5/6 使用 `max_completion_tokens`，其余使用 `max_tokens` |

### Diff

| 设置 | 说明 |
| --- | --- |
| Maximum files | 最多分析多少个文件 |
| Excluded patterns | 逗号分隔 glob，如 `*.lock,dist/*,node_modules/*` |

### 高级

- **Use the IDE HTTP proxy for custom endpoints**：自定义接口走 IDE 的 HTTP 代理；内置引擎请使用上面的 `Upstream proxy`。

---

## 自定义模型示例

以下均为占位值，请替换成你自己的。**不要把真实 Key 写进 README、提交信息或截图。**

| 提供方 | Base URL | 模型示例 |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4.1-mini` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4.1-mini` |
| 本地 Ollama | `http://127.0.0.1:11434/v1` | `qwen2.5-coder:7b` |
| 任意兼容服务 | `http://127.0.0.1:8788/v1` | `my-model` |

设置好 Base URL 和 Key 后，点 **Test connection** 或 **Refresh models**，把返回的模型 ID 加入列表即可。

---

## 给其他工具使用

设置页的 **Endpoint for other tools** 显示内置引擎的对外 OpenAI 兼容端点，点 **Copy AI endpoint** 复制完整地址，例如：

```text
http://127.0.0.1:51234/v1
```

- 其他工具把此地址填到 OpenAI Base URL；API Key 可留空，或填任意非空字符串（端点不校验 Key）。
- `/v1/models` 只返回 **免费且探测可用** 的模型；地区受限、已下架、限流/超时和未知状态的模型不会返回。
- 其他 OpenAI 兼容路径（`/v1/chat/completions`、`/v1/responses`、`/v1/messages`）会透明转发到内置引擎，支持流式响应。
- 端点只监听 `127.0.0.1`；IDE 退出后端点失效。任何本机进程都可以使用它，请勿把端口转发到局域网/公网。
- 引擎端口会变，但对外端点端口在本次 IDE 会话内保持稳定；重启引擎后仍使用同一个复制地址。

---

## 常见问题

### 直接调用 OpenCode 免费接口报 `FreeTierError`
这是预期行为。免费层校验 TLS/请求头指纹，普通 HTTP 客户端无法通过。请使用「OpenCode Zen（内置）」提供方，让插件启动内置引擎。

### 内置引擎启动失败
1. 打开设置页，把鼠标悬停在「引擎状态」上可看到日志文件路径；
2. 检查本机能否访问 `https://opencode.ai`；国内网络可在 `Upstream proxy` 填写代理；
3. 点 **Restart engine**；
4. 如果当前平台没有内置二进制，设置页会提示，请改用自定义接口（或在源码里用 `scripts/build-native-engine.sh` 生成）。

### 免费模型 429 / 403 / “not available in your country”
免费模型会受地区、并发和上游额度限制。插件会在打开项目时和提交前探测，自动跳过“地区受限 / 已下架 / 临时限流”的模型，并在生成时按策略换下一个；也可以在设置里点 **Check availability** 强制重新探测，或删掉不稳定模型，只保留常用的几个。

### 勾选了未跟踪文件会怎样
插件会先把选中的未跟踪文件加入默认变更列表（Git 等 VCS 执行 `git add`），等待 VCS 把它们注册成 `Change` 后，再读取内容生成提交信息；添加或注册失败会报错并停止，不会在缺少这些文件内容的情况下生成。

### 模型参数需要自己填吗
不需要。上下文长度、输入/输出上限、推理档位都从 `/v1/models` 自动读取，显示在模型参数表里；生成时会按模型声明的上限自动收紧。只有自定义模型没有出现在 `/v1/models` 时，才需要在模型列表里手动填 ID。

### 打开项目时会不会拖慢 IDE
预热在后台线程执行，不阻塞 UI；同一 IDE 会话 10 分钟内只做一次。可以在高级设置里关闭 **Probe model availability when a project opens**，提交前仍会按缓存/过期情况补探。

### 生成的提交信息是英文/中文不对
检查 `Message language`。`Auto` 跟随 IDE 语言；也可以在提示词模板里保留 `{language}` 占位符，插件会替换成对应语言。

### 想改默认提示词但不想每次改
在设置里编辑 **System prompt template** 后点 OK，配置会持久化到 IDE 配置目录的 `ai-git-commit.xml`。

### 换 IDE 后要重新配置吗
同一台机器上的 JetBrains IDE 各自保存插件配置；API Key 在 IDE 凭据库中，按 Base URL 区分。

### 多个 IDE 同时运行会冲突吗
不会。每个 IDE 进程使用独立的引擎端口、随机 Key 和独立配置/日志目录。

---

## 隐私与安全

- 插件只把 **仓库名、选中改动的 diff、提示词** 发送到你在设置里选择的接口；内置模式发送到 OpenCode Zen。
- API Key 存在 IDE 凭据库（PasswordSafe）；内置引擎的本地 Key 是每次启动随机生成、只用于回环地址。
- 引擎只监听 `127.0.0.1`，不开放局域网端口，也没有 WebUI。
- 插件不包含遥测、不上传使用数据。
- 提交信息里请不要写入真实密钥、内网地址、个人信息。

---

## 兼容性

- 编译基线：IntelliJ Platform 2024.2.5（IC），since-build `242`。
- 目标 IDE：IntelliJ IDEA / PyCharm / GoLand / WebStorm / RubyMine / PhpStorm 等 2024.2+。
- 依赖模块：`com.intellij.modules.platform`、`com.intellij.modules.vcs`（所有目标 IDE 均内置）。
- 插件用到的提交 UI API：`VcsDataKeys.COMMIT_MESSAGE_CONTROL`、`VcsDataKeys.COMMIT_WORKFLOW_UI`、`Vcs.MessageActionGroup`，这些在 2024.2–2026.2 均存在。
- IntelliJ Plugin Verifier 对 **IU-262.10968.63（IntelliJ IDEA Ultimate 2026.2.3）** 的结果：`Compatible`，无弃用 API 使用。
- 平台测试（`BasePlatformTestCase`）验证了插件描述符加载、Action 注册、设置页创建，以及内置引擎的完整启动/健康检查/停止流程。
- 详细校验记录见 [docs/verification.md](docs/verification.md)。

---

## English summary

AI Git Commit is a JetBrains IDE plugin that generates a commit message from the changes selected in the Commit tool window.

- One-click action in the commit toolbar (`Vcs.MessageActionGroup`) and `Ctrl+Alt+Shift+G`.
- Model parameters (context window, input/output limits, reasoning levels) are read from `/v1/models`; no per-model editing.
- Availability is probed when a project opens and before a commit. Region blocks, retired free models and rate limits are detected and skipped with failover.
- A copyable loopback endpoint (`Copy AI endpoint`) lets other local tools use the same free models; its `/v1/models` only lists free models that passed the probe.
- Built-in, auto-managed OpenCode Zen engine: no separate gateway or Docker at runtime. OpenCode's free tier validates the official client's Bun TLS/HTTP fingerprint and rejects plain Java HTTP clients with `FreeTierError`, so the plugin bundles a pinned opencode2api build and starts it on loopback with a random key.
- Custom OpenAI-compatible endpoints: base URL, API key (stored in the IDE credential store), model list, rotation/failover, reasoning effort, input/output budgets, temperature, timeout and output-token field.
- Chinese/English commit messages and plugin UI (Auto / English / 简体中文), editable default system prompt.
- Build with WSL + Docker: `PROXY=http://127.0.0.1:7890 scripts/build-in-docker.sh buildPlugin`. Gradle and Go caches live in named Docker volumes.
- Rebuild the bundled engine: `OPENCODE2API_SRC=<checkout> scripts/build-native-engine.sh`.
- Verified compatible with IntelliJ IDEA Ultimate 2026.2.3 by the IntelliJ Plugin Verifier; compiled against platform 2024.2.5.
- GPL-3.0-or-later. The bundled engine is opencode2api (GPL-3.0-or-later, pinned `0bb23c7`).

---

## 目录结构

```text
.
├── build.gradle.kts
├── settings.gradle.kts
├── scripts/
│   ├── build-in-docker.sh       # WSL + Docker 构建/测试
│   └── build-native-engine.sh   # 交叉编译内置引擎
├── src/main/java/dev/aicommit/
│   ├── action/                  # 提交工具栏 Action
│   ├── bridge/                  # 内置引擎生命周期管理
│   ├── commit/                  # diff 收集与生成编排
│   ├── core/                    # 纯 Java：HTTP/SSE/模型轮换/提示词
│   ├── i18n/                    # 中英文资源包
│   ├── icons/
│   └── settings/                # 设置持久化与设置页
├── src/main/resources/
│   ├── META-INF/plugin.xml
│   ├── messages/                # AiCommitBundle*.properties
│   └── native/                  # 五个平台的内置引擎
└── src/test/java/               # 单元测试
```

---

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。每个版本在 GitHub 上以 `v<version>` 标签发布，标签说明和 Release 说明来自对应版本的 changelog；Release 附带可安装的 zip。

---

## 致谢

- 感谢 [opencode2api](https://github.com/jasonxu114514/opencode2api) 项目及其作者：本插件的内置 OpenCode Zen 引擎基于该项目（固定提交 `0bb23c7`），并遵循其 GPL-3.0-or-later 许可证。
- 感谢 [IntelliJ Platform](https://github.com/JetBrains/intellij-community) 和 JetBrains 提供的 IDE 插件框架、测试框架与文档。
- 感谢所有提交 issue、测试免费模型和反馈中文/英文提示问题的用户。

---

## 许可证

本项目以 **GPL-3.0-or-later** 发布，见 [LICENSE](LICENSE)。

内置引擎来自 [jasonxu114514/opencode2api](https://github.com/jasonxu114514/opencode2api)（GPL-3.0-or-later，固定提交 `0bb23c7`）。二进制随插件分发，源码与许可证：

- 源码：<https://github.com/jasonxu114514/opencode2api>
- 许可证副本：`src/main/resources/META-INF/third-party/opencode2api/LICENSE`

如果你再分发本插件，请同时遵守 GPL-3.0-or-later 的源码提供义务。
