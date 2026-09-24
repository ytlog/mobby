# 手机本地推理引擎调研与接入目录

核对日期：2026-09-24。配套架构见[本地模型 Gradle 模块与 HTTP 服务](local-model-runtime.md)。本表基于上游官方仓库和文档，属于设计输入；所有后端在独立模型服务中均尚未实现或真机验收。上游 main 分支不等于已经发布的稳定包，实施时必须锁定 tag/commit、依赖及构建哈希。

## 1. 分类原则

“加入架构”表示分配后端 ID、适配路径及验收条件，不表示把全部原生库打进默认 APK。按构建配置选择后端、按设备验证结果展示可运行组合。协议适配由统一 HTTP 层实现，不要求每个引擎自带 Responses/Messages 服务。

- **A：优先接入**：有官方 Android LLM 路径，纳入主要实现计划。
- **B：专项验证**：有移动推理/研究路径，但 Android LLM、所选模型或发布方式需要额外验证。
- **C：兼容/外部服务**：用于旧模型包或已有服务器，不能据此承诺当前手机可运行。
- **D：相关技术边界**：不作为 Android 开源 LLM 引擎直接打包。

A/B 是本项目的工程优先级，不是性能排名。GPU/NPU 都须绑定芯片、驱动、模型编译配置；支持 ARM64 不等于支持 Android Bionic，支持 Android 不等于支持所有 LLM。

## 2. 主要 LLM 引擎

