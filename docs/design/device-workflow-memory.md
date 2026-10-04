# 手机操作历史与多步执行协议（设计提案）

状态：首版已实施，以下完整 JSON 模型仍是后续扩展目标。首版集中在 `DeviceWorkflowManager`：屏幕技能新增 `workflow-start`、`workflow-run`（最多预测 3 步）、`workflow-finish`、`workflow-list`、`workflow-replay`；每个真实 `click/type/back` 自动记录到应用私有的 `device-workflow-history.db`。整段步骤通过验收后才标记为可回放。已验证流程可用 ID 逐步回放，回放前核对当前应用、页面结构及目标唯一性，回放后核对页面结构或输入结果。每一步仍经 `mobby.device/1` 产生独立操作卡，外层请求 ID 持久去重。首版仅支持屏幕插件，不支持按自然语言自动检索案例、自动参数命名、可视化历史管理或 Shell/CLI 命令回放；下文 `case` 和通用 `mobby.workflow-history/1` 为后续设计，不应视为已实现。

首版实际使用方式：Agent 先 `workflow-start` 标记本次任务，再用 `workflow-run` 提交最多 3 个 `click/type/back` 步骤；成功的 `workflow-run` 自动封存。若用原有的单步 `screen` 命令操作，任务验收后调用 `workflow-finish` 才封存。下一次使用 `workflow-list` 找到流程 ID；`workflow-replay` 按 ID 执行，并用 `values.input_2` 等字段传入本次输入。只有 `replayable=true` 的整段流程可回放。历史只存控件查询词、动作、应用包名和不含页面文字的结构摘要；`type` 的原文不写入该历史库。密码输入框的步骤不可回放。当前的通用协议、设置入口、完整 UI 元素断言和跨应用布局适配仍按后文继续演进。

首版控件查询词采用长度与格式过滤，仍可能包含用户起的名称；本地历史库只供应用私有访问，用户内容分类与删除界面需要后续实现。当前 App 清单关闭了自动备份。单元测试覆盖两步执行、参数替换、同请求去重、模糊控件与页面变化停止、未知效果不重发，以及生成的 Node helper 到本地桥接。真机完整回放仍需在可覆盖安装且已开启无障碍的设备上验收。

## 1. 要记录什么

一次任务保存为 `task`，每次实际设备动作保存为 `step`。每条可复用的 `step` **直接包含下一次可提交给设备桥接的动作模板**，以及执行前、执行后的页面条件。它同时保留证据：动作前看到了什么、实际提交了什么、执行器确认了什么、动作后看到了什么、是否达到预期。失败、取消和效果不明的尝试也保存，但不进入可复用流程。

现有 `mobby.device/1` 的 `DeviceOperation` 继续服务当前运行的操作卡、状态和去重。它的 `input` 是展示用投影，不等于完整 `args`；`screen_observation` 是动作后的一次观察，截图资源还可能被缓存淘汰。因此历史协议使用独立私有库，不能靠现有操作卡反推可执行记录，也不改变现有桥接请求含义。现有 `requestId` 去重只覆盖当前 run；历史流程每次执行都生成新的请求 ID。

### 记录模型 `mobby.workflow-history/1`

| 实体 | 必需字段 | 含义 |
| --- | --- | --- |
| `task` | `id`, `runId`, `goalTemplate`, `createdAt`, `endedAt?`, `outcome`, `steps[]` | 一次用户任务；`outcome` 为 `running/verified/failed/cancelled/unknown`，`goalTemplate` 是脱敏后的目标描述，不保存完整对话。 |
| `observation` | `id`, `capturedAt`, `packageName`, `windowSignature`, `visibleElements`, `quality` | 一次真实读取；`visibleElements` 只保留匹配所需的角色、短标签、可点击性和稳定定位线索；`quality` 标示空树、截断、受保护窗口等。 |
| `step` | `id`, `taskId`, `index`, `beforeObservationId`, `replay`, `dispatch`, `receipt`, `afterObservationId?`, `verification`, `state` | 一次实际尝试。`index` 单调递增；不能以新请求 ID 静默重复效果不明的动作。 |
| `replay` | `plugin`, `action`, `args`, `preconditions[]`, `postconditions[]`, `replayable` | 完整动作模板。`args` 的每个值是 `literal` 或 `slot`；两个条件集合是机器可判定的页面断言。`replayable=false` 的历史步骤只供查看。 |
| `dispatch` | `operationId`, `requestId`, `sentAt` | 实际被桥接接受的本轮动作身份；真实参数只供本轮执行，不另存完整副本到学习库。 |
| `receipt` | `status`, `effectState`, `errorCode?`, `finishedAt` | 来自现有设备执行协议的事实；`accepted` 只能说明受理，不能说明目标页面已出现。 |
| `verification` | `verdict`, `checks[]`, `checkedAt`, `reason?` | 独立比较动作前后真实观察与预期；值为 `passed/failed/indeterminate`。 |

