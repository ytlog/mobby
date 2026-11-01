# 屏幕操作模式

## 用户行为

在「设置 → 插件 → 屏幕 → 屏幕操作模式」选择：

- **当前屏幕（无障碍）**：保留原有模式，操作手机当前窗口，执行期间隐藏应用悬浮层、保持亮屏；需启用无障碍与任务通知。
- **虚拟屏幕（Shizuku）**：用户安装并启动 Shizuku，授予 mobby 权限，再点击连接。通过 UserService 使用其 shell 或 root 身份。
- **虚拟屏幕（root）**：用户选择并连接后，通过设备现有 `su` 请求授权，启动专用于屏幕功能的工作进程；不安装 root、不改系统权限。

虚拟屏首版要求 Android 13 以上、手机亮屏且解锁；屏幕操作前检查锁屏状态。mobby 内显示任务时通过 View.keepScreenOn 保持亮屏，退出或结束任务后恢复。任务开始时创建一个 720×1280、240 dpi 的独立显示，Agent 使用 `apps` 查找应用包名，再用 `launch` 在副屏启动。用户停留在 mobby，会话上方显示可收起的副屏预览。预览只读；点击、滑动和输入由当前 Agent 的屏幕工具执行。

任务结束、停止或宿主连接断开后释放虚拟屏及其任务内容。应用数据仍属于原应用，虚拟屏不提供分身、独立登录或独立数据。一次仅允许一个虚拟屏任务；模式切换和重新连接必须等待屏幕任务结束。模式保存到宿主 SharedPreferences，权限连接不跨进程重启恢复，需要重新连接。

## 权限与执行边界

四个 Agent CLI、Shell、HOME、网关和 Node 桥接保持原执行身份。权限仅授予独立屏幕工作进程。Shizuku 使用官方 API/provider 13.1.5；不接入第三方 libtermux-android 内尚未启用的 Shizuku 命令封装。

Shizuku UserService 的 Binder 仅交换私有连接地址；root 通过 APK 中的 `RootScreenMain` 启动。两者复用同一个工作进程实现和本机 TCP 协议。地址只监听 127.0.0.1，每个连接使用随机 256 位临时令牌认证，令牌不进入 Agent、命令行、日志或持久存储。协议只暴露固定屏幕操作，不提供任意 shell 命令接口。输入参数保持结构化，启动包名严格校验。

工作进程只接受 UID 2000 或 0；root 模式额外要求连接身份为 0。依赖隐藏 API 的调用只发生在权限工作进程。`VirtualDisplay` 使用独立 ImageReader Surface 获取画面；UiAutomation 使用不压制已有无障碍服务的连接读取目标 displayId 的应用窗口。截图、节点、触摸和返回键都指向同一副屏，不读取默认活动窗口作为替代。

副屏输入法策略设为隐藏，文字通过目标节点的 `ACTION_SET_TEXT` 写入，避免将副屏键盘回退到主屏。密码输入拒绝。虚拟屏中 `home` 和 `recents` 明确不支持，以防厂商系统路由到主屏；应用切换使用 `apps`/`launch`。现有模式的桌面和最近任务能力保留。连接失效、超时、无窗口、不支持的语义及系统拒绝均返回失败，不回退到当前屏幕、不自动重试输入。

屏幕结果增加 `backend` 和 `displayId`，原有 screenshot resource、动作回执、取消与工作流协议继续使用。生成给 Agent 的技能写明本次后端。用户程序平台的 `screen.*` SDK 仍保持原有当前屏幕语义，不因聊天选择虚拟屏而获得提权能力。

## 当前限制

- 厂商 ROM、应用单实例/不可调整尺寸行为、跨应用跳转可能影响副屏可用性。部分应用可能自行跳到主屏；不能据此承诺任意应用都能隔离运行。
- Android 13 的焦点与输入法行为仍需设备验收，不能保证用户与 Agent 完全独立地同时输入。Android 14 以上启用副屏自身焦点标志。
- 受保护内容可能呈黑色；不绕过安全窗口保护。没有节点的自绘界面使用截图和坐标输入，未提供 OCR。
- 预览是按需刷新 JPEG，收起或页面不可见时停止刷新，不是视频流。
- 连接权限不是后台常驻保证；Shizuku、su 后端或宿主被系统结束后需重新连接。

## 验证与复现

`ScreenChannelTest` 覆盖临时令牌握手、中文及 shell 元字符保持原值、root 身份拒绝、损坏响应关闭连接且不重试输入。`ScreenControlTest` 覆盖保存虚拟模式后不能借用现有无障碍权限回退到当前屏幕、切回当前模式恢复原有权限判断，以及启动参数校验。

独立真机探针使用生产工作进程，以 ADB shell 身份验证底层路径；它不能替代 Shizuku 授权与 UserService 生命周期或真实 root 验收。已有手机可执行：

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
runtime/device-plugins/build_probe.sh
python3 runtime/device-plugins/probe_screen.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --jar runtime/device-plugins/build/screen-probe/worker.jar
```

构建脚本仅使用本机已有 Platform 36 和 Build Tools 35，不下载系统镜像。默认在副屏启动系统设置，检查窗口树、画面、非法输入拒绝、全局按键拒绝和关闭。专用测试 App 位于 `probe/`，APK 与测试签名仅在忽略的 build 目录生成。允许安装测试 App 的手机可安装 `build/screen-probe/probe.apk`，然后传入 `--package dev.mobby.screenprobe` 验证计数按钮、坐标点击、中文输入和滚动；结束后卸载测试 App。

2026-11-02：已连接小米 Android 13，shell 工作进程探针验证独立副屏创建、系统设置启动到同一 displayId、截图、节点树、非法坐标及全局按键拒绝和关闭。独立测试 APK 安装被手机以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，故尚未完成计数按钮、中文输入与滚动探针。手机未检测到 Shizuku 或 su；新增输入法隔离后再次探针时，手机已 Dozing 且 showing=true，窗口读取如实失败，未绕过锁屏。两种实际权限连接、最新输入法隔离、App 内预览与真实 Agent 完整操作仍需验收。

本机 Library 中的 GraalVM 17.0.12 编译 Java/Kotlin 可用，但标准 AGP jlink 转换报 `Module jdk.internal.vm.ci not found`。本轮本地验证使用临时 Gradle init 脚本移除 `JdkImageInput` 参数提供器并设置 `options.release=17`；不修改仓库构建配置、不跳过编译或测试。这是本地工具链限制，不能描述为标准命令无条件通过。

最终本地检查：JDK 17 配合上述本地初始化脚本，Debug APK、App lint、App 278 项、设备插件 57 项、Termux 32 项单元测试、本地化 3 项测试通过；Python 46 项、Node 网关/程序 SDK 30 项通过。当前屏幕模式的快捷无障碍设置入口与原回归保留。APK 未覆盖安装到手机，不能把 shell 探针当作 App 内完整验收。
