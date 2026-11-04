# 聊天页玻璃控件

使用 [Backdrop / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，固定依赖 `io.github.kyant0:backdrop:1.0.0`。该版本将 Compose UI/Foundation 解析到 1.9.4、Kotlin 标准库解析到 2.2.21；不修改 Kotlin Gradle 插件。依赖许可证为 Apache-2.0，实际依赖图记录在 `third_party/maven/dependencies.lock.json`。

## 实现范围

- 聊天页顶部菜单、Agent 选择胶囊、新建/更多操作组、底部输入区采用统一 `GlassSurface`。
- 时间线通过 `rememberLayerBackdrop` / `layerBackdrop` 提供真实背景采样；工具栏和输入区为同级前景，不能录进自己的采样层。
- 时间线滚动内容保留根据顶部栏及输入区实际高度计算的上下留白，尾部回复与“最新消息”按钮保持在输入区上方。滚动经过玻璃区域的内容允许作为模糊背景显示。
- 普通玻璃使用 18dp 模糊、8dp 边缘折射高度、12dp 折射量，叠加深浅主题底色和渐变细边框。图标和文字在效果层之后绘制，保持清晰。
- 发送/停止保持高对比的反色圆形底，避免高透明度损害可辨识度；图标按钮按压缩放到 0.97，120ms 过渡，遵循现有减少动态效果设置。

## 平台和验收边界

Android 13+ 使用模糊与折射；Android 12 使用模糊，库内关闭不支持的折射；Android 11 及以下使用实色底与细高光边框。没有时间线采样上下文的复用界面（例如悬浮对话）也使用实色底，不声称具备真实背景模糊。

首版不替换设置页和消息正文中的全部按钮。主机 UI 测试验证控件状态、对比度、滚动及遮挡约束；真实玻璃视觉及帧率需要在设备上验收。

## 本轮验证（2026-11-04）

- Android Studio JBR 21：`:app:assembleDebug`、`:app:testDebugUnitTest`（314 项）、`:termux-core:testDebugUnitTest`（32 项）、`:app:lintDebug` 全部通过。
- 按项目要求先尝试 JDK 17，但本机 GraalVM 17 在 Android 36 的 `JdkImageTransform` / `jlink` 阶段失败；未修改项目配置来掩盖该环境问题。
- Android 13 连接手机已按现有 versionCode 108 覆盖安装成功，启动并核对浅色聊天页截图，顶部胶囊与底部输入区正常显示，原会话保留。
- 未进行真机深色主题、Android 12/旧系统及 GPU 帧率测量；浅色静态截图不能替代滚动折射效果与性能验收。未发送模型请求或启动录音。
