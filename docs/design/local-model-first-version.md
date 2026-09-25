# 本地模型服务首版实现与使用

状态：2026-09-25 首版实现；完整目标方案见[本地模型模块与 HTTP API](local-model-runtime.md)。

## 已实现

`:app` 只依赖 `:local-model` 并打开它的管理页面。服务在同 APK 的 `:local_model` 独立进程运行，监听 `127.0.0.1:11435`。管理页通过 HTTP 查询、下载和加载模型；显式 Android Intent 只负责启动前台 Service。模块不依赖现有 Node 桥接或 Agent 代码。服务进程跳过宿主 Application 的运行时初始化。

当前唯一后端是固定提交的 llama.cpp arm64 CPU，支持单文件 GGUF。在线目录查询千问 2.5 0.5B、千问 3 0.6B、[千问 3.5 0.8B](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF)、Gemma 3 270M/1B 和 [Gemma 4 E2B](https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF) 的 GGUF 仓库，按引擎格式、量化格式、文件大小和 SHA-256 元数据筛选。千问 3.5 可选 Q4_0（约 563 MB）和 Q8_0（约 834 MB）；Gemma 4 E2B 只选 Q4_0（约 2.84 GB）。当前只支持文本推理，因此不下载这两个多模态模型的视觉 projector 或 Gemma 4 的 MTP 文件；图片输入仍不可用。Gemma 4 还需要足够的设备存储和运行内存，具体手机能否顺利加载需要实机验证。可选来源为[魔搭 ModelScope](https://modelscope.cn/)、[Hugging Face](https://huggingface.co/) 和 [HF 镜像](https://hf-mirror.com/)；中文首次使用默认魔搭，英文首次使用默认 Hugging Face。用户手动选择会保存在模块设置中，模型列表与下载都只使用所选来源，不自动切源。下载先写临时文件，校验长度和 SHA-256 后才登记为已安装。模型权重不打包进 APK。

管理页沿用宿主设置页的明暗配色、圆角分组、标题和按钮层级。使用顺序：打开“设置 → 本地模型服务”后页面自动启动服务并显示千问列表；切换“Gemma”查看另一组；在“下载来源”选择手机可访问的网站；点击某个量化版本的“下载”，进度和大小会在卡片内显示；完成后在“已下载”中点击“加载”；最后复制地址、推理密钥和模型 ID，填入同一部手机上客户端的自定义网关。服务地址只监听本机；其他设备不能直接访问。页面重新打开时会恢复尚在运行的下载进度。

## HTTP API

管理页显示“运行中”并加载模型后，把 `http://127.0.0.1:11435/v1` 和页面复制的推理令牌配置给客户端。所有路由都要求 `Authorization: Bearer <token>`；`x-api-key` 与 `x-goog-api-key` 也接受，但多个不同值会拒绝。管理令牌和推理令牌分开，用模块专用 Keystore 别名加密保存在设备上。

| 路由 | 用途 |
| --- | --- |
| `GET /local/v1/health`、`GET /local/v1/engines` | 服务状态、引擎能力 |
| `GET /local/v1/catalog/models?backend=llama&family=Qwen&source=modelscope` | 在线筛选模型；`family=Gemma`、`source=huggingface` 或 `source=hf-mirror` 同理。未指定来源时 HTTP API 默认为魔搭，与页面语言默认值独立 |
| `POST /local/v1/installs`、`GET /local/v1/operations`、`GET /local/v1/operations/{id}` | 下载候选项、查询进度；需管理令牌 |
| `GET /local/v1/models`、`POST /local/v1/loads`、`POST /local/v1/models/unload` | 已安装模型和加载状态；加载/卸载需管理令牌 |
| `POST /local/v1/server/stop` | 停止服务；需管理令牌 |
| `GET /v1/models`、`POST /v1/responses`、`POST /v1/chat/completions`、`POST /v1/messages` | 模型列表与文本生成 |

推理目前仅支持纯文本的 system/user/assistant 消息与流式文本输出。工具、图片、未实现字段在生成前报错；不做静默转换。Gemini、Ollama 协议、Agent 工具调用、断点续传、下载取消和镜像源仍是设计目标，当前版本不能宣称兼容完整 Codex/Claude Code/OpenCode 请求。一个时刻只加载一个模型；模型 ID 来自安装目录。

加载模型后可用如下请求验证文本推理（将示例模型 ID 和令牌替换为页面显示的实际值）：

```sh
curl http://127.0.0.1:11435/v1/chat/completions \
  -H 'Authorization: Bearer <推理令牌>' \
  -H 'Content-Type: application/json' \
  -d '{"model":"<已加载模型 ID>","messages":[{"role":"user","content":"你好"}],"max_tokens":64}'
```

## 构建与验证

先运行 `git submodule update --init third_party/llama.cpp`，使用项目现有 Android SDK/NDK、JDK 17 执行 `./gradlew :app:assembleDebug`。`app/src/androidTest/.../LocalModelSmokeTest.kt` 验证独立进程、HTTP 健康检查以及手机网络上的 Qwen/Gemma 目录查询。该测试不下载几百 MiB 的权重；模型生成需要另在设备上下载并运行验证。

设计文档是目标架构，不能将其中未来接口、五组协议、多源下载和资源调度视为当前实现。后续迭代必须据此更新实现状态，不在现有首版上假设这些能力已经存在。
