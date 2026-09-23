# 设备插件方案

本文是设备能力插件的实施方案。`:device-plugins` 已替换「使用当前手机」。不保留 `plugin:PHONE:ACCESSIBILITY`、`PhonePlugin`、`phone.cjs`，也不把旧引用映射成新引用。开发阶段草稿里无法识别的插件引用直接丢弃，不写数据库升级脚本。各插件的真实设备操作尚未逐项验收。

## 1. 目标

Android 能力统一进入模块 `:device-plugins`。屏幕、短信、通讯录、日历、相册、存储、相机、麦克风、位置、传感器、剪贴板和 Office 共用：

- 一份插件目录
- 一种引用格式
- 一种技能包
- 一条绑定 `127.0.0.1` 的命令通道

CLI 中的 Node 不能调用 Android API。模型阅读技能文件，用 Bash 执行技能自带的脚本；脚本把命令交给应用进程，由应用进程访问短信、相册、相机等接口。

交付方式继续使用技能包，不引入 MCP，不改写 Codex 或 Claude Code 的配置文件。

## 2. 模块职责

| 模块 | 职责 |
| --- | --- |
| `:device-plugins` | 目录、清单权限、无障碍服务、取景与录音界面、执行器、技能包生成、回环协议、目录 URI 的私有存储 |
| `:runtime-android` | 运行开始时创建桥接并安装技能，进程结束时拆除。不包含设备动作的实现 |
| `:runtime-api` | 插件摘要包含授权类型和可加入草稿的引用。界面不解析权限字符串 |
| `:interaction-ui` | 插件页按目录分类。开启、使用、移除和写入开关都由摘要驱动 |
| `:speech` | 只做按住说话，不承接麦克风插件 |

`:device-plugins` 不依赖运行引擎和界面。`:runtime-android` 依赖 `:device-plugins`。应用清单合并库模块声明的权限和服务。

无障碍服务类放在 `:device-plugins`，清单里只保留这一个服务。覆盖安装开发包后，在系统设置里重新打开一次。

## 3. 引用与技能

引用格式为 `plugin:device:<能力>`。会修改数据的动作使用独立引用，默认不加入草稿。

| 能力 | 读取或使用 | 写入 | 技能名 |
| --- | --- | --- | --- |
| 屏幕 | `plugin:device:screen` | — | `mobby-screen` |
| 短信 | `plugin:device:sms` | `plugin:device:sms:send` | `mobby-sms` |
| 通讯录 | `plugin:device:contacts` | `plugin:device:contacts:write` | `mobby-contacts` |
| 日历 | `plugin:device:calendar` | `plugin:device:calendar:write` | `mobby-calendar` |
| 相册 | `plugin:device:media` | — | `mobby-media` |
| 存储 | `plugin:device:storage` | 在已选定目录内写出 | `mobby-storage` |
| 相机 | `plugin:device:camera` | — | `mobby-camera` |
| 麦克风 | `plugin:device:microphone` | — | `mobby-microphone` |
| 位置 | `plugin:device:location` | — | `mobby-location` |
| 传感器 | `plugin:device:sensors` | — | `mobby-sensors` |
| 剪贴板 | `plugin:device:clipboard` | `plugin:device:clipboard:write` | `mobby-clipboard` |
| Office | `plugin:device:office` | 写回工作区或已授权文件 | `mobby-office` |

写入引用依附对应的读取引用。读取从草稿移除时，发送或修改一并移除。

`mobby-` 前缀是插件保留技能名。`SKILL.md` 只描述本轮真正加入的动作。用户目录里若已有同名技能，这一轮失败并说明冲突，不覆盖该文件，也不把插件正文另外塞进提示。

切换 Agent 时保留 `plugin:device:` 引用，普通技能仍按 Agent 清除。能力集合与上一轮不同就重启 CLI，令牌和技能一起更换。允许的引用数量等于目录中的引用数。不在目录中的引用拒绝执行。

插件页分类来自目录数据：手机、沟通、文件。空的占位分类不再保留。

## 4. 一轮中的调用链

1. 草稿只保留目录中的 `plugin:device:` 引用。
2. 能力集合变化时重启 CLI，同时重建桥接、令牌和技能目录。
3. 为每个已加入的插件生成 `skills/mobby-<id>/SKILL.md` 和同一份参数化脚本。
4. 脚本装入当前 Agent 的技能根目录，Codex 为 `~/.agents/skills`，Claude Code 为 `~/.claude/skills`，OpenCode 为 `~/.config/opencode/skills`。目录内写入临时标记。提示只给出调用名和 `SKILL.md` 路径：Codex 使用 `$mobby-<id>`，Claude Code 与 OpenCode 使用 `/mobby-<id>`。
5. 模型执行 Node 脚本。脚本向 `127.0.0.1` 的本轮端口发送一行 JSON，然后关闭连接。
6. 应用进程核对令牌、插件、动作和参数，并在执行前再次检查系统权限。
7. 文本直接返回。照片、录音和文档写入本轮收件箱，只返回 CLI 可见的路径。
8. 进程结束时关闭端口，删除临时技能和收件箱。取消、超时、权限被收回保持为失败，不能显示为成功。

请求是一行 JSON，字段为 `token`、`plugin`、`action`、`args`。响应包含 `ok` 与 `result`，失败时 `ok` 为 false 并带 `error`。二进制内容不放进 JSON。所有插件使用这一个格式。

