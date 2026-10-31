# 用户程序平台实施记录

对应 [方案](design/android-capability-platform.md) 与 [技术架构](design/program-platform-architecture.md) 的第一阶段。本文记录实际实现、验证证据和尚未验证的范围；能力是否可用以本文和真机结果为准，不以方案目录为准。

## 已实现

| 范围 | 实现 | 代码 |
| --- | --- | --- |
| 模块 | `:program-platform`（`runtime/programs`），边界测试限定依赖 `device-plugins`、`runtime-api`、`localization` | `runtime/test_module_boundaries.py` |
| 数据 | `programs.db`：程序、版本、授权、权限申请、配置、凭据、订阅、全局开关、运行、操作、键值、日志 | `store/ProgramsDatabase.kt` |
| 能力注册与调用 | 注册表、Schema 子集校验、调用管线（去重、校验、权限、派发前记录、修订号复核、期限、频率上限、试运行） | `capability/`、`call/` |
| 手机能力 | 短信、联系人、日历、剪贴板、位置、传感器、授权目录，经 `DeviceExecutors` 复用现有执行器 | `capability/DeviceCapabilities.kt` |
| 平台基础能力 | `storage.kv`、`storage.files`、`storage.sql`、`net.http`、`secrets`、`config`、`log`、`notify.show`、`time.schedule` | `base/`、`event/ScheduleCapability.kt` |
| 程序包 | `mobby.program/1` 严格校验、安装副本、更新只取旧授权与新申请的交集、删除可选保留数据 | `program/` |
| 权限 | 系统、全局开关、程序授权（范围、到期）、调用上下文四层；用户只能缩小授权；程序只能查询自己的权限并提交申请 | `permission/` |
| 运行 | 每个运行一个 Node 进程、并发与排队上限、启动超时、空闲与期限、宽限停止、退出分类、重启恢复；凭据经标准输入 | `run/RunSupervisor.kt`、`runtime-android/.../TermuxProgramLauncher.kt` |
| 本地接口 | ktor CIO 绑定 `127.0.0.1`，运行凭据认证，拒绝 `Origin` 与非回环 `Host`；操作、能力、权限、订阅、SSE 事件流、界面消息 | `api/` |
| 事件 | 订阅索引、平台事件、WorkManager 定时（每条订阅一个唯一任务） | `event/` |
| 程序界面 | 每程序独立页面源、只读安装副本、内容安全策略、拒绝权限请求与弹窗、来源绑定的消息桥、固定标题栏 | `ui/`、`app/.../ProgramUiActivity.kt` |
| SDK | 后台 `mobby` 与 `runner.cjs`、页面 `/_mobby/sdk.js` | `src/main/assets/program-sdk`、`program-ui-sdk` |
| 程序开发页 | 输入框“+”菜单中的“程序开发”是管理入口，左侧栏“程序”分组和空对话的程序图标按最近打开列出已启用程序并直接打开页面，空对话快捷卡片“程序开发”附加开发插件：让 AI 写新程序；“待处理”自动列出工作区中待安装或待更新的程序，一页确认即安装、授权、填写必填配置并启用；“已安装”用文字显示状态并可直接启停；详情（权限、申请、配置、事件、运行与日志）、权限总览、让 AI 修改 | `app/.../programcenter/` |
| 开发插件 | `plugin:device:programs` 的 `catalog`、`validate`、`dry-run`、`records`；`plugin:program:<id>` 附加程序 | `dev/`、`device/ProgramDevelopment.kt` |

## 验证

- `:program-platform` 单元测试：数据库、能力、包校验、安装、权限、调用管线、本地接口、运行管理、存储、网络与凭据、事件路由、界面请求规则、开发服务。
- `:device-plugins` 单元测试：新增开发插件命令与附加程序限制。
- `node --test runtime/programs/sdk-tests/sdk.test.cjs runtime/programs/sdk-tests/ui-sdk.test.mjs`：后台 SDK 调用与恢复、runner 凭据与事件分发、页面 SDK。
- AGENTS.md 中的 Node、Python 与 Gradle 检查（构建、单元测试、lint）。本机 python.org 版 Python 缺少证书，`generateLicenseNotices` 联网补取 POM 时需要设置 `SSL_CERT_FILE=/etc/ssl/cert.pem`。

