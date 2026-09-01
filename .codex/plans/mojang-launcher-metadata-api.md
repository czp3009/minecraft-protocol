# Distribution Metadata 公共库实施计划

- 状态：`:distribution-metadata` 已实施并通过 JVM、JS、WasmJS、MinGW 与 Android 验证；demo 迁移按要求暂缓
- 记录日期：2026-09-01
- 最后修订：2026-09-02
- 适用版本：仓库所选择的 Minecraft 官方版本；本计划不改变版本选择
- 模块：`:distribution-metadata`
- 公共包名：`com.hiczp.minecraft.distribution.metadata`
- 后续迁移调用方：`:demo:launcher` 与 `:demo:web-map` 的 `serverMain`
- 计划文件名：为保留现有引用暂不改名；模块、类型和公开文档不再使用 `launcher-metadata` 旧名称

## 1. 最终设计

1. 模块名使用 `distribution-metadata`。启动器只是消费者之一，Mojang 的 Piston 内部代号也不作为领域模块名。
2. 只支持 Version Manifest V2 和当前 Java 版分发链使用的现代 schema，不提供旧 schema 兼容层。
3. 覆盖现代 Piston Meta reference graph 中的全部 metadata 文档：Version Manifest V2、version metadata、asset index、当前
   Java runtime catalog 和 runtime file manifest。
4. 模块是薄 HTTP API。公开 `MinecraftDistributionMetadataApi` 是 Ktorfit interface，直接返回 typed wire model；
   `MinecraftDistributionMetadataApiClient` 用 Kotlin `by` 委托给 generated implementation。
5. caller 提供并关闭 `HttpClient`，同时安装 JSON `ContentNegotiation`。模块不创建 engine、不安装插件、不关闭 client，也不添加
   retry、cache、offline 或 response-validation policy。
6. 模块不验证 metadata 的 SHA-1、size、URL host、version ID 或其他跨文档关系，不提供 metadata byte limit，也不定义模块专用异常。
7. HTTP framing/engine 负责报告截断的 response body，JSON converter 负责报告不完整或 malformed document。metadata 调用不再重复做
   SHA-1/size 完整性校验。
8. `MinecraftAssetObject.toDownload()` 只根据 wire hash 派生资源 URL；它不校验 hash/size，也不下载资源。
9. client/server JAR、library、asset、logging 和 runtime binary 的 streaming、落盘与可选制品校验仍属于 downloader，不属于本模块。
10. 本轮不修改 demo。未来迁移 demo 时，不添加或保留 metadata 文档的 SHA-1/size/ID 校验；二进制制品下载策略单独处理。

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

每个 public operation 恰好发起一个 GET。caller 显式组合 reference graph，不提供隐式多请求的 `resolveEverything`。

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

`MinecraftAssetObject.toDownload()` 使用 Ktor URL builder 拼接固定 HTTPS host 和 path segments，并把 hash 规范为小写。它不检查
hash 长度、字符集或 size，也不进行 I/O。

若以后建立独立 binary download transport，streaming `HttpStatement` 应属于该 downloader。metadata JSON operation 没有返回
`HttpStatement` 的必要。

## 5. 模块职责与依赖

`distribution-metadata` 负责：

- fixed-root 和 caller-supplied URL 的一请求 HTTP operation；
- 当前 wire DTO 与 kotlinx.serialization serializer；
- asset object 到 download descriptor 的确定性 URL 转换；
- borrowed `HttpClient` 与 Ktorfit generation。

它不负责：

- metadata reference 的 hash、size、host 或 identity 校验；
- 下载或保存 binary/text resource；
- filesystem、temporary file、atomic move、extraction 或 symbolic-link materialization；
- selection、installation、launch、retry、cache、resume、progress、offline 或 UI policy。

模块 targets 与 `account-auth` 当前实际 build script 对齐，包括配置过的 JVM、Native、Android、JS 和 WasmJS targets。

`commonMain` dependencies：

