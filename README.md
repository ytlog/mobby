<p align="center">
  <img src="app/icon/mobby-blue-flat.svg" width="88" alt="mobby 图标">
</p>

<h1 align="center">mobby</h1>

<p align="center">在手机上看页面、处理文件、操作应用、写程序。</p>

<p align="center">
  <a href="#看着页面直接问">页面问答</a> ·
  <a href="#处理手机里的照片和文件">照片与文件</a> ·
  <a href="#让它帮你操作-app">操作 App</a> ·
  <a href="#在手机上写个能用的小程序">手机编程</a> ·
  <a href="#开始使用">开始使用</a>
</p>

mobby 让 Pi、Claude Code、Codex、OpenCode 这些 Code Agent 直接在 Android 手机上运行，接入屏幕、相册、文件等手机能力，成为你的个人 AI 助手。你可以让它看懂当前页面、处理照片和文件、操作 App，也可以直接在手机上写代码、运行程序。

## 看着页面，直接问

浏览网页时遇到一段没看懂的英文，点开悬浮球，进入悬浮对话，再点“识别屏幕”。mobby 会读取当前页面，你可以让它翻译、解释，或者接着追问。

> 这个页面在讲什么？Python 有哪些特点，初学者应该从哪里开始？

下面这次演示是在 Python 官网提问，回答直接显示在悬浮窗里。

<p><img src="docs/media/screen-qa.png" width="280" alt="在 Python 网页上打开悬浮对话，用中文解释当前页面"></p>

[查看真机录屏](docs/media/screen-qa.mp4)

## 处理手机里的照片和文件

给当前任务加入“查看照片”或“访问文件”，选好要开放的照片和目录，就可以让它识别图片、复制文件、整理清单，再把结果保存回手机。

> 看一下最近的照片，把最新一张复制到指定文件夹，再写一份图片说明和文件清单。

这次演示导出了图片副本、Markdown 说明和 CSV 清单，并回读检查了文件内容。原图保留在原来的位置。

<p><img src="docs/media/media-files.png" width="280" alt="照片识别和文件导出完成后的对话"></p>

[查看真机录屏](docs/media/media-files.mp4)

## 让它帮你操作 App

把“使用手机”加入任务，告诉它要去哪、做什么。它会读取屏幕，点击按钮、输入文字、切换页面，按步骤操作手机。

> 去应用商店下载番茄ToDo，打开添加待办的功能。

这次演示从小米应用商店安装了番茄ToDo，进入添加待办和自定义时长页面。应用操作还会遇到误点或服务中断，复杂流程需要留意执行结果。

<p><img src="docs/media/app-feature.png" width="280" alt="番茄ToDo 的自定义时长页面"></p>

[查看真机录屏 · 8 倍速](docs/media/app-feature-8x.mp4)

## 在手机上写个能用的小程序

说清楚想做什么，让 Agent 在手机工作区写代码、启动服务，再用手机浏览器打开。

> 做一个旅行打包清单，按证件、衣物、电子和洗漱用品分类。可以勾选、添加、删除，刷新后还能保留。

这个清单就是 Codex 在手机上写出来的。页面显示打包进度，勾选和新增的物品保存在浏览器里，出发前可以逐项检查。

<p><img src="docs/media/travel-checklist.png" width="280" alt="手机浏览器中的旅行打包清单"></p>

[查看真机录屏](docs/media/travel-checklist.mp4) · [查看示例源码](docs/examples/travel-checklist/)

## 开始使用

目前支持 Android 8.0 及以上的 ARM64 设备。首次打开会初始化随安装包提供的运行环境；在网关设置中填写服务地址、API Key 和模型，保存后即可开始对话。

页面问答需要开启桌面悬浮球、悬浮窗权限和屏幕无障碍服务；照片与文件按任务授权。四段演示均使用 Codex，在 Android 13 真机上录制，具体过程见[演示说明](docs/media/real-device-demos.md)。

[构建与使用](docs/getting-started.md) · [网关设置](docs/gateway.md) · [项目文档](docs/README.md) · [MIT 许可证](LICENSE)