`windowSignature` 是本地归一化特征，如包名、窗口类型、稳定的控件角色和标签集合；不是整页文本的哈希。动态列表、时间、广告位变化不应导致匹配失败；也不能只凭包名判定同一页面。`visibleElements` 对密码框不记录文本，对短信正文、联系人、通知、搜索输入等用户内容默认不记录原文。归一化和敏感分类在落盘前执行；无法安全分类就标记该观察不可学习。

`replay.args` 使用与现有动作定义相同的参数名。例如 `screen.click` 需要 `query`，`screen.type` 需要 `text`。字面量写作 `{"kind":"literal","value":"搜索"}`；任务参数写作 `{"kind":"slot","name":"keyword"}`。回放器将槽位从**当前任务**绑定成真实字符串，按 `DeviceActionDefinition` 再校验，生成新的 `requestId` 后才提交现有 `DeviceRequest`。模板里不存旧 `requestId`、令牌或可直接复用的旧用户输入。

一个非敏感的可回放示例（省略数据库行 ID 与时间）：

```json
{
  "schema": "mobby.workflow-history/1",
  "task": {"id": "task-1", "runId": "run-1", "goalTemplate": "在当前应用搜索{keyword}", "outcome": "verified"},
  "step": {
    "id": "step-1", "index": 1,
    "before": {"packageName": "com.example.store", "windowSignature": "store:home:search-entry", "visibleElements": [{"role": "button", "label": "搜索", "clickable": true}], "quality": "complete"},
    "replay": {
      "plugin": "screen", "action": "click",
      "args": {"query": {"kind": "literal", "value": "搜索"}},
      "preconditions": [{"kind": "package_is", "value": "com.example.store"}, {"kind": "element_visible", "role": "button", "label": "搜索", "clickable": true}],
      "postconditions": [{"kind": "element_visible", "role": "edit_text", "label": "搜索商品"}],
      "replayable": true
    },
    "dispatch": {"operationId": "op-1", "requestId": "req-1"},
    "receipt": {"status": "succeeded", "effectState": "confirmed"},
    "after": {"packageName": "com.example.store", "windowSignature": "store:search:empty", "visibleElements": [{"role": "edit_text", "label": "搜索商品", "clickable": true}], "quality": "complete"},
    "verification": {"verdict": "passed", "checks": [{"kind": "element_visible", "role": "edit_text"}]},
    "state": "verified"
  }
}
```

`receipt.effectState=confirmed` 在屏幕动作中表示 Android 执行器确认点击/手势完成；示例的 `verification=passed` 才说明预期输入框出现。两者独立。截图不属于持久历史，原有 `observationRef` 只供本轮排错；资源消失不改变已保存的结构化判断。

后续输入搜索词的步骤可直接保存为 `{"plugin":"screen","action":"type","args":{"text":{"kind":"slot","name":"keyword"}},...}`。回放时必须由当前用户目标提供 `keyword`；缺少参数就停止。登录凭据、验证码等内容不生成可绑定的自动回放槽位。首版只允许白名单中的文本参数槽与固定控件标签；不支持任意表达式、脚本或跨步骤读取页面文本填参。

## 2. 写入协议与状态

历史库由应用进程写入。Agent 只能提交候选计划及调用现有设备桥接，不能直接写「已成功」或改写回执。每个事实带来源：`model_proposal`、`device_receipt`、`accessibility_observation`、`local_verifier`。读历史供模型参考时，它是数据，不是新的系统指令。

