# 本地模型服务首版实现与使用

状态：2026-09-25 首版实现；完整目标方案见[本地模型模块与 HTTP API](local-model-runtime.md)。

## 已实现

`:app` 只依赖 `:local-model` 并打开它的管理页面。服务在同 APK 的 `:local_model` 独立进程运行，监听 `127.0.0.1:11435`。管理页通过 HTTP 查询、下载和加载模型；显式 Android Intent 只负责启动前台 Service。模块不依赖现有 Node 桥接或 Agent 代码。服务进程跳过宿主 Application 的运行时初始化。

当前唯一后端是固定提交的 llama.cpp arm64 CPU，支持单文件 GGUF。在线目录查询千问 2.5 0.5B、千问 3 0.6B、[千问 3.5 0.8B](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF)、Gemma 3 270M/1B 和 [Gemma 4 E2B](https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF) 的 GGUF 仓库，按引擎格式、量化格式、文件大小和 SHA-256 元数据筛选。千问 3.5 可选 Q4_0（约 563 MB）和 Q8_0（约 834 MB）；Gemma 4 E2B 只选 Q4_0（约 2.84 GB）。当前只支持文本推理，因此不下载这两个多模态模型的视觉 projector 或 Gemma 4 的 MTP 文件；图片输入仍不可用。Gemma 4 还需要足够的设备存储和运行内存，具体手机能否顺利加载需要实机验证。可选来源为[魔搭 ModelScope](https://modelscope.cn/)、[Hugging Face](https://huggingface.co/) 和 [HF 镜像](https://hf-mirror.com/)；中文首次使用默认魔搭，英文首次使用默认 Hugging Face。用户手动选择会保存在模块设置中，模型列表与下载都只使用所选来源，不自动切源。下载先写临时文件，校验长度和 SHA-256 后才登记为已安装。模型权重不打包进 APK。

管理页沿用宿主设置页的明暗配色、圆角分组、标题和按钮层级。使用顺序：打开“设置 → 本地模型服务”后页面自动启动服务并显示千问列表；切换“Gemma”查看另一组；在“下载来源”选择手机可访问的网站；点击某个量化版本的“下载”，进度和大小会在卡片内显示；完成后在“已下载”中点击“加载”。已加载模型会自动出现在宿主的网关列表，并标明“临时 · 本地模型”，无需复制地址、密钥或模型 ID；停止模型或本地服务后，临时网关从列表移除。网关记录和选中状态只保存在宿主进程内，不写入长期网关配置；每次列出或使用时，宿主通过本机 HTTP 健康接口核对加载状态。服务地址只监听本机；其他设备不能直接访问。页面重新打开时会恢复尚在运行的下载进度。

## HTTP API

管理页显示“运行中”并加载模型后，宿主将 `http://127.0.0.1:11435/v1` 作为临时网关地址接入。同机独立 HTTP 客户端可展开页面的“其他客户端接入”复制连接信息；宿主网关无需此步骤。所有路由都要求 `Authorization: Bearer <token>`；`x-api-key` 与 `x-goog-api-key` 也接受，但多个不同值会拒绝。管理令牌和推理令牌分开，用模块专用 Keystore 别名加密保存在设备上。

| 路由 | 用途 |
| --- | --- |
| `GET /local/v1/health`、`GET /local/v1/engines` | 服务状态、引擎能力 |
| `GET /local/v1/catalog/models?backend=llama&family=Qwen&source=modelscope` | 在线筛选模型；`family=Gemma`、`source=huggingface` 或 `source=hf-mirror` 同理。未指定来源时 HTTP API 默认为魔搭，与页面语言默认值独立 |
| `POST /local/v1/installs`、`GET /local/v1/operations`、`GET /local/v1/operations/{id}` | 下载候选项、查询进度；需管理令牌 |
| `GET /local/v1/models`、`POST /local/v1/loads`、`POST /local/v1/models/unload` | 已安装模型和加载状态；加载/卸载需管理令牌 |
| `POST /local/v1/server/stop` | 停止服务；需管理令牌 |
| `GET /v1/models`、`POST /v1/responses`、`POST /v1/chat/completions`、`POST /v1/messages` | 模型列表、文本与工具调用生成 |

推理支持纯文本 system/user/assistant 消息，以及 Responses、Messages、Chat Completions 的函数工具定义、调用与结果回传。Responses 的文本自定义工具映射为模型工具模板中的字符串参数，再还原为原协议的 `custom_tool_call`；SSE 的工具事件在完整生成和校验后发送。模型必须带有 llama.cpp 可识别的工具聊天模板；模型不支持工具、生成未知工具名或无效 JSON 参数时明确报错。图片、严格 JSON Schema、显式推理强度、云端内置工具和未实现字段仍拒绝，不做静默转换。本地网关运行 Codex 时关闭仅云端可用的 web_search 与多 Agent 工具；运行 Claude Code 时将输出请求限制为 1,024 token 并关闭云端推理配置；运行 OpenCode 时为本地模型声明 32,768 token 上下文和 1,024 token 输出上限。三者仍发送各自原生协议请求，Node 桥接不删除工具或转换协议。以上是协议实现，尚未经过手机上实际模型的端到端工具循环验收，不宣称完整兼容所有 Agent 版本。一个时刻只加载一个模型；模型 ID 来自安装目录。

加载模型后可用如下请求验证文本推理（将示例模型 ID 和令牌替换为页面显示的实际值）：

```sh
curl http://127.0.0.1:11435/v1/chat/completions \
  -H 'Authorization: Bearer <推理令牌>' \
  -H 'Content-Type: application/json' \
  -d '{"model":"<已加载模型 ID>","messages":[{"role":"user","content":"你好"}],"max_tokens":64}'
```

需要工具调用的客户端在请求中提供工具定义和历史工具结果。比如 Responses 使用 `tools:[{"type":"function","name":"read_file","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}]`，模型返回 `output[].type="function_call"` 和 `call_id`；客户端执行工具后再提交同一 `call_id` 的 `function_call_output`。Messages 使用 `tool_use` / `tool_result`，Chat Completions 使用 `tool_calls` / `role="tool"`。服务只生成调用提议，工具由 Agent 客户端执行。工具请求目前完整生成后再发送 SSE 事件，长提示和低速模型可能等待较久；工具请求上下文最多 32,768 token，输出最多 1,024 token，超过时明确失败。

## 构建与验证

先运行 `git submodule update --init third_party/llama.cpp`，使用项目现有 Android SDK/NDK、JDK 17 执行 `./gradlew :app:assembleDebug`。`app/src/androidTest/.../LocalModelSmokeTest.kt` 验证独立进程、HTTP 健康检查以及手机网络上的 Qwen/Gemma 目录查询。该测试不下载几百 MiB 的权重；模型生成需要另在设备上下载并运行验证。

设计文档是目标架构，不能将其中未来接口、五组协议、多源下载和资源调度视为当前实现。后续迭代必须据此更新实现状态，不在现有首版上假设这些能力已经存在。