### 真机验收（2026-10-24，小米平板 Android 16）

用一个计数程序（后台 `main.js` 收到界面消息后写 `storage.sql`、`storage.kv`，请求 `https://api.github.com/zen`，记日志并回发界面）验证：

- 经程序开发页安装：预览列出申请的权限并提示需安装后授予；安装后为“已暂停”，授予三项权限并启用后变为“可工作”。
- 打开程序界面：页面 SDK 直接查询 `storage.sql`，表不存在时如实返回 `INVALID_ARGUMENT`。
- 点按“加一”：每次界面消息拉起一个 Node 进程，计数 1→4 持续累加，覆盖安装后数据保留；`net.http` 返回 200 与正文；空闲后进程以退出码 0 结束，运行记录与日志一致。
- 运行期间撤销 `net.http`：下一次网络调用返回 `PERMISSION_DENIED: program_off`，其他能力不受影响。

真机发现并修复：详情页的程序日志只在运行记录数量变化（运行入队）时读取一次，晚于入队写入的日志不显示，现改为监听日志表；运行记录的结果、触发来源和结束原因原先显示内部取值，现按界面语言显示。

### 手机 AI 编写程序（2026-10-25，同一平板，Pi + 免费模型路由）

从输入框“+”菜单 →“程序开发”→“让 AI 写新程序”附加开发插件后，让手机 AI 编写 `header-watch`（每 15 分钟带凭据请求 `httpbin.org/headers`，写入 `storage.sql`，发通知；页面列出最近 20 条并可刷新）。AI 自行写出清单、后台和页面，调用 `dry-run` 与 `validate`，试运行记录显示网络与通知为模拟、SQL 实际写入试运行库并在结束后清理。

由此发现并修复的平台缺陷：

- 试运行中模拟的 `net.fetch` 没有状态与正文，`json()` 抛出看似解析失败的 `SyntaxError`，AI 只能靠猜测绕开。现在模拟响应带 `simulated: true`、`status` 为 `null`，读取正文抛出 `MobbyError` `SIMULATED`。
- 开发指南没有说明 `sql.query` 的结果形状，AI 把行当作对象读取。指南现说明 `{columns, rows}`、每行是数组，以及试运行中命令与网络调用的返回。
- 对话中开发插件结果卡片显示“此结果类型尚不支持展示”，用户看不到校验和试运行结果。结果改为 `program_report` 类型，卡片列出校验结论、每次调用与日志、记录数量。

AI 读取新指南后改用 `response.simulated`，其后的两次试运行无错误；但页面仍按对象读取行，属于该模型未按指南修改，安装后列表会显示 `undefined`。尚未验收：安装该程序、填写测试凭据、15 分钟定时在后台触发、通知打开页面；验收中平板被其他应用占用而暂停。

### 安装与后台定时（2026-10-26，同一平板）

- 新版结果卡片在对话中显示试运行结论与每次调用。经“程序开发”页安装 `header-watch`，授予四项权限，启用后立即触发一次定时运行：`net.http` 返回 200，SQL 写入两次，`notify.show` 成功；页面显示该条记录。
- 凭据：密钥字段原未声明密码输入，平板的讯飞中文输入法改写了输入内容，保存的值与输入不符（记录显示 7 个字符）。已改为密码键盘并关闭自动更正；修复后的实际输入与回显尚未在平板复测（屏幕已锁定）。
- 覆盖安装后首次定时运行以 `runtime_not_ready` 失败：内置可执行文件是指向 APK 原生库目录的链接，更新后目录变化，只有界面启动的运行环境准备会重建链接。程序启动器现在会在运行环境文件与内置版本一致时自行重建链接，版本不一致时报告 `runtime_update_pending`。该修复由单元测试覆盖，平板上的后台路径未能验证，原因见下条。
- 启用后 6 小时内没有任何定时运行。系统记录显示 MIUI 拒绝在后台启动 mobby 的 `SystemJobService`（`AutoStartManagerService: Reject service`），任务标记为 `Restricted due to: frozen`，应用待机分组为 `RARE`。这是系统的自启动与省电限制，需要用户在系统设置中允许 mobby 自启动并取消省电限制；应用不修改系统设置。在此之前，该平板上的后台定时和更新后后台重建链接都无法验收。