1. 开始任务，持久化 `task(running)`。读取当前页面，落盘 `before`；读取失败则记录 `quality=unavailable`，不预测点击。
2. AI 一次产生最多 3 个 `replay` 候选，填好 `plugin/action/args/preconditions/postconditions`。这是预测队列，不是已执行记录。应用先校验当前插件授权、动作白名单、参数槽、目标是否可定位；首步通过才进入派发。
3. 在派发前持久化 `step(proposed)`、回放模板、前置观察 ID 与唯一 `requestId`；绑定现有操作的 `operationId` 后写 `dispatched`。如果历史库写入失败，停止自动链。实际动作仍由 `mobby.device/1` 执行和去重。
4. 保存执行器返回的终态与 `effectState`。超时或连接断开时用原 `requestId` 查本轮 `operation.status`；仍不明则记 `unknown` 并停止。不能换 ID 重发写动作。
5. 动作结束后重新读取页面，保存 `after`。按 `replay.postconditions` 验证包名、窗口特征及所需控件；如果观察缺失或质量不足，记 `indeterminate`，不声称成功。
6. 仅当回执允许成功判断且验证通过，记 `verified`，再检查预测队列中的下一步前置条件。页面不符、目标多义、权限变化、取消或用户手动改变页面时结束此队列并让 AI 根据最新页面重规划。
7. 任务结束时单独验收目标，写 `task.outcome`。步骤全部通过仍不自动等于任务完成；例如搜到商品列表不等于完成下单。只有任务目标和相关步骤均验证通过，才提取可复用案例。

记录写入需保持事实顺序和崩溃恢复：任务/步骤/观察在同一私有 SQLite 库中按事务追加，状态只能前进，终态不可被模型覆盖。重启后把未完成步骤核对现有 `operation.status`；无法确定的保留 `unknown`，不自动续跑。`operationId`/`requestId` 只用于关联当轮执行，不作为跨任务去重键。历史写入不能干扰现有操作卡；若在派发前失败则不执行该步，派发后失败则停止后续步骤并如实保留设备操作结果。

## 3. 可复用流程与检索

从已验收任务抽取独立的 `case`：`caseId`、规范化目标、应用包名、入口页面特征、参数槽、按序排列的 `replay` 步骤、来源 `taskId`、成功次数、失败次数、最近验证时间、协议版本。案例直接引用或复制已验证步骤的**可执行模板**，无须再让 AI 从自然语言重新翻译动作。原始历史是证据；`case` 是可删除、可重新生成的派生索引。没有足够证据时只保留历史，不生成案例。一次成功可以生成候选案例，但默认需要再次成功验证后才提升检索优先级。

例如搜索流程中的两个实际设备调用可以保存为：

```json
{
  "caseId": "search-in-store",
  "slots": ["keyword"],
  "steps": [
    {"plugin": "screen", "action": "click", "args": {"query": {"kind": "literal", "value": "搜索"}}, "preconditions": [{"kind": "element_visible", "role": "button", "label": "搜索"}], "postconditions": [{"kind": "element_visible", "role": "edit_text", "label": "搜索商品"}]},
    {"plugin": "screen", "action": "type", "args": {"text": {"kind": "slot", "name": "keyword"}}, "preconditions": [{"kind": "element_visible", "role": "edit_text", "label": "搜索商品", "focused": true}], "postconditions": [{"kind": "element_value_equals_slot", "role": "edit_text", "slot": "keyword"}]}
  ]
}
```

这两个 `steps` 按现有 `DeviceRequest` 的 `plugin/action/args` 生成请求；`preconditions/postconditions` 只用于本地判定。`element_value_equals_slot` 仅在当前执行期间比较，不把实际搜索词写入长期历史。若无障碍服务取不到输入框的值，验收返回 `indeterminate`，不会把这次回放记为成功。

新任务先按目标、当前应用和页面特征检索少量案例。AI 可以选择案例或提议新的多步模板；**已验证案例由回放器直接逐步执行**，不必让 AI 再生成具体设备函数。每步先读取新页面、匹配 `preconditions`、绑定当前槽位、校验参数，随后发起真实设备请求，再用新观察核对 `postconditions`。目标控件必须唯一，`type` 还需确认焦点落在预期输入框；否则停止。候选案例与当前 UI 不一致时直接舍弃，不用旧坐标硬点。`screen.tap` 的坐标与屏幕尺寸、旋转和布局绑定，首版案例只复用语义目标（如可见控件标签），不复用裸坐标。输入文字运行时从当前用户请求绑定；缺值就停止，不从历史拿旧值。执行失败时保存该次失败，并降低案例优先级；绝不覆写原成功记录。

