# 按引擎匹配模型与多源下载设计

状态：设计提案，尚未实现；核对日期：2026-09-24。本文将用户所说的“hg”按 Hugging Face（HF）理解。配套见[模块与 HTTP API](local-model-runtime.md)、[引擎目录](local-model-engines.md)。

## 1. 用户流程与默认策略

在“本地模型”中选择已安装的引擎，或选择“自动推荐”，应用根据引擎版本、手机硬件、可用内存、上下文与任务能力列出匹配模型，显示下载大小、运行内存估计、量化、发布者、许可证与来源。用户选择后生成下载计划，再安装；不在浏览模型列表时自动下载权重。

默认顺序为 **Hugging Face → 引擎官方备用发布源 → 经登记并验证内容一致的镜像**。已经验证的本地缓存优先复用。用户可以指定首选源或固定一个来源；固定来源失败时暂停，不擅自换源。HF 没有适配产物时直接展示可用的官方产物，不先下载原始权重尝试运行。

“官方发布者”与“托管平台”是不同维度：官方项目在 HF 发布也是官方模型源；HF 搜索结果不是全部官方模型。ModelScope 等备用平台也不能自动视作原文件的镜像。默认只自动切换下载计划中明确列出的等价来源，进度页显示切换原因、实际域名和已验证字节数。

没有满足要求的模型时，返回“暂无适配产物”及原因，例如“缺少该 MLC 模型库”“只有 CUDA ONNX 版本”“模型内存超过预算”。不下载无法加载的 `.safetensors`，不在手机自动执行转换脚本。

## 2. 各引擎的来源与选择规则

下面是已查证的目录入口，不是已锁定版本的安装清单，也不代表列出的模型适合当前手机。

