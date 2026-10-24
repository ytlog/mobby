# 用户程序平台技术架构

状态：技术设计，尚未实现。日期：2026-10-24。

本文是 [Android 能力底座与用户程序平台设计](android-capability-platform.md) 的技术实现方案：模块划分、进程与线程、组件接口、协议、数据模型、关键时序、安全、测试和实施拆分。产品范围、权限规则和阶段目标以方案文档为准；两者冲突时先修订方案文档。

## 1. 设计约束

- 遵守 [AGENTS.md](../../AGENTS.md)：不获取 root，不修改系统权限；网关密钥只在加密存储；开发数据库结构变化直接重建；保持现有聊天执行路径和 `mobby.device/1` 协议不变。
- 手机能力只有一套执行实现：程序路径复用 `device-plugins` 中的执行器，不另写屏幕、短信等实现。
- 程序与 mobby 共享应用 UID。平台的权限只约束经过本地 API 的调用，不宣称程序间隔离。
- 本地 HTTP 服务沿用本地模型服务的做法：ktor CIO、只绑定 `127.0.0.1`、Bearer 令牌。
- 新模块必须进入 `runtime/test_module_boundaries.py` 的依赖白名单，依赖方向在测试中强制。

## 2. 总览

```mermaid
flowchart TB
    subgraph App["app（主进程）"]
        UI["程序中心 / 权限管理 UI"]
        Graph["AppGraph 组装"]
    end
    subgraph Platform[":program-platform"]
        API["本地 API 服务\nktor CIO · 127.0.0.1"]
        Gate["PermissionGate\n四层权限"]
        Registry["CapabilityRegistry"]
        Router["EventRouter"]
        Runs["RunSupervisor"]
        Dev["DevelopmentService\ncatalog · validate · dry-run · records"]
        Store[("programs.db")]
    end
    subgraph Device[":device-plugins"]
        Exec["DeviceExecutors\n现有动作 + screen.read/click"]
        Events["ScreenEvents"]
        Coord["ScreenCoordinator"]
        Bridge["聊天设备桥接\nmobby.device/1"]
    end
    subgraph Runtime[":runtime-android"]
        Launcher["TermuxProgramLauncher"]
        AgentRun["Agent 运行"]
    end
    Node["程序进程\nNode + mobby SDK"]
    A11y["ScreenAccessService"]

    UI --> Platform
    Graph --> Platform
    Node -- "HTTP / SSE" --> API
    API --> Gate --> Registry --> Exec
    Exec --> Coord
    A11y --> Events --> Router --> Runs
    Runs --> Launcher --> Node
    AgentRun --> Bridge -- "plugin:device:programs" --> Dev
    Platform --> Store
```

主路径：

1. 能力调用：程序进程 → 本地 API → 凭据认证 → 参数校验 → 权限闸门 → 操作记录 → 协调器 → 设备执行器 → 回执。
2. 事件：系统来源 → 事件路由 → 订阅匹配与权限投影 → 交给进行中的运行，或拉起新运行。
3. 开发：Agent 在聊天中通过现有设备桥接调用开发服务；安装和授权只在程序中心进行。

## 3. 模块划分

### 3.1 Gradle 模块与依赖

| 模块 | 目录 | 职责 | 允许的项目依赖 |
| --- | --- | --- | --- |
| `:program-platform`（新增） | `runtime/programs` | 能力注册、权限、程序存储、事件路由、运行管理、本地 API、开发服务 | `device-plugins`、`runtime-api`、`localization` |
| `:device-plugins`（扩展） | `runtime/device-plugins` | 设备执行器公开入口、屏幕读取与点击、屏幕事件、屏幕协调器、开发插件命令入口 | 不变：`runtime-api`、`localization` |
| `:runtime-android`（扩展） | `runtime/android` | 用 termux 执行器实现程序进程启动；安装 SDK 文件 | 增加 `program-platform` |
| `app`（扩展） | `app` | 组装平台；程序中心与权限管理 UI | 增加 `program-platform` |

依赖方向：`app → runtime-android → program-platform → device-plugins → runtime-api`。`device-plugins` 不依赖 `program-platform`；聊天开发插件需要的平台功能通过端口注入（见 3.3）。