```mermaid
flowchart TD
    A[用户任务 + 当前页面] --> B[检索已验证案例]
    B --> C[选择已验证步骤模板或 AI 提议至多 3 步]
    C --> D[读取当前页面并取出本步回放模板]
    D --> E{当前页面符合前置条件?}
    E -- 否 --> R[记录不匹配并重新规划]
    E -- 是 --> F[绑定参数并记录新 requestId 调用设备动作]
    F --> G[保存设备回执并重新观察页面]
    G --> H{效果已知且后置断言通过?}
    H -- 否或不明 --> I[记录失败或不明并停止自动链]
    H -- 是 --> J[记录本步 verified]
    J --> K{还有步骤且前置条件符合?}
    K -- 是 --> D
    K -- 否 --> L[验收任务目标并更新案例]
```

图中的循环每次都必须重新经过真实页面校验；候选计划中的第 2、3 步不会因为上一动作的点击回调成功而自动执行。

## 4. 私有存储、权限与清理

- 首版使用应用私有的 `device-workflow-history.db`，包含流程、步骤和外层请求去重表。下文通用任务/案例/观察分表是后续结构，开发期结构变化仅重建此库；现有 `runtime-journal.db`、SharedPreferences、HOME 与工作区不动。SQLite 中只存首版所需的结构摘要与步骤模板，输入原文不写入该历史库。
- 首版默认只记屏幕操作的脱敏结构和可回放模板。原始页面树、截图、密码、短信正文、电话号码、令牌、完整命令行、Agent 提示和模型内部推理不进入这个历史库。`screen.type` 的原值不存，只存当前任务绑定的槽位名；敏感字段整步设为 `replayable=false`。哈希不能充当匿名化：短词、号码仍可猜测，因此敏感内容不存可离线枚举的摘要。现有运行日志中的展示输入另有存储规则，实施时需一并审查，不能把本库的脱敏承诺误说成全应用都不保存。
- 首版保留最近 30 天内最多 100 段流程，每段最多 100 步后自动分段；外层请求去重记录保留以避免重复执行。32 MiB 总预算、设置中的查看/删除/关闭学习、备份排除和案例索引均属后续实施范围。实际产品设置与现有事件保留设置须分开标注，避免误认为清理运行日志就清理了学习记录。
- 每次复用仍检查当轮 `plugin:device:screen` 引用、系统无障碍权限、停止状态和设备操作白名单。历史不会扩大权限；现有写入插件的独立引用规则也不变。用户删除历史后，案例和检索索引同步删除。

## 5. 实施边界与验收

1. 先落地纯 Kotlin `workflow-history/1` 数据模型、状态机与脱敏规则，独立 SQLite 存储；接入当前 `DeviceOperationPort` 的可信回执，不能从 CLI 文本或展示卡倒推结果。
2. 为 `screen.snapshot` 和动作后的新鲜观察接入结构化采集。现有 `ScreenAccessService` 的 `Observation.text` 是可读文本，缺稳定控件 ID；`screen.type` 还可能退回到第一个可编辑控件。回放前须补充可匹配的元素描述、唯一目标及焦点校验，并约束数量；采集不到时明确降级为 `indeterminate`。
3. 做单步可回放模板生成与验收，再做案例检索、参数槽绑定与逐步回放。模板只含现有动作白名单；首版从 `click/type/back` 开始，`snapshot` 作为观察而非可回放的效果步骤。是否允许 `home/recents/tap` 进入案例须按观察能力单独验收。保持现有逐动作桥接，不新增一次性盲执行的批量命令。
4. 测试覆盖：前后观察区别、点击成功但页面未达预期、模糊目标、敏感值不落盘、未知副作用不重发、崩溃恢复、删除联动、授权撤销、布局变化后停止。真机用至少两个实际页面验证复用与界面变化后的停止；模拟测试不能代替真机验收。

本协议记录的“命令”是具体设备动作及其参数模板。若以后要求复用 Agent 在 Shell 中执行的命令，需要先建立单独的可信命令来源、作用域和敏感参数分类；当前终端输出与运行命令回执不足以证明任意 Shell 命令可以安全重放。
