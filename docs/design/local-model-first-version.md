# 本地模型服务首版实现与使用

状态：2026-09-25 首版实现，宿主 UI 入口暂时隐藏；完整目标方案见[本地模型模块与 HTTP API](local-model-runtime.md)。

## 已实现

`:app` 仍依赖 `:local-model`，但当前不从宿主页面打开其管理页。服务在同 APK 的 `:local_model` 独立进程运行，监听 `127.0.0.1:11435`。管理页通过 HTTP 查询、下载和加载模型；显式 Android Intent 只负责启动前台 Service。模块不依赖现有 Node 桥接或 Agent 代码。服务进程跳过宿主 Application 的运行时初始化。

当前唯一推理引擎是固定提交的 llama.cpp arm64，支持单文件 GGUF。构建机有兼容的 shaderc 时同时打包 Vulkan GPU；设备 Vulkan API 至少为 1.2 且实际枚举到可用 GPU 后，模型文件小于 1.5 GB，或设备内存至少 12 GB 且文件小于 3 GB 时，才尝试将全部层卸载至 GPU；其他组合直接使用 CPU。已知 Gemma 4 在部分卸载的 CPU/GPU 混合路径上会崩溃，因此当前不使用部分卸载。模型和 4096 token 上下文在 GPU 上初始化成功后才显示 Vulkan；失败时回退 CPU 并显示原因。CPU 构建启用 KleidiAI 内核，实际使用哪些指令由设备运行时选择；NPU 尚未接入。推理过程只保留一个模型、一个 native 上下文。相邻请求的聊天模板编码结果有相同 token 前缀时，复用该前缀的 KV 状态并只处理后缀；前缀不一致、上下文容量不足或后端不允许裁剪时重算。切换或卸载模型会释放上下文。缓存存在于进程内，不写入磁盘，也不跨服务重启保留。