源码包：`program-platform` 使用 `com.github.ytlog.mobby.android.programs`；`app` 中的界面使用 `com.github.ytlog.mobby.android.programcenter`。界面包可以复用 `conversation.ui` 的设置组件，`conversation.ui` 不引用程序平台，界面通过 `ConversationHostActions` 注入的页面插槽进入设置导航。

### 3.2 `:program-platform` 内部包

| 包 | 内容 |
| --- | --- |
| `capability` | `CapabilityDescriptor`、`CapabilityRegistry`、`CapabilityHandler`，以及把现有设备动作包装为能力的适配器 |
| `program` | `mobby.program/1` 清单模型、`ProgramPackageReader`、安装与版本管理 |
| `permission` | 四层权限模型、`PermissionResolver`、`PermissionGate`、到期计时 |
| `event` | 事件信封、`EventSource` 接口、屏幕与定时来源、`EventRouter`、订阅索引 |
| `run` | `RunSupervisor`、运行状态机、运行凭据、`ProgramProcessLauncher` 端口 |
| `api` | ktor 服务、路由、请求与错误协议、SSE |
| `dev` | 能力目录生成、校验、试运行、记录读取 |
| `store` | Room 数据库 `programs.db`、DAO、保留与清理 |
| 根包 | `ProgramPlatform` 门面，供 `app` 与 `runtime-android` 使用 |

### 3.3 `:device-plugins` 的扩展

| 新增 | 说明 |
| --- | --- |
| `DeviceExecutors`（公开） | 包装现有内部 `DeviceActions`，接受与传输无关的上下文：取消检查、临时目录、资源登记。聊天路径继续经 `DeviceCommands` 调用同一执行器 |
| `ScreenWindow`、`ScreenNode` | 当前窗口的不可变快照：包名、窗口类名、节点的 viewId、类名、文字、描述、可点击、可见、按显示比例的边界；密码字段显示为 `[secure]` |
| `ScreenAccessService.withWindow` | 在调用线程读取一次窗口，并在节点释放前按索引点击；不截图、不隐藏悬浮窗、不固定等待 |
| `ScreenEvents` | 无障碍事件的元数据转发与动态事件类型（`setServiceInfo`）；按需求方登记，取并集 |
| `ScreenCoordinator` | 聊天屏幕会话优先；后台派发租约；聊天动作等待进行中的后台派发 |
| `ProgramDevelopmentPort` | 开发插件命令的端口，由 `app` 在启动时注入平台实现；未注入时开发插件不可用 |
| 目录项 `programs` | `DeviceCatalog` 新增“程序开发”插件，命令 `catalog`、`validate`、`dry-run`、`records`，经现有桥接与令牌执行 |

现有 `snapshot`、`click(query)`、`tap` 等聊天动作的语义不变。

### 3.4 `:runtime-android` 的扩展

- `TermuxProgramLauncher` 实现 `ProgramProcessLauncher`：用现有 termux 执行器启动 `node`，独立进程组，记录 PID 与启动时间（沿用 `ProcessRegistry` 的身份校验做法）防止 PID 复用误杀。
- 运行时初始化时把 SDK（`mobby` 模块与 `runner.js`）写入前缀目录下的固定位置，随应用版本更新；程序进程通过 `NODE_PATH` 引用，不需要网络安装。
- 程序进程与 Agent 进程分开登记，取消和恢复互不影响。

## 4. 进程与线程

| 执行体 | 所在 | 说明 |
| --- | --- | --- |
| 平台控制 | 主进程，单线程协程调度器 | 程序安装、权限变更、订阅索引重建、运行状态变化都串行执行，避免竞态 |
| 事件路由 | 主进程，单线程 | 接收来源投递的事件，匹配订阅并分发；不读节点 |
| 无障碍回调 | 主线程 | 只提取元数据交给事件路由，立即返回 |
| 能力调用 | ktor 请求协程 + IO 调度器 | 屏幕读取与点击在后台线程；复用的旧屏幕动作仍按原实现切到主线程 |
| 本地 API | ktor CIO 线程 | 只绑定回环地址 |
| 程序进程 | 子进程（Node） | 由 `RunSupervisor` 拉起、监视和结束 |

