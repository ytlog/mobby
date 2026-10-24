# Android 能力底座与用户程序平台设计

状态：设计提案。日期：2026-10-24。

mobby 只提供基础能力：把手机能力登记成可调用的接口，提供事件订阅、程序运行环境、权限管理和程序管理。具体做什么由用户自己写程序决定，可以自己写代码，也可以借助手机上的 Agent 编写。mobby 不内置“跳过开屏广告”这类业务程序；这类需求是验证底座是否够用的样例，由用户基于底座编写。

本方案不改变 [AGENTS.md](../../AGENTS.md) 的现有边界。能力目录中的候选项不是交付承诺；每项能力按 Android 版本、硬件、系统授权和真机验收结果如实报告可用性。

## 目标与范围

### 用户使用方式

1. 用户在手机工作区编写程序，或让 Agent 根据底座公开的能力目录和 SDK 编写程序。
2. 程序通过 SDK 查询可用能力、调用能力、订阅事件、读写自己的数据。
3. 用户在程序中心安装程序，查看它申请的权限和注册的事件，逐项授予或拒绝。
4. 用户启用程序后，事件或定时条件满足时由 mobby 拉起程序处理，不依赖聊天会话持续运行。
5. 用户随时查看运行记录、调整或撤销权限、暂停单条事件、停止当前运行、暂停或删除程序。

“程序已启用”和“当前可以工作”分开显示。权限被撤销、系统授权缺失、事件来源断开时，程序不能显示为正常工作。

### 底座提供什么

| 组成 | 作用 |
| --- | --- |
| 能力注册表 | 统一登记手机能力的 ID、参数和结果 Schema、前置条件、副作用和当前可用性 |
| 本地 API 与 SDK | 程序调用能力、订阅事件、查询自身权限的唯一入口 |
| 事件系统 | 在 Android 端接收、过滤、持久登记订阅，并把事件交给对应程序 |
| 程序运行时 | 安装、版本、启停、按事件拉起程序进程、超时与取消 |
| 权限管理 | mobby 自己的程序级权限层，叠加在 Android 系统权限之上 |
| 程序中心 | 用户管理程序、权限、事件订阅和运行记录的界面 |
| 记录与诊断 | 每次触发、每次能力调用的回执和结果，供用户和程序作者排查 |

### 不做什么