### 后台定时、凭据与通知复测（2026-10-29，同一平板）

- 后台受限的根因：即使进程存活，mobby 在后台时仍处于待机分组 `RARE`，系统把定时任务推迟数小时；此前还被系统强制停止过一次，强制停止会清除全部定时任务，直到再次打开应用。“程序开发”页新增“后台运行”状态，未豁免电池优化时说明影响并打开系统设置页（小米上为 mobby 的“电量详情”），由用户选择；mobby 不自行修改系统设置。用户选择“无限制”后，mobby 进入系统电池优化白名单，待机分组变为 `EXEMPTED`。
- 之后约 6 小时内，屏幕熄灭、mobby 在后台时共 22 次定时运行全部正常结束（退出码 0），其中 20 个间隔正好 15 分钟，1 个为 43 分钟。每次运行都发出通知；点按通知打开该程序页面。
- 凭据：改为密码键盘后，输入 18 个字符，保存 18 个字符；刷新后 `httpbin.org` 返回 200，回显的 `Authorization` 与 `Bearer <输入值>` 完全一致。比对在本机脚本中进行，未打印测试值。
- 更新安装后直接进入程序详情，保留授权与“可工作”状态；安装预览显示事件的过滤条件和处理函数。
- 一次在应用启动时触发的运行以退出码 2 结束：平台已因空闲请求停止，但停止消息送达前事件流被切断，运行器报 `TypeError: terminated`。记录如实显示“空闲退出 · 退出码 2”；该次运行持续 87 秒，可能与系统冻结后台进程有关，原因未确定，未据推测修改代码。

### AI 修改程序后的更新安装（2026-10-29，同一平板）

- 经“让 AI 修改此程序”，手机 AI 为 `header-watch` 新增 `label`（字符串，通知标题）、`maxRows`（数字，页面行数）两项配置和可选的 `storage.files` 权限，页面增加“Export CSV”。版本号未变（仍为 1.0.0），安装预览显示“将从版本 1.0.0 更新”，并列出新的权限与配置。
- 更新后原有四项授权与已保存的凭据保留，新增的 `storage.files` 显示“未授权”，配置显示默认值。授权 `storage.files`，把 `label` 设为 `HW-Check`、`maxRows` 设为 3 后：页面只列出最近 3 条；点 Refresh 触发后台运行，新通知标题为“Header Watch · HW-Check”（平台在标题前加程序名），页面随后刷新为含新记录的 3 条；点 Export CSV 写入程序文件区 `header-watch-export.csv`，表头加 42 行，与程序库中的 42 条一致。调用记录中 `storage.files` 写入成功、效果为已确认，调用方为页面。
- 配置值经 adb 按键输入，没有经过平板的中文输入法；数字配置的输入法表现未单独验证。
- `httpbin.org/headers` 会回显请求头，因此替换后的凭据随响应进入程序自己的库、页面和导出文件。凭据替换只保证程序代码与日志拿不到明文，无法阻止远端把它回传。

### 桌面快捷方式（2026-10-29，同一平板）

- 长按桌面上的 mobby 图标，菜单中出现“H Header Watch”（名称首字图标）；点按直接打开该程序页面，返回后回到 mobby 主界面。
- 把程序暂停后，mobby 的动态快捷方式随即清空；用快捷方式携带的同一 intent 启动，进入该程序详情（显示“已暂停”）。重新启用后快捷方式恢复。程序 ID 不存在时进入程序开发页列表。
- “添加到桌面”：系统接受了请求，但 MIUI 中 mobby 的“桌面快捷方式”权限默认未开启（系统记录 `MIUIOP(10017): ignore`），请求被静默丢弃，没有确认框，应用也收不到拒绝结果。因此点按后改为提示“已请求添加。未出现请在系统应用权限中开启‘桌面快捷方式’”；mobby 不修改该系统设置。开启权限后实际固定到桌面的效果尚未验证。

