# 公开仓库与发布安全排查

日期：2026-09-29。仓库当前保持私有，本文不授权改变仓库可见性。

## 已完成：历史密钥与发布链路

- 本地可达 255 个提交、2,649 个文本版本的初筛，以及 Gitleaks 8.30.1 完整 Git 历史扫描均未发现密钥。没有因此改写 Git 历史。
- 45 个历史 Actions 运行中，44 份日志可下载，Gitleaks 扫描未发现密钥；运行 36529209095 的日志不可取得，不记为通过。报告和原始日志仅保存在仓库外的私有审查目录。
- 两份历史明文 R8 映射 artifact 已先备份、加密、解密比对验证，再从 GitHub 删除。映射含代码符号，不是签名私钥，但公开仓库不能把 Actions artifact 当成私有存储。
- 新发布流程分离构建与签名 runner。构建 runner 没有签名 secrets，签名 runner 不执行 Gradle、npm 或项目构建脚本，使用已构建 APK 和 Android 官方 apksigner；临时 keystore 在签名结束时删除。
- R8 映射使用 age 1.3.2 的公开接收者加密后上传。仓库仅保留公开接收者，解密身份留在设备外的本机私有目录；没有新增供 CI 解密的 secret。age 下载固定版本与 SHA-256。
- GitHub Actions 固定完整提交 SHA，checkout 不持久化令牌；仓库默认令牌只读、禁止 PR 自动批准、只允许 GitHub 官方 Actions。推送和 PR 增加完整历史密钥扫描，输出始终脱敏。

## 已完成：媒体与依赖许可

- 演示图片和 GIF 共 3,053 帧完成 OCR，识别错误为 0；Gitleaks 未检出密钥，文字中未检出邮箱或手机号。51 处 URL 都是 loopback 地址；11 张静态图片已人工视觉检查。OCR 不保证识别所有敏感内容。
- 后续提交使用 GitHub noreply 地址。历史存在一个作者身份，未擅自改写；公开后其原作者信息仍会可见。
- Claude Code 程序与归档从 APK 移除，设备直接从官方 registry 下载固定版本并做 SHA-512 校验、受限解压与安装回滚；失败不标为可用，也不阻断其他 Agent。
- APK 保留 Codex、bubblewrap、Bun/JavaScriptCore/TinyCC、OpenCode、GCC 和 ONNX Runtime 的原文通知。设置 → 开源许可证提供离线查看入口。
- Android release 的 114 个解析产物归并为 113 个依赖坐标，包含传递依赖；构建保留原 LICENSE/NOTICE，核对 POM 和父 POM，依赖或许可漂移停止。
- 最终 Termux 87 个包与锁文件一致，GPL/LGPL 上游源码、官方补丁、对应配方、Bun/WebKit 等重建材料纳入 Release 源码包流程。源码清单和下载校验通过前不能发布 APK；详细材料与边界见 [许可处理](license-compliance.md) 和 [对应源码](third-party-sources.md)。

## 尚需注意的边界

- 当前私有仓库套餐的分支保护 API 返回 403；不能声称已经启用保护。公开或升级后配置 main 的强制检查、禁止强推和删除、工作流审查，以及 fork PR 运行审批。
- 未取得的历史 Actions 日志没有验证；媒体 OCR 加人工静态检查不能证明每帧画面绝无隐私。
- 没有在不同机器完整重编全部上游程序或确认位一致；组件的许可边界不能仅靠自动清单判断。今后链接方式、许可和源码版本变化必须重新核对。

## 公开的固有影响

项目自身的 MIT 许可允许合规复用及商业使用，第三方组件分别遵循其原始许可证。本轮保留既有许可，不把保护开发信息解释为擅自收紧版权许可。公开后已经被克隆或 fork 的代码不能保证收回；Git 作者信息也会可见。这些属于公开决策本身的影响。

## 加密映射恢复

在保存解密身份的本机，下载对应 Actions 的 mapping.txt.age，使用固定 age 版本运行：

```sh
age --decrypt --identity /path/outside/repository/r8-identity.txt --output mapping.txt mapping.txt.age
```

私有身份应离线备份。遗失它会导致已加密映射无法恢复；需要轮换接收者时更新 .github/r8-mapping-recipient.txt，并保留旧身份供旧版本映射解密。解密产物和身份不能提交。