- `api(libs.ktor.client.core)`：公共构造函数接收 `HttpClient`；
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
}
```

返回值直接交给 caller 的 Ktor `ContentNegotiation` 解码。接口不使用 `@Streaming`、`HttpStatement` 或 generic document
return type。

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
entries。runtime manifest 使用 sealed interface、`@SerialName` 和 `@JsonClassDiscriminator("type")` 表达 `file`、
`directory`、`link`，并保留 raw/lzma downloads、executable flag 和 link target。模块不据此创建文件、解压或设置权限。

### 7.5 JSON 配置

caller 安装 kotlinx.serialization JSON converter，并建议 `ignoreUnknownKeys = true` 以容忍官方增加字段。现代 required
fields 仍保持 required； 优先使用 generated annotation-driven serializer。仅 argument 的 string-or-object 以及其 value 的
string-or-array 属于无 discriminator 的 JSON union，保留两个只负责 shape dispatch 的最小 handwritten serializers，实际
subtype encode/decode 仍委托 annotation-generated serializers；其他 model 不手写 serializer。

## 8. HTTP 与失败语义

- dynamic URL 原样交给 Ktorfit/Ktor，不限制 scheme、host、port、user-info 或 fragment；
- 模块不读取原始 bytes，不计算 SHA-1，不比较 declared size，也不检查 version ID；
- 模块不覆盖 caller 的 `expectSuccess`；non-2xx 行为由 caller 的 Ktor client 配置决定；
- transport、TLS、response validation、ContentNegotiation、serialization 和 cancellation failure 原样传播；
- 不捕获并包装成 module-specific exception；
- 截断 body 由 HTTP engine/body reader 报告，无法构成完整 JSON 时 serializer 也会失败；demo 无需为 metadata 再实现一层
  hash/size 完整性校验。

## 9. Demo 后续迁移

本轮明确不修改 `demo`。后续另行迁移时遵守以下边界。

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
- 保留 launcher-owned selection、planning、filesystem、process 和 binary downloader policy；
- asset object 通过公共 `toDownload()` 派生 URL。

如果移除 demo-local metadata interface 后 Ktorfit/KSP 已无其他用途，再删除 demo 的 Ktorfit/KSP wiring；不要删除仍由 typed
response 使用的 Ktor ContentNegotiation。

### 9.2 `demo/web-map`

- 只在 `serverMain` 增加新模块依赖；`jsMain` 不增加 metadata dependency；
- 在已有 caller-owned `HttpClient` 上安装 JSON `ContentNegotiation`，使用
  `Json { ignoreUnknownKeys = true }`，再用它构造 `MinecraftDistributionMetadataApiClient` 并调用 manifest/version
  metadata；JVM 与 Native 的 engine-specific `HttpClient` 构造路径都必须应用同一配置；
- 删除 private Piston DTO 和 metadata-only hash/size/ID 校验；
- client JAR 是单独的 binary artifact，其 streaming、temporary publication、ZIP/resource processing 和 downloader integrity
  policy 保持在
  `OfficialAssetRepository`。

这里“不校验”的决定只针对 metadata HTTP document；是否验证落盘二进制制品由各 downloader 自己决定。

## 10. Repository wiring 与文档

- `settings.gradle.kts` 注册 `:distribution-metadata`；
- root README/AGENTS module table 将其描述为现代 Mojang version、asset 和 Java runtime metadata；
- module README 只记录最终公共合同：V2/current schema、caller-owned configured client、operations、asset URL derivation 和无
  validation 语义；
- V1/V2 讨论、方案演变与 deferred demo migration 留在本计划，不写入 module README；
- module AGENTS 只记录 local ownership、typed Ktorfit、caller ContentNegotiation、无 semantic validation 和验证命令。

## 11. 测试矩阵

模块 common tests 覆盖：

- V2 fixed path、runtime fixed path、GET、Accept header 和一次请求；
- 可配置 Piston Meta base URL 对两个 fixed-root operations 的作用；
- caller-installed JSON ContentNegotiation；
- dynamic URL 原样转发，不做 metadata host/identity validation；
- caller `expectSuccess` 行为与 cancellation 原样传播；
- operation 后 caller client 仍可使用；
- manifest required fields 和现代 version models；
- arguments 的 modern wire variants；
- asset-index objects 与无 validation 的 resource URL derivation；
- runtime dynamic platform/component maps；
- runtime file/directory/link、raw/lzma 与 encode/decode symmetry；
- unknown optional fields、missing required fields 和 unknown required discriminator；
- 全部自动化 HTTP tests 使用 MockEngine，不访问 live Mojang service。

不测试 metadata SHA-1、size、byte limit、URL allowlist 或 module-specific exception，因为这些能力不存在。

## 12. 验证命令

所有 Gradle invocation 顺序执行。Windows 使用 platform-native wrapper：

```shell
.\gradlew :distribution-metadata:jvmTest
.\gradlew :distribution-metadata:jsNodeTest
.\gradlew :distribution-metadata:wasmJsNodeTest
.\gradlew :distribution-metadata:mingwX64Test
.\gradlew :distribution-metadata:compileAndroidMain
```

修改 build wiring 时额外验证 configuration-cache store/reuse。demo 未迁移前不把 demo 改动或回归任务列为本轮完成条件。

## 13. 完成标准

1. `:distribution-metadata` 是带 README、AGENTS、实际支持 targets 和公共 Kotlin API 的独立 runtime module。
2. 模块只存在 V2 manifest API/model，并覆盖现代 version metadata、asset index、Java runtime catalog 和 runtime manifest。
3. 模块公开 annotated Ktorfit API interface，并拥有 generated implementation；consumer 不应用 Ktorfit/KSP Gradle plugin。
4. API 借用 caller-configured `HttpClient`，每次 operation 一个 GET，不创建 engine、不安装插件、不关闭 client。
5. response 通过 caller ContentNegotiation 直接反序列化，不存在 metadata hash/size/host/identity validation、byte limit
   或专用异常。
6. asset object 可确定性转换为 resources host 的 download descriptor，但模块不下载对象或校验 descriptor。
7. JVM、JS、WasmJS、适用 Native 和 Android compile checks 通过；自动化测试无 live network。
8. demo 保持未修改；其迁移边界已在本计划中记录。

## 14. 不随本计划实现

- 历史 manifest/version schema compatibility；
- general-purpose launcher、installer、runtime installer 或 process manager；
- object/binary downloader 与 filesystem materialization；
- metadata 或 binary retry/cache/resume/rate-limit/offline policy；
- metadata SHA-1/size verification、URL allowlist、byte limit 或 cross-document identity validation；
- 任意 product/catalog ID 发现；
- authentication API 重构；
- demo migration。