- 不新增 Android 进程；`:local_model` 进程与本平台无关。
- 第一阶段的运行是有期限的短任务，不新增前台服务。无障碍服务被系统绑定期间主进程通常存活；定时触发由 WorkManager 唤起主进程。后台进程能否在各厂商系统上稳定拉起子进程，是第一阶段必须实测的问题（见第 13 节）。

## 5. 核心组件

### 5.1 能力注册表

```kotlin
data class CapabilityDescriptor(
    val id: String,                 // "screen.read"
    val version: Int,
    val kind: CapabilityKind,       // QUERY, COMMAND, EVENT, SESSION, HANDOFF
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val scope: ScopeKind,           // NONE, PACKAGES, RECORDS, DIRECTORIES
    val sideEffects: Set<SideEffect>,
    val prerequisites: List<Prerequisite>,
    val timeoutMillis: Long,
    val cancellable: Boolean,
    val limits: CapabilityLimits,
)

interface CapabilityHandler {
    val descriptor: CapabilityDescriptor
    fun availability(system: SystemState): Availability
    suspend fun invoke(call: CapabilityCall): CapabilityOutcome
}
```

- 注册表在启动时由代码登记，只包含有处理器的能力；目录、SDK 类型和开发技能都从注册表生成。
- 适配器把 `screen.tap` 等调用转换为 `DeviceExecutors.perform("screen", "tap", args, context)`，结果 `DeviceResult` 的 `effectState` 原样映射为调用结果的效果状态。
- `screen.read` 和 `screen.click` 直接使用 `ScreenAccessService.withWindow`；`screen.click` 的参数是节点描述（viewId、文字、描述、类名、区域），在同一次读取中匹配，恰好一个时点击，否则返回 `TARGET_NOT_UNIQUE` 或 `RESOURCE_MISSING`。

### 5.2 权限闸门

```kotlin
class PermissionGate(store: GrantStore, switches: SwitchStore, system: SystemState, clock: Clock) {
    fun check(run: RunIdentity, capability: CapabilityDescriptor, scope: CallScope): GateDecision
}

sealed interface GateDecision {
    data class Allowed(val grantRevision: Long) : GateDecision
    data class Denied(val layer: Layer, val reason: DenyReason) : GateDecision
}
```

- 按“系统权限 → 全局开关 → 程序权限（开启、范围、到期）→ 本次运行上下文”的顺序检查，返回第一个不满足的层和原因。
- 允许时返回授权修订号；派发前再次比较当前修订号，变化则重新检查，避免排队期间撤权后继续派发。
- 撤权、关闭全局开关、到期时，平台控制线程先更新授权，再取消该程序未派发的调用并通知运行。

### 5.3 程序存储与安装

- `ProgramPackageReader` 校验目录：只接受普通文件和子目录，拒绝符号链接、绝对路径和 `..`；限制文件数和总大小；清单严格解码，未知字段拒绝；入口文件必须存在；权限申请只能引用注册表中的能力，范围必须符合该能力的 `ScopeKind`；订阅只能引用事件能力，且过滤范围不超出对应权限申请。
- 安装把文件复制到 `files/programs/packages/<id>/<version>-<checksum>/`，先写临时目录再原子重命名；数据库记录版本和校验和。运行时从安装副本执行，不从工作区执行，避免安装后被改动的文件直接生效。
- 更新时旧版本的运行按旧版本结束；新版本的权限只继承旧授权与新申请的交集，扩大部分需要用户授予。

### 5.4 事件路由

```kotlin
interface EventSource {
    val type: String                       // "screen.window"
    fun demand(scopes: Set<Scope>)        // 由路由按有效订阅的并集设置
}

data class ProgramEvent(
    val eventId: String, val type: String, val source: String,
    val observedAt: Long, val expiresAt: Long?, val scope: Scope, val payload: JsonObject,
)
```