| 后端 ID / 项目 | 组织与官方证据 | Android 集成与模型产物 | 设计定位及主要限制 |
| --- | --- | --- | --- |
| `llama` / llama.cpp | 社区；[Android 指南](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) | NDK/C++ + JNI；GGUF，视觉模型还需配套产物 | A，CPU 基线，GPU 单独验证；按固定版本支持的架构、模板与量化加载 |
| `litert-lm` / LiteRT-LM | Google；[仓库](https://github.com/google-ai-edge/LiteRT-LM)、[Kotlin 指南](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md) | Android Kotlin/C++ API；`.litertlm` 模型包 | A，Google 主路径；工具、多模态及加速能力按具体模型/设备探测，不把 NPU 支持视为所有设备通用 |
| `onnx-genai` / ONNX Runtime GenAI | Microsoft；[官方支持矩阵](https://github.com/microsoft/onnxruntime-genai)、[构建说明](https://onnxruntime.ai/docs/genai/howto/build-from-source.html) | Android 被列入支持系统；C/C++/Java，Java 路径需源码构建；ONNX 图、external data、生成配置和 tokenizer | A，微软 LLM 主路径；不能拿桌面 Python wheel 代替 Android 库，锁定 ORT 与 GenAI 配对版本 |
| `mlc` / MLC LLM | MLC 社区；[Android SDK](https://llm.mlc.ai/docs/deploy/android.html) | mlc4j + TVM runtime + 编译模型库 + 转换权重 | A，移动 GPU 路径；模型库与权重/编译参数绑定，不直接加载 GGUF |
| `executorch` / ExecuTorch | PyTorch/Meta；[项目](https://github.com/pytorch/executorch)、[Android LLM 指南](https://github.com/pytorch/executorch/blob/main/docs/source/llm/run-on-android.md) | Android AAR、LLM runner；导出 `.pte` 及 tokenizer/附属权重 | A，PyTorch 端侧路径；导出图、算子、后端 delegate、模型版本须匹配；预训练权重不能直接替代导出产物 |
| `mnn` / MNN-LLM | 阿里；[MNN](https://github.com/alibaba/MNN)、[Android 示例入口](https://github.com/alibaba/MNN/blob/master/project/android/apps/MnnLlmApp/README_CN.md) | C++ LLM 接口/JNI；MNN 转换模型、配置及 tokenizer；有 Android 多模态应用 | A，重点比较手机 CPU/多模态；官网性能数字不能当作本项目设备数据 |
| `mllm` / MLLM | UbiquitousLearning；[官方仓库](https://github.com/UbiquitousLearning/mllm) | ARM/Android 路径，移动多模态及 QNN AOT 研究路线 | B，面向手机 LLM 的专项后端；不把 Ascend/Jetson 支持映射为 Android NPU 支持 |
| `powerserve` / PowerServe | PowerServe 社区；[官方仓库](https://github.com/powerserve-project/PowerServe) | Android ARM64 编译、手机本地服务及 NPU 相关路径 | B，限定机型/模型评估；需验证 QNN 依赖、进程启动和取消，不能把研究加速结论当通用保证 |
| `fastllm` / FastLLM | 社区；[官方 README](https://github.com/ztxz16/fastllm/blob/master/README_EN.md) | C++ 引擎，仓库列有 Android/其他平台入口；当前主线还覆盖桌面与服务部署 | B，固定 Android 构建专项验证；上游 HTTP 兼容接口与 Python 工具不代表可直接移植到手机 |
| `bitnet` / BitNet | Microsoft；[官方仓库](https://github.com/microsoft/BitNet) | 1-bit LLM 专用推理框架，ARM CPU 是研究候选 | B，尚未由本次资料确认可直接使用的 Android SDK；独立权重/内核组合，不能视作任意模型的一键量化后端 |
| `ollama` / Ollama | Ollama；[下载平台](https://ollama.com/download)、[仓库](https://github.com/ollama/ollama) | 已有服务通过 HTTP 连接；应用托管 Android 版须单独移植 | C，保留服务后端；官方桌面/Linux 分发不等于 Android 内嵌 SDK；云模型不得冒充手机本地模型 |

微软同时有 **ONNX Runtime** 与 **ONNX Runtime GenAI**：前者是执行图的基础，后者提供生成循环、采样和 KV 管理等 LLM 能力，不能混成一个未经验证的“ONNX 支持”。Google 同理：**LiteRT** 是底层运行时，**LiteRT-LM** 是本设计优先使用的 LLM 层。Phi、Gemma、Qwen 是模型家族，不是引擎。

## 3. 通用手机推理与旧路径

以下项目纳入后端目录和扩展计划；通用算子推理能力不能直接承诺 Agent 工具调用。

| 后端 ID / 项目 | 官方证据 | 接入定位 |
| --- | --- | --- |
| `onnx` / ONNX Runtime Mobile | Microsoft；[移动指南](https://onnxruntime.ai/docs/get-started/with-mobile.html)、[Android/移动部署](https://onnxruntime.ai/docs/tutorials/mobile/) | B，用于 embedding、视觉编码器等；若直接承载 LLM，需要自建并验证生成 runner，不能仅调用一次图推理就宣称聊天可用 |
| `litert` / LiteRT（TensorFlow Lite 后继） | Google；[官方仓库](https://github.com/google-ai-edge/LiteRT) | B，通用张量模型及配套编码器；LLM 优先复用 LiteRT-LM，避免维护重复生成循环 |
| `ncnn` / ncnn | 腾讯；[官方仓库](https://github.com/Tencent/ncnn) | B，Android CPU/Vulkan 通用推理；官方入口也列 LLM/embedding/视觉语言示例，但须单独核验相应 runner 与目标模型，不能宣称只适合 CNN 或已全面支持 LLM |
| `paddle-lite` / Paddle Lite | 百度；[官方仓库](https://github.com/PaddlePaddle/Paddle-Lite) | B，手机/边缘通用推理；LLM 解码、KV、量化及 Android 发布产物另验，优先考虑配套编码任务 |
| `mindspore-lite` / MindSpore Lite | MindSpore；[官方介绍](https://www.mindspore.cn/lite/en/)、[Android 下载矩阵](https://www.mindspore.cn/lite/cloud_docs/en/master/use/downloads.html) | B，有 Android 推理库/AAR 路径；手机 LLM 与云端/Ascend 路径分开验证，不能由服务器能力推导手机能力 |
| `bolt` / Bolt | 华为 Noah；[官方仓库](https://github.com/huawei-noah/bolt) | B，有 Android ARM 构建和 Java/C API；适合作为通用推理候选，现代 LLM、维护状态、算子支持需专项确认 |
| `mediapipe-llm` / MediaPipe LLM Inference | Google；[官方状态与指南](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) | C，旧模型包适配；官方已声明维护模式并推荐 LiteRT-LM，新集成不重复投入两套 Google 主路径 |

## 4. 不混入“Android 开源引擎”的相关技术

| 项目/能力 | 为什么单独列出 |
| --- | --- |
| [MLX](https://github.com/ml-explore/mlx) / Apple 平台生态 | 开源且适合 Apple silicon，但目标服务是 Android；列为未来其他平台后端，不进入 Android 构建 |
| [Qualcomm AI Hub Models](https://github.com/qualcomm/ai-hub-models) / QNN | 模型、示例与 NPU 工具链相关能力；不能把开放示例等同于所有 SDK/驱动都开源。QNN 作为可选执行后端依赖，经许可、芯片与系统版本审核再分发 |
| Google AICore / 系统模型服务 | 与可由本应用打包、独立进程管理的开源引擎不是同一交付方式；本期不以其作为离线引擎兜底 |
| Vulkan、OpenCL、XNNPACK、NNAPI | 图形/计算 API、内核库或系统加速接口，不分别包装成完整 LLM 后端；由上层引擎选择 |
| 手机 LLM 聊天 App | 示例/产品可用作参考，不能直接当作可链接的引擎库；优先接其上游 runtime |

本轮覆盖 18 个主要/相关运行时条目，另列平台边界；这是有官方证据的可维护目录，不宣称穷尽所有项目。今后新增后端遵守同一个 SPI 和状态机，不增加新的协议入口。

模型权重下载按引擎匹配器动态生成 recipe 并选择 HF 适配变体，并支持官方备用源及内容一致的镜像；具体入口、文件选择和换源规则见[模型匹配与多源下载](local-model-downloads.md)。

## 5. 构建注册与上线门槛

每个目录条目分开记录 `researchStatus`、`packaged`、`deviceCompatible`、`modelVerified`、`protocolProfiles`。本次所有 `packaged/modelVerified=false`；UI 不把调研条目显示为可选择运行的引擎。

构建配置选择 `llama`、`litert-lm`、`onnx-genai`、`mlc`、`executorch`、`mnn` 等独立 adapter。通用引擎可只实现 `EmbeddingBackend` 或 `EncoderBackend`；没有生成 runner 时不得实现承担生成职责的空 `LocalModelBackend` 并返回假成功。后端描述符含任务类型集合，不强迫每个项目拥有所有功能。

验收材料必须包括：

1. 官方来源及不可变 commit/tag、许可证清单、SDK/驱动附加条款、可重现构建命令和 artifact 哈希。
2. Android ABI/API、NDK/STL、16 KiB 页大小兼容、native 符号/依赖冲突、最低设备要求。
3. 模型包格式、tokenizer、模板、量化、支持的内容块、工具与 JSON schema 能力。
4. 真实模型加载、首 token、持续生成、计数、取消与卸载；native 崩溃和重复装卸。
5. 断网推理、无自动云回退；关闭可选遥测/下载行为并用流量检查证明离线边界。
6. 同机同任务下的内存、能耗、温度与质量；NPU 加速必须确认真实执行分区，不能仅据 delegate 创建成功推断。
7. HTTP 协议合同与目标 Agent 真实工具循环分别验收；不支持字段必须可诊断地拒绝。

推荐交付顺序：llama CPU + HTTP 协议基线 → LiteRT-LM/ONNX GenAI/MNN/MLC/ExecuTorch → 其他专项后端。先完成可使用的统一服务，再逐个加入引擎；全部共享端口、鉴权、模型目录、调度和错误语义。
