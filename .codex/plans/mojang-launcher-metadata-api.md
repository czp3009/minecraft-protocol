# Distribution Metadata 公共库实施计划

- 状态：metadata API、流式下载与两个 demo 的迁移已完成；JVM、JS、WasmJS、适用 MinGW 测试与 Android 编译通过，demo configuration
  cache 已验证 store/reuse
- 记录日期：2026-09-01
- 最后修订：2026-09-05
- 适用版本：仓库所选择的 Minecraft 官方版本；本计划不改变版本选择
- 模块：`:distribution-metadata`
- 公共包名：`com.hiczp.minecraft.distribution.metadata`
- 已迁移调用方：`:demo:launcher` 与 `:demo:web-map` 的 `serverMain`
- 计划文件名：为保留现有引用暂不改名；模块、类型和公开文档不再使用 `launcher-metadata` 旧名称

## 1. 最终设计

1. 模块名使用 `distribution-metadata`。启动器只是消费者之一，Mojang 的 Piston 内部代号也不作为领域模块名。
2. 只支持 Version Manifest V2 和当前 Java 版分发链使用的现代 schema，不提供旧 schema 兼容层。
3. 覆盖现代 Piston Meta reference graph 中的全部 metadata 文档：Version Manifest V2、version metadata、asset index、当前
   Java runtime catalog 和 runtime file manifest。
4. 模块是薄 HTTP API。公开 `MinecraftDistributionMetadataApi` 是 Ktorfit interface，metadata 返回 typed wire model，下载返回
   `HttpStatement`；
   `MinecraftDistributionMetadataApiClient` 用 Kotlin `by` 委托给 generated implementation。
5. caller 提供并关闭 `HttpClient`，typed metadata 调用需要安装 JSON `ContentNegotiation`，流式下载不需要。模块不创建
   engine、不安装插件、不关闭 client，也不添加
   retry、cache、offline 或 response-validation policy。
6. 模块不验证 metadata 的 SHA-1、size、URL host、version ID 或其他跨文档关系，不提供 metadata byte limit，也不定义模块专用异常。
7. HTTP framing/engine 负责报告截断的 response body，JSON converter 负责报告不完整或 malformed document。metadata 调用不再重复做
   SHA-1/size 完整性校验。
8. `MinecraftLibraryDownload`、`MinecraftLoggingFile` 和 `MinecraftAssetObject` 提供 `toDownload()`；前两者原样投影下载字段，asset
   object 根据 wire hash 派生资源 URL。纯函数 `minecraftAssetPath(hash)` 统一资源相对路径计算，所有转换均不校验
   hash/size，也不下载资源。
9. 模块提供 `download(url): HttpStatement`，以及 `downloadAsset(hash)`、`download(MinecraftDownload)`、
   `download(MinecraftAssetObject)` 扩展；准备请求不发起 I/O，每次执行发送一个 GET。
10. 两个 demo 使用公共 metadata API、wire models 和流式下载，不添加或保留 metadata 文档的 SHA-1/size/ID
    校验；流的消费、落盘、进度与二进制制品校验策略由 demo 持有。

## 2. Version Manifest V1 与 V2 调研记录

2026-09-01 对两个官方 endpoint 的直接核对结果：

| 项目                                            | V1                               | V2                                  |
|-------------------------------------------------|----------------------------------|-------------------------------------|
| 路径                                            | `/mc/game/version_manifest.json` | `/mc/game/version_manifest_v2.json` |
| 顶层 `latest`                                   | 有                               | 有                                  |
| 顶层 `versions`                                 | 有                               | 有                                  |
| version entry 的 `id/type/url/time/releaseTime` | 有                               | 有                                  |
| version entry 的 `sha1`                         | 无                               | 有，required                        |
| version entry 的 `complianceLevel`              | 无                               | 有，required                        |

当前响应中，移除 V2 entry 的 `sha1` 和 `complianceLevel` 后，两者的 `latest`、版本顺序和其他字段相同。这只是当时的服务响应
事实，不视为永久服务保证。

