# GitHub 手动发布 Android Release

仓库的 **Publish Android release** 工作流只通过 GitHub Actions 的 **Run workflow** 手动运行。它执行运行时检查、Android 单测和 lint，构建经过 R8 压缩、优化、混淆及资源收缩的 ARM64 release APK，验证签名和版本信息，然后创建 `v<版本>` 标签与 GitHub Release。APK 与 SHA-256 校验文件是公开的 Release 附件；R8 `mapping.txt` 只保存在 Actions artifact 中 90 天，用于崩溃堆栈反混淆。

## 首次设置

1. 准备一个长期保存的 Android 签名 keystore。若已有对外发布的同一 `applicationId`，必须使用它原来的签名密钥；签名不同的 APK 不能覆盖安装。不要使用开发机的 debug keystore。妥善离线备份 keystore、别名和口令。项目源码包与 `applicationId` 均为 `com.github.ytlog.mobby.android`。
2. 在 GitHub 仓库 **Settings → Secrets and variables → Actions → Repository secrets** 添加：

   | Secret | 内容 |
   | --- | --- |
   | `MOBBY_RELEASE_KEYSTORE_BASE64` | keystore 文件的单行 Base64 内容 |
   | `MOBBY_RELEASE_STORE_PASSWORD` | keystore 口令 |
   | `MOBBY_RELEASE_KEY_ALIAS` | 签名密钥别名 |
   | `MOBBY_RELEASE_KEY_PASSWORD` | 密钥口令 |

3. 确认仓库允许 Actions 使用 `GITHUB_TOKEN` 创建 Release。工作流声明 `contents: write`，无需另建个人访问令牌。

签名文件和口令存放在仓库目录之外，不能提交 Git。Debug 保持 Android 默认开发签名；上述 Secrets、release 签名和 R8 配置只用于 release。部分签名环境变量缺失时，release 的签名校验会失败，Debug 打包不依赖这些变量。工作流安装 SDK 35 和 36，以满足当前各模块的编译要求。

编译前会检查锁定依赖的下载地址。Termux 仓库会移除旧包；检查报告 404 时，应根据官方 Packages 索引更新对应包在 `runtime/agents.lock.json` 中的版本、地址和 SHA-256，并校验下载文件。构建仍只使用锁定版本，不自动改用最新版或跳过校验。

可在本机用以下命令生成第一个 Secret 的值；不要把输出保存到仓库文件中：

```sh
base64 < path/to/release.jks | tr -d '\n'
```

## 发布

1. 将待发布代码合入仓库默认分支。到 **Actions → Publish Android release → Run workflow**，选择默认分支；工作流会拒绝从其他分支发布。
2. 输入 `version`，例如 `0.1.0`；输入递增的正整数 `version_code`，例如 `1`。前者会写入 APK `versionName` 并生成 `v0.1.0` 标签；后者写入 Android `versionCode`，每次覆盖升级都必须高于前一版。同名标签已存在时工作流会停止。
3. 工作流成功后，在仓库 **Releases** 下载 `mobby-v<版本>-arm64-v8a.apk`，并用 `SHA256SUMS.txt` 校验。失败时不会创建新 Release；先查看失败步骤并修复后重跑。

仅支持 ARM64 和 Android 8.0 及以上。签名和构建成功不能替代在真实手机上安装、覆盖升级和验证 Shell、Pi、Claude Code、Codex、OpenCode、网关及本地模型；首次发布前应完成这些验收。当前 debug APK 使用 Android 开发签名，通常不能被 release APK 直接覆盖；请先备份需保留的设备数据。

## 本地验证

不设置四个签名环境变量时，`./gradlew :app:assembleRelease` 生成未签名的优化 APK，便于本地检查 R8；发布工作流则要求四个 GitHub Secrets 齐备。需要本地签名时设置 `MOBBY_RELEASE_STORE_FILE`、`MOBBY_RELEASE_STORE_PASSWORD`、`MOBBY_RELEASE_KEY_ALIAS`、`MOBBY_RELEASE_KEY_PASSWORD` 后构建。可用 `-Pmobby.versionName=0.1.0 -Pmobby.versionCode=1` 指定版本。不要将密钥、口令、APK、mapping 或本地配置提交到 Git。