- 订阅索引按事件类型和范围建立，权限、订阅、启用状态变化时在平台控制线程重建，再整体替换给路由线程。
- 每个事件先过滤，再做权限投影：只有当前授权范围内的订阅能收到。
- 分发：该订阅已有进行中的运行时，放入运行的事件队列（有界，屏幕类事件可合并只保留最新）；否则请求 `RunSupervisor` 拉起新运行。
- 屏幕来源：`ScreenEvents` 在有订阅时增加内容变化事件类型；事件只含包名、类名、时间，程序需要节点时调用 `screen.read`。
- 定时来源：`time.schedule` 订阅登记为 WorkManager 任务（周期最短 15 分钟）；需要精确时间且满足条件时使用 AlarmManager。到期后唤起主进程，由路由按订阅拉起运行。错过策略按订阅配置。
- 平台事件：`permission.changed`、`subscription.changed`、`run.stopping` 由平台直接投递给相关运行。

### 5.5 运行管理

```kotlin
interface ProgramProcessLauncher {
    fun launch(spec: ProcessSpec): ProgramProcess
}

data class ProcessSpec(
    val runId: String, val programDir: File, val entry: String,
    val workingDir: File, val sdkDir: File, val apiPort: Int, val credential: String,
)

interface ProgramProcess {
    val pid: Int
    suspend fun awaitExit(): Int
    fun terminate()        // SIGTERM 进程组，宽限期后 SIGKILL
}
```

运行状态：

```mermaid
stateDiagram-v2
    [*] --> Starting
    Starting --> Running: SDK 连接 /v1/events
    Starting --> Failed: 启动超时或进程退出
    Running --> Stopping: 期限到、空闲、用户停止、撤权、删除
    Running --> Exited: 进程自行退出
    Stopping --> Exited: 进程在宽限期内退出
    Stopping --> Killed: 超过宽限期
    Exited --> [*]
    Failed --> [*]
    Killed --> [*]
```

- 每次运行生成随机凭据，绑定 programId、版本、runId、是否试运行；凭据只保存在内存，运行结束即作废。凭据通过子进程的标准输入第一行传入，不放在参数、环境变量或文件里。
- 每个程序同一时间最多一个运行；全局并发、运行期限、空闲退出时间有上限，数值实测后进入配置。
- 运行结束时取消其未完成的调用：未派发的标记为取消；已派发的等待执行器回执，拿不到可靠结果的记为 `EFFECT_UNKNOWN`。
- 应用进程重启时：按 PID 与启动时间结束遗留的程序进程；未结束的运行记为中断；已派发未回执的调用记为结果不明，不重发。

### 5.6 本地 API 服务

- ktor CIO，`127.0.0.1`，端口由系统分配，平台启动时创建、关闭时停止。
- 所有请求需要 `Authorization: Bearer <run credential>`；拒绝带 `Origin` 头的请求，`Host` 必须是回环地址，不开启 CORS。
- 请求体大小、并发请求数和每个运行的调用速率有上限。
- 管理类操作（安装、启用、授权）不在这个 API 上提供，只能在程序中心完成。

### 5.7 开发服务

`DevelopmentService` 实现 `ProgramDevelopmentPort`，供聊天开发插件调用：

| 命令 | 实现 |
| --- | --- |
| `catalog` | 从注册表生成能力列表（ID、Schema、副作用、范围、本机可用性）、SDK 说明和程序包格式；同时写入本轮技能目录 |
| `validate` | 对工作区路径运行 `ProgramPackageReader`，不安装；返回问题列表 |
| `dry-run` | 以试运行凭据拉起运行：只接收请求中提供的模拟事件；查询类能力只在本轮聊天已选中的设备插件范围内执行；命令类能力不派发，记录调用并返回 `SIMULATED`；运行期限更短，结束后删除临时数据 |
| `records` | 只读取本轮草稿中以 `plugin:program:<id>` 附加的程序的运行与调用记录 |

- 工作区路径必须位于默认工作区的 `programs/` 下，使用现有 `DevicePaths` 的包含检查。
- 开发插件不提供安装、启用、授权和恢复订阅的命令。

### 5.8 程序中心

- `ProgramCenterViewModel` 订阅平台暴露的 `Flow`：程序列表、权限、订阅、全局开关、系统权限状态、运行与调用记录。
- 页面：程序列表、安装确认、程序详情（概览、权限、事件、运行记录）、权限管理（按能力、按程序、按事件来源）。
- 安装确认页展示来源目录、文件清单、权限申请与范围、订阅；确认后调用平台安装。
- 所有修改经平台门面执行，界面不直接访问数据库。