本仓库现有 consumer 已使用 V2，项目又面向新版，因此公共 API 只提供 V2。类型使用
`MinecraftVersionManifest`/`MinecraftVersionReference`，不加冗余的 `V2` 后缀。

Piston Meta 的 `/v1/products` 和 `/v1/packages` 是 route namespace，不代表 Version Manifest V1；支持这些现代引用路径与只支持
Manifest V2 不冲突。

## 3. Piston Meta 范围

### 3.1 支持的 HTTP graph

| 文档                  | 当前 URI 形状                                                                     | URL 来源                                | 公共 operation             |
|-----------------------|-----------------------------------------------------------------------------------|-----------------------------------------|----------------------------|
| Version Manifest V2   | `GET /mc/game/version_manifest_v2.json`                                           | 固定                                    | `versionManifest()`        |
| Version metadata      | 通常为 `GET /v1/packages/{sha1}/{fileName}`                                       | manifest entry 的绝对 `url`             | `versionMetadata(url)`     |
| Asset index           | 当前为 `GET /v1/packages/{sha1}/{fileName}`                                       | version metadata 的 `assetIndex.url`    | `assetIndex(url)`          |
| Java runtime catalog  | `GET /v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json` | 固定                                    | `javaRuntimeCatalog()`     |
| Runtime file manifest | 当前为 `GET /v1/packages/{sha1}/{fileName}`                                       | runtime catalog entry 的 `manifest.url` | `javaRuntimeManifest(url)` |

每个 metadata operation 恰好发起一个 GET。caller 显式组合 reference graph，不提供隐式多请求的 `resolveEverything`。

### 3.2 “全部”的边界

Piston Meta 没有可枚举所有 product/catalog ID 的公开 root index。当前公开证据能稳定定义的是上表中的现代 Java 分发
graph，因此本模块 不包装旧 launcher、Dungeons 或任意未知 product ID，也不提供 generic product discovery API。

### 3.3 不支持的内容

- 历史 version document schema，例如 `minecraftArguments`；
- 旧 asset layout 和兼容字段；
- launcher/Dungeons product metadata；
- launcher content、新闻或 patch notes；
- 任意 product/catalog ID 的发现与访问。

Version Manifest V2 仍会列出历史版本。这不代表所有历史 version document 都在现代 schema 合同内；caller 选择不兼容 entry
时，让 JSON/schema failure 原样传播。

## 4. 下载地址与模块边界

Piston Meta 返回的 metadata 会引用其他下载 host：

| 下载内容                                               | host                                                  | metadata 地址形式             | 本模块行为               |
|--------------------------------------------------------|-------------------------------------------------------|-------------------------------|--------------------------|
| client/server、mapping、logging、library、runtime file | `piston-data.mojang.com` 或 `libraries.minecraft.net` | 完整 URL                      | 原样公开 wire descriptor |
| asset object                                           | `resources.download.minecraft.net`                    | asset index 只有 hash 与 size | 派生 download descriptor |

asset object 地址规则：

```text
https://resources.download.minecraft.net/{hash 的前两个字符}/{完整 hash}
```

`minecraftAssetPath(hash)` 返回小写的 `{hash 的前两个字符}/{完整 hash}`。`MinecraftAssetObject.toDownload()` 的 URL
构造使用同一个函数，并通过 Ktor URL builder 拼接固定 HTTPS host。demo 在这个相对路径前添加自己的安装目录；纯函数不选择目录、不检查
hash 长度或字符集，也不进行 I/O。

`MinecraftLibraryDownload.toDownload()` 与 `MinecraftLoggingFile.toDownload()` 原样保留 `sha1`、`size` 和 `url`；`path` 与
`id` 留在原始 wire descriptor，供应用选择安装位置。三种 `toDownload()` 集中在 `MinecraftDownload.kt`，不增加 size 或完整性校验。

asset-index JSON 文档本身由 version metadata 中的完整 `assetIndex.url` 引用，通过 `assetIndex(url)` 解码；hash 下载针对索引中的单个
asset object。

