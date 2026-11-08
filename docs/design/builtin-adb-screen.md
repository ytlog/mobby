# 内置 ADB 屏幕服务

在“设置 → 运行环境 → 屏幕操作模式”选择“虚拟屏幕（内置 ADB）”。屏幕实现和启动入口随 mobby 打包，无需安装其他 App。保留原无障碍和已有 root 模式，默认使用无障碍模式；外部 Shizuku 模式已移除。

## 使用

1. 在系统开发者选项中手动开启无线调试，手机保持亮屏解锁。虚拟屏仍要求 Android 13 以上。
2. 屏幕向导按需完成通知、Wi-Fi、开发者模式、无线调试和配对，并提供对应厂商说明。如果用户已经启用 mobby 的无障碍服务，点击向导里的系统设置入口会启动一次短时脚本。它只在主屏前台的 Android 系统设置中识别已知页面和导航项，尝试定位系统版本号、进入无线调试详情，或在配对步骤打开“使用配对码配对设备”。在系统开启开发者模式并返回向导后，如果还缺无线调试，会直接进入该设置页。脚本遇到未知页面、锁屏、离开系统设置或超时时停止，页面可以继续手动操作。版本号的连续点击、无线调试开关和系统信任确认由用户完成。
   点击“通过通知配对”会先处理必要通知权限，随后启动通知配对并打开系统设置。mobby 发现本机配对服务后，在通知中提供输入六位码的操作，输入时保持系统配对弹窗打开。发现等待最多 90 秒，通知流程最多 120 秒；可回到页面取消。需要允许 mobby 通知。MIUI 默认样式如隐藏了回复操作，需由用户把通知样式改为 Android；官方 Shizuku 的 MIUI 配对说明也有此要求。
3. 使用系统通知的直接回复输入，配对码只在当次内存中使用，不存储或记录。不通过切换回 mobby 或打开 Activity 填码：实际小米手机会在系统配对界面暂停时关闭配对服务，浮动 Activity 也会导致这个问题，因此未保留已验证不可用的输入路径。
4. 从本页面主动开始的配对成功后，返回 mobby 自动尝试连接一次；已有配对身份时可直接点击“连接”。默认发现本机 TLS 连接端口；发现失败时进入独立的手动端口步骤，填写无线调试首页的连接端口，它与配对端口不同。失败不自动循环重试，端口只在内存中保留。详见[首次使用与屏幕操作引导](setup-guide.md)。
5. 选择屏幕能力后，Agent 在独立副屏中打开、读取和操作应用。用户留在 mobby 内查看预览。重启或工作进程退出后重新连接；系统遗忘该配对时重新配对。

## 权限与生命周期

ADB 身份私钥由软件 RSA 生成，以独立 Android Keystore AES 密钥 `mobby.screen.adb.seal` 进行 AES-256-GCM 加密，密文通过 AtomicFile 保存在应用的 noBackupFilesDir。密钥材料仅在配对、连接时解密到内存；现有网关别名 `mobby.gateway` 不变。另保存是否曾成功配对的非敏感标记，不保存 IP、配对码、私钥明文或屏幕令牌。损坏或缺少加密密钥时明确失败，不静默重建已保存身份。只连接 `127.0.0.1`；mDNS 结果必须属于本机网络接口，远端设备记录不会被连接。仅接受无线调试 TLS，拒绝旧的明文 ADB。

应用只通过 ADB 执行固定的 `AdbScreenMain` 启动命令，路径做 shell 引号转义，不接收用户命令。该入口要求 UID 2000，启动同 APK 的子进程，子进程 `setsid()` 脱离 ADB 会话，再经启动通道返回随机端口和临时认证令牌。应用握手连接私有屏幕 socket 后关闭启动用的 ADB 连接。工作进程没有任意 shell 操作接口；心跳、单任务租约、错误不重试输入、断线释放和不回退主屏沿用现有屏幕实现。

副屏工作进程连接存活时，每次发送屏幕操作、读取工作流页面或提交私密输入前，宿主都使用同一配对身份向启动时的本机 ADB 端口重新做 TLS 认证，并核对 ADB 守护进程公钥。心跳也执行此检查。认证连接只用于握手，不运行命令。无法验证授权时，宿主关闭副屏连接、清除已配对标记，并提示用户在屏幕操作模式中重新配对和连接。所选模式保持内置 ADB，失败的输入不会重放。

通知配对服务运行在普通应用 UID，短时使用 dataSync 前台服务以便在系统配对弹窗中输入。服务不自动重启，不在开机时运行，也不自动更改系统无线调试、安装或权限设置。CLI、Shell、Node 与模型服务均不接触 ADB 凭据或提权入口。

## 固定源码与修改

