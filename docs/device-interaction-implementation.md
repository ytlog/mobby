# Android 设备交互时间线实施

对照 `docs/design/android-device-interaction.html` 的 mobby.device/1 设计，本次接入现有设备插件的真实动作；设计中的候选、排除能力不因此获得执行权限。

## 模块边界

- `device-interaction`：纯 Kotlin 协议、状态约束、固定按钮和文案，包名 `com.github.ytlog.mobby.android.deviceinteraction.model`。不依赖 Android、会话或 Runtime。
- `device-interaction-ui`：Compose 设备操作卡片和资源预览，包名 `com.github.ytlog.mobby.android.deviceinteraction.ui`。只依赖协议与本地化，通过回调表达用户操作。
- `interaction-ui` 中同根包的 `ConversationDeviceCard` 是装配适配器，绑定会话、停止、响应和资源读取。记录组件不持有 ViewModel，不启动设备操作。
- `device-plugins` 保留 Android 执行职责，通过 `DeviceOperationPort` 上报事实；`runtime-engine` 实现逐运行的持久化端口，先保存再允许执行。三个 Agent 使用相同桥接协议。

## 时间线记录与实际能力

展示模板覆盖 `basic`、`message_list`、`message_send`、`media_grid`、`batch_change`、`file_list`、`file_transfer`、`capture`、`record_change`、`measurement`、`connection`、`system_handoff`、`screen_control`。每个设备操作保留独立卡片，按事件顺序插入思考、工具步骤和回复之间；运行时展开详情，终态自动折叠，点标题可重新展开。未知展示类型可展开诊断，候选能力使用没有执行按钮的不可用提示。

现有屏幕、短信、联系人、日历、媒体、目录文件、相机、麦克风、位置、传感器、剪贴板、Office 动作返回结构化结果。相册首屏最多提供六份授权媒体缩略图（Android 10 及以上）；其他条目保留名称和种类。连续屏幕动作各占原始事件位置，历史状态保留；悬浮条读取相同操作事实。

采集面板由真实执行器发起，拍照/录音后可以预览或试听、重采、取消、确认使用。确认后才注册持久资源并返回给 CLI。短信 API 提交、发送回执、送达回执分别处理；当前没有订阅送达回执，不推断送达成功。部分发送、无回执、取消后的不确定副作用不会显示成成功。

## 协议与恢复

请求包括版本、requestId、plugin、action 和 args；token 仅由本轮生成的 helper 注入。未知版本、未知参数和越权动作拒绝执行。每轮 requestId 与参数指纹绑定：相同请求只回放已保存状态，冲突返回 REQUEST_CONFLICT。该去重范围是当前 run，不是跨新任务的全局去重。

类型化 `DeviceOperationUpdated` 与文本/工具事件共享序列和快照。内部事件携带 `DeviceRecord(operation, fingerprint, order)`；fingerprint/order 是存储排序元数据，不是模型提供的卡片描述。取消与恢复终止未完成操作，不自动重放。等待点响应校验 runId、operationId、interactionId、revision 和固定响应值，并在交付执行器前持久化消费记录。

`operation.status` 查询本轮原请求；`operation.resource` 只允许导出该操作已登记的 resourceRef。图片、录音和文件先保存进现有 ResourceStore，再清理临时 inbox。资源受工作区隔离、完整性验证和附件存储预算约束。预览失败显示资源不可用，不重新采集。二进制文件目前提供名称/大小和经授权的 CLI 导出；没有增加任意路径或外部 URL 打开入口。

结果结构沿用设计中的 kind/data/resourceRefs。屏幕结果额外携带 `text` 保存可访问性观察；联系人/日历列表在 `file_items.items` 中保留 recordId，后续修改仍走明确 action。Office 写入结果保存文本预览和持久文件引用。

## 验证边界

回归覆盖协议版本拒绝、参数与权限校验、重复请求、真实 Node helper 到本地桥接、资源归属、恢复与终态、卡片窄屏渲染及无自动执行、资源在临时文件删除后重开。模块边界测试约束卡片不能引用执行器、Runtime 或会话实现。

手机的实际短信运营商回执、相机/麦克风硬件流程、媒体权限组合，以及把悬浮球放到目标按钮上的点击场景仍需真机验收；模拟执行器与 Robolectric 不替代这部分验收。此次未增加候选能力的 Android 执行器。