### 程序列表与一步安装（2026-10-29，同一平板）

- 程序开发页改为“我的程序”：顶部“让 AI 写新程序”，“待处理”列出工作区中与已安装版本校验和不同的程序，“已安装”每行用文字显示状态并带启停开关；权限总览与全部暂停移到右上角“更多”；后台已豁免时不显示后台状态。Header Watch 显示“每 15 分钟 · 10:31 正常”；工作区副本与已安装版本相同，未出现在“待处理”中。
- 让手机 AI 新写 `expense-log`（`storage.sql`、`time.schedule` 必需，`notify.show` 可选，必填配置 `currency`，每天 21:00 提醒）。写完回到列表即出现在“待处理”，显示“AI 写好了，还没安装”。安装页按用途列出“在自己的数据库里存数据”“按时自动运行：每天 21:00”“发通知 · 可选”（默认勾选），并要求填写 Currency；未填写时点“安装并启用”提示先填写。填入后一次确认即回到列表：三项权限均已授予，`currency` 为 `"CNY"`，程序已启用，状态“每天 21:00”。行内开关切到“已暂停”再切回正常。
- 再让 AI 在不改版本号的情况下增加可选 `storage.files` 与 CSV 导出。列表显示“有新版本，多要 1 项权限”；更新页中已授予的“发通知”不再提供勾选，“读写自己的文件 · 新增 · 可选”默认勾选，Currency 已保存不再要求填写。点“更新”后 `storage.files` 已授予，程序保持启用，配置保留。
- 未在平板上验证：程序包在确认前被改动时的重新载入、无法通过校验的程序在“待处理”中的显示（两者由单元测试覆盖）、后台受限时顶部提示的显示；Expense Log 自身页面与导出功能未操作。

- 打开 Expense Log 页面：首次进入时页面在建表前查询，显示“no such table”，这是 AI 程序的问题，平台如实返回了错误。添加一笔 12.5 后列表与本月合计“12.50 CNY”正确，币种取自配置。备注显示为“-”，可能是自动化点按没有落在备注框上，未确认。
- 点“Export CSV”没有生成文件：AI 再次写成 `files.write({op, path, text})`，后台处理函数报 `$.path must be string`，程序日志记有 `handler_failed`。页面仍显示“Exported”，因为 `ui.send` 在消息送达后台时就返回，不等处理函数结束；开发指南已补充这一点，并说明用 `ui.post` 回报结果。试运行不覆盖界面消息，因此没有发现该错误。
- 同时暴露平台问题：处理函数失败的那次运行仍以退出码 0 结束，列表显示“正常”。运行器现在统计未处理的事件（处理函数抛错或未注册），结束时以 1 退出。安装新版本后再次导出，运行记录为“空闲退出 · 退出码 1”，列表显示“每天 21:00 · 11:05 失败”（红色）；修复前同样的失败记为退出码 0。Expense Log 的导出错误本身未修改，可经“让 AI 修改此程序”修复。

- 经“让 AI 修改此程序”请手机 AI 修复上述三处问题（附加了程序开发与该程序）。第一次修改后 `files.write` 改为位置参数，后台用 `ui.post` 回报成功或失败，页面先建表；但 `ui/app.js` 在非 async 的 `onMessage` 回调里用了 `await`，整个页面模块因语法错误不执行，表格空白且不显示任何错误。`validate` 与试运行都没有发现：校验不检查脚本语法，试运行不覆盖页面，页面脚本错误也不进入程序日志。把错误位置告诉 AI 后，它改为 async 回调并用 `node --check` 检查脚本。
- 两次更新都只需在“待处理”点“更新”并确认一次（无新增权限，无勾选项），数据库中已有的记录保留。修复后页面正常显示历史记录；添加 8 和 3（备注 coffee）后合计 23.50 CNY；导出生成 `expenses.csv`，含表头与 3 行，该次运行退出码 0。此前备注为空是测试时输入法处于拼音模式、文字停在候选栏未上屏，与程序无关。

### 页面错误记录与脚本语法检查（2026-10-29，同一平板）

