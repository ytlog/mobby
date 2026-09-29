# App 图标

当前图标采用浅雾蓝底与三层蓝色环带，保留用户确认稿的轮廓和中心留白，使用色面区分翻转，不添加高光、纹理或投影。

- [确认稿](../../app/icon/mobby-blue-flat-reference.png)：设计参考，不打包进 APK。
- [矢量源图](../../app/icon/mobby-blue-flat.svg)：从确认稿轮廓整理的可缩放版本。
- 配色：背景 `#E7F3FE`；上层 `#6BB5F1`；前层 `#3F9AEB`；右侧翻面 `#3471CE`。

## Android 资源

- `conversation-ui/src/main/res/drawable/ic_launcher_background.xml`：铺满自适应图标图层的浅蓝背景。
- `conversation-ui/src/main/res/drawable/ic_launcher_foreground.xml`：透明背景的蓝色环带，108 单位画布中的 72 单位构图；圆角和圆形裁切交给桌面。
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`：同一轮廓和留白的单色遮罩，供支持主题图标的桌面使用。
- `app/src/main/res/drawable/ic_launcher.xml`：完整圆角图标，供会话快捷方式使用。
- 两个 `mipmap-anydpi-v26` 入口共享上述自适应图层。

预览图外侧的白色展示画布不进入运行时图标。运行时使用矢量资源；修改轮廓或色面时同步更新 SVG、彩色资源及单色遮罩。

## 桌面悬浮球

悬浮球直接复用上述蓝色前景矢量，使用同款浅蓝圆底和细描边；展开面板使用雾白卡片、细蓝边框和轻阴影，顶部显示标题与独立收起按钮；打开与停止分别使用浅蓝、浅红按钮，并有点击反馈。面板由 `PetTrayView` 统一绘制，按内容测量高度，支持长标题、大字体及窄屏；空间不足时可滚动。品牌配色集中在 `conversation-ui/src/main/res/values/brand_colors.xml`，启动图标与悬浮球共享。空闲时静态显示，运行时右下角显示轻微呼吸的状态点，停止中显示红色状态点。拖动、停止、回到对话和屏幕操作避让逻辑保持不变。
