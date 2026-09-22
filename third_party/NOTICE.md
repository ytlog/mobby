# 第三方代码与运行资源

## libtermux-android

- 来源：https://github.com/libtermux/libtermux-android
- 固定提交：3a7e2ae63c4824fac384769c9afb8bc579458da9
- 原始许可证：libtermux-android/LICENSE（Apache-2.0）
- 以源码快照纳入仓库；只构建 core、bootstrap-arm64。
- 本地 mobby.gradle.kts 使用 AGP 8.6 / Kotlin 1.9，独立于上游发布构建。
- CommandExecutor：增加 argv 接口，用进程组管道执行替代批量伪流式和顺序阻塞读取。
- PipeProcess.kt、pipe_process.cpp：新增 JNI 进程组执行、取消、超时和输出限制。
- TermuxBridge.bash：移除硬编码 com.termux shebang，直接交给配置的 Bash。
- NativeLibBootstrapProvider：必须存在可执行 Bash，不能仅以 native 目录非空判断就绪。
- CMakeLists.txt：加入管道执行 JNI 文件。

## Termux bootstrap

来源及 SHA-256：runtime/bootstrap.lock.json。
程序打包来自 Termux 官方 bootstrap-aarch64.zip，保留原始数据与 share/ 中版权文件。
其程序分别适用各自许可证，不能以 SDK 的 Apache-2.0 许可证替代。
上游包配方与源码：https://github.com/termux/termux-packages/tree/master/packages
当前 APK 为本地验证产物；对外分发前需逐包整理许可证及对应源码提供义务。

## 自动安装依赖

`runtime/agents.lock.json` 固定 Termux 官方仓库的 Git、Node.js LTS、npm、ripgrep 及动态库版本、下载地址和 SHA-256。
构建时只提取包内容，不在构建机执行 deb/npm 安装脚本；程序由 Android 安装器放入 nativeLibraryDir。
应用启动时自动部署数据、链接和 JavaScript CLI，执行版本检查后报告安装状态。

- Claude Code：官方 npm `@anthropic-ai/claude-code` 2.1.112，保留 LICENSE.md。使用 JavaScript 版本配合 Android Node.js；未纳入其他平台的可选音频/图像原生插件。
- Codex：官方 npm `@openai/codex` 0.155.1-linux-arm64 的静态 musl CLI。源码与许可证：https://github.com/openai/codex/tree/rust-v0.155.1
- Codex 配套的 bubblewrap 从同一固定 npm 包提取，安装为 `bin/bwrap` 供官方沙箱启动器发现；未修改二进制或关闭隔离。许可证见 [bubblewrap/COPYING](bubblewrap/COPYING)，对应源码为上述 Codex 固定标签内的 `codex-rs/vendor/bubblewrap`。
- 两个 npm 包均按锁文件中的 npm SHA-512 integrity 校验。当前仅集成命令行任务所需文件，不包含 Codex 语音组件。
- OpenCode：社区包 [C04-wq/opencode-termux](https://github.com/C04-wq/opencode-termux) `v1.18.32-0`（MIT）中的 `opencode-termux-aarch64.tar.gz`。它包含官方 OpenCode 1.18.32 的 ARM64 musl 程序，以及 musl 加载器、libgcc 与 libstdc++。官方程序本身不是 Android 可直接执行的 PIE，因此由本仓库的 `runtime/opencode_launcher.c` 交给随包的静态 musl 加载器启动。未修改这些二进制。
- `runtime/agent_launcher.c` 为 npm / npx / Claude Code 提供 Android 原生入口，参数直接传入 Node.js。

## 技能元信息解析

技能导入使用 Maven 依赖 `org.yaml:snakeyaml:2.3`（Apache-2.0），通过 SafeConstructor 读取数据，禁止重复键、集合别名和任意对象构造；不执行导入文件。上游：https://bitbucket.org/snakeyaml/snakeyaml 。依赖由 Gradle 获取，不把构建缓存纳入仓库。

## Markdown 解析

回复使用 `org.commonmark:commonmark:0.30.0` 及同版本 tables、strikethrough、autolink、task-list-items 扩展（BSD-2-Clause）；上游与许可证：[commonmark-java](https://github.com/commonmark/commonmark-java)。自动链接的传递依赖为 `org.nibor.autolink:autolink:0.12.0`（MIT），上游：[autolink-java](https://github.com/robinst/autolink-java)。由 Gradle 获取依赖，不提交缓存。Compose 自行渲染解析结果，不执行 HTML。

## 语音识别

应用内识别使用 Maven 依赖 `com.alphacephei:vosk-android:0.3.47`（Apache-2.0）及其传递依赖 `net.java.dev.jna:jna:5.13.0`（Apache-2.0）。上游：[vosk-api](https://github.com/alphacep/vosk-api)。依赖由 Gradle 获取，不把 AAR 或本地库提交进仓库。

中文小模型 `vosk-model-small-cn-0.22`（Apache-2.0）不放入安装包。用户第一次按住说话时从 https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip 下载到应用私有目录，并用 SHA-256 `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba` 校验。模型文件不提交进仓库。