- `third_party/libadb-android` 来自上游 3.1.1；原归档 SHA-256：`3afbe40b278ddea4480ebb8e7b707404f827d8e41b383e12f4b1b0b82dab1d84`。选择 Apache-2.0 分支并保留其 BSD/MIT 文件声明。
- `third_party/spake2-java` 来自上游 2.2.1；原归档 SHA-256：`ee08d37d187eca2868a66aa49493fc5754e674e506cfeef03300d9b219860e59`。使用 Android 原生实现；C 子模块固定为 `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`，源码已随仓库保留。纯 Java 实现未通过上游密钥交换测试，不参与 mobby 编译。
- 两个 `mobby.gradle.kts` 使用项目已有 AGP、JDK、NDK 27.2 和 CMake 3.22.1，构建 ARM64 原生库，保留 16 KiB 页面支持。不下载新系统镜像，不在用户设备下载二进制。
- ADB 传输源码修改明确标注：TLS-only、连接/读取/打开超时、取消关闭配对 socket、空安全清理、借用的身份不由单次连接销毁、每次构造 TLS 上下文、不记录对端身份或连接异常详情、修复快速 OPEN/OKAY 的等待竞态。
- 原生 SPAKE2 的临时随机数从 libc `rand()` 改为系统 `arc4random_buf()`；`spake2_entropy_test.c` 先复现可由 `srand()` 重置的密钥材料，再验证修改。JNI 上下文清理改为幂等，并与原生操作同步，避免取消与结束重复释放。
- 原文许可证、版权声明和配套源码随 App 离线许可页面及同版本源码包提供。SPAKE2 保留 LGPL-3.0 及所含文件的许可，完整 mobby 源码和构建脚本可用于修改库、重新链接和调试；无额外禁止逆向调试库修改的限制。Conscrypt 2.5.3 与 Bouncy Castle 1.81 是固定 Maven 依赖，纳入许可锁定清单。
- Conscrypt AAR 的 `conscrypt.properties` 标识 BoringSSL 提交 `49f0329110a1d93a5febc2bceceedc655d995420`。AAR 本身没有原文通知文件，因此另外保留同版本 Conscrypt NOTICE、其引用的 Netty/Harmony 许可和该 BoringSSL 提交的完整 LICENSE，随离线许可页面展示。Bouncy Castle 同版本原始 LICENSE.html 亦保留；未将 `LICENSE.class` 误当作文本声明。

## 验证

Android 13 小米实测中，从 mobby 的「通过通知配对」启动，已启用的无障碍服务自动从开发者选项进入无线调试详情并打开系统六位码弹窗。测试停在系统弹窗，未输入配对码，退出后原有配对和无线调试开关保持原状。其他厂商的自动导航仍需逐台验证。

真实系统配对先复现了本机 Android Keystore RSA 私钥在所用 Conscrypt TLS 签名中的 InvalidKeyException。最终实现使用 Keystore 加密的软件身份，替换该不可用路径，不在握手失败时切换身份或回退到其他权限。

2026-11-03，使用已有 JBR 21、SDK 和 NDK 构建 mobby 99 并覆盖安装，保留现有配置、HOME 与工作区。没有安装其他辅助 App 或系统镜像。原生 SPAKE2 手机测试通过（匹配与不同秘密、重复清理），宿主 CSPRNG 回归先失败后通过。

`BuiltinAdbPairingDeviceTest` 在真实系统配对弹窗保持前台时，将当次六位码经只在内存使用的私有测试通道交给 mobby 通知的实际 RemoteInput PendingIntent，触发正式配对服务、TLS/SPAKE2 和 Android 系统配对。成功用例耗时 8.626 秒，系统无线调试列表显示 `mobby-screen`。这验证实际后端，不等同于 MIUI 通知回复按钮的手动点击验收；后者等待用户将通知样式切换为 Android。实际配对码未作为 instrumentation 参数、日志、源码或文件保存。

随后停止了本任务先前启动的 Shizuku shell 服务，在另一个 instrumentation 进程中重新加载加密身份并通过内置 ADB 连接。当前手机没有广播 `_adb-tls-connect._tcp`（App 发现超时，电脑 mDNS 也未发现），因此使用系统无线调试首页的本机连接端口；没有扫描端口或切换到其他权限。`builtinAdbVirtualScreenKeepsMobbyVisibleAndReleasesDisplay` 通过，耗时 19.684 秒，覆盖正式 DeviceHost 协议、应用启动、语义与坐标点击计数、中文输入、真实滚动、截图、mobby 主屏预览与亮屏、拒绝 home/recents、返回和副屏释放。主屏实际截图显示 Count: 2、中文文本与 Scroll position: 840；结束后 `dumpsys display` 不存在 mobby-agent，Shizuku 服务仍未运行。

生产 APK 的 `AdbScreenMain` 还单独经 USB ADB 验证：启动进程退出后，脱离会话的工作进程仍可完成认证、open/apps/close；随后清理副屏。这是启动生命周期探针，不替代上述实际配对与内置模式用例。

可复现屏幕回归（前置：更新主包和测试包、已经配对/授权、通知允许、手机亮屏解锁）：

```sh
python3 runtime/device-plugins/accept_screen.py --adb "$ANDROID_HOME/platform-tools/adb" --mode adb
```

连接端口没有广播时增加 `--port <系统显示的连接端口>`；可用 `--preview /tmp/mobby-preview.png` 保存测试阶段主屏预览。脚本通过显式 instrumentation 状态与主机 `am start` 完成前台握手，处理实测 MIUI 后台启动限制，不修改应用或系统权限。传入 `--mode current` 可执行无障碍模式的相应用例。

本轮尚未覆盖真实模型/Agent 完整任务、root 后端或新无障碍设备回归；原有无障碍服务仍未启用，不自动授予。模拟连接、算法自测和启动探针均未替代正式手机验收结论。

最终检查使用现有 JBR 21 执行 Debug APK、测试 APK、App/设备插件/Termux/本地化单测和 App lint，合计 374 项单测通过，lint 无错误；Python 49 项（包含实际 C 编译的随机数回归）和 Node 网关/SDK 30 项通过。最终覆盖更新后通过产品页面填写系统连接端口并成功连接；手机保留在已连接的内置 ADB 模式，现有配置未清除。新增的 SPAKE2 和 Conscrypt ARM64 ELF LOAD 对齐均为 0x4000。

另执行 `:app:minifyReleaseWithR8` 通过，验证 Release 编译与入口/JNI 保留规则。首次 Release 检查发现许可任务把 vendored build 目录也作为输入，产生隐式任务依赖；已将输入收窄到实际声明、说明和源码文件，修正后通过。