文本结果上限 16KB。收件箱位于应用 HOME 下、仅本轮使用的目录，CLI 与应用同 UID，因此能读取这些路径。

## 5. 隔离

系统权限按应用 UID 授予。插件不能拆成多个系统沙箱。隔离由下面几层完成：

1. 未授权的插件不能加入草稿。安装或首次打开时不一次性申请全部权限。
2. 桥接白名单只包含本轮引用对应的动作。短信技能发出的相机动作会被拒绝。
3. 每个插件只有目录里登记的 `action`，没有自由命令。
4. 每次调用前重新检查系统权限。运行中权限被收回时，下一条命令失败。
5. 文件路径只能落在当前工作区、本轮收件箱，或该插件自己保存的目录 URI 内。绝对路径逃逸、`..` 和符号链接一律拒绝。

令牌只用于限制本机其他进程。Codex 使用 `danger-full-access`，模型可以读到脚本中的令牌，令牌不是对模型的保密边界。

加入草稿即授权这一轮。格式正确的 `can_use_tool` 按原始参数放行一次，插件动作不再逐次弹出确认卡。发送短信、修改通讯录、修改日历、写入剪贴板因为使用独立引用，默认关闭；用户打开后，同一轮内直接执行。

`:speech` 的按住说话与麦克风插件都会声明 `RECORD_AUDIO`。系统已经允许录音时，麦克风插件仍要由用户加入草稿后才会安装技能。按住说话不会装上麦克风技能。

## 6. 各插件的动作与数据

| 插件 | 授权 | 动作 | 返回给模型的内容 |
| --- | --- | --- | --- |
| 屏幕 | 系统无障碍设置 | `snapshot` `click` `type` `tap` `back` `home` `recents` | 窗口树文本，单次最多约 150 个节点。密码框为 `[secure]` |
| 短信 | `READ_SMS`；发送另要 `SEND_SMS` | `list`；发送为 `send` | 最近约 20 条，正文截断 |
| 通讯录 | `READ_CONTACTS`；修改另要 `WRITE_CONTACTS` | `list`；修改为 `create` `update` `delete` | 有限条文本 |
| 日历 | `READ_CALENDAR`；修改另要 `WRITE_CALENDAR` | `list`；修改为 `create` `update` `delete` | 有限条文本 |
| 相册 | Android 13 起 `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` | `list` `copy` | 已授权条目复制到收件箱后的路径。部分照片授权时插件仍可用 |
| 存储 | 用户通过文件选择器选定的目录 URI | `list` `copy` `export` | 目录内列表。读出时复制到收件箱，写出时写回该目录。CLI 不接收 `content://` URI |
| 相机 | `CAMERA` | `photo` | 前台取景完成后的收件箱路径 |
| 麦克风 | `RECORD_AUDIO` | `record` | 前台录音完成后的收件箱路径 |
| 位置 | `ACCESS_COARSE_LOCATION` 与 `ACCESS_FINE_LOCATION` | `current` | 一次定位的经纬度、精度和时间。使用 `LocationManager` |
| 传感器 | 运动与环境传感器无危险权限 | `sample` | 一次采样，不保持订阅 |
| 剪贴板 | 无危险权限；写入使用独立引用 | `read`；写入为 `write` | 纯文本。应用不在前台时读取失败 |
| Office | 无权限 | `inspect` `read` `write` | xlsx、docx、pptx 的文本摘要，或写回后的路径 |

屏幕插件没有调用任意 Android API 的动作。打开屏幕插件不会同时获得短信或相册。

包含屏幕插件的一轮，从设备桥接建立到该轮结束保持屏幕常亮。无障碍服务放一块不接收焦点和触摸的 1 像素窗口，并持有屏幕唤醒锁；窗口和锁都在桥接关闭时放开，启动失败或服务被系统拆掉时同样放开。不修改系统息屏时间，不申请悬浮窗权限，不解除锁屏。这一轮没有屏幕插件时不持有。

相机和麦克风在命令到达时把取景或录音界面拉到前台，用户能看见快门或录音状态。位置只做前台的一次读取，不做持续跟踪，不申请后台位置，不引入 Google Play 服务。

短信不注册接收，也不把应用设为默认短信应用。存储覆盖 SD 卡和用户选中的可移动目录，不申请所有文件访问。目录 URI 保存在应用私有存储。Office 在应用进程中解析和写回文件，不执行宏，也不通过无障碍操作其他 Office 应用。文件必须位于工作区、收件箱或已授权目录。

插件页的「开启」按摘要中的授权类型动作：

- 危险权限：先说明该插件会让 Agent 做什么，再只申请这一项引用所需的权限。
- 无障碍：打开系统无障碍设置，回到插件页后重新检查。
- 存储：打开目录选择器，只持久化用户选中的那一棵目录。

未授权的插件不能加入草稿。

## 7. 以后可以追加的引用

以下能力以后按同一目录追加，每一项单独授权：通知读取、拨号（`CALL_PHONE`）、通话记录、本机号码、活动识别、身体传感器、蓝牙。

以下能力不进入目录：悬浮窗、修改系统设置、设备管理器、使用情况访问、查询全部应用、安装或卸载应用、后台位置、接听电话、读取日志、Shizuku 或 root。联网、前台服务和发送通知属于应用自身，不出现在插件目录。

## 8. 落地顺序

第一刀用目录换掉现有手机插件的服务、脚本、校验和插件页，让屏幕动作在新协议上跑通。随后按目录逐个增加执行器。两套实现不并存。
