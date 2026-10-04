# 第三方许可的处理规则与当前实现

## 法律与许可条款

开源软件仍受著作权保护。以中国法为例，《著作权法》第十条规定复制、发行等权利，第二十六条规定使用他人作品的许可规则；取得开源许可意味着在该许可条件内使用，并不意味着放弃版权。[国家版权局公布的法律原文](https://www.ncac.gov.cn/xxfb/flfg/flfg_532/202103/t20210309_50530.html)

分发时具体需要保留哪些材料，取决于组件的许可版本、是否修改、链接与组合方式，而非组件是否“引用自 GitHub”。

| 许可 | 分发时需处理的核心内容 | 是否统一强制 App 界面列出 |
| --- | --- | --- |
| MIT | 保留原版权声明和完整许可条件、免责声明；只写 MIT 或仓库链接不足以替代 | 未统一规定必须做设置页 |
| BSD-2/3-Clause | 源码保留声明；二进制的随附材料保留版权、条件与免责声明；BSD-3 另有限制背书条款 | 可在随附文档或材料中保留 |
| Apache-2.0 | 提供许可副本；保留适用的原 NOTICE；修改的原文件带明显修改说明；源码保留适用声明 | NOTICE 可在随附 NOTICE、文档或界面中保留，不是必须启动弹窗 |
| GPL | 保留许可与声明，按对应版本提供完整对应源码、构建脚本及修改材料；组合/衍生作品还需审查许可范围 | 某些交互界面的法律通知义务与是否修改、上游界面有关，不能一概而论 |
| LGPL | 保留许可与声明、库源码；静态链接时还需提供能修改库并重新链接所需的使用方材料，不能只写 LGPL | 设置页不能代替源码及重新链接条件 |
| MPL-2.0 | 对受 MPL 覆盖的文件提供对应源码及获取方式，保留通知；范围通常按文件判断 | 告知接收者源码获取方式，不等于强制全部 App 改许可 |
| 专有许可 | 先核对是否有再分发授权；署名或放许可文本不能自行创造授权 | 按其实际许可处理 |

依据：[MIT 原文](https://opensource.org/license/mit)、[BSD-3 原文](https://opensource.org/license/bsd-3-clause)、[Apache-2.0 第 4 条](https://www.apache.org/licenses/LICENSE-2.0)、[GPLv3 第 5、6 条](https://gcc.gnu.org/onlinedocs/libstdc++/manual/appendix_gpl.html)、[LGPL-2.1 第 6 条](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html)、[MPL-2.0 第 3 条](https://www.mozilla.org/en-US/MPL/2.0/)。不能把所有 GPL/LGPL 版本、例外条款或组合方式混为一谈。

## 本项目采用的三处保留

1. **App 中**：设置 → 开源许可证。离线显示原版权、许可与 NOTICE，提供同版本 Release 的对应源码入口及 Claude Code 官方许可入口。无需先初始化 Agent 或登录网关。
2. **仓库中**：`third_party/` 保留原文通知与必要的许可副本；`third_party/maven/dependencies.lock.json` 记录经审查的 Android 运行依赖；`runtime/release-sources.lock.json` 记录运行程序、配方与对应源码材料；项目根 MIT 仅覆盖本项目有权许可的部分。
3. **Release 中**：APK 与对应源码包在同页下载，附两者的校验和。源码包不靠“请自行找上游 master”替代对应版本的实际材料。说明见 [对应源码](third-party-sources.md)。

## Android 与运行依赖

当前 release 解析结果是 116 个产物，按重复坐标归并为 **115 个 Maven 依赖**，包含传递依赖：107 个 Apache-2.0、5 个 BSD-2-Clause、3 个 MIT。新增的 AndroidX WindowManager 两个坐标已核对其 POM 与 Apache-2.0 许可并写入锁定清单。构建读取实际 releaseRuntimeClasspath、Maven POM 及父 POM，保留 JAR/AAR（含 classes.jar）里的许可与 NOTICE；缺少原文的 autolink、SLF4J 使用固定版本的官方原文。新增依赖、版本或许可变化须审查清单，不能自动以项目 MIT 填充。

Sherpa-ONNX AAR 另外包含 ONNX Runtime 1.28.2。其版本与 Sherpa 固定版本的 Android 构建配方及实际 ELF 字符串一致；MIT 原文和完整 ThirdPartyNotices 随 App 保留。Eigen 的 MPL 覆盖源码按 ONNX 1.28.2 deps.txt 的固定提交与校验和提供。当前 ARM64 JNI 没有检出 espeak/piper 符号，上游也已移除该依赖；不凭名称把它误判为旧版 GPL TTS 组合。

Termux 最终 87 个包单独按自身许可处理。Pi 与 npm 依赖中的原文继续保留；Codex 保留 Apache LICENSE/NOTICE，bubblewrap 保留其 **LGPL-2.0 原文**及源码。OpenCode 内置 Bun 的 JavaScriptCore、TinyCC 使用 LGPL，GCC 运行库还需考虑 Runtime Library Exception；这些不能概括为 OpenCode 的 MIT。Claude Code 不随 APK 再分发程序，改由设备向官方 registry 直接下载并校验。

项目没有把 GPL 程序的源码编译进 App 的 Kotlin/JNI 实现；它们通过独立命令行进程执行。依据 [GPL 的独立作品聚合条款](https://gcc.gnu.org/onlinedocs/libstdc++/manual/appendix_gpl.html)，同处一个 APK 本身不意味着所有代码都必须改成 GPL。这里是基于当前代码边界的合规处理判断；若以后复制 GPL 实现、增加紧密链接或修改组件，必须重新审查，不能沿用这一结论。

这份记录说明已核对的材料与工程措施，不构成对任何司法辖区、发行方式或全部链接关系的法律认证。不能仅凭设置页存在就声称所有分发义务都已完成。

补充材料按固定发布版本筛选：Codex 使用 `codex-cli`、`codex-bwrap` 在 `aarch64-unknown-linux-musl` 上的普通依赖图，排除测试依赖、构建工具和过程宏；原 crate 用 Cargo.lock 的 SHA-256 校验，Git 依赖使用锁定提交。Rust 标准库和实际 ELF 中的 OpenSSL 单独保留许可；实际随包版本以 `runtime/agents.lock.json` 为准。材料见 `third_party/codex/dependency-notices.json`。

Bun 材料见 `third_party/bun/dependency-notices.json`，包括 Linux ARM64 构建输入、WebKit 内的原版权声明、内嵌 polyfill 的普通依赖闭包和原手工维护 JS 文件的声明；排除仅用于 Windows 的 libuv 和 npm 构建工具 esbuild。原上游 LICENSE 文件保持完整，不删改其中的历史说明。Termux 许可模板仍保留在上游数据包内，App 页面仅展示最终包清单实际使用的模板。

少数 Rust crate 明确在 Cargo.toml 授予 Apache-2.0/MIT，却未随归档提供独立许可文件。优先恢复同提交的上游原文或源文件头；无法恢复独立文件时，提供其明确授予的标准许可副本并保留原 Cargo.toml 作者元数据，记录在 `license_copy_fallbacks`，不编造版权年份或持有人。对多选许可使用允许的分支，例如 self_cell 选择 Apache-2.0，不将未选择的 GPL 分支作为 APK 的适用许可。

OpenCode 的 `third_party/opencode-termux/dependency-notices.json` 根据固定源码提交的 bun.lock，保留普通 workspace 依赖闭包和 Linux ARM64 musl 原生辅助包的声明；排除开发依赖、@types、其他平台包，type-fest 等纯类型材料及不要求保留通知的 0BSD/CC0 不额外展示。DOMPurify 的多选许可采用 Apache-2.0，json-schema 采用 BSD-3-Clause。npm 归档缺少独立许可证时，恢复发布者原文并记录不可变 Git blob，或对明确的 package.json 授权提供标准副本；不把没有授权的依赖默认归为项目 MIT。CC-BY 数据保留原作者声明、原材料及许可地址，依据 [CC-BY 3.0](https://creativecommons.org/licenses/by/3.0/legalcode) 和 [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/legalcode.en) 的署名条件。

App JNI 使用的 Android NDK r27c libc++/ABI/展开运行库保留其实际工具链 NOTICE 中的对应 LLVM 许可段落，排除编译器工具等未再分发部分。llama.cpp 的 ARM64 KleidiAI v1.24.0 核心使用 Apache-2.0，保留原许可证与原 Arm 文件版权声明；BSD 测试/示例组件没有纳入 App，不额外列入。

发布流水线必须通过 APK 检查、完整源码附件打包和下载校验后才进入签名发布；源码清单或下载校验失败会阻止发布。本轮未在手机上重新验收许可证页面，也未完整重编全部上游原生程序。