- 程序页面向 WebView 控制台报告的错误（未捕获异常、语法错误、被拦截的资源、`console.error`）写入程序日志，消息为 `page_error`，带文件相对路径和行号；每个页面最多记 20 条，超出后记一条 `page_errors_truncated`。详情页“程序日志”和开发插件 `records` 都能读到。
- `validate` 用设备上的 Node 解析程序包内全部脚本而不执行：`.cjs` 按 CommonJS，`.mjs` 按模块，`.js` 后台优先 CommonJS、页面优先模块，两种方式都无法解析才报错；结果写入 `problems` 并使 `valid` 为 false。运行环境无法检查时给出“scripts were not syntax-checked”警告，不当作通过。检查脚本为 SDK 中的 `check.cjs`，模块经 `node --input-type=module --check` 从标准输入解析，代码不会执行（有测试确认）。
- 平板验收：让手机 AI 写一个故意在非 async 回调中使用 `await` 的测试程序 `syntax-probe`，`validate` 返回 `valid: false` 与 `ui/app.js:2: SyntaxError: Unexpected reserved word`；`expense-log`、`header-watch` 校验通过且无警告（说明检查实际执行）。安装该测试程序并打开页面后，程序日志出现 `page_error`：“Uncaught SyntaxError: Unexpected reserved word”，`ui/app.js` 第 2 行。验收后删除程序及数据，并删除其工作区目录。
- 同时发现：覆盖安装应用时正在进行的 Header Watch 运行被记为“中断（process_restarted）”，该次运行的调用均已成功，列表却显示“失败”。现在“中断”单独显示为“已中断”，不标红；平板上显示“每 15 分钟 · 12:46 已中断”。
- 发现的缺口：删除程序后工作区源目录仍在，它会立刻重新出现在“待处理”，且没有忽略入口。见下一节。

### 忽略与删除后的待处理列表（2026-10-29，同一平板）

- 安装页（含无法通过校验的程序）底部新增“忽略这个版本”；删除程序时也会忽略工作区里同 id 的源目录。平台把目录名和内容指纹记入 `dismissed_candidate`，内容不变时不再列入“待处理”，AI 修改后重新出现；安装该目录会清除记录。工作区源文件不会被删除。删除确认框说明了这一点。新增表使开发数据库按约定重建，已安装程序需重新安装，程序数据目录保留。
- 平板验收（versionCode 31）：重建后 Expense Log 与 Header Watch 均列为“AI 写好了，还没安装”。对 Header Watch 点“忽略这个版本”后从列表消失；安装 Expense Log（填写 CNY）后再“删除，保留程序数据”，列表变为空状态，工作区源目录与数据目录仍在。让手机 AI 把两个程序的补丁版本各加一（1.0.1）并校验，回到列表两者重新出现；重新安装后 Expense Log 页面仍显示原有 3 条记录、合计 23.50 CNY，Header Watch 立即运行并显示“每 15 分钟 · 14:28 正常”。
- 尚无在 AI 未修改的情况下撤回“忽略”的入口。

### 试运行覆盖界面消息与 SDK 参数检查（2026-10-29，同一平板）

