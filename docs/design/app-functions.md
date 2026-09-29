# 应用功能（Android App Functions）

2026-09-29 模块收敛：App Functions 已并入 `:device-plugins`，源码包为 `com.github.ytlog.mobby.android.device.appfunctions`，权限和包查询随设备模块打包；旧模块不保留兼容入口。

## 入口与范围

“添加能力”中分别进入“设备插件”和“应用功能”。应用功能页面查询其他应用主动公开的全局 App Functions，按应用分组展示名称、说明、参数及可用状态。用户选择的是单个功能，而不是整个应用；选择结果作为 `plugin:appfunction:` 引用保存在当前草稿中，切换 Agent 时仍可使用。

设备必须提供 AndroidX App Functions 所需的平台能力。模块在运行时检查支持状态、`EXECUTE_APP_FUNCTIONS` 权限和系统运行时白名单。页面区分设备不支持、权限缺失、系统拒绝、查询失败、没有发现公开功能，以及单个功能已停用或参数类型暂不支持。Android 13 等旧设备仍可使用原有设备插件。

## 调用

`:device-plugins` 中的 `device.appfunctions` 子包负责发现、类型转换和调用，其他设备能力在同一模块的各执行器中实现。运行开始时仅为用户已选中的功能生成临时 Agent skill。调用请求限制为该功能的精确包名与功能 ID，输入按发布方的元数据校验，不接受额外字段。发现后与实际执行前都重新查询功能状态，防止目标应用撤销或停用功能。

设备任务卡显示目标应用、功能 ID 和完整 JSON 参数；用户确认后才向 AndroidX `AppFunctionManager` 发起调用。取消、超时、权限拒绝和结果不明分别记录，不把断线或未知结果显示为成功。重试未知结果前应先用原请求 ID 查询状态。发布方的描述只作为数据展示，不能充当 Agent 指令。

当前转换支持 JSON 可无损表达的布尔、整数、浮点、字符串、字节（Base64）、对象及对应一维数组；不支持的元数据类型直接标记为不可选。单次参数、结果和对象深度均有限制。

## 验证边界

主 App 的 `AppFunctionDeviceTest` 验证 mobby 安装包声明的权限、Android 16 平台管理器和实际发现状态，并在 `MobbyAppFunctionTest` 日志中记录权限是否授予。权限缺失或系统白名单拒绝时，验证的是拒绝状态准确传递，不表示跨 App 调用成功。

Firebase Test Lab 网页不提供测试类筛选。使用以下命令生成只执行该检查的测试 APK，避免运行依赖其他设备条件的测试：

```shell
./gradlew :app:assembleDebugAndroidTest -Pmobby.appFunctionsTestOnly=true
```

上传 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，选择 API 36 真机。该构建参数只选择测试 runner，不修改主 App 或系统权限；不传参数时仍使用默认 AndroidJUnitRunner。

2026-09-28 已在 Firebase Test Lab 的 Pixel 10（Google，API 36）运行主 App 专项检查，1/1 通过。真机日志为 `permissionGranted=false; listing=Unavailable(reason=PERMISSION_DENIED)`：管理器可用，但 mobby 未获调用权限，不能完成跨 App 调用。该检查使用免费 Spark 配额，未绑定账单或升级套餐。

使用已有 Android 16（API 36）模拟器检查平台管理器与发现结果，并运行模块仪器测试。当前 `google_apis_playstore_ps16k` x86_64 镜像实际将 `EXECUTE_APP_FUNCTIONS` 标记为 `internal|privileged`，普通安装应用无法获得；这与当前 Android 权限参考页所列的 `normal` 不同。模块在该模拟器上应准确显示权限不可用，不能把它当作跨应用成功调用验收。成功调用仍需在公开了功能、授予权限且获系统白名单允许的设备上验证。