`download(url)` 返回 streaming `HttpStatement`，不解释下载内容。hash 与描述符扩展都委托给这个唯一下载入口。
`downloadAsset(hash)` 与 `toDownload()` 共用 URL 派生函数，不为只有 hash 的调用伪造 size。流必须在 `execute { ... }`
作用域内消费，结束、失败或取消时由 Ktor 释放响应。

## 5. 模块职责与依赖

`distribution-metadata` 负责：

- fixed-root 和 caller-supplied URL 的一请求 HTTP operation；
- 延迟执行的流式下载请求，以及 asset hash 和 download descriptor 扩展；
- 当前 wire DTO 与 kotlinx.serialization serializer；
- library/logging download descriptor 投影，以及 asset object 的相对路径与 URL 派生；
- borrowed `HttpClient` 与 Ktorfit generation。

它不负责：

- metadata reference 的 hash、size、host 或 identity 校验；
- 检查、缓存或保存 binary/text resource 的内容；
- filesystem、temporary file、atomic move、extraction 或 symbolic-link materialization；
- selection、installation、launch、retry、cache、resume、progress、offline 或 UI policy。

模块 targets 与 `account-auth` 当前实际 build script 对齐，包括配置过的 JVM、Native、Android、JS 和 WasmJS targets。

`commonMain` dependencies：

- `api(libs.ktor.client.core)`：公共构造函数接收 `HttpClient`，下载接口返回 `HttpStatement`；
- `api(libs.ktor.client.content.negotiation)` 与 `api(libs.ktor.serialization.kotlinx.json)`：caller 配置 typed Ktorfit
  response 所需 JSON converter，这属于公开调用合同；
- `api(libs.kotlinx.serialization.core)`：公开 wire types 带 serializer；
- `api(libs.kotlinx.serialization.json)`：公开 JSON discriminator annotation、caller JSON 配置与无法用注解表达的 untagged
  argument union；
- `api(libs.ktorfit.lib.light)`：公开 annotated API interface 的 ABI 与 generated transport。

不添加 HTTP engine 或 hash library dependency。Ktorfit/KSP plugins 只应用于拥有 annotated interface 的模块，consumer
不应用这些插件。

## 6. HTTP API

### 6.1 Ktorfit interface

```kotlin
interface MinecraftDistributionMetadataApi {
    @GET("mc/game/version_manifest_v2.json")
    suspend fun versionManifest(): MinecraftVersionManifest

    @GET("")
    suspend fun versionMetadata(@Url url: String): MinecraftVersionMetadata

    @GET("")
    suspend fun assetIndex(@Url url: String): MinecraftAssetIndex

    @GET("v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json")
    suspend fun javaRuntimeCatalog(): MinecraftJavaRuntimeCatalog

    @GET("")
    suspend fun javaRuntimeManifest(@Url url: String): MinecraftJavaRuntimeManifest

    @Streaming
    @GET("")
    suspend fun download(@Url url: String): HttpStatement
}
```

metadata 返回值直接交给 caller 的 Ktor `ContentNegotiation` 解码；`download(url)` 只准备 `HttpStatement`，不读取 body，也不要求
JSON converter。

### 6.2 Public facade

```kotlin
class MinecraftDistributionMetadataApiClient(
    httpClient: HttpClient,
    pistonMetaBaseUrl: String = PISTON_META_BASE_URL,
) : MinecraftDistributionMetadataApi by createMinecraftDistributionMetadataApi(httpClient, pistonMetaBaseUrl)
```

client 不手写转发方法，直接委托 generated Ktorfit implementation。动态方法直接接收 URL，不接收整个 reference DTO，也不做隐式
validation 或 normalization。`createMinecraftDistributionMetadataApi` helper 与 `PISTON_META_BASE_URL` 都放在 client
源文件中并设为
`private`，不污染 interface 文件或模块 API。公开 constructor 的 `pistonMetaBaseUrl` 可覆盖两个 fixed-root operations 的
base URL，默认值 是该私有常量；绝对 reference URL 不受 override 影响。

### 6.3 下载扩展

扩展以 `MinecraftDistributionMetadataApi` 为 receiver，放在独立 source file，返回 `HttpStatement`：