- 起因：Expense Log 的三处错误中，`files.write({op, path, text})` 只在真实使用时暴露，接口只回 `$.path must be string`；AI 只试运行了定时处理函数，没有试运行页面发来的导出消息。另发现试运行与已安装程序同 id，试运行中的 `ui.post` 会送到已安装程序打开的页面。
- 现在后台与页面 SDK 在发请求前检查参数类型，不符时以 `TypeError` 拒绝并给出正确写法；试运行的 `ui.post` 不再送到页面，记入结果的 `uiMessages`；运行器加载入口后打印已注册的处理函数，试运行结果给出 `handlers` 与本次未覆盖的 `notExercised`；开发指南说明页面的 `ui.send` 以 `ui.message` 处理函数接收，并给出模拟事件写法。
- 平板验收（versionCode 32，Pi）：只要求 AI 先用一个定时事件试运行 Expense Log，结果为 `handlers: ["onSchedule","ui.message"]`、`notExercised: ["ui.message"]`、`uiMessages: []`。AI 随后按 `ui/app.js` 实际发送的消息试运行 `ui.message`：`addExpense` 返回 `uiMessages: [{"type":"updated"},{"type":"added"}]`；`exportCSV` 的 `storage.sql` 失败（`no such table: expenses`），`uiMessages` 为 `exportFailed`。这暴露了 Expense Log 的一个真实问题：后台导出前不建表，新安装后先点导出会失败；程序本身未修改。用平板上的 Node 直接调用 `files.write({op, path, text})`，得到 `TypeError: files.write(path, text): path must be a string, got an object. Arguments are positional, e.g. files.write('report.csv', text)`，没有发出请求。
- 试运行的 `ui.post` 不送到已打开页面由单元测试覆盖，平板上该次试运行时页面未打开。
- 随后在同一对话中请手机 AI 修复导出：它在导出处理中先 `CREATE TABLE IF NOT EXISTS` 再查询，版本改为 1.0.2，`validate` 通过；`exportCSV` 试运行的调用依次为 `storage.sql`（建表）、`storage.sql`（查询）、`storage.files`，`uiMessages` 为 `exported`，`addExpense` 仍为 `updated`、`added`。在“待处理”点“更新”一次完成；页面点“Export CSV”后 `expenses.csv` 重新写入（表头加 3 行），页面无错误，列表显示“每天 21:00 · 15:20 正常”。
- 同时观察到：已有对话中技能说明里的 `device.cjs` 路径失效，AI 先报 `Cannot find module`，自行查找新路径后继续。见下一节。

### 技能脚本路径跨轮不变（2026-10-29，同一平板）

- 根因：设备桥目录按每轮请求 ID 生成（`files/device-bridge/<请求 ID>`），本轮结束即删除，与是否重装无关；暂存到 Agent 技能目录的 `SKILL.md` 仍写着这个目录里的脚本路径。同一个 Pi 对话的历史里已出现 5 个不同的请求目录，AI 继续对话时照搬上一轮的命令。
- 修复：`SkillStore.stage` 复制脚本后，把 `SKILL.md` 中的源脚本目录替换为技能目录下的 `scripts/`（Pi 为 `~/.pi/agent/skills/mobby-programs/scripts/device.cjs`）。路径每轮相同，脚本内的端口与令牌仍按本轮重写。回归测试：两轮不同端口和桥接目录产生相同的 `SKILL.md`，且不含桥接目录。
- 平板验证：新建 Pi 对话，第一轮读取 `SKILL.md` 后用技能目录路径执行 `catalog`；第二轮未重新读取说明，直接用同一路径执行 `validate`，连到第二轮的桥接并返回 expense-log 1.0.2 校验通过。
- 修复前的旧对话历史里仍是旧的请求目录路径，下一次使用时 AI 会先失败一次再查找；历史记录不做改写。

### 启动时的误报与未附加程序的拒绝说明（2026-10-29，同一平板）

- 启动误报：覆盖安装后首次打开，界面短暂弹出“操作未完成，请检查连接或存储后重试”。根因是运行环境就绪前，界面刷新会读取 Agent 能力，`AndroidRuntimePorts.capabilities()` 在这时就去列技能目录，而 `RuntimeEnvironment.sdk` 尚未初始化，抛出 `UninitializedPropertyAccessException`，被界面的统一兜底捕获。现在未就绪时各 Agent 报 `NOT_READY`，技能列表为空，不再读取 HOME；就绪后界面照常重新刷新。回归测试 `RuntimeCapabilitiesStartupTest`。平板上覆盖安装后冷启动，逐帧截图没有再出现提示。
- 未附加程序：`records` 对未附加的程序返回的原文是 “Attach the program to this chat first”，AI 据此告诉用户程序“尚未安装”。现在的拒绝说明会写明程序 ID 未附加到本对话、这与是否安装无关，并写出用户的操作入口“让 AI 修改此程序”；技能说明也补充了未附加时即使已安装也会被拒绝。平板上在未附加的对话里请求 expense-log 记录，AI 如实说明未附加，并给出这个入口。

开发中由测试发现并修复的缺陷：目录占用统计跟随符号链接；URL 中的凭据占位符导致解析失败；运行在进程启动后才登记凭据，快速子进程会被拒；事件队列满时停止通知无法送达；试运行只清理了程序包副本而遗留试运行存储。

