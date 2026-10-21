# Changelog

## [1.0.0] - 2026-10-21

### 新增

- 提交工具栏一键生成提交信息，支持 `Ctrl+Alt+Shift+G` 快捷键。
- 中文/英文提交信息；插件界面、按钮、提示和校验错误支持「自动 / English / 简体中文」。
- 可编辑的默认系统提示词，支持 `{format_rules}`、`{body_rules}`、`{subject_limit}`、`{body_limit}`、`{language}` 占位符。
- 内置自动管理的 OpenCode Zen 引擎，默认使用免费模型，无需安装或运行单独网关。
- 模型轮换与失败切换：轮询、失败切换、随机、固定四种策略。
- 自动从 `/v1/models` 读取上下文窗口、输入/输出上限、推理档位和默认推理档位，生成时自动按模型限制收紧预算。
- 打开项目和提交前探测模型可用性，区分地区受限、已下架、临时限流/超时和未知状态，并优先跳过不可用模型。
- 设置页模型参数表、可用性状态、强制重新探测，以及打开项目时自动预热开关。
- 自定义 OpenAI 兼容端点、API Key、模型列表、推理级别、输入/输出上限、温度、超时、重试和输出上限字段。
- 勾选未跟踪文件时，先加入 VCS（Git 执行 `git add`）再读取 diff 生成提交信息。
- 外部工具接入：一键复制 AI 端点，其他本地工具可直接使用免费模型；对外 `/v1/models` 只返回免费且探测可用的模型，并代理 chat/responses/messages 请求。

### 说明

- 插件兼容 IntelliJ IDEA、PyCharm、GoLand、WebStorm 等基于 IntelliJ Platform 2024.2+ 的 IDE。
- 内置引擎基于 [opencode2api](https://github.com/jasonxu114514/opencode2api)（GPL-3.0-or-later），仅监听本机，随插件启动/停止。
- 插件与内置引擎均以 GPL-3.0-or-later 发布，详见 `LICENSE` 与 `NOTICE`。