- `downloadAsset(hash: String)`：派生 resources host URL 后调用 `download(url)`；
- `download(minecraftDownload: MinecraftDownload)`：只使用 descriptor 的 `url`；
- `download(minecraftAssetObject: MinecraftAssetObject)`：直接委托 `downloadAsset(minecraftAssetObject.hash)`，不创建只用于取得
  URL 的中间描述符。

扩展不检查 hash 格式、SHA-1、size 或 body。调用方在 `execute { httpResponse -> ... }` 中通过 `bodyAsChannel()`
消费流，并自行选择进度、缓冲和持久化策略；无 block 的 `execute()` 会缓冲响应。

## 7. Wire models

### 7.1 Version Manifest V2

- `MinecraftVersionManifest(latest, versions)`；
- `MinecraftLatestVersions(release, snapshot)`；
- `MinecraftVersionReference(id, type, url, time, releaseTime, sha1, complianceLevel)`。

`sha1` 和 `complianceLevel` 保持 required。version type 保留 wire string，未知官方值不应使整份 manifest 因本地 enum 封闭而失败。

### 7.2 Version metadata

覆盖现代 schema 中的 arguments、asset index、downloads、Java version、libraries、rules、logging、main class、minimum launcher
version、 version identity 和 timestamps。不实现 `minecraftArguments` fallback。

argument serializer 保留 literal string 与 conditional object 两种形状，包括 string/list value 和 `default-user-jvm`。

### 7.3 Asset index

- `MinecraftAssetIndex(objects)`；
- map key 为 logical resource path；
- value 为 `MinecraftAssetObject(hash, size)`。

asset URL 是派生值，不伪装成 wire field；serialization round trip 保持官方 JSON shape。

### 7.4 Java runtime

catalog 使用可透明序列化其底层 map 的 annotated value class，保留动态 platform/component maps 和有序 version
entries。entry 的 `manifest` 直接复用 `MinecraftDownload`，因为它同样只有 `sha1`、`size` 和 `url`，不保留重复的专用
reference DTO。runtime manifest 使用 sealed interface、`@SerialName` 和 `@JsonClassDiscriminator("type")` 表达 `file`、
`directory`、`link`，并保留 raw/lzma downloads、executable flag 和 link target。模块不据此创建文件、解压或设置权限。

### 7.5 JSON 配置

typed metadata caller 安装 kotlinx.serialization JSON converter，并建议 `ignoreUnknownKeys = true` 以容忍官方增加字段。现代
required
fields 仍保持 required； 优先使用 generated annotation-driven serializer。仅 argument 的 string-or-object 以及其 value 的
string-or-array 属于无 discriminator 的 JSON union，通过两个 internal `JsonContentPolymorphicSerializer` 选择实际
subtype，保留 primitive 字符串类型检查；公共库负责通用编解码流程，具体 subtype 使用 annotation-generated serializers。其他
model 不手写 serializer。

## 8. HTTP 与失败语义

- dynamic URL 原样交给 Ktorfit/Ktor，不限制 scheme、host、port、user-info 或 fragment；
- 模块不读取原始 bytes，不计算 SHA-1，不比较 declared size，也不检查 version ID；
- 模块不覆盖 caller 的 `expectSuccess`；non-2xx 行为由 caller 的 Ktor client 配置决定；
- transport、TLS、response validation、ContentNegotiation、serialization 和 cancellation failure 原样传播；
- 不捕获并包装成 module-specific exception；
- 截断 body 由 HTTP engine/body reader 报告，无法构成完整 JSON 时 serializer 也会失败；demo 无需为 metadata 再实现一层
  hash/size 完整性校验。

## 9. Demo 迁移

两个 demo 已按以下边界迁移。launcher 的下载计划直接持有公共 `MinecraftDownload`，asset index 通过 typed API
读取后原子保存；web-map 的 JVM/Native 共用 `serverMain` HTTP 配置，engine 由对应 target 的依赖提供。

### 9.1 `demo/launcher`

