# 跨 Agent 技能目录

App 在首次初始化及后续启动时，将自带的 `skill-creator`、`skill-installer` 两份中立 `SKILL.md` 分别放到 Pi 的 `~/.pi/agent/skills`、Codex 的 `~/.agents/skills`、Claude Code 的 `~/.claude/skills`、OpenCode 的 `~/.config/opencode/skills`。四份内容相同，不调用 OpenAI API、Codex 专属工具或 OpenAI 技能仓库。它们只依赖当前 Agent 可用的文件和 Shell/Git 能力。

App 的技能列表展示这两项内置技能和 App 保存的共享技能，不按 Agent 分组。Codex 自带的 `.codex/skills/.system` 技能不再纳入 App 技能存储与解析。安装时只更新带 `.mobby-bundled` 标记的 App 副本，遇到同名用户目录则保留用户内容，不覆盖。

右上角加号弹窗提供对话创建、导入文件和手动创建。手动创建与导入保存都先检查四个目录的同名冲突，再将同一份 `SKILL.md` 写入四个 Agent 的技能目录，并以 `.mobby-shared` 标识。列表只展示四个目录中都有有效、内容一致副本的技能。页面读取当前会话 Agent 对应的副本以获得可执行引用；无会话时读取 Pi 对应副本。界面无需选择 Agent。通过 App 自带 Skill Creator 或 Skill Installer 在 CLI 中创建和安装技能时，也要求写入四个目录并添加相同标识。

`SkillStore` 是技能目录的单一管理类，负责自带技能安装、共享技能保存与同名检查、列表读取、内容预览所需的引用解析，以及运行期间设备插件技能的临时挂载。`SkillDocument` 集中校验 `SKILL.md`；`RuntimeService` 只将管理接口映射到运行时 API，`ConversationViewModel` 只保存页面编辑状态和异步操作状态。目前产品没有“修改或删除已安装技能”的接口；会话中的“移除技能”只解除该会话的引用，不删除磁盘文件。后续增加编辑或删除时应在 `SkillStore` 中实现目录变更，再通过运行时 API 暴露，避免页面直接操作文件。

`skill-creator` 创建标准的 `SKILL.md`；`skill-installer` 由用户指定本地目录或 Git 仓库，不预设供应商目录。安装外部技能时，目标技能自己的服务、工具或账号依赖仍需单独检查；本方案仅保证 App 自带两项技能不绑定 OpenAI，不能保证任意第三方技能可跨 Agent 执行。

Image Gen 暂不放入列表。现有同名技能依赖 Codex 的 `image_gen` 工具，或要求 OpenAI API 密钥；当前 App 没有为四个 Agent 提供统一图像生成能力，直接展示会产生不可用入口。

目录路径依据各产品的技能发现规则：[Codex 技能](https://learn.chatgpt.com/docs/build-skills)、[Claude Code 技能](https://code.claude.com/docs/en/skills)、[OpenCode 技能](https://opencode.ai/docs/skills)、[Pi 技能](https://github.com/earendil-works/pi-mono/blob/main/packages/coding-agent/docs/skills.md)。实际运行与安装行为需要在四个手机端 CLI 中验收。

Pi 的显式调用使用 `/skill:<name>`。App 为 Pi RPC 显式加载共享目录；升级时仅把原三个目录都有有效、同名、内容一致且带 `.mobby-shared` 标识的技能补入 Pi 目录，不覆盖 Pi 已有的用户目录。
