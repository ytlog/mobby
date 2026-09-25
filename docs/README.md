# 项目文档

`docs/` 是本项目统一的文档目录，技术设计与交互评审统一放在 `docs/design/`。

## 阅读入口

| 文档 | 用途 |
| --- | --- |
| [构建与使用](getting-started.md) | 环境要求、构建命令、使用方式、当前状态和升级兼容 |
| [项目需求](requirements.md) | 第一阶段本地 Agent 运行验证的目标与范围 |
| [运行验证方案](design/runtime-test-plan.md) | 初始测试控制台方案；实际实现与验证进展以实施记录为准 |
| [Android 对话 UI 评审稿](design/conversation-ui-review.html) | 当前交互规范：会话与执行、顶部菜单、输入、插件/技能、Android 行为、实现架构及可点击原型；原生接入进度见实施记录 |
| [交互三层架构与 Runtime 技术设计](design/interaction-runtime-architecture.html) | Compose 独立模块、三层职责、Runtime 模块、固定接口与事件协议、数据所有权和迁移验收 |
| [本地模型模块与 HTTP API](design/local-model-runtime.md) | 独立 Gradle 模块与专用进程、五组 Agent 协议、模型管理、资源调度与验收；设计提案 |
| [本地模型首版实现与使用](design/local-model-first-version.md) | 已实现但宿主入口暂时隐藏的 llama.cpp 服务、Qwen/Gemma 下载 UI、HTTP 路由及当前限制 |
| [手机推理引擎调研](design/local-model-engines.md) | 微软、Google、Meta、阿里、腾讯等 18 项引擎目录，Android 接入证据、可选后端与限制 |
| [模型匹配与多源下载](design/local-model-downloads.md) | 按引擎选择 Hugging Face 模型、官方备用源与镜像、版本和文件校验、断点续传与下载 API |
| [设备交互卡片实施](device-interaction-implementation.md) | 独立协议与 Compose 模块、真实动作卡、取消恢复、资源归属与验收边界 |
| [设备插件方案](design/device-plugins.md) | 设备能力插件：模块、引用、技能通道与权限隔离。旧的「使用当前手机」已替换 |
| [跨 Agent 技能目录](design/portable-skills.md) | App 自带 Skill Creator、Skill Installer 的展示、安装路径与跨 Agent 边界 |
| [界面语言](localization.md) | 中英文切换、文案维护与测试边界 |
| [网关接入](gateway.md) | 协议路径、限制和联调范围 |
| [README 展示素材](media/) | 原创示意图、短动画和可复现的生成脚本 |
| [实施记录](implementation.md) | 已实现内容、交互契约实施检查点、验证证据及待验收事项 |

## 维护约定

- 新增需求、设计、使用说明、实施记录放在本目录，设计文档放在 `design/`。
- 根目录仅保留精简的 [README](../README.md) 与 [AGENTS.md](../AGENTS.md)，不散放方案或报告。
- HTML 交互评审稿维护交互规范，HTML 技术设计维护架构与接口；不额外维护同名 Markdown 副本或跳转文件。
- 文档中的命令与非链接源码路径以项目根目录为基准；文档链接使用相对路径。
- 第三方源码自带的 README、CHANGELOG、LICENSE 和配套资源保留原位，来源与适配见 [第三方说明](../third_party/NOTICE.md)。
- 构建产物、缓存、机器配置和真实网关配置不作为项目文档提交。
