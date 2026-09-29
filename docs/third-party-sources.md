# 发布包、许可证与对应源码

新的发布流程要求 APK 同时提供 `mobby-v<版本>-sources.tar.gz` 和 `SHA256SUMS.txt`。源码包与 APK 在同一 GitHub Release 中下载，不要求购买、注册额外服务或支付源码费用。APK 内的 `assets/bootstrap/data.zip` 保留 Termux 的版权文件，并包含 `share/mobby/licenses/` 下的附加许可证与通知。此前已经发布的 APK 不会因流程修改而自动补齐这些材料，须在完成检查后重新发布。

## 清单与材料

`runtime/release-sources.lock.json` 固定最终 87 个 Termux 包的版本、许可证、配方提交，以及 GPL/LGPL 对应的上游源码归档和补丁校验和。构建从 bootstrap 的 dpkg 状态与最终覆盖包计算实际版本，与清单逐项比较；bootstrap 或 Agent 锁文件改变必须重新审查清单，不能继续发布旧材料。

源码包包括：

- `mobby-source.tar`：发布提交的项目源码、Android 构建文件、本地启动器、资源部署脚本，以及已修改的 libtermux-android 源码。
- 两个固定 Termux 配方快照：bootstrap 使用 `3326a2db3ffdc2920d99376e990f17ca9151b5d2`，覆盖依赖使用 `e392c87ee0005397d3b3b9b43576c270bef76cd8`。快照包含完整配方、构建框架和包补丁；清单中的 `recipe` 指明子包对应的主配方。
- Termux GPL/LGPL 包的上游源码归档，及 Bash 5.3.9、Readline 8.3.3 的全部官方增量补丁。termux-licenses 的源码是配方快照内的许可证文件，没有额外上游归档。
- Codex 0.155.1 的源码快照，包含它使用的 bubblewrap 源码、许可证与构建文件。
- OpenCode 1.18.32、opencode-termux v1.18.32-0、Bun 1.3.14、Bun 使用的 TinyCC 固定提交，以及 musl 1.2.5 的源码。社区打包脚本包含 musl 的 Termux resolver 路径补丁。
- Bun 使用的 WebKit 固定提交 `5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b`。保留根文件、Source、Tools、WebKitLibraries 与 CI 构建配置；省略 LayoutTests、PerformanceTests 和网站内容。GitHub 拒绝该仓库的全量快照，流程使用固定提交的稀疏 Git 检出，验证没有修改后打包已跟踪的构建材料。
- GCC 14.2.0 与对应 Alpine 配方提交 `1a03a2a9c9e77f1a07d48b6f6805e72c8c63c03f`。已逐字节比对 OpenCode 内的 libgcc、libstdc++ 与 Alpine 14.2.0-r4，确认一致。保留 GCC 许可证和 Runtime Library Exception。
- ONNX Runtime 1.28.2 所用 Eigen 的固定源码归档（MPL-2.0），与官方 deps.txt 校验和一致；ONNX 的原文许可及完整 ThirdPartyNotices 随 APK 保留。
- llama.cpp 的固定源码快照，补齐 Git archive 不包含的子模块。

归档在 `upstream/` 中保留原始内容，不执行上游源码归档里的代码。URL 与 SHA-256 位于源码包内的清单；WebKit 使用不可变 Git 提交校验，并仅打包该检出中已跟踪的文件。项目 MIT 不替代第三方组件各自的许可证。

## 重建与修改

解开相应源码归档和配方快照，按照快照自带的 Termux 构建说明使用对应 Android NDK 和构建环境，通过 `build-package.sh -a aarch64 <recipe>` 构建需要修改的包。Bash、Readline 必须使用清单列出的全部增量补丁及配方内的 Termux 补丁。

Bun 的 JavaScriptCore、TinyCC 使用 LGPL。修改库并重新链接时，使用上述固定 Bun 与 WebKit/TinyCC 源码及 Bun 自带构建说明，启用本地 WebKit 构建模式而非下载预编译库；再用固定 OpenCode 源码中的 `packages/opencode/script/build.ts` 编译 CLI。Bun 的第三方通知与许可证原文随 APK 保留，见 [上游通知](https://github.com/oven-sh/bun/blob/bun-v1.3.14/LICENSE.md)。本项目不限制对这些组件进行修改、重新链接或为调试修改而逆向工程。

musl 的 resolver 改动和 GCC 的 Alpine 补丁见 opencode-termux、Alpine 配方快照。项目源码中的 `runtime/opencode_launcher.c` 与 `runtime/prepare_bootstrap.py` 描述这些产物如何部署到 Android nativeLibraryDir。修改各组件后可更新本地锁文件、校验和和源码清单，再按项目 README 的 JDK 17/Android 构建流程生成 APK。

这些材料支持获取和修改对应源码；本轮没有在不同机器完整重编所有上游程序，不声称生成位完全一致的二进制。GPL 下载分发需同时提供对应源码，不能只用项目许可证替代，参见 [GNU 分发说明](https://www.gnu.org/licenses/gpl-faq.html.en#SourceAndBinaryOnDifferentSites)。

## Claude Code

APK 不含 Claude Code 的 JavaScript 程序或 npm 归档。设备从官方 npm registry 下载固定版本，校验 SHA-512 后提取 JavaScript 入口及官方许可证，失败保持未就绪，支持重新初始化重试。App 没有修改它的许可条款；使用时遵循 [官方许可证](https://github.com/anthropics/claude-code/blob/main/LICENSE.md)。Shell、Pi、Codex 与 OpenCode 不依赖这次下载成功。