## 6. 协议

### 6.1 能力调用

```http
POST /v1/operations
Authorization: Bearer <run credential>
Content-Type: application/json

{"schema":"mobby.program-api/1","requestId":"r-42","capability":"screen.click",
 "args":{"viewId":"com.example.app:id/skip"},"deadlineMs":3000}
```

成功：

```json
{"requestId":"r-42","operationId":"op-9","status":"succeeded","effect":"confirmed",
 "result":{"accepted":true}}
```

失败：

```json
{"requestId":"r-42","operationId":"op-9","status":"failed","effect":"none",
 "error":{"code":"PERMISSION_DENIED","layer":"program","reason":"program_off"}}
```

- `requestId` 在一次运行内唯一；相同 `requestId` 且参数指纹相同返回原操作，参数不同返回 `REQUEST_CONFLICT`。
- `GET /v1/operations/{requestId}` 查询原操作，用于超时或断线后确认结果。
- `status` 取值：`succeeded`、`failed`、`cancelled`、`timeout`、`unknown`、`simulated`；`effect` 取值：`none`、`confirmed`、`unknown`。

### 6.2 事件流

```http
GET /v1/events
Authorization: Bearer <run credential>
Accept: text/event-stream
```

```text
event: program.event
data: {"eventId":"e-7","type":"screen.window","subscription":"app-window","observedAt":1761282000000,
       "scope":{"package":"com.example.app"},"payload":{"packageName":"com.example.app","className":"com.example.app.SplashActivity"}}

event: run.stopping
data: {"reason":"permission_changed"}
```

SDK 连接事件流即表示运行进入 `Running`；收到 `run.stopping` 后 SDK 停止接收新事件并在宽限期内退出。

### 6.3 其他入口

| 入口 | 说明 |
| --- | --- |
| `GET /v1/capabilities` | 本程序可见的能力、Schema 与可用性 |
| `GET /v1/permissions` | 本程序的权限状态与原因 |
| `POST /v1/permissions/requests` | 提交权限申请，等待用户在程序中心处理 |
| `GET/POST/DELETE /v1/subscriptions` | 本程序订阅的查询、登记与注销，范围不超出授权 |
| `GET/PUT/DELETE /v1/data/kv/{key}`、`/v1/data/files/{path}` | 本程序私有数据，有配额 |
| `POST /v1/log` | 写入运行记录，有长度与频率限制 |

### 6.4 SDK

```javascript
const mobby = require("mobby");

mobby.on("app-window", async (event, ctx) => { /* 处理事件 */ });
await mobby.capabilities.list();
await mobby.call("sms.list", {});
await mobby.screen.read();
await mobby.screen.click({ viewId, text });
await mobby.permissions.list();
await mobby.permissions.request("screen.click", { packages: ["com.example.app"] });
await mobby.data.set("key", value);
```

- `runner.js` 读取标准输入中的凭据，加载程序入口，连接事件流，把事件分发给 `mobby.on` 注册的处理函数；处理函数异常写入运行记录，不吞掉。
- 每个调用自动生成 `requestId`；超时后先查询原操作，不用新 ID 重发。
- 错误抛出带 `code`、`layer`、`reason` 的 `MobbyError`。

### 6.5 开发插件命令

经现有 `mobby.device/1` 桥接调用，参数与结果为 JSON：

```text
catalog
validate {"path":"~/workspace/programs/my-program"}
dry-run  {"path":"~/workspace/programs/my-program","events":[{"type":"screen.window","payload":{...}}],"timeoutMs":20000}
records  {"programId":"my-program","limit":50}
```

## 7. 数据模型

`programs.db`（Room，开发阶段结构变化直接重建）：

| 表 | 主要字段 |
| --- | --- |
| `program` | programId、名称、用户状态、当前版本、安装时间、更新时间 |
| `program_version` | programId、版本、校验和、安装路径、入口、清单 JSON、安装时间 |
| `program_grant` | programId、能力、授予范围、申请范围、开启、必需、来源、授予时间、到期时间、修订号 |
| `permission_request` | 请求 ID、programId、能力、范围、状态、时间 |
| `subscription` | 订阅 ID、programId、版本、事件类型、过滤范围、handler、来源（清单或运行时）、用户暂停、最近触发时间、触发次数 |
| `capability_switch` | 能力、全局开关、修改时间 |
| `run` | runId、programId、版本、触发来源与订阅、是否试运行、开始结束时间、终态、退出码、原因 |
| `operation` | operationId、runId、requestId、参数指纹、能力、授权修订号、受理派发完成时间、状态、效果、错误 |
| `program_kv` | programId、键、值、更新时间 |

