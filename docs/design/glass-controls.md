# 聊天页玻璃控件

使用 [Backdrop / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，固定依赖 `io.github.kyant0:backdrop:1.0.0`。该版本将 Compose UI/Foundation 解析到 1.9.4、Kotlin 标准库解析到 2.2.21；不修改 Kotlin Gradle 插件。依赖许可证为 Apache-2.0，实际依赖图记录在 `third_party/maven/dependencies.lock.json`。

## 实现范围

- 聊天页顶部菜单、Agent 选择胶囊、新建/更多操作组、底部输入区采用统一 `GlassSurface`。
- 时间线通过 `rememberLayerBackdrop` / `layerBackdrop` 提供真实背景采样；工具栏和输入区为同级前景，不能录进自己的采样层。
- 时间线滚动内容保留根据顶部栏及输入区实际高度计算的上下留白，尾部回复与“最新消息”按钮保持在输入区上方。滚动经过玻璃区域的内容允许作为模糊背景显示。
- 普通玻璃使用 1.5dp 模糊、6dp 边缘折射高度、20dp 折射量，不开启额外深度折射。浅色/深色底色遮盖分别为 10%/8%，仅保留弱表面光照；移除内阴影以减少磨砂和实体按钮观感。真实采样路径只用库内 0.4dp 方向高光，不叠加整圈描边；高光强度浅色 0.40、深色 0.36，falloff 为 6，集中在局部边缘。图标和文字在效果层之后绘制，保持清晰。
- 玻璃覆盖的时间线不再叠加旧的边缘刷白渐隐，直接采样未被遮罩抹掉的内容；不添加伪造背景图案。
- 发送/停止保持高对比的反色圆形底，避免高透明度损害可辨识度；图标按钮按压缩放到 0.97，120ms 过渡，遵循现有减少动态效果设置。

## 平台和验收边界

Android 13+ 使用模糊与折射；Android 12 使用模糊，库内关闭不支持的折射；Android 11 及以下使用实色底与细高光边框。没有时间线采样上下文的复用界面（例如悬浮对话）也使用实色底，不声称具备真实背景模糊。

首版不替换设置页和消息正文中的全部按钮。主机 UI 测试验证控件状态、对比度、滚动及遮挡约束；真实玻璃视觉及帧率需要在设备上验收。

## 本轮验证（2026-11-04）

- Android Studio JBR 21：`:app:assembleDebug`、`:app:testDebugUnitTest`（314 项）、`:termux-core:testDebugUnitTest`（32 项）、`:app:lintDebug` 全部通过。
- 按项目要求先尝试 JDK 17，但本机 GraalVM 17 在 Android 36 的 `JdkImageTransform` / `jlink` 阶段失败；未修改项目配置来掩盖该环境问题。
- Android 13 连接手机已按现有 versionCode 108 覆盖安装成功，启动并核对浅色聊天页截图，顶部胶囊与底部输入区正常显示，原会话保留。
- 未进行真机深色主题、Android 12/旧系统及 GPU 帧率测量；浅色静态截图不能替代滚动折射效果与性能验收。未发送模型请求或启动录音。

### 通透感调整

用户反馈首版玻璃不明显后，新增白底材质明暗回归测试，先复现上下区域亮度均为 1.0，再调整材质和采样遮罩。新的测试在 API 34 验证与玻璃层共用的表面光照；Robolectric 无法可靠执行库内 AGSL，因此不以该测试声称验证了折射。试验中混用 API 31/34 原生渲染导致测试进程崩溃，最终测试统一为 API 34，不需要额外隔离脚本。

JBR 21 下构建成功，常规 App 315 项与 termux-core 32 项测试、App lint 全部通过。已再次按 versionCode 108 覆盖安装到 Android 13 手机，核对稳定首屏截图和当前进程崩溃日志：玻璃边缘与表面明暗可见，图标及正文正常，未发现崩溃。真实滚动折射强度、深色主题和帧率仍待进一步验收。

### 深色高光修正

深色真机截图暴露出库内高光与手工描边叠加、强表面渐变造成的金属观感。先添加会失败的深色光照回归测试（上、下区域亮度为 0.0513 / 0.0203），再去除真实采样路径的重复描边，减弱并集中方向高光，同时压低深色表面、内阴影及投影的明暗差。无采样路径保留低亮度的渐隐边缘，不画强烈整圈亮边。

JBR 21 下构建、App 316 项与 termux-core 32 项测试、App lint 全部通过。versionCode 108 已覆盖安装到同一 Android 13 手机；检查稳定深色首屏，原先连续亮圈消失，表面渐变明显减弱，图标与正文仍清晰，当前进程无崩溃日志。浅色光照与发送/停止对比度由回归测试验证；本轮未切换真机主题，未测量 GPU 帧率或发送模型请求。

### 清透材质与真实 GPU 检查

用户仍反馈磨砂观感后，新增使用共用光照绘制的黑白背景透过率测试：先验证旧材质未能保留足够背景对比度，再将深色遮盖降到 8%、浅色降到 10%、模糊降到 1.5dp，并移除内阴影、减弱表面渐变。背景采样、表面光照和图标绘制继续独立；发送/停止的强调底色保持高对比。

新增 `GlassTransmissionDeviceTest`，在 Debug 自带的独立 `ComponentActivity` 中实际绘制 Backdrop 玻璃，检查背景黑白两侧亮度差大于 0.60，交换背景后再次检查，验证 GPU 下的透过与更新。测试不写聊天、账号或偏好设置。设备限制曾使测试框架的后台 Activity 启动无法进入前台，测试现统一通过 UiAutomation 显式启动固定测试 Activity，并设总超时；自动复跑 1.919 秒通过，无需手动干预。

JBR 21 下主包、测试包构建、App 317 项与 termux-core 32 项单测、App lint 全部通过。主包以 versionCode 108 覆盖安装到 Android 13 手机。可用 `adb shell am instrument -w -r -e class com.github.ytlog.mobby.android.conversation.ui.GlassTransmissionDeviceTest com.github.ytlog.mobby.android.test/androidx.test.runner.AndroidJUnitRunner` 复跑已安装的设备测试。该测试不代替帧率测量或完整 iOS 动效验收；纯黑或纯白背景上的静态折射本来就缺少可见纹理，不添加伪造背景图案。