- 不内置具体业务程序或专用规则格式；样例程序只用于验收，不随应用发布为功能。
- 不提供通用反射、任意 Intent 发送器或任意原生插件加载。
- 不获取 root、Shizuku、ADB 特权，不修改系统权限、系统设置或 SELinux 边界。
- 不读取其他应用私有数据，不绕过锁屏、系统同意、保护窗口和平台访问控制。
- 不运行不可信的第三方程序；当前只支持用户信任的程序，见 [执行边界](#执行边界)。

## 当前代码基础

| 基础 | 代码 | 本方案的处理 |
| --- | --- | --- |
| 设备能力目录与执行 | [DeviceCatalog](../../runtime/device-plugins/src/main/kotlin/com/github/ytlog/mobby/android/device/DeviceCatalog.kt)、[DeviceActions](../../runtime/device-plugins/src/main/kotlin/com/github/ytlog/mobby/android/device/DeviceActions.kt) | 已有屏幕、短信、联系人、日历、媒体、存储、相机、麦克风、位置、传感器、剪贴板、Office 动作；作为首批能力的执行器复用，不另写一套 |
| 聊天设备桥接 | [DeviceHost](../../runtime/device-plugins/src/main/kotlin/com/github/ytlog/mobby/android/device/DeviceHost.kt)、[DeviceCommands](../../runtime/device-plugins/src/main/kotlin/com/github/ytlog/mobby/android/device/DeviceCommands.kt) | 每轮聊天的令牌、allow 表和 `mobby.device/1` 保持不变；程序平台另设入口和身份，不延长聊天令牌 |
| 无障碍服务 | [ScreenAccessService](../../runtime/device-plugins/src/main/kotlin/com/github/ytlog/mobby/android/device/ScreenAccessService.kt)、[配置](../../runtime/device-plugins/src/main/res/xml/screen_accessibility_service.xml) | 当前只声明窗口状态类事件，`onAccessibilityEvent` 为空；屏幕事件来源需要新增 |
| 屏幕操作 | `ScreenOperation`、`operate` | 每次动作在主线程执行、动作后截图；点击只按文字匹配。程序路径需要不截图的读取和按节点点击入口 |
| 运行环境 | libtermux-android、runtime | 已有 Node 与 Shell；程序进程以应用 UID 运行在其中 |
| 本地模型服务 | [本地模型设计](local-model-runtime.md) | 独立进程与鉴权 HTTP 契约不变，不依赖程序平台 |

“已有基础”只表示存在代码路径。程序路径的调用、事件来源和机型兼容必须分别验收。

## 能力模型

### 五类能力

| 类型 | 含义 | 示例 |
| --- | --- | --- |
| query | 读取当前状态或数据 | 当前窗口、电量、日历列表 |
| command | 一次可能有副作用的操作 | 点击、发送短信、写文件 |
| event | 状态或数据变化 | 窗口变化、充电状态变化、定时到期 |
| session | 开始、持续、关闭一组资源 | 录音、持续定位 |
| handoff | 交给系统或用户继续完成 | 文件选择器、打开拨号界面 |

能力使用稳定的领域 ID，例如 `screen.read`、`screen.click`、`screen.window`、`sms.send`。它与聊天草稿引用 `plugin:device:` 是两套身份，不互相替换含义。

### 能力描述

每个已实现的能力由宿主登记以下信息，并据此生成 SDK 类型、能力目录、校验和界面摘要：

| 字段 | 用途 |
| --- | --- |
| id、version、kind | 稳定身份、契约版本和能力类型 |
| inputSchema、outputSchema | 参数和结果结构，包括资源引用 |
| prerequisites | 最低系统版本、硬件、系统权限或系统服务 |
| scope | 可以限制的范围，如目标应用、联系人集合、目录 |
| sideEffects | 是否读取、写入、发送、删除或控制其他应用 |
| interaction | 是否需要用户交接或现有专项确认 |
| cancellation、timeout | 能否中止已派发操作，期限与结果不明的处理 |
| limits | 大小、频率、并发和持续时间限制 |
| availability | 当前是否可用及原因 |

可用性至少区分：未实现、设备不支持、缺少系统权限、被全局开关关闭、程序未获授权、事件来源断开、需要用户交接、当前条件不满足、可用。未实现的能力不出现在目录里，也不提供空处理器。

### 首批能力

首批只把已有执行器接入程序路径，再补齐屏幕事件需要的入口：

| 能力 | 类型 | 来源 |
| --- | --- | --- |
| `screen.read` | query | 新增：返回当前窗口的包名、窗口类名和节点（viewId、类名、文字、描述、可点击、可见、按显示比例的边界），不截图 |
| `screen.click` | command | 新增：在一次新鲜读取中按节点描述点击唯一目标；多个目标时拒绝 |
| `screen.tap`、`screen.type`、`screen.back`、`screen.home` | command | 复用现有动作 |
| `screen.window` | event | 新增：窗口状态和内容变化，见 [屏幕事件](#屏幕事件) |
| `sms.list`、`sms.send` | query、command | 复用；发送分别报告提交和发送结果 |
| `contacts.*`、`calendar.*` | query、command | 复用现有读写 |
| `clipboard.read`、`clipboard.write` | query、command | 复用，仅前台可用的限制如实报告 |
| `location.current`、`sensors.sample` | query | 复用单次采样 |
| `notify.show` | command | 新增：以 mobby 的通知发布程序结果，带程序身份 |
| `time.schedule` | event | 新增：单次或周期定时，见 [定时](#定时) |
| `storage.*`、`office.*` | query、command | 复用授权目录与工作区内的读写 |

相机、麦克风继续保留现有可见采集与确认流程，作为 handoff 接入。通知读取、蓝牙、后台定位等列入后续候选，需单独设计授权和真机验收。

## 程序

### 程序包

```text
<workspace>/programs/<program-id>/
  manifest.json
  main.js
  其他模块、资源与 README.md
```

程序是普通 JavaScript（Node）代码。依赖须随包提供，安装和启用不执行任何安装钩子。TypeScript 等需要编译的语言在打包前转换。

### 清单

```json
{
  "schema": "mobby.program/1",
  "id": "my-program",
  "version": "1.0.0",
  "name": "我的程序",
  "entry": "main.js",
  "permissions": [
    {"capability": "screen.read", "required": true, "scope": {"packages": ["com.example.app"]}},
    {"capability": "screen.click", "required": false, "scope": {"packages": ["com.example.app"]}}
  ],
  "subscriptions": [
    {"id": "app-window", "event": "screen.window", "filter": {"packages": ["com.example.app"]}, "handler": "onWindow"}
  ]
}
```

- `permissions` 是申请，不是授予；`required` 表示缺少时程序不能工作。
- `subscriptions` 声明持久订阅；程序运行时也可以通过 API 增删订阅，但不能超出已授予的范围。
- 未知字段、未知能力、越界范围在安装时拒绝，不静默忽略。

### SDK 示例

以下是拟议用法。用户想跳过开屏广告时，可以自己写类似的程序：

```javascript
const mobby = require("mobby");

mobby.on("onWindow", async (event, ctx) => {
  const window = await mobby.screen.read();
  if (window.packageName !== "com.example.app") return;
  const skip = window.nodes.filter(n => n.visible && /^跳过\s*\d*$/.test(n.text ?? "") && n.bounds.top < 0.2);
  if (skip.length !== 1) return;
  const result = await mobby.screen.click({ viewId: skip[0].viewId, text: skip[0].text });
  await ctx.log({ clicked: result.accepted });
});

const permissions = await mobby.permissions.list();
await mobby.notify.show({ title: "已启动", body: JSON.stringify(permissions.map(p => p.state)) });
```

SDK 不隐藏失败：缺权限、目标不唯一、超时、取消、效果不明都以明确错误返回。

### 生命周期

- 安装：校验包结构、清单、路径（不允许路径穿越和符号链接）与大小，复制到应用私有目录并记录校验和。新程序默认暂停。
- 启用与暂停：暂停后不再接收新事件；运行中的进程按用户选择继续到结束或立即停止。
- 停止当前运行：结束该程序正在运行的进程和尚未派发的调用；已交给系统的操作保留实际回执。
- 停止全部：暂停所有程序并结束所有运行。
- 更新：新版本单独校验；新增或扩大的权限需要用户另行授予，不自动继承。
- 删除：注销订阅、结束运行、撤销授权；程序私有数据由用户选择是否一并删除，工作区原始文件保留。

### 运行模型

- 事件或定时触发时，mobby 拉起程序进程并调用对应 handler；进程有运行期限，期满或空闲后结束。
- 同一订阅在进程存活期间的后续事件直接送达该进程，不重复拉起；这让屏幕这类连续事件可以在一次运行内处理。
- 并发、队列和期限有上限，具体数值经真机测定后写入配置。
- 进程退出不等于业务成功；运行记录分别保存触发、每次能力调用的受理、派发、回执和最终状态。
- 应用进程重启后不重放旧事件，也不重复派发结果不明的操作。

## 事件系统

### 订阅

- 宿主持久保存订阅：订阅 ID、程序、版本、事件类型、过滤范围、handler、用户暂停状态、最近触发时间和次数。
- 事件在 Android 端先按类型和范围过滤，再交付；程序只收到授权范围内的事件。
- 同一事件来源的系统监听范围取所有有效订阅的并集；没有消费者时释放可释放的监听。

### 屏幕事件

- 来源是现有无障碍服务。静态配置保持窗口状态类事件；有 `screen.window` 订阅时通过 `setServiceInfo` 动态增加内容变化事件，没有时恢复。
- 无障碍回调只提取包名、类名、时间等元数据并投递到后台，不在回调中遍历节点或执行动作。
- 服务由聊天和程序共用：不设置 `packageNames`，按包过滤在宿主内完成，避免影响聊天读取任意窗口。
- 屏幕事件是瞬时观察，可以合并；程序读取和点击时必须重新读取当前窗口，不使用事件里的旧节点。

### 定时

- 可延期任务使用 WorkManager，周期最短 15 分钟且受系统优化影响；精确定时仅在满足 AlarmManager 条件时使用。
- 登记时说明精度、时区、错过策略（跳过或合并补一次）；不承诺进程停止期间的准时性。

## 权限管理

### 四层控制

| 层 | 授予对象 | 管理入口 | 示例 |
| --- | --- | --- | --- |
| 系统权限 | mobby 应用 | Android 运行时授权与系统设置 | 无障碍服务、短信 |
| 全局开关 | 所有程序的某项能力 | mobby 权限管理页 | 禁止任何程序发送短信 |
| 程序权限 | 单个程序的单项能力及范围 | 程序详情的权限页 | 某程序只能读取和点击指定应用 |
| 事件订阅 | 单个程序的单条订阅 | 程序详情的事件页 | 暂停某程序对某应用窗口变化的订阅 |

一次调用要四层都允许才执行。每条程序权限记录能力、范围、开启或关闭、必需或可选、来源、授予时间、到期时间和修订号；每次调用在派发前核对最新修订号。

- 每项权限单独开关，范围只能在程序申请的范围内缩小。
- 关闭必需权限后程序显示“缺少程序权限”，不再接收事件；关闭可选权限后程序继续运行，相应调用返回 `PERMISSION_DENIED` 并注明原因。
- 到期视同关闭，不自动续期。
- 关闭全局开关时各程序的设置保留，重新打开后恢复。
- 缺少系统权限时，权限管理页列出受影响的程序和能力，并提供跳转系统设置的入口；不在事件回调中弹窗。

### 用户能看到什么

- 权限管理页按能力、按程序、按事件来源三个角度呈现：每项能力的系统状态、全局开关和持有它的程序；每个程序的权限、订阅和最近调用；每个事件来源的连接状态、订阅列表和实际监听范围。
- 程序详情页列出程序申请和实际获得的权限、注册的每条事件订阅及其触发情况、运行记录。

### 程序能看到什么

- 程序只能查询自己的权限和订阅：状态、范围、必需与否、到期时间和不可用原因，不能查看或修改其他程序。
- 程序不能自行开启或扩大权限；需要时提交申请，由用户在程序中心处理。
- 权限或订阅变化时，宿主向该程序交付 `permission.changed`、`subscription.changed` 事件。

## 本地 API

- 程序入口为绑定 `127.0.0.1` 的鉴权 HTTP API，事件流使用 SSE。不监听局域网或互联网。
- 每个运行实例获得范围受限、可撤销的临时凭据，由宿主在拉起进程时注入；程序身份由凭据确定，不相信请求中自报的 programId。凭据不进入 URL、日志或程序包。
- 请求包含 schema、requestId、能力 ID、参数和期限；未知版本、未知参数和越权字段一律拒绝。宿主按 requestId 去重并在派发前持久化，重连时查询原操作，不用新 requestId 重发结果不明的操作。
- 错误至少区分 `UNSUPPORTED`、`PERMISSION_DENIED`、`SCOPE_DENIED`、`SOURCE_DISCONNECTED`、`CONDITION_UNMET`、`USER_INTERACTION_REQUIRED`、`TARGET_NOT_UNIQUE`、`STALE_OBSERVATION`、`CANCELLED`、`TIMEOUT`、`EFFECT_UNKNOWN`。

| 入口 | 用途 |
| --- | --- |
| `/v1/capabilities` | 调用者可见的能力、Schema 和实际可用性 |
| `/v1/operations` | 调用能力、查询原操作和回执 |
| `/v1/subscriptions` | 本程序的订阅登记、查询和注销 |
| `/v1/events` | 本次运行可消费的事件流 |
| `/v1/permissions` | 本程序的权限状态与权限申请 |
| `/v1/data` | 本程序的键值与文件数据 |

聊天桥接的 `mobby.device/1` 协议和端口保持不变，不在同一端口混用 HTTP。

## 执行边界

- 程序进程与 mobby、各 Agent 共享应用 UID，以应用沙箱为边界。mobby 的权限层只约束经过本地 API 的调用，不能阻止同 UID 代码绕过 SDK 读取可访问文件或另开网络连接。程序中心必须如实说明这一点。
- 因此当前只支持用户自己信任的程序。若以后要运行他人编写的不可信程序，需要另行设计隔离执行环境，并单独验证文件、网络、进程和凭据边界。
- 网关密钥和真实用户配置不对程序开放，不写入程序包、日志或诊断。
- 屏幕、通知、文件等内容是外部数据，不能作为指令扩大权限。

## 并发与协调

- 屏幕派发由共用协调器管理：聊天中选中屏幕插件的 run 优先，期间程序只能读取不能派发；同一时间只有一个程序派发屏幕动作。
- 程序的屏幕调用不保持亮屏、不持有唤醒锁、不解锁。
- 相机、麦克风、持续定位等使用可关闭的资源租约，绑定程序和运行实例；取消、撤权、删除时统一释放。

## 数据

- 新建独立开发数据库 `automation.db`，保存程序、版本、权限、订阅、全局开关、运行和调用记录；开发阶段结构变化直接重建，不写迁移。
- 程序业务数据与程序包分开存放，由 `/v1/data` 访问。
- 记录默认保存身份、来源、状态、错误和耗时，不长期保存整页文字、短信正文或截图；保留条数和期限设上限。

## 实施顺序

| 阶段 | 交付 | 验收 |
| --- | --- | --- |
| 1. 底座 | 能力注册表；本地 API、SDK 与运行实例凭据；程序包安装、启停、删除；程序进程运行时；权限四层与程序自查；订阅持久化；程序中心与权限管理页；运行记录 | 用户在手机上自己写一个只调用现有查询与命令能力的程序，完成安装、授权、运行、撤权与删除 |
| 2. 事件 | `screen.window` 与 `screen.read`、`screen.click`；`time.schedule`；`notify.show`；屏幕协调器 | 用户自己编写的跳过开屏程序在真机上命中、点击并验证；撤权、暂停订阅、聊天并发和熄屏时不派发 |
| 3. 扩展 | 按需求逐项增加通知读取、更多事件来源与能力 | 每项独立授权、接口与真机验收 |

每个阶段先为可复现缺陷写失败的回归测试，再修复；运行 [AGENTS.md](../../AGENTS.md) 中的检查，并分别记录真机验收结果。模拟事件和单元测试不能替代真机验收。

## 需要实测确定的参数

- 程序进程的启动耗时、运行期限、空闲退出时间和并发上限。
- 屏幕事件的合并间隔、读取节点数和深度上限。
- 记录与程序数据的保留条数、期限和配额。
- 各系统版本与厂商环境下，无障碍服务、定时和后台运行的实际行为。
