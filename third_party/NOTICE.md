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
最终版本、对应配方提交、源码归档与补丁见 `runtime/release-sources.lock.json`，与 APK 一起发布的源码包见 [源码说明](../docs/third-party-sources.md)。不能用 master 分支替代发布时的对应配方。

## 自动安装依赖

`runtime/agents.lock.json` 固定 Termux 官方仓库的 Git、Node.js LTS、npm、ripgrep 及动态库版本、下载地址和 SHA-256。
构建时只提取包内容，不在构建机执行 deb/npm 安装脚本；程序由 Android 安装器放入 nativeLibraryDir。
应用启动时自动部署数据、链接和 JavaScript CLI，执行版本检查后报告安装状态。

- Pi：官方 npm `@earendil-works/pi-coding-agent` 0.87.1（MIT），源码：[pi-mono](https://github.com/earendil-works/pi-mono)。`runtime/pi-package/package-lock.json` 固定全部传递依赖及 integrity，构建执行 `npm ci --ignore-scripts --omit=optional`；保留依赖中的 LICENSE 和运行资源，排除源码映射及 pi-tui 的桌面原生 TUI 辅助程序。Android RPC 使用内置 Node.js；不运行 npm 生命周期脚本、不纳入其他平台的可选 esbuild 二进制。
- Claude Code：APK 不再内置它的程序或 npm 归档。设备从官方 registry 下载固定 2.1.112，校验 SHA-512，保留官方 LICENSE.md。使用 JavaScript 版本配合 Android Node.js；下载失败不标为可用，不阻断其他 Agent。
- Codex：官方 npm `@openai/codex` 0.155.1-linux-arm64 的静态 musl CLI。源码与许可证：https://github.com/openai/codex/tree/rust-v0.155.1
- Codex 配套的 bubblewrap 从同一固定 npm 包提取，安装为 `bin/bwrap` 供官方沙箱启动器发现；未修改二进制或关闭隔离。许可证见 [bubblewrap/COPYING](bubblewrap/COPYING)，对应源码为上述 Codex 固定标签内的 `codex-rs/vendor/bubblewrap`。
- Codex 构建下载及 Claude Code 设备下载均按锁文件中的 npm SHA-512 integrity 校验。当前仅集成命令行任务所需文件，不包含 Codex 语音组件。
- OpenCode：社区包 [C04-wq/opencode-termux](https://github.com/C04-wq/opencode-termux) `v1.18.32-0`（MIT）中的 `opencode-termux-aarch64.tar.gz`。它包含官方 OpenCode 1.18.32 的 ARM64 musl 程序，以及 musl 加载器、libgcc 与 libstdc++。官方程序本身不是 Android 可直接执行的 PIE，因此由本仓库的 `runtime/opencode_launcher.c` 交给随包的静态 musl 加载器启动。未修改这些二进制。
- OpenCode 内置 Bun 1.3.14，Bun 静态链接的 JavaScriptCore 与 TinyCC 使用 LGPL；相应原文通知、许可证与重建源码一起提供。musl 1.2.5 使用 MIT，完整版权文件保留在 `musl/COPYRIGHT` 并随 App 展示；Alpine GCC 14.2.0-r4 运行库适用原许可证与 Runtime Library Exception，不能只以社区包 MIT 概括这些库。
- Codex 普通 ARM64 musl 依赖、Rust 标准库与内嵌 OpenSSL 的声明见 `codex/dependency-notices.json`；Bun 的原生库、WebKit 与内嵌 polyfill 声明见 `bun/dependency-notices.json`，随 App 离线展示。原文件与内容摘要一并保留；OpenCode 的普通 JavaScript 依赖与本平台原生辅助包声明见 `opencode-termux/dependency-notices.json`。
- APK 额外携带 Codex 的 LICENSE/NOTICE、bubblewrap COPYING、OpenCode/社区包许可、Bun 及 LGPL 通知和 GCC 原文，位于 bootstrap 数据包的 `share/mobby/licenses/`。
- `runtime/agent_launcher.c` 为 npm / npx / Pi / Claude Code 提供 Android 原生入口，参数直接传入 Node.js。

## 技能元信息解析

Android 的完整运行依赖（含传递依赖）见 `third_party/maven/dependencies.lock.json`。构建按实际依赖保留原始许可和 NOTICE，打包到 App 的“设置 → 开源许可证”页面；该清单不替代各组件原文。

技能导入使用 Maven 依赖 `org.yaml:snakeyaml:2.3`（Apache-2.0），通过 SafeConstructor 读取数据，禁止重复键、集合别名和任意对象构造；不执行导入文件。上游：https://bitbucket.org/snakeyaml/snakeyaml 。依赖由 Gradle 获取，不把构建缓存纳入仓库。

## Markdown 解析

回复使用 `org.commonmark:commonmark:0.30.0` 及同版本 tables、strikethrough、autolink、task-list-items 扩展（BSD-2-Clause）；上游与许可证：[commonmark-java](https://github.com/commonmark/commonmark-java)。自动链接的传递依赖为 `org.nibor.autolink:autolink:0.12.0`（MIT），上游：[autolink-java](https://github.com/robinst/autolink-java)。由 Gradle 获取依赖，不提交缓存。Compose 自行渲染解析结果，不执行 HTML。

## 语音识别

应用内识别使用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8（Apache-2.0）的 Android AAR。构建时按固定 SHA-256 获取 AAR；安装包只保留 arm64-v8a 的 JNI 与 ONNX Runtime 库，未使用的 C/C++ API 库不打包，AAR 不提交进仓库。

其中 ONNX Runtime 1.28.2 的 MIT 许可及完整嵌套依赖声明保留在 `third_party/onnxruntime/`，并随 App 离线展示；受 MPL 覆盖的 Eigen 对应源码纳入 Release 源码清单。

流式中文模型 [sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23)（Apache-2.0）不放入安装包。首次语音输入时下载官方归档并校验 SHA-256；只提取 int8 encoder、int8 joiner、decoder 和 tokens，随后删除归档。没有第二套本地语音模型或文字整理模型。归档及模型文件不提交进仓库。

归档提取使用 `org.apache.commons:commons-compress:1.27.1`（Apache-2.0），由 Gradle 获取，不提交构建缓存。

## Android JNI 运行库

随 APK 的 Android NDK r27c libc++ 运行库原许可段落见 `android-cxx/`；不是将整个构建工具链的许可套用到 App。llama.cpp 使用的 KleidiAI v1.24.0 ARM64 核心原文见 `kleidiai/`（Apache-2.0），未打包其 BSD 测试/示例。
