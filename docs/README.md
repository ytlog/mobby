# 项目文档

`docs/` 是本项目统一的文档目录，技术设计与交互评审统一放在 `docs/design/`。

## 阅读入口

| 文档 | 用途 |
| --- | --- |
| [项目介绍（英文）](../README.md) · [中文](README.zh-CN.md) | 页面问答、照片与文件、应用操作和手机编程四个使用场景 |
| [构建与使用](getting-started.md) | 环境要求、构建命令、使用方式、当前状态和开发数据库规则 |
| [公开仓库与发布安全排查](public-release-audit.md) | 历史密钥、Actions 产物、分发授权及公开前的检查记录 |
| [GitHub 手动发布](release.md) | Release 签名密钥、手动触发、版本号、R8 和验收 |
| [项目需求](requirements.md) | 第一阶段本地 Agent 运行验证的目标与范围 |
| [运行验证方案](design/runtime-test-plan.md) | 初始测试控制台方案；实际实现与验证进展以实施记录为准 |
| [Android 对话 UI 评审稿](design/conversation-ui-review.html) | 当前交互规范：会话与执行、顶部菜单、输入、插件/技能、Android 行为、实现架构及可点击原型；原生接入进度见实施记录 |
| [App 图标](design/app-icon.md) | 确认稿、蓝色环带矢量资源、自适应图层与单色图标 |
| [悬浮对话](design/floating-conversation.md) | 悬浮球职责、共享会话执行与时间线、屏幕识别入口、窗口生命周期和验收范围 |
| [交互三层架构与 Runtime 技术设计](design/conversation-runtime-architecture.html) | Compose 展示包、三层职责、Runtime 模块、固定接口与事件协议、数据所有权和迁移验收 |
| [Gradle 模块收敛评审](design/module-consolidation.md) | 17 → 13 个模块的核查与合并、按职责分组的目录、边界检查与验证 |
| [会话运行状态管理](design/conversation-state-management.md) | 统一运行状态规则、快照投影、独立输出加载、恢复和摘要一致性 |
| [项目、目录与会话数据](design/project-workspace-management.md) | 项目即执行目录、默认目录、会话迁移、项目 Skill 与数据管理职责 |
| [本地模型模块与 HTTP API](design/local-model-runtime.md) | 独立 Gradle 模块与专用进程、五组 Agent 协议、模型管理、资源调度与验收；设计提案 |
| [本地模型首版实现与使用](design/local-model-first-version.md) | 已实现但宿主入口暂时隐藏的 llama.cpp 服务、Qwen/Gemma 下载 UI、HTTP 路由及当前限制 |
| [手机推理引擎调研](design/local-model-engines.md) | 微软、Google、Meta、阿里、腾讯等 18 项引擎目录，Android 接入证据、可选后端与限制 |
| [模型匹配与多源下载](design/local-model-downloads.md) | 按引擎选择 Hugging Face 模型、官方备用源与镜像、版本和文件校验、断点续传与下载 API |
| [设备交互卡片实施](device-operation-implementation.md) | 独立协议与 Compose 模块、真实动作卡、取消恢复、资源归属与验收边界 |
| [设备插件方案](design/device-plugins.md) | 设备能力插件：模块、引用、技能通道与权限隔离。旧的「使用当前手机」已替换 |
| [应用功能方案](design/app-functions.md) | Android App Functions 的独立入口、发现、调用确认与验证边界 |
| [Pi Agent](design/pi-agent.md) | 默认 Agent、固定运行依赖、RPC 会话、Responses 网关与验收 |
| [跨 Agent 技能目录](design/portable-skills.md) | App 自带 Skill Creator、Skill Installer 的展示、安装路径与跨 Agent 边界 |
| [界面语言](localization.md) | 中英文切换、文案维护与测试边界 |
| [网关接入](gateway.md) | 协议路径、限制和联调范围 |
| [演示素材](media/) | 真机演示素材与剪辑脚本 |
| [英文真机演示](media/en/README.md) | 英文界面的四项录屏、实际验证结果及商店语言限制 |
| [英文旅行清单源码](examples/travel-checklist-en/) | 手机 Codex 生成的英文网页与 Node 服务 |
| [四项真机演示](media/cn/README.md) | 四个使用场景的实际结果、录屏和已知问题 |
| [旅行打包清单示例](examples/travel-checklist/) | 本次由手机 Codex 生成的清单网页与 Node 服务 |
| [Weekend Bag 示例](examples/weekend-bag/) | Codex 在 Android 手机上生成的打包清单源码 |
| [实施记录](implementation.md) | 已实现内容、交互契约实施检查点、验证证据及待验收事项 |

## 维护约定

- 新增需求、设计、使用说明、实施记录放在本目录，设计文档放在 `design/`。
- 开发约定见根目录的 [AGENTS.md](../AGENTS.md)；方案、使用说明与实施记录统一放在 `docs/`。
- HTML 交互评审稿维护交互规范，HTML 技术设计维护架构与接口；不额外维护同名 Markdown 副本或跳转文件。
- 文档中的命令与非链接源码路径以项目根目录为基准；文档链接使用相对路径。
- 第三方源码自带的 README、CHANGELOG、LICENSE 和配套资源保留原位，来源与适配见 [第三方说明](../third_party/NOTICE.md)。
- 构建产物、缓存、机器配置和真实网关配置不作为项目文档提交。