| 引擎 | HF / 官方入口 | 必须选取的产物与限制 |
| --- | --- | --- |
| llama.cpp | [官方模型获取说明](https://github.com/ggml-org/llama.cpp/blob/master/docs/models.md)中的 HF 路径；优先官方发布或已审核转换仓库 | 选择一个 GGUF 量化变体，包含该变体全部分片；视觉模型连同精确匹配的 projector。不能下载所有量化或按扩展名随便选第一项 |
| LiteRT-LM | [LiteRT Community](https://huggingface.co/litert-community)，由 [Google 项目](https://github.com/google-ai-edge/LiteRT-LM)指向 | `.litertlm` 及所需伴随文件；校验 SDK 版本、支持的 backend 和设备要求，不能把旧 `.task` 当作可互换文件 |
| ONNX Runtime GenAI | Microsoft 模型仓库，例如 [Phi ONNX 目录](https://huggingface.co/microsoft/phi-4-onnx/tree/main) | 只选择经验证的 CPU/mobile 或对应设备 EP 子目录，携带生成配置、tokenizer 和 external data；目录存在不等于手机内存足够 |
| MLC | [mlc-ai](https://huggingface.co/mlc-ai)、[Android 打包说明](https://llm.mlc.ai/docs/deploy/android.html) | 转换权重、配置与 tokenizer；必须对应 APK 已打包的 `modelLibId`、量化与编译参数。缺少模型库时不能靠下载权重解决 |
| MNN | 官方[预转换模型目录](https://github.com/alibaba/MNN/wiki/models)明确列出 [HF taobao-mnn](https://huggingface.co/taobao-mnn) 与 [ModelScope MNN](https://modelscope.cn/organization/MNN) | 使用适配该 MNN runtime 的完整模型包与配置。HF/ModelScope 逐文件验证后才登记为镜像；同名仓库不足以证明等价 |
| ExecuTorch | [官方 LLM 导出与部署流程](https://docs.pytorch.org/executorch/stable/llm/working-with-llms.html)关联的模型/产物 | 接收已导出、与 runtime/delegate 匹配的 `.pte` 和配套文件；没有已发布产物时标记“需要在开发机导出”，不直接下载 HF 原模型假装可运行 |
| MLLM / PowerServe / FastLLM / BitNet | 使用[引擎目录](local-model-engines.md)中各官方项目明确引用的模型发布地址 | 逐项目维护 artifact recipe；没有官方适配说明的仓库仅是搜索候选，不能因为名称相似自动安装 |
| ONNX / LiteRT / ncnn / Paddle Lite / MindSpore Lite / Bolt | 对应官方模型库或经验证的 HF 转换产物 | 按 generation/embedding/encoder 任务分别匹配，不能把分类/编码模型列为聊天模型 |
| MediaPipe LLM | Google 官方旧模型包路径 | 仅配旧 adapter；推荐新安装使用 LiteRT-LM，不能自动跨格式替换 |
| Ollama backend | 真实 Ollama 服务自己的注册表/受支持导入流程 | 不向其私有 blob 目录直接写 HF 文件；外部服务的 pull 由其所有者管理。托管版管理 adapter 后续可委托 pull 并核对 digest；HTTP 推理端仍不接受任意拉取命令 |

官方直链、对象存储与 GitHub Release 也可作为来源，但要锁定发布版本、精确文件及校验和；网页“下载按钮”不是永久文件身份。没有查证的镜像站不预置为可信默认源；允许用户添加 HF-compatible endpoint，经验证后作为用户源展示，而不冠以官方名义。

## 3. 目录、推荐与安装资格

分三层，不直接把全网搜索结果当作可安装列表：

1. **ModelCatalog**：应用内置或签名更新的精选 artifact 目录，含引擎配方、来源身份与哈希；能离线浏览上次目录。目录更新锁定 schema/version、签名和过期策略，禁止执行目录中的脚本。
2. **HubDiscovery**：通过 [HF Hub API](https://huggingface.co/docs/hub/api)搜索仓库和获取元数据；标签、library_name、文件名只用来发现候选。搜索不携带会话历史，目录元数据未验证不得作为指令执行。
3. **ArtifactResolver**：选择并锁定一个适配变体，展开完整依赖文件集合，生成 `InstallPlan`。无合格 recipe/完整性依据的搜索结果只能显示“待适配”，不开放一键推荐安装。

硬门槛先过滤：backend 已打包、runtime ABI/API/版本、模型架构与量化支持、必需模型库或 delegate、任务/工具/图片能力、许可证与访问资格、磁盘和目标上下文下的内存。之后按本机已验证记录、任务/中文适用性、资源开销与来源可信度排序。下载量或参数规模不能代替适配证据。

推荐结果给出 `VERIFIED_ON_DEVICE / COMPATIBLE_UNVERIFIED / NEEDS_EXPORT / INCOMPATIBLE / ACCESS_REQUIRED` 及原因、测试设备和数据日期。资源数据未知不编造内存值；可展示候选与“待验证”，不能声称流畅。用户修改上下文或引擎后重新评估，不静默改小上下文以凑内存。

## 4. 身份与数据结构

模型内容身份和网络来源分开。`ModelRef.revision` 仍是规范化内容 manifest 的 SHA-256；包含固定文件集合、兼容参数及原始模型版本。可变镜像列表、临时 URL、健康状态和访问 token 不参与内容 revision，换等价源不会产生新模型版本。

| 对象 | 核心字段 |
| --- | --- |
| `ArtifactRecipe` | recipeId/version、backendId/buildRange、format、architecture、quantization、variant、必需文件及角色、设备/任务要求 |
| `SourceDescriptor` | sourceId、HF/ENGINE_OFFICIAL/MODELSCOPE/HF_MIRROR/DIRECT 类型、base origin、发布者与官方证据、允许重定向目标策略、credentialRef、trusted/user 标记 |
| `ArtifactOrigin` | sourceId、repoId、解析后的不可变 revision、subfolder/明确文件映射、上游模型身份；不同平台的 revision 不要求字符串相同 |
| `ResolvedFile` | 规范化相对路径、实际长度、expectedSha256、哈希依据、候选下载 locator；分片索引/依赖角色 |
| `SourcePolicy` | 有序 allowedSourceIds、PINNED/AUTO_VERIFIED_EQUIVALENT、Wi-Fi/流量条件、限速与并发预算 |
| `InstallPlan` | artifact manifest digest、recipe version、backend/device 评估快照、完整文件表、下载/峰值磁盘字节、来源顺序、许可状态、expiresAt |
| `DownloadCheckpoint` | installId、文件身份、sourceId、已验证块/字节范围、对象验证器、临时文件；不保存签名 URL 或 token |

文件选择先解析 branch/tag 为完整 commit，整个计划固定该版本，不逐文件访问 main。精确指定某一变体的文件及依赖闭包，不能简单下载仓库全部内容。HF 支持指定 revision 和文件过滤，参见[下载文档](https://huggingface.co/docs/huggingface_hub/guides/download)；Android 实现使用网络 API，不要求安装 Python、Git LFS 或 HF CLI。

完整性依据优先用审核目录中的 SHA-256 或已认证上游的内容摘要。HF 文件 metadata/ETag 需按存储类型解释，Git blob ID、LFS SHA-256 与其他存储标识不能互相冒充；本地重新计算 SHA-256 只能证明字节身份，不能独自证明来源可信。[HF 文件下载元数据](https://huggingface.co/docs/huggingface_hub/package_reference/file_download)

推荐安装计划必须已有可信的逐文件 SHA-256。上游小配置文件只有其他对象标识时，可先通过可信源读取受限大小元数据，按上游对象格式验证再计算 SHA-256；大文件缺少可信摘要则等待目录审核补齐，不为了“生成计划”后台下载全部权重。用户自行导入仍可计算自有 manifest，但应标为来源未验证，不能自动加入可信镜像组。

## 5. 下载、重定向与换源

流程为 `RESOLVING → PLANNED → QUEUED → DOWNLOADING → VERIFYING → INSTALLED`；可暂停于 `PAUSED_NETWORK / PAUSED_USER / ACCESS_REQUIRED`，或结束为 FAILED/CANCELLED。元数据解析不等于接受下载；用户启动 install 后才拉取权重。暂停可恢复原 installId，取消为终态，再次安装用新操作并复用已校验缓存。

下载器位于 `:local_model`，使用 Android HTTP 栈，与推理 API 的 server/Agent Node 桥接分离。所有厂商 SDK 的自动下载须关闭或接入同一受控下载器，不能绕过来源策略。

- HF 文件从固定 repo/revision 的 resolve 路径获取；正常下载可能跳转 CDN 或短期签名地址。允许来源专属、有限次数的 HTTPS 重定向，逐跳验证域名/目标地址，不盲目全局禁止重定向，也不放开任意跳转。跨 origin 默认去除 Authorization/Cookie，不把 HF token 转发给镜像或 CDN；签名 URL 只在内存使用。拒绝降级 HTTP、环回/私网目标和重定向循环。另行配置的企业内网源须显式独立授权，不能由公网上游重定向获得。
- 令牌只发送给所属 credential origin，HF 私有/gated 模型通过原服务确认访问权限；401/403、许可未接受不触发第三方镜像绕过。访问资格和许可证单独展示，参见 [HF gated models](https://huggingface.co/docs/hub/models-gated)。
- 续传核对 expected hash/总长、对象验证器和 Content-Range。服务器返回 200 而非 206 时重建该文件，不将完整响应追加到分片；416 先核对本地长度并做完整哈希，失败则重下。
- 超时/可恢复连接错误/部分 5xx 使用有界重试（建议每源最多 3 次，指数退避）；429 尊重 Retry-After，不立即切镜像规避限额。网络条件不满足则暂停。磁盘不足、取消、认证失败和摘要不符不作普通网络重试。
- AUTO 模式只切到同计划中逐文件 expected hash 与长度匹配的备选。没有可信分块哈希时，跨源切换重新下载当前未完成文件；已有完整且验证通过的文件继续复用。有经验证的分块摘要时才复用部分块。每个文件完成后仍做全量 SHA-256。
- 哈希不符隔离文件并标记该源异常，当前安装失败并允许用户明确重试其他已验证源；不能更改 expected hash 让校验“通过”。同名但内容不同的官方备用包是另一 artifact，须重新生成计划，不能接着旧分片下载。
- 不将 LFS 指针、登录 HTML、错误 JSON 或未完整分片发布为权重。长度、哈希、容器基本结构、依赖闭包全部通过，才原子发布 manifest；状态为 INSTALLED，加载/生成验收仍是后续步骤。

断网或服务被杀保留 checkpoint 和 staging；重启后标记暂停，用户恢复时重新解析短期下载地址并确认固定版本，不自动重放推理。下载进度区分网络传输字节与完整性已确认字节，未知长度显示不定进度。

后台大文件下载需要单独的 Android 下载生命周期设计，不沿用推理 specialUse 服务作为无限下载许可。首版可只保证前台下载与暂停续传；后台能力另行评估适用的用户发起数据传输/前台 dataSync 机制与系统限制，并保持单一写入者。下载与推理共享资源预算，默认最多 2 个文件传输，校验/解压限速以避免内存和热冲突。

## 6. Kotlin 与 HTTP 扩展

不增加第二个安装器。`ModelCatalogClient`、`ArtifactResolver` 接到已有 `LocalModelManager.inspect/install`；源配置、凭据、目录缓存和下载操作均由独立进程唯一写入。

```kotlin
interface ModelCatalogClient {
    suspend fun recommend(query: RecommendationQuery): LmResult<CandidatePage>
    suspend fun search(query: HubSearchQuery): LmResult<CandidatePage>
    suspend fun resolve(request: ResolveArtifactRequest): LmResult<InstallPlan>
    suspend fun sources(): LmResult<List<SourceSummary>>
}
```

`RecommendationQuery` 包含 backendId 或 AUTO、任务/profile、上下文、资源预算；设备事实由服务端采集，不信任 HTTP 客户端伪造。`ResolveArtifactRequest` 引用受控 candidateRef、variantId、sourcePolicyId；结果复用已有 InstallPlan。模型凭据通过 Binder 的秘密输入或单独受限管理入口保存；不放查询参数、普通 DTO 或响应正文。

| HTTP 管理路由 | 契约 |
| --- | --- |
| `GET /local/v1/catalog/models?backend=...&task=...` | 按本机能力过滤的分页推荐，返回匹配原因、artifact 变体与可用来源；支持离线旧目录并标日期 |
| `POST /local/v1/catalog/search` | `{providerId,query,cursor}`，有界 HF/官方源发现；结果标注 reviewed/unverified |
| `GET /local/v1/sources`、`PUT /local/v1/sources/{id}` | 获取/配置允许的来源与优先级；只有管理 scope 可访问；凭据只显示 hasCredential |
| `POST /local/v1/install-plans` | 原有 `{sourceRef}` 保留；新增受控 sourceRef 可指已解析 candidate+variant+policy，不接受请求直接携带任意文件下载 URL |
| `POST /local/v1/installs` | `{planId}`，重新校验磁盘、访问权限、来源政策与计划有效期后返回 202 |
| `POST /local/v1/operations/{id}/pause`、`.../resume` | 仅下载类操作，异步回执；恢复固定版本和源策略，政策被撤销则不恢复 |
| `GET /local/v1/operations/{id}` | 增 currentSource、resolvedRevision、bytesTransferred/Verified、pauseReason、sourceSwitchHistory（只含源 ID/脱敏原因） |

新增错误：`NO_COMPATIBLE_ARTIFACT`、`BACKEND_NOT_PACKAGED`、`MODEL_LIBRARY_MISSING`、`SOURCE_UNAVAILABLE`、`SOURCE_POLICY_CHANGED`、`REVISION_UNAVAILABLE`、`ACCESS_REQUIRED`、`INTEGRITY_METADATA_MISSING`、`SOURCE_CONTENT_MISMATCH`。允许用户切源不代表允许改变模型格式、许可或版本。

## 7. UI 与验收

模型卡展示“适合哪个引擎/版本、所需内存与上下文、量化、来源、是否通过真机测试”。下载详情显示 HF 仓库与固定 revision、文件数量、总体积/新增下载量、许可入口、首选/备用源。提供暂停、继续、取消、切换到已验证备选源；切到不同 artifact 时重建计划并显示变化。模型更新由用户发起，不在运行中的 model alias 后悄悄替换文件。

最低验收用例：

- 相同模型仓库有 GGUF/ONNX/原始权重时只选目标引擎支持的完整变体；MNN/LiteRT/MLC/ExecuTorch 缺依赖或编译库时拒绝计划。
- main 在下载中变化不影响固定 commit；分片缺失、量化选错、tokenizer 或外部数据路径逃逸被拒绝。
- HF 正常 CDN 重定向可下载，跨域不泄漏 token；过期签名链接可重新解析而不改变 revision；401/403 不切公共镜像绕过资格。
- 原源中断切镜像：完整文件复用、未验证分片重下；同名不同字节拒绝；Range 200/206/416、429、断网、磁盘满、校验失败如实报告。
- 下载时进程被杀后可恢复、取消后不自动重启、热压力下不与推理争抢资源；checkpoint 无密钥/签名 URL。
- 先用小型虚假文件与测试服务器验证传输协议，再在已连接手机上下载一个真实受支持模型，并断网加载/生成；下载完成不能代替引擎和 Agent 验收。

本次只完善设计与来源依据，没有下载权重、配置真实 token、启用镜像或修改生产 Gradle 模块。首个实现阶段先交付 HF provider + 精选 recipe + 官方备用源；镜像 provider 复用同一完整性与策略接口，不另建绕过验证的下载路径。