- `commonMain` 增加 `implementation(project(":distribution-metadata"))`；
- 使用现有 shared `HttpClient` 构造 `MinecraftDistributionMetadataApiClient`；
- 保留 JSON `ContentNegotiation`，因为 typed Ktorfit response 依赖 caller converter；
- 删除 demo-local Mojang metadata Ktorfit interface 和重复 wire DTO；
- 使用 `versionReference.url`、`assetIndex.url` 和 runtime `manifest.url` 调用动态 operation；
- 修正当前只展开 `arguments.jvm`、忽略 `arguments.defaultUserJvm` 的行为。`jvm` 是始终使用的版本级 JVM 参数；
  `default-user-jvm` 是未配置用户自定义 JVM 参数时使用的默认用户参数，当前包含 heap 与 GC defaults。demo 目前没有自定义
  JVM 参数入口，因此先按相同 rules 展开 `defaultUserJvm`，再展开 `jvm`；以后若增加自定义 JVM 参数，只用自定义值替换
  `defaultUserJvm`，不得替换或省略 `jvm`；
- 不校验 metadata document 的 SHA-1、size 或跨文档 ID，不增加 metadata byte limit；
- 使用现代 metadata 必填的 Java major，`LaunchPlan.requiredJavaMajor` 为非空 `Int`，启动前始终检查可用 Java major；
- 保留 launcher-owned selection、planning、filesystem、process 和 binary downloader policy；
- `ResourceDownloader` 通过公共 `download(MinecraftDownload)` 获取流，在 `execute` 中写入临时文件，随后验证大小与 SHA-1
  并原子发布；
- library、logging 和 asset object 通过公共 `toDownload()` 得到下载描述符；asset 路径使用公共 `minecraftAssetPath(hash)`
  ，demo 只添加 `assets/objects/` 前缀。

demo-local metadata interface、重复 wire DTO、自定义 argument serializer 和 Ktorfit/KSP wiring 已移除；保留 typed response
使用的 Ktor ContentNegotiation。

### 9.2 `demo/web-map`

- 只在 `serverMain` 增加新模块依赖；`jsMain` 不增加 metadata dependency；
- 在已有 caller-owned `HttpClient` 上安装 JSON `ContentNegotiation`，使用
  `Json { ignoreUnknownKeys = true }`，再用它构造 `MinecraftDistributionMetadataApiClient` 并调用 manifest/version
  metadata；JVM 与 Native 的 engine-specific `HttpClient` 构造路径都必须应用同一配置；
- 删除 private Piston DTO 和 metadata-only hash/size/ID 校验；
- client JAR 通过公共 `download(MinecraftDownload)` 获取响应流；`OfficialAssetRepository` 在 `execute`
  中读取并更新进度，继续拥有内存缓冲、temporary publication、ZIP/resource processing 和 downloader integrity policy。

这里“不校验”的决定只针对 metadata HTTP document；是否验证落盘二进制制品由各 downloader 自己决定。

## 10. Repository wiring 与文档

- `settings.gradle.kts` 注册 `:distribution-metadata`；
- root README/AGENTS module table 将其描述为现代 Mojang metadata 与 streaming downloads；
- module README 只记录最终公共合同：V2/current schema、caller-owned configured client、operations、streaming response
  生命周期、asset URL derivation 和无
  validation 语义；
- V1/V2 讨论与方案演变留在本计划，不写入 module README；
- module AGENTS 只记录 local ownership、typed Ktorfit、caller ContentNegotiation、无 semantic validation 和验证命令。

## 11. 测试矩阵

模块 common tests 覆盖：