- `operation` 在派发前写入，回执后更新，用于去重和重启后的结果核对。
- 记录默认不保存整页节点、短信正文和截图；调用参数中的正文类字段只保存长度与摘要。`run` 和 `operation` 按条数和天数清理。
- 程序私有文件保存在 `files/programs/data/<programId>/`，与安装包目录分开。

## 8. 关键时序

### 8.1 手机 AI 编写并由用户安装

```mermaid
sequenceDiagram
    participant U as 用户
    participant A as 手机 AI（聊天 run）
    participant B as 设备桥接
    participant D as DevelopmentService
    participant C as 程序中心
    participant P as ProgramPlatform
    U->>A: 描述需求，选中“程序开发”插件
    A->>B: catalog
    B->>D: catalog
    D-->>A: 能力目录、SDK、包格式
    A->>A: 写入 ~/workspace/programs/my-program
    A->>B: validate
    B->>D: validate → 问题列表
    A->>B: dry-run（模拟事件）
    B->>D: 试运行，命令不派发
    D-->>A: 调用记录与日志
    A-->>U: 说明用途、权限、事件、试运行结果
    U->>C: 打开安装，查看申请
    C->>P: install(path)
    P-->>C: 已安装，默认暂停
    U->>C: 授权并启用
```

### 8.2 事件触发与能力调用

```mermaid
sequenceDiagram
    participant S as ScreenAccessService
    participant E as ScreenEvents
    participant R as EventRouter
    participant M as RunSupervisor
    participant N as 程序进程
    participant API as 本地 API
    participant G as PermissionGate
    participant X as 设备执行器
    S->>E: 窗口事件元数据
    E->>R: screen.window
    R->>R: 匹配订阅，权限投影
    R->>M: 无进行中运行，请求启动
    M->>N: 启动 node runner.js（标准输入传凭据）
    N->>API: GET /v1/events
    API-->>N: program.event
    N->>API: POST /v1/operations screen.click
    API->>G: check
    G-->>API: Allowed(rev)
    API->>API: 写 operation（派发前）
    API->>X: withWindow 匹配并点击
    X-->>API: accepted
    API-->>N: succeeded / confirmed
```

### 8.3 运行中撤权

1. 用户在程序中心关闭 `screen.click`；平台控制线程更新授权并增加修订号。
2. 闸门对该程序之后的调用返回 `PERMISSION_DENIED`；已通过闸门但未派发的调用在派发前因修订号变化被拒绝。
3. 若关闭的是必需权限，平台向运行发送 `run.stopping`，宽限期后结束进程；订阅索引重建，不再拉起该程序。
4. 运行记录写入原因 `permission_changed`。

### 8.4 应用进程重启

1. 平台启动时读取遗留的程序进程身份，校验启动时间后结束它们。
2. 未结束的 `run` 记为中断；已派发未回执的 `operation` 记为 `unknown`。
3. 重建订阅索引和事件来源需求，重新登记定时任务；不重放进程停止期间的屏幕事件。

## 9. 安全

| 风险 | 处理 |
| --- | --- |
| 局域网或网页访问本地 API | 只绑定回环；拒绝 `Origin` 头和非回环 `Host`；不开启 CORS |
| 一个程序冒充另一个程序 | 身份只来自运行凭据；凭据随运行生成、只在内存、运行结束作废 |
| 程序自行扩大权限 | API 不提供授权入口；申请只进入待处理列表；更新不自动扩大 |
| 绕过 SDK 的同 UID 代码 | 无法阻止；程序中心如实说明；只支持用户信任的程序 |
| 读取网关密钥和用户配置 | 不提供相关能力；凭据与配置不进入程序目录、环境变量和日志 |
| 恶意或损坏的程序包 | 严格校验路径、大小、文件类型和清单；从安装副本运行 |
| 资源耗尽 | 运行期限、并发、事件队列、请求速率、数据配额都有上限 |
| 外部内容诱导 | 屏幕文字、通知内容等作为数据返回，不影响授权判断 |

