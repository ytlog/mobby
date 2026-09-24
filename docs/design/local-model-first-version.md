# 本地模型服务首版实现与使用

状态：2026-09-25 首版实现；完整目标方案见[本地模型模块与 HTTP API](local-model-runtime.md)。

## 已实现

`:app` 只依赖 `:local-model` 并打开它的管理页面。服务在同 APK 的 `:local_model` 独立进程运行，监听 `127.0.0.1:11435`。管理页通过 HTTP 查询、下载和加载模型；显式 Android Intent 只负责启动前台 Service。模块不依赖现有 Node 桥接或 Agent 代码。服务进程跳过宿主 Application 的运行时初始化。

当前可运行的引擎是固定提交的 llama.cpp arm64 CPU 后端，支持单文件 GGUF。在线目录从 Hugging Face 的 `Qwen/Qwen3-0.6B-GGUF` 和 `ggml-org/gemma-3-270m-it-GGUF` 查询，按 GGUF、文件大小和 LFS SHA-256 元数据过滤。用户在“设置 → Local model service”中选 Qwen/Gemma、搜索、下载并加载。下载时先写临时文件，校验长度和 SHA-256 后才登记为已安装。下载需要手机能访问 Hugging Face，模型权重不打包进 APK。

MLC 的 `JSONFFIEngine` 适配器位于 `:local-model-backend-mlc`，但当前构建没有官方 `mlc_llm package` 生成的 `mlc4j`、native runtime 和具体模型的编译库。管理 API 与 UI 因此报告 MLC 未打包，不提供不可运行的 MLC 权重下载。若要启用，先按[MLC Android 打包说明](https://github.com/mlc-ai/mlc-llm/blob/main/docs/deploy/android.rst)在构建机为目标模型生成 Android 产物；仅下载 MLC 权重不足以运行。生成目录预留在被忽略的 `local_model_mlc/mlc4j`。该适配器还需完成模型清单、多文件安装与真机验证后才能宣布 MLC 可用。

## HTTP API

先在管理页点击 Start，显示 `LISTENING` 后把 `http://127.0.0.1:11435/v1` 和页面复制的推理令牌配置给客户端。所有路由都要求 `Authorization: Bearer <token>`；`x-api-key` 与 `x-goog-api-key` 也接受，但多个不同值会拒绝。管理令牌和推理令牌分开，用模块专用 Keystore 别名加密保存在设备上。

| 路由 | 用途 |
| --- | --- |
| `GET /local/v1/health`、`GET /local/v1/engines` | 服务状态、引擎能力 |
| `GET /local/v1/catalog/models?backend=llama&family=Qwen` | 在线筛选模型；`family=Gemma` 同理 |
| `POST /local/v1/installs`、`GET /local/v1/operations/{id}` | 下载候选项、查询进度；需管理令牌 |
| `GET /local/v1/models`、`POST /local/v1/loads`、`POST /local/v1/models/unload` | 已安装模型和加载状态；加载/卸载需管理令牌 |
| `POST /local/v1/server/stop` | 停止服务；需管理令牌 |
| `GET /v1/models`、`POST /v1/responses`、`POST /v1/chat/completions`、`POST /v1/messages` | 模型列表与文本生成 |

推理目前仅支持纯文本的 system/user/assistant 消息与流式文本输出。工具、图片、未实现字段在生成前报错；不做静默转换。Gemini、Ollama 协议、Agent 工具调用、断点续传、下载取消、镜像源与 MLC 模型安装仍是设计目标，当前版本不能宣称兼容完整 Codex/Claude Code/OpenCode 请求。一个时刻只加载一个模型；模型 ID 来自安装目录。

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