## 尚未验证

- 真机：在 MIUI 开启“桌面快捷方式”权限后固定到桌面的快捷方式，及其他启动器上的确认框；程序提交的权限申请、手机能力调用、`boolean`、`enum`、`package` 配置类型、经中文输入法输入的数字配置；第二台已连接手机。
- 小米平板在允许自启动、省电策略“无限制”后的后台定时已连续运行约 6 小时；更长时间、重启后以及其他厂商系统上的表现，和 Android 12 起子进程限制的实际阈值仍未验证。
- 系统 WebView 的内容安全策略与 Service Worker 拦截（小米平板上 `WEB_MESSAGE_LISTENER` 消息桥已实际工作，拦截行为未单独测试）。
- 第二阶段能力中屏幕事件与操作尚未实现；`ai.call`、`entry` 与 `net.websocket` 已实现，见下文。

## 移除 Office 插件

Office 插件（`plugin:device:office`，程序能力 `office.inspect/read/write`）只能算样子：读 xlsx 时只取文字单元格，数字、日期、公式结果都读不到，相同的值会去重，行列结构也丢了；写入时整份替换原文件，只能写纯文本行，所有内容都塞进 A 列；生成的 pptx 缺少母版和版式；测试只验证了它自己写出的文件。现在把插件条目、执行器、参数规则、程序能力和文案直接删除，不保留兼容处理。手机上的 Agent 可以在工作区里用 shell、`unzip` 和 Node 直接处理这些文件。

## 程序想法入口

程序开发页“让 AI 写新程序”下方新增“试试这些想法”：记账本、每日提醒、网页变化监控。点一项会把程序开发插件加到当前对话，并把这条想法的完整需求填进输入框，不自动发送，用户可以改完再发，程序仍由手机上的 AI 来写。输入框已有文字时保留原文，想法接在新的一行。三条需求只用到现有能力：存储与导出、`time.schedule` 的 `daily` 和 `every`、`net.http`、`notify.show`。

## 程序调用 AI（ai.call）

按用户确认的方案实现：安装时由用户选网关和模型，按次数和 token 限额，第一版只支持文字。`AiCallCapability` 校验参数、检查当日用量后经 `ModelGatewayPort` 发请求；宿主的 `AppModelGateway` 用运行时的 `GatewayTextModels` 按原生协议直接请求网关，与网关连接检查同一做法，请求体、响应体和异常信息不进日志。安装确认页在允许 `ai.call` 时显示“AI 模型”选择；程序详情显示当前模型和今天的用量，可更换模型或调整三项上限。SDK 新增 `ai.call({system, messages, maxOutputTokens})`，开发指南已写明用法和限制。

测试覆盖：能力层（未选模型、调用转发、三项上限、跨天清零、用量估算、可能已到达网关的失败计入用量、schema 拒绝图片和工具字段）、安装与更换（未选或选了不存在的模型时拒绝、删除程序清除绑定）、网关请求（Responses 与 Messages 的请求体和解析、4xx 与 5xx 的区分）和 SDK（参数检查、试运行）。真实网关下由手机 AI 写程序调用的端到端验证尚未进行。

## 分享与快捷方式触发程序（entry）

`entry` 拆为两个事件能力，程序在清单中分别申请并订阅（`filter` 为 `{}`）：

- `entry.share`：用户在其他应用里分享文字，系统分享面板中出现 mobby，点后选择交给哪个程序。只接受 `text/plain`，载荷 `{text, subject?}`，正文与标题合计不超过 32 KiB；空文字或超长时说明原因，不截断。分享入口 `ProgramShareActivity` 只在有程序能接收时启用，没有时不出现在分享面板。发送方应用的身份不传给程序。图片和文件分享尚未实现。
- `entry.shortcut`：程序快捷方式改为指向不导出的 `ProgramShortcutActivity`，只有 mobby 自己的快捷方式能到达。订阅了该事件且可以接收时，点快捷方式只运行处理函数并提示“已交给某程序处理”，mobby 不会到前台；否则仍按原规则打开页面或详情。同步快捷方式时同时更新已固定到桌面的快捷方式，使其指向新入口；导出的 `MainActivity` 仍可打开程序，但不触发入口事件。