- V2 fixed path、runtime fixed path、GET、Accept header 和一次请求；
- 可配置 Piston Meta base URL 对两个 fixed-root operations 的作用；
- caller-installed JSON ContentNegotiation；
- dynamic URL 原样转发，不做 metadata host/identity validation；
- caller `expectSuccess` 行为与 cancellation 原样传播；
- operation 后 caller client 仍可使用；
- download statement 延迟执行、无需 JSON converter、binary body 可在传输完成前消费，以及 caller request 配置的保留；
- 下载扩展的 URL 派生，以及 descriptor SHA-1/size 不参与下载、不触发校验的语义；
- library/logging 的 `toDownload()` 可直接供流式下载使用，asset 相对路径与下载 URL 共享派生逻辑；
- 下载提前结束和 consumer cancellation 的响应释放，caller `expectSuccess` 与无隐式 retry；
- manifest required fields 和现代 version models；
- arguments 的 modern wire variants、完整 JSON 往返与非法 union 形状的拒绝；
- asset-index objects 与无 validation 的 resource URL derivation；
- runtime dynamic platform/component maps；
- runtime file/directory/link、raw/lzma 与 encode/decode symmetry；
- unknown optional fields、missing required fields 和 unknown required discriminator；
- 全部自动化 HTTP tests 使用 MockEngine，不访问 live Mojang service。

不测试 metadata SHA-1、size、byte limit、URL allowlist 或 module-specific exception，因为这些能力不存在。

## 12. 验证命令

所有 Gradle invocation 顺序执行。Windows 使用 platform-native wrapper：

```shell
.\gradlew.bat :distribution-metadata:jvmTest
.\gradlew.bat :distribution-metadata:jsNodeTest
.\gradlew.bat :distribution-metadata:wasmJsNodeTest
.\gradlew.bat :distribution-metadata:mingwX64Test
.\gradlew.bat :distribution-metadata:compileAndroidMain
```

Demo 迁移已通过以下检查，JVM invocation 重复执行以验证 configuration-cache store/reuse：

```shell
.\gradlew.bat :demo:launcher:jvmTest :demo:web-map:jvmTest --max-workers=2
.\gradlew.bat :demo:launcher:mingwX64Test :demo:web-map:compileKotlinMingwX64 :demo:web-map:jsNodeTest --max-workers=2
.\gradlew.bat :demo:web-map:dependencyInsight --dependency distribution-metadata --configuration jsCompileClasspath
```

web-map 的 JS compile classpath 没有 `distribution-metadata`。launcher 测试覆盖默认用户 JVM 参数与版本 JVM 参数的顺序、asset
index 保存及安装校验；web-map 测试通过 MockEngine 和内存 ZIP 覆盖 metadata 到资源的完整加载与损坏 binary 的拒绝。

流式下载新增 5 个 common tests，已在 JVM、JS、WasmJS 与 MinGW 上通过；两个 demo 在接入公共下载 API 后也已通过上述 JVM、MinGW
与 JS 检查，Android main 编译通过。

## 13. 完成标准

1. `:distribution-metadata` 是带 README、AGENTS、实际支持 targets 和公共 Kotlin API 的独立 runtime module。
2. 模块只存在 V2 manifest API/model，并覆盖现代 version metadata、asset index、Java runtime catalog 和 runtime manifest。
3. 模块公开 annotated Ktorfit API interface，并拥有 generated implementation；consumer 不应用 Ktorfit/KSP Gradle plugin。
4. API 借用 caller-configured `HttpClient`，每次 metadata operation 或 download statement 执行一个 GET，不创建
   engine、不安装插件、不关闭 client。
5. metadata response 通过 caller ContentNegotiation 直接反序列化，不存在 metadata hash/size/host/identity validation、byte
   limit
   或专用异常。
6. 模块通过 URL 提供流式下载，以及 asset hash、download descriptor 和 asset object 扩展；asset URL 共用确定性派生逻辑，不校验
   descriptor 或下载内容。
7. JVM、JS、WasmJS、适用 Native 和 Android compile checks 通过；自动化测试无 live network。
8. 两个 demo 直接使用新模块，重复 metadata DTO、HTTP API、下载请求和不再需要的 Gradle plugins 已删除；应用持有流的消费、binary
   校验与文件处理。

## 14. 不随本计划实现

- 历史 manifest/version schema compatibility；
- general-purpose launcher、installer、runtime installer 或 process manager；
- binary integrity verification 与 filesystem materialization；
- metadata 或 binary retry/cache/resume/rate-limit/offline policy；
- metadata SHA-1/size verification、URL allowlist、byte limit 或 cross-document identity validation；
- 任意 product/catalog ID 发现；
- authentication API 重构。