## 10. 可靠性与结果语义

- 调用状态与效果分开：`succeeded/confirmed` 才表示副作用已确认；`unknown` 不能显示为成功。
- 派发前持久化调用记录；超时或断线后按 `requestId` 查询原操作。
- 平台不对没有幂等保障的操作自动重试。
- 运行结束和进程退出码不等于业务成功；运行记录保留每次调用的独立状态。

## 11. 性能

- 无障碍回调只做元数据转发；节点读取只发生在程序调用 `screen.read` 或 `screen.click` 时。
- 同一订阅的连续事件在一次运行内处理，避免每个事件都启动 Node。
- 屏幕事件在队列中合并，只保留最新状态。
- Node 启动耗时、读取窗口耗时、事件到点击的端到端延迟在第一阶段真机上测量后再确定期限和合并参数。

## 12. 测试策略

| 层 | 内容 | 工具 |
| --- | --- | --- |
| 程序包与清单 | 合法包、未知字段、路径穿越、符号链接、超限、越权订阅 | JUnit |
| 权限闸门 | 四层组合、到期、修订号竞态、撤权后排队调用 | JUnit |
| 事件路由 | 过滤、权限投影、合并、暂停订阅、需求并集 | JUnit |
| 运行管理 | 启动超时、空闲退出、强制结束、重启恢复，使用假启动器 | JUnit + 协程测试 |
| 本地 API | 认证、Origin/Host 拒绝、去重、冲突、错误格式、SSE | ktor `testApplication` |
| 存储 | DAO、清理、重建 | Robolectric + 内存 Room |
| 设备扩展 | 窗口快照、事件转发、协调器、聊天路径回归 | Robolectric |
| SDK | runner 的凭据读取、事件分发、错误、超时查询 | `node --test` |
| 模块边界 | 新模块依赖白名单与包引用 | `runtime/test_module_boundaries.py` |
| 真机 | 见方案文档各阶段验收 | 已连接手机 |

对可复现的缺陷先写失败的回归测试再修复。完成后运行 [AGENTS.md](../../AGENTS.md) 列出的 Node、Python 和 Gradle 检查，并补充 `:program-platform:testDebugUnitTest` 与 SDK 测试。

## 13. 实施拆分

第一阶段（底座）按以下顺序实施，每项完成后独立测试与提交：

| 序号 | 工作项 | 依赖 |
| --- | --- | --- |
| 1 | 新建 `:program-platform` 模块与边界测试更新；`programs.db` 与 DAO | — |
| 2 | 能力注册表与描述；`DeviceExecutors` 公开入口；现有查询与命令能力的适配器 | 1 |
| 3 | 程序包读取、安装、版本、删除 | 1 |
| 4 | 权限模型、闸门、全局开关、到期 | 1、2 |
| 5 | 本地 API 服务、认证、操作去重与记录 | 2、4 |
| 6 | 运行管理与 `TermuxProgramLauncher`；SDK 与 runner | 5 |
| 7 | 订阅持久化与事件路由（先接平台事件与手动触发） | 4、6 |
| 8 | 开发服务与聊天开发插件 | 3、6 |
| 9 | 程序中心、安装确认与权限管理页 | 3、4、7 |
| 10 | 真机验收：手机 AI 编写只用现有能力的程序，完成安装、授权、运行、撤权、删除 | 全部 |

第二阶段在此之上增加 `screen.window`、`screen.read`、`screen.click`、`ScreenEvents`、`ScreenCoordinator`、`time.schedule` 与 `notify.show`。

## 14. 待验证问题

- 应用在后台时，由无障碍事件或 WorkManager 触发能否在主流厂商系统上稳定拉起 Node 子进程；若不能，是否需要为程序运行增加前台服务及其类型与用户可见状态。
- Node 冷启动耗时是否满足屏幕事件场景；若不满足，是否允许运行在空闲期限内保持进程。
- 定时任务在省电策略下的实际延迟。
- 高频内容变化事件对耗电和主线程的影响。