两种事件只发给当前有效的订阅（程序已启用、能力已授权、订阅未暂停），运行记录的触发来源显示“分享”和“桌面快捷方式”。开发指南写明了两种事件的载荷和试运行写法。

测试覆盖：授权前后与暂停订阅时的投递、载荷内容、同一运行中的事件顺序、超长和空文字的拒绝、入口订阅拒绝过滤条件；App 侧覆盖快捷方式指向新入口、订阅快捷方式的程序改为运行、只有能接收的程序出现在分享列表。

2026-10-31 小米平板验收（versionCode 62）：在加了“程序开发”插件的对话里，让手机上的 Pi 写一个接收分享并响应快捷方式的程序。Pi 第一次申请了两项权限，但 `subscriptions` 为空，`validate` 和试运行都通过了，装上后却收不到任何事件。为此新增两项检查：`validate` 对“申请了事件能力但没有订阅使用”给出警告；试运行结果增加 `unsubscribed`，列出没有任何订阅会送达的模拟事件；开发指南也补上了订阅示例。Pi 根据警告补上订阅后，安装页显示“接收你分享给它的文字”“点它的桌面快捷方式时运行”。从系统分享面板选 mobby，再选程序，提示“已交给「Clip inbox」”，程序发出通知，运行记录的触发来源为 `share`、正常退出。长按 mobby 图标点该程序，留在桌面并提示“已交给「Clip inbox」处理”，程序发出“1 clip saved”通知。撤销分享权限后分享入口被禁用，重新授权后恢复；用 shell 直接启动快捷方式入口被系统以 not exported 拒绝。验收后已删除该程序及数据和工作区源码。固定到桌面的快捷方式（MIUI 需开启桌面快捷方式权限）和其他启动器尚未验证。

## 程序的 WebSocket 连接（net.websocket）

`net.websocket` 是会话类能力，按域名授权，规则与 `net.http` 相同：默认只允许 `wss://`，`ws://` 需要 `insecureHttp`；拒绝回环、私有网段等地址；凭据占位符 `{{secret:name}}` 可用在请求头和查询参数里，只填给绑定的域名。凭据的绑定域名现在可以落在 `net.http` 或 `net.websocket` 申请的范围内。

- 实现：没有引入新的网络库。`WebSocketConnection` 按 RFC 6455 在 Android 平台的 Socket 与 TLS 上实现客户端，不协商扩展；客户端帧都加掩码，服务端帧带掩码、保留位、非法控制帧或无效 UTF-8 时按协议关闭并说明原因。连接打开到已检查过的地址，TLS 用 URL 的域名做 SNI 和证书校验。握手和 TLS 都有超时。
- 接口：`open`、`send`（文字或 base64 二进制）、`receive`（最多等 25 秒，返回已收到的消息，连接结束时附带 `{code, reason, by}`）、`close`。SDK 提供 `net.websocket(url, {headers, protocols})`，返回的对象有 `send`、`receive`、`close` 和 `for await` 迭代，后台与页面写法相同。
- 生命周期：连接属于打开它的运行或页面会话，其他调用方拿不到；运行停止、页面关闭、授权撤销或收窄时由 mobby 关闭。每个调用方最多 4 个连接，单条消息最大 1 MiB；未读消息超过 256 条或 4 MiB 时关闭连接并报告 `receive_buffer_full`，不静默丢消息。
- 记录：每次调用都写入操作记录，包含域名、`WEBSOCKET`、握手状态 101 和收发字节数，不保存消息内容。
- 试运行不打开连接：`send` 返回模拟结果，`receive` 抛出 `SIMULATED`。

测试用手写的本地 WebSocket 服务端覆盖：收发文字和二进制、关闭握手、对端关闭、分片拼接、自动回 pong、带掩码和非 UTF-8 帧的拒绝、积压关闭、握手被拒或伪造、未授权域名、私有地址和未授权 ws、凭据只填给绑定域名、连接归属与释放、授权收窄后关闭，以及服务端不响应 TLS 时按超时失败（首次测试时这一情况会一直卡住，已修复）。