在线目录查询千问 2.5 0.5B、千问 3 0.6B、[千问 3.5 0.8B](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF)、Gemma 3 270M/1B 和 [Gemma 4 E2B](https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF) 的 GGUF 仓库，按引擎格式、量化格式、文件大小和 SHA-256 元数据筛选。千问 3.5 可选 Q4_0（约 563 MB）和 Q8_0（约 834 MB）；Gemma 4 E2B 只选 Q4_0（约 2.84 GB）。当前只支持文本推理，因此不下载这两个多模态模型的视觉 projector 或 Gemma 4 的 MTP 文件；图片输入仍不可用。Gemma 4 还需要足够的设备存储和运行内存，具体手机能否顺利加载需要实机验证。可选来源为[魔搭 ModelScope](https://modelscope.cn/)、[Hugging Face](https://huggingface.co/) 和 [HF 镜像](https://hf-mirror.com/)；中文首次使用默认魔搭，英文首次使用默认 Hugging Face。用户手动选择会保存在模块设置中，模型列表与下载都只使用所选来源，不自动切源。下载先写临时文件，校验长度和 SHA-256 后才登记为已安装。模型权重不打包进 APK。

管理页、Qwen/Gemma 下载与模型加载功能仍保留在独立模块中，模型文件、下载来源设置和鉴权数据不删除。当前宿主隐藏“设置 → 本地模型服务”和“添加网关 → 本地模型服务”，不自动接入或展示临时本地网关；因此普通用户界面暂不提供进入管理页或启动服务的路径。已配置的远端网关和会话继续按原有流程使用。恢复入口前需重新完成真机的模型加载、工具调用和网关端到端验收。

## HTTP API

管理页显示“运行中”并加载模型后，宿主将 `http://127.0.0.1:11435/v1` 作为临时网关地址接入。同机独立 HTTP 客户端可展开页面的“其他客户端接入”复制连接信息；宿主网关无需此步骤。所有路由都要求 `Authorization: Bearer <token>`；`x-api-key` 与 `x-goog-api-key` 也接受，但多个不同值会拒绝。管理令牌和推理令牌分开，用模块专用 Keystore 别名加密保存在设备上。

| 路由 | 用途 |
| --- | --- |
| `GET /local/v1/health`、`GET /local/v1/engines` | 服务状态、引擎能力；健康响应包含实际运行后端 `backend` 和回退原因 `backendReason`（未加载时均为 `null`），以及最近一次推理的阶段、提示词/复用/生成 token 数，以及加载、预填充、首次输出和总耗时。只记录数量与时间，不记录提示词、生成内容或密钥 |
| `GET /local/v1/catalog/models?backend=llama&family=Qwen&source=modelscope` | 在线筛选模型；`family=Gemma`、`source=huggingface` 或 `source=hf-mirror` 同理。未指定来源时 HTTP API 默认为魔搭，与页面语言默认值独立 |
| `POST /local/v1/installs`、`GET /local/v1/operations`、`GET /local/v1/operations/{id}` | 下载候选项、查询进度；需管理令牌 |
| `GET /local/v1/models`、`POST /local/v1/loads`、`POST /local/v1/models/unload` | 已安装模型和加载状态；加载/卸载需管理令牌 |
| `POST /local/v1/server/stop` | 停止服务；需管理令牌 |
| `GET /v1/models`、`POST /v1/responses`、`POST /v1/chat/completions`、`POST /v1/messages` | 模型列表、文本与工具调用生成 |

推理支持纯文本 system/user/assistant 消息，以及 Responses、Messages、Chat Completions 的函数工具定义、调用与结果回传。Responses 的文本自定义工具映射为模型工具模板中的字符串参数，再还原为原协议的 `custom_tool_call`；SSE 的文本增量在生成过程中发送，工具事件在完整生成和校验后发送。模型必须带有 llama.cpp 可识别的工具聊天模板；模型不支持工具、生成未知工具名或无效 JSON 参数时明确报错。图片、严格 JSON Schema、显式推理强度、云端内置工具和未实现字段仍拒绝，不做静默转换。本地网关运行 Codex 时关闭仅云端可用的 web_search 与多 Agent 工具；运行 Claude Code 时将输出请求限制为 1,024 token 并关闭云端推理配置；运行 OpenCode 时为本地模型声明 32,768 token 上下文和 1,024 token 输出上限。三者仍发送各自原生协议请求，Node 桥接不删除工具或转换协议。以上是协议实现，尚未经过手机上实际模型的端到端工具循环验收，不宣称完整兼容所有 Agent 版本。一个时刻只加载一个模型；模型 ID 来自安装目录。

加载模型后可用如下请求验证文本推理（将示例模型 ID 和令牌替换为页面显示的实际值）：

```sh
curl http://127.0.0.1:11435/v1/chat/completions \
  -H 'Authorization: Bearer <推理令牌>' \
  -H 'Content-Type: application/json' \
  -d '{"model":"<已加载模型 ID>","messages":[{"role":"user","content":"你好"}],"max_tokens":64}'
```

需要工具调用的客户端在请求中提供工具定义和历史工具结果。比如 Responses 使用 `tools:[{"type":"function","name":"read_file","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}]`，模型返回 `output[].type="function_call"` 和 `call_id`；客户端执行工具后再提交同一 `call_id` 的 `function_call_output`。Messages 使用 `tool_use` / `tool_result`，Chat Completions 使用 `tool_calls` / `role="tool"`。服务只生成调用提议，工具由 Agent 客户端执行。工具请求的文本可逐段显示；模型若选择调用工具，工具参数仍需等待完整生成和校验。长提示的预填充阶段也可能等待较久；工具请求上下文最多 32,768 token，输出最多 1,024 token，超过时明确失败。管理页的“推理统计”会显示最近请求处于准备、处理提示词或生成阶段，以及 KV 前缀复用量和首段输出耗时。复用减少重复预填充，不能加速不共享前缀的首次请求；具体性能收益仍需在目标手机上对同一模型和请求实测。

## 构建与验证

先运行 `git submodule update --init third_party/llama.cpp`，使用项目现有 Android SDK/NDK、JDK 17 执行 `./gradlew :app:assembleDebug`。构建机还需安装支持 llama.cpp 着色器的新版 `glslc`（shaderc）和包含 `vulkan.hpp` 的 Vulkan-Headers，才会打包 Vulkan；否则明确警告并构建 CPU 版。需要强制检查 GPU 构建时使用 `./gradlew -Pmobby.requireVulkan=true :app:assembleDebug`。`app/src/androidTest/.../LocalModelSmokeTest.kt` 验证独立进程、HTTP 健康检查以及手机网络上的 Qwen/Gemma 目录查询。`LocalModelGpuDeviceTest.kt` 会在设备已下载 GGUF 且声明 Vulkan Compute 时，实际加载权重并生成 4 个 token，核对运行后端。两项测试都不会下载权重；完整 Agent 工具循环仍需设备验收。

2026-09-25 真机验证：M2007J1SC / Adreno 650 的物理设备仅报告 Vulkan 1.1，强行卸载模型会在驱动核心函数入口处崩溃，因此此机型明确回退 CPU。已下载的 Gemma 4 E2B Q4_0 在 CPU 上冷加载约 52 秒；原普通聊天路径不支持其 Jinja 模板，修正为与工具路径共用模板处理器后，固定“你好”提示词的首 token 约 1.1 秒，4 token 约 2 秒。以上是独立 native 探针的结果，非网关端到端或长上下文性能保证。 同日另一台 25098PN5AC / Adreno 840（Vulkan 1.4）上，Gemma 4 部分卸载 16/36 层时，带工具的生成在首 token 后出现 NaN 或 Vulkan DEVICE_LOST；完整卸载 36/36 层后，同一固定工具请求连续生成 128 token 成功。该结果来自隔离 native 探针，真实网关长对话仍需验收。

设计文档是目标架构，不能将其中未来接口、五组协议、多源下载和资源调度视为当前实现。后续迭代必须据此更新实现状态，不在现有首版上假设这些能力已经存在。
