# 四项真机演示

2026-09-28，小米 M2007J1SC / Android 13，使用 mobby 内的 Codex 和真实模型网关。ADB 用于场景准备、输入任务、系统授权、录屏及独立核验。以下任务由手机 Agent 执行；网页交互核验由测试者在手机浏览器中操作。

| 场景 | 实际结果 | 录屏 |
| --- | --- | --- |
| 悬浮球页面问答 | 从 Python 官网唤起悬浮对话，识屏后返回中文解释和入门建议 | [1 分 38 秒](screen-qa.mp4) |
| 图片与文件 | 列出两张授权图片，复制最新图片，导出 Markdown、CSV 和图片副本；回读核验通过，副本与原图 SHA-256 一致 | [3 分钟](media-files.mp4) |
| 安装并打开 App 功能 | 从小米应用商店安装番茄ToDo，进入添加待办及自定义时长页面 | [8 倍速，4 分 51 秒](app-feature-8x.mp4) |
| 手机编程 | 创建旅行打包清单和 Node 服务；HTTP 200；添加、删除、勾选及刷新保存通过，最终保留 13 件物品、2 件已打包 | [5 分钟](travel-checklist.mp4) |

## 应用操作中的问题

多个“安装”按钮导致首次误装推荐区的抖音精选；Agent 识别后重新定位番茄ToDo 并安装。首次配置期间屏幕插件返回 `PERMISSION_REVOKED`，任务如实结束；返回 mobby 后服务恢复，重新加入屏幕插件并返回目标应用继续操作。

续接时已输入“专注演示”并打开自定义时长页面。测试额外加入的“启动 5 分钟计时”未完成，随后停止这部分任务。误装的抖音精选已卸载，番茄ToDo 保留。这里的完成范围是安装应用并打开功能入口。

应用操作视频为完整过程的 8 倍速版本，包含等待、误点、中断和续接，原速约 38 分 47 秒。其余三段保持原速。所有录屏均无音轨。

## 旅行清单示例

源码在 [travel-checklist](../examples/travel-checklist/)，不依赖第三方包。在有 Node.js 的环境中运行：

```sh
node docs/examples/travel-checklist/server.cjs
```

浏览器打开 `http://127.0.0.1:18790/`。清单保存在当前浏览器的 localStorage 中。

手机 Agent 首次启动因没有 `/tmp` 目录失败，重试后又发现普通后台进程退出，随后改为脱离终端会话运行，并独立验证 HTTP 200。手机浏览器刷新及重新打开后，新增物品和勾选状态仍保留。本次没有验证设备重启后自动运行。

## README 精剪动画

中文 README 已嵌入循环播放的 GIF，并链接到相同剪辑的 MP4。精剪删除无效首尾、部分误点恢复过程和重复静止画面，保留任务、关键操作与结果；不是连续完整录屏。上表仍链接原录屏，已知问题和验收范围保持不变。

| 场景 | 精剪时长 | 视频 |
| --- | --- | --- |
| 页面问答 | 17 秒 | [查看](zh-CN/screen-qa-edited.mp4) |
| 图片与文件 | 35 秒 | [查看](zh-CN/media-files-edited.mp4) |
| 安装及打开功能 | 40 秒 | [查看](zh-CN/app-feature-edited.mp4) |
| 手机编程 | 53 秒 | [查看](zh-CN/travel-checklist-edited.mp4) |

页面问答、文件和编程片段的等待采用 2～12 倍速；应用操作以原有 8 倍速素材剪辑，局部调整后相当于原速的 4～24 倍。具体剪辑点见 [zh-CN/edits.json](zh-CN/edits.json)，复用 [英文剪辑脚本](en/edit_recordings.py)：

```sh
python docs/media/en/edit_recordings.py docs/media --edits docs/media/zh-CN/edits.json --output docs/media/zh-CN --ffmpeg /path/to/ffmpeg
```
