# 用户程序平台实施记录

对应 [方案](design/android-capability-platform.md) 与 [技术架构](design/program-platform-architecture.md) 的第一阶段。本文记录实际实现、验证证据和尚未验证的范围；能力是否可用以本文和真机结果为准，不以方案目录为准。

## 已实现

| 范围 | 实现 | 代码 |
| --- | --- | --- |
| 模块 | `:program-platform`（`runtime/programs`），边界测试限定依赖 `device-plugins`、`runtime-api`、`localization` | `runtime/test_module_boundaries.py` |
| 数据 | `programs.db`：程序、版本、授权、权限申请、配置、凭据、订阅、全局开关、运行、操作、键值、日志 | `store/ProgramsDatabase.kt` |
| 能力注册与调用 | 注册表、Schema 子集校验、调用管线（去重、校验、权限、派发前记录、修订号复核、期限、频率上限、试运行） | `capability/`、`call/` |
| 手机能力 | 短信、联系人、日历、剪贴板、位置、传感器、授权目录、Office，经 `DeviceExecutors` 复用现有执行器 | `capability/DeviceCapabilities.kt` |
| 平台基础能力 | `storage.kv`、`storage.files`、`storage.sql`、`net.http`、`secrets`、`config`、`log`、`notify.show`、`time.schedule` | `base/`、`event/ScheduleCapability.kt` |
| 程序包 | `mobby.program/1` 严格校验、安装副本、更新只取旧授权与新申请的交集、删除可选保留数据 | `program/` |
| 权限 | 系统、全局开关、程序授权（范围、到期）、调用上下文四层；用户只能缩小授权；程序只能查询自己的权限并提交申请 | `permission/` |
| 运行 | 每个运行一个 Node 进程、并发与排队上限、启动超时、空闲与期限、宽限停止、退出分类、重启恢复；凭据经标准输入 | `run/RunSupervisor.kt`、`runtime-android/.../TermuxProgramLauncher.kt` |
| 本地接口 | ktor CIO 绑定 `127.0.0.1`，运行凭据认证，拒绝 `Origin` 与非回环 `Host`；操作、能力、权限、订阅、SSE 事件流、界面消息 | `api/` |
| 事件 | 订阅索引、平台事件、WorkManager 定时（每条订阅一个唯一任务） | `event/` |
| 程序界面 | 每程序独立页面源、只读安装副本、内容安全策略、拒绝权限请求与弹窗、来源绑定的消息桥、固定标题栏 | `ui/`、`app/.../ProgramUiActivity.kt` |
| SDK | 后台 `mobby` 与 `runner.cjs`、页面 `/_mobby/sdk.js` | `src/main/assets/program-sdk`、`program-ui-sdk` |
| 程序中心 | 设置中的程序列表、安装预览、详情（权限、申请、配置、事件、运行与日志）、权限管理、让 AI 修改 | `app/.../programcenter/` |
| 开发插件 | `plugin:device:programs` 的 `catalog`、`validate`、`dry-run`、`records`；`plugin:program:<id>` 附加程序 | `dev/`、`device/ProgramDevelopment.kt` |

## 验证

- `:program-platform` 单元测试：数据库、能力、包校验、安装、权限、调用管线、本地接口、运行管理、存储、网络与凭据、事件路由、界面请求规则、开发服务。
- `:device-plugins` 单元测试：新增开发插件命令与附加程序限制。
- `node --test runtime/programs/sdk-tests/sdk.test.cjs runtime/programs/sdk-tests/ui-sdk.test.mjs`：后台 SDK 调用与恢复、runner 凭据与事件分发、页面 SDK。
- AGENTS.md 中的 Node、Python 与 Gradle 检查（构建、单元测试、lint）。本机 python.org 版 Python 缺少证书，`generateLicenseNotices` 联网补取 POM 时需要设置 `SSL_CERT_FILE=/etc/ssl/cert.pem`。

开发中由测试发现并修复的缺陷：目录占用统计跟随符号链接；URL 中的凭据占位符导致解析失败；运行在进程启动后才登记凭据，快速子进程会被拒；事件队列满时停止通知无法送达；试运行只清理了程序包副本而遗留试运行存储。

## 尚未验证

- 真机：安装、授权、配置、定时触发、后台 Node 进程拉起、网络与凭据、程序界面、开发插件与让 AI 修改的完整流程。
- 应用在后台时由 WorkManager 拉起 Node 子进程的稳定性，以及 Android 12 起子进程限制的实际阈值。
- 两台已连接手机的系统 WebView 对 `WEB_MESSAGE_LISTENER`、内容安全策略和 Service Worker 拦截的实际行为。
- 第二阶段能力（屏幕事件与操作、`net.websocket`、`ai.call`、`entry`）尚未实现。
