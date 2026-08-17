# Minecraft 世界结构化文件的强类型模型与低内存流式 IO

## 状态

尚未实施。本计划取代原先只覆盖玩家 advancements/statistics JSON 的计划，把范围扩展到存档目录中由官方世界持久化代码拥有、
且能够被本库诚实表达的结构化文件。实施前和发布前均运行 `./gradlew -q minecraftVersion`，所有模型以仓库所选 Minecraft 版本的
官方服务端/客户端实现为依据；计划和公开文档不复制版本常量的字面值。

计划只描述后续实现，不在本阶段修改生产 API。

## 目标与总原则

最终为结构化世界文件提供相互对称的五层能力：

1. 可直接调用编译器生成 `serializer()` 的 Kotlin 强类型模型；
2. 类型与 `kotlinx.io.Source` / `Sink` 之间的直接流式编解码；
3. JSON 的 `String` / `JsonElement` 和 NBT 的 `NbtTag` / `NbtDocument` 完整值适配器；
4. `world-io` 中保持原有路径、并发协调、恢复和落盘策略的文件读写；
5. 无法或不适合强类型化时仍可使用的无损 tree 与原始输入/输出流。

共同约束：

- 不增加最大文件字节数、最大解压字节数、最大集合大小或其他库策略限制。NBT modified UTF、Anvil sector/location、压缩记录长度等
  二进制表示本身的固有限制仍然存在。
- 流式读取直接从最终输入流构造调用方请求的 Kotlin 值；流式写入直接把 Kotlin 值编码到最终输出流。不得先创建完整 `String`、
  `JsonElement`、`NbtDocument` 或中间 `ByteArray`，除非调用方明确选择相应完整值 API。
- 保持现有磁盘/网络 IO 策略不变。不得为了降低内存而增加临时文件、磁盘 spool、第二遍文件读取或网络往返，也不得把磁盘当作
  swap。
- Anvil chunk record 必须先写压缩后长度。调用方未提供长度的 typed/NBT 便捷写入允许且必须只暂存该单个 chunk 的压缩结果；调用方已知
  压缩长度时继续使用零额外 payload 缓冲的流式写入。不得为了规避这一格式事实改成临时文件。
- 不在短小、无循环的同步逻辑里人工调用 `ensureActive()`。取消只在现有协程挂起/准入边界传播；已经开始的同步编码和物理 commit
  按现有策略完成，避免留下由协作式取消截断的文件或状态机。
- typed API 是便利且版本匹配的视图，不取代原始无损 API。对于更新版本、模组或调用方扩展字段，`NbtDocument`、`NbtTag`、
  `JsonElement` 和 raw source/sink 始终保留。
- 不实现 Mojang DataFixer，不隐式改写 `DataVersion`，也不把旧版本输入偷偷迁移为仓库当前版本。

## 强类型建模规则

### 嵌套内容使用嵌套声明

所有只属于某个根文件模型的内容类型都声明在其最窄拥有者内部，例如 `LevelDat.Data`、`LevelDat.Data.Version`、
`PlayerData.InventoryEntry`、`TerrainChunk.Section` 和 `StructureTemplate.Block`，不再创建含义松散的顶层
`LevelDataData`、`ChunkSectionData` 等类型。

这里的“内部类”落实为 Kotlin 的 nested declaration；不得使用 `inner` 关键字。`inner` 会捕获外部实例，不适合不可变、可独立序列化的
data class。只有确实被多个文件族共同拥有的格式级原语才允许顶层声明，例如已有的坐标和压缩类型；不要仅为了减少少量重复就把
文件私有结构提升成共享 API。

代表性形状如下，具体字段必须由官方 codec/读写代码核对后补全：

```kotlin
@Serializable
data class LevelDat(
    @SerialName("Data")
    val data: Data,
) {
    @Serializable
    data class Data(
        @SerialName("DataVersion")
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val dataVersion: Int? = null,
        @SerialName("Version")
        val version: Version? = null,
    ) {
        @Serializable
        data class Version(
            @SerialName("Name")
            val name: String,
            @SerialName("Id")
            val id: Int,
            @SerialName("Snapshot")
            val snapshot: Boolean,
        )
    }
}
```

### 优先使用生成式 serializer

默认方案是 `@Serializable` data class、普通集合/数组、nullable/default、`@SerialName` 和必要的 `@EncodeDefault`。按以下顺序尝试：

1. 编译器生成 serializer 的 data class、enum、sealed 类型和标准集合；
2. `Map<String, T>` 表达键本来就动态的官方结构；
3. `NbtCompound` / `NbtList` / `NbtTag` 表达由 registry、identifier、模组或实体类型决定的开放子树；
4. 只有物理结构确实无法由以上方式诚实表达时才写自定义 serializer。

不得为了追求“全字段都有 Kotlin 属性”而为每种动态 identifier 生成 enum，也不得用大量自定义 serializer 重写现有
`kotlinx.serialization` / `NbtFormat` 能力。每一个新增自定义 serializer 都必须在源码旁说明不可替代原因，并有直接
stream、tree、 缺省字段、异常和官方互操作测试。

当前已知唯一明确需要的例外是 advancements JSON 根对象：它在同一个 object 中混合可选 `DataVersion: Int` 与任意 advancement
identifier 到 progress object 的映射，标准生成式 serializer 无法表示这个异构动态 map。只为这一层提供一个紧邻模型的
serializer；`PlayerAdvancements.Progress` 仍使用编译器生成 serializer。若实现期发现其他结构需要自定义 serializer，先考虑把真正开放的
局部字段降为 `NbtTag`，不能未经计划复核就扩散自定义 codec。

### 完整性与未知字段

内置强类型模型以仓库所选版本为完整目标。默认 typed decoder 不忽略未知字段，避免“读取成功、写回时静默删除未知数据”。需要保留
模组字段、未来字段或本计划尚未强类型化的子树时，调用方使用 raw tree API，或使用模型中明确声明为 `NbtCompound` / `NbtTag`
的开放字段。 不得实现一个把任意 unknown fields 扁平塞回 data class 的通用自定义 serializer。

## 官方证据与完整文件清单

实施第一步先建立按“路径生产者、路径消费者、物理包装、根结构、落盘策略”记录的清单。证据顺序遵循仓库规则：匹配版本的官方
server/client JAR，其次才是 revision-matched Wiki。至少检查：

- `LevelResource`、`LevelStorageSource`、`PlayerDataStorage`、`PlayerAdvancements`、`ServerStatsCounter`；
- `SavedDataStorage` 及所有可达 `SavedDataType`，包括只在特定游戏状态或命令执行后创建的文件；
- terrain/entity/POI 的官方 region producer 与 consumer；
- `StructureTemplateManager` 的 generated structure 读写；
- 官方 fixture 实际生成的默认世界，以及通过命令触发地图、command storage、boss event 等条件文件后的目录树。

默认官方世界已经证明除 `.mca` / `.mcc` 外还存在 `level.dat`、玩家目录、世界级 namespaced saved data、维度级 saved data 和
generated structure 入口。因此实现不得再把 NBT 世界文件排除在 typed scope 外。

### 纳入第一等 typed 支持的文件族

| 文件族            | 典型路径                                                 | 物理格式                                   | 强类型根模型/能力                             |
|-------------------|----------------------------------------------------------|--------------------------------------------|-----------------------------------------------|
| 世界元数据        | `level.dat`、`level.dat_old` 及恢复副本                  | GZIP + unnamed compound NBT                | `LevelDat`                                    |
| 玩家数据          | `players/data/<uuid>.dat`、`.dat_old` 及官方 legacy 路径 | GZIP + unnamed compound NBT                | `PlayerData`                                  |
| 玩家进度          | `players/advancements/<uuid>.json` 及 legacy 路径        | UTF-8 JSON                                 | `PlayerAdvancements`                          |
| 玩家统计          | `players/stats/<uuid>.json` 及 legacy 路径               | UTF-8 JSON                                 | `PlayerStatistics`                            |
| 世界级 saved data | `<world>/data/<namespace>/<path>.dat`                    | GZIP 或 legacy NONE + unnamed compound NBT | 通用 wrapper + 每个已知官方 payload           |
| 维度级 saved data | `<dimension>/data/<namespace>/<path>.dat`                | GZIP 或 legacy NONE + unnamed compound NBT | 通用 wrapper + 每个已知官方 payload           |
| terrain chunk     | `<dimension>/region/r.*.*.mca` 与 `c.*.*.mcc`            | Anvil record + compressed NBT              | `TerrainChunk`                                |
| entity chunk      | `<dimension>/entities/r.*.*.mca` 与 sidecar              | Anvil record + compressed NBT              | `EntityChunk`                                 |
| POI chunk         | `<dimension>/poi/r.*.*.mca` 与 sidecar                   | Anvil record + compressed NBT              | `PoiChunk`                                    |
| 保存的结构模板    | `generated/<namespace>/structure/**/*.nbt`               | GZIP + unnamed compound NBT                | `StructureTemplate`                           |
| 数据包元数据      | `datapacks/*/pack.mcmeta` 或包内同名条目                 | UTF-8 JSON                                 | `DataPackMetadata`，但不改变 archive/目录策略 |

这里的恢复副本和 legacy 路径复用同一个内容模型，不为路径状态复制类型。

### saved data 必须逐族建模

saved data 不是一个固定 payload，不能用名为 `SavedData` 的单个 data class 假装完整。提供一个标准外层：

```kotlin
@Serializable
data class SavedDataDocument<T>(
    @SerialName("data")
    val data: T,
    @SerialName("DataVersion")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dataVersion: Int? = null,
)
```

然后为官方 `SavedDataType` 的 payload 分别建模。根据当前官方默认世界和已知条件创建路径，清单至少包含以下族；最终名称和
identifier 必须由实施期的全部 `SavedDataType` 审计确认，不能只照抄这份示例列表：

- 世界级：custom boss events、game rules、random sequences、scheduled events、scoreboard、stopwatches、weather、world clocks、
  world-gen settings；
- 维度级：chunk tickets、raids、world border，以及 End 的 dragon fight；
- 条件创建：command storage、map index、单张 map data、wandering trader 及官方代码中其余可达 saved-data 类型。

每个 payload 的专属子结构嵌套在自己的根类中，例如 `ScoreboardData.Objective`、`ScoreboardData.Team`、`MapData.Banner` 和
`RaidsData.Raid`。真正开放的 command-storage compound、registry-backed world-gen 节点等允许在最窄字段使用 `NbtCompound`
，而不是 引入复杂的手写 serializer 或让 `world-format` 反向依赖 `protocol-model` / `protocol-vanilla-data`。

### region 内部 NBT

`.mca` / `.mcc` 容器模型保持现状，同时为三类 payload 增加匹配版本的 typed root：

- `TerrainChunk` 强类型化根字段、sections、heightmaps、tick lists 等稳定结构；block entity、structure start 或
  registry-backed palette 中无法封闭的部分保留最窄 `NbtCompound`/`NbtTag`。
- `EntityChunk` 强类型化 chunk envelope；entity-specific 数据由 `id` 决定且允许模组扩展，先保留为 `NbtCompound` 列表，不在
  `world-format` 复制整个实体继承树。
- `PoiChunk` 强类型化 section、record 和 occupancy 等固定内容，优先做到完全生成式 serializer。

`RegionStorageDirectory` 与 typed model 建立明确映射。高层便利方法不能让调用方把 `EntityChunk` 写进 terrain directory；通用
serializer 重载仍允许高级调用方显式操作自定义 payload。

### 其他存档内容的处理方式

以下内容也必须在路径/API 清单中明确处理，但不伪装成上述强类型持久化模型：

- `session.lock` 是专用进程 lease 协议，只由 `WorldDirectoryLock` 管理；不提供普通 typed write，避免绕过锁状态机。
- `icon.png` 是标准 PNG，`resourcepacks/resources.zip` 是 archive。为大文件提供 raw `Source` / `Sink` 访问即可；不在本项目发明
  PNG/ZIP serializer，也不改变替换策略。
- `datapacks` 可以是目录或 ZIP，内部资源类型由路径决定且可由用户扩展。第一阶段内置固定的 `pack.mcmeta` 模型，并提供通用
  JSON/NBT serializer 与 raw stream 入口；不要把所有 datapack registry 资源误称为一个固定“世界存档模型”。若未来为
  recipes、loot tables、 worldgen 等增加完整模型，应由对应数据包格式计划逐族审计，而不是在本次 world IO 改动中生成不完整类型。
- `generated` 下正常二进制 structure `.nbt` 纳入 typed 支持；仅调试构建使用的 `.snbt` 在仓库拥有正式 SNBT format 前保留
  raw text， 不手写半套文本解析器。
- 世界目录之外的 `server.properties`、ops/whitelist/ban/usercache JSON 和日志属于服务端实例，不属于 `world-io` 的世界目录职责。

## 模块与依赖设计

### `world-format`

把职责准确扩展为“文件系统无关的 Minecraft 世界磁盘格式与版本匹配的逻辑模型”。继续拥有 Anvil 和压缩，同时新增 JSON、独立 NBT
内容模型及结构模板模型，不拥有 Okio `Path`、`FileSystem`、备份、锁或同步策略。

需要：

- 应用根工程已声明的 Kotlin serialization 插件；
- 公开签名直接使用的 `kotlinx-serialization-core`、`kotlinx-serialization-json`、`nbt`、`nbt-serialization` 与
  `kotlinx-io-core`
  按 ABI 需要声明为 `api`，仅内部 JSON IO adapter 保持 `implementation`；
- 按文件族分 package/file，根模型拥有其 nested data class；不要把所有模型堆进一个 `WorldDataModels.kt`；
- 使用一个明确配置为 `NbtRootEncoding.UNNAMED` 的世界文件 NBT format。它仍是现有 `NbtFormat` 的配置实例，不另写一套 NBT
  codec；
- JSON 直接使用配置明确的 `Json` 与模型 serializer。除 advancements 异构根外，不为每个模型创建只会转发的 `*JsonFormat` 类；
- 给 `RegionChunkNbtFormat` 增加 `SerializationStrategy<T>` / `DeserializationStrategy<T>` stream 重载，已有
  `NbtDocument` 方法作为适配器；
- 不新增 `world-model` 聚合模块，不依赖 `protocol-model`，不造成发布图反向依赖。

版本相关模型仍是手写语义代码，不提交生成源码。若官方 schema 中存在大量可确定字段，可增加 build-time 分析报告作为证据，但生成器必须
遵循 `buildSrc/AGENTS.md`，且不能让运行时读取官方 JAR。

### `world-io`

`world-io` 只组合路径、文件、协调和已有 commit 策略：

- `NbtFileStore` 增加 generic serializer/deserializer 的直接 stream 方法；内部复用当前解压 source 与压缩 sink，不先转成
  `NbtDocument`；
- `Utf8JsonFileStore` 的 typed、tree、text、raw 四类方法命名清晰并对称；不通过返回类型猜测重载；
- `MinecraftWorldAccess` 和 `OpenMinecraftWorld` 中每个 typed/tree/raw 表示进入同一个 logical-file coordinator；
- `LiveMinecraftWorldReader` 提供对应的只读 typed/tree/raw API，但仍不获取 `session.lock`、不恢复、不修改；
- `WorldRegionStore` / `RegionFileStore` 提供 generic typed chunk 与三个已知 storage typed shortcut；
- `MinecraftWorldPaths` 增加 generated structure、world icon、map resource、datapack metadata 等经过官方验证的路径构造；所有
  identifier/path 验证只防止路径逃逸，不增加内容大小限制。

不得把 typed serializer 逻辑复制到 `world-io`，也不得让公共 facade 绕过 `OpenMinecraftWorld` 的 lifecycle/coordination。

## 公开 API 目标形状

具体名称可在实现时按现有风格微调，但 raw、tree、typed 必须可从名称区分。允许为了简洁进行 API 破坏。

### 独立 NBT 文件

```kotlin
suspend fun readLevelData(): LevelDat
suspend fun readLevelDataNbt(): NbtDocument
suspend fun <T> readLevelData(deserializer: DeserializationStrategy<T>): T
suspend fun <T> readLevelData(block: (Source) -> T): T

suspend fun writeLevelData(value: LevelDat)
suspend fun writeLevelDataNbt(document: NbtDocument)
suspend fun <T> writeLevelData(serializer: SerializationStrategy<T>, value: T)
suspend fun writeLevelData(block: (Sink) -> Unit)

suspend fun readPlayerData(playerUuid: String): PlayerData?
suspend fun readPlayerDataNbt(playerUuid: String): NbtDocument?
suspend fun <T> readPlayerData(playerUuid: String, deserializer: DeserializationStrategy<T>): T?
suspend fun writePlayerData(playerUuid: String, value: PlayerData)
```

player data 的其余 tree/generic/raw write 与 level data 对称。默认高层方法返回强类型；当前返回 `NbtDocument` 的同名 API
迁移到显式
`...Nbt` 名称。

saved data 保留 identifier/dimension generic 入口：

```kotlin
suspend fun <T> readSavedData(
    identifier: String,
    deserializer: DeserializationStrategy<SavedDataDocument<T>>,
    dimension: DimensionDirectory = DimensionDirectory.Overworld,
): SavedDataDocument<T>?

suspend fun <T> writeSavedData(
    identifier: String,
    serializer: SerializationStrategy<SavedDataDocument<T>>,
    value: SavedDataDocument<T>,
    dimension: DimensionDirectory = DimensionDirectory.Overworld,
)
```

同时为已知官方族提供薄的命名 extension，例如 `readScoreboardData()`、`readMapData(mapId)`、`readRaidsData(dimension)` 及对应
write； extension 只固定 identifier、dimension 和生成式 serializer，最终仍进入 generic coordinated method，不复制 IO。

### 玩家 JSON

```kotlin
suspend fun readAdvancements(playerUuid: String): PlayerAdvancements
suspend fun readAdvancementsJson(playerUuid: String): JsonElement
suspend fun readAdvancementsText(playerUuid: String): String
suspend fun <T> readAdvancements(playerUuid: String, block: BufferedSource.() -> T): T

suspend fun writeAdvancements(playerUuid: String, value: PlayerAdvancements)
suspend fun writeAdvancementsJson(playerUuid: String, value: JsonElement)
suspend fun writeAdvancementsText(playerUuid: String, value: String)
suspend fun writeAdvancements(playerUuid: String, block: BufferedSink.() -> Unit)
```

statistics 完全对称。`PlayerAdvancements` 形状为：

```kotlin
@Serializable(with = PlayerAdvancementsSerializer::class)
data class PlayerAdvancements(
    val dataVersion: Int? = null,
    val advancements: Map<String, Progress>,
) {
    @Serializable
    data class Progress(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val criteria: Map<String, String> = emptyMap(),
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val done: Boolean = true,
    )
}
```

紧邻模型的 internal `PlayerAdvancementsSerializer` 使用 map-like `SerialDescriptor` / composite API 逐项处理，source
路径不得先解码
`JsonObject`、`Map<String, JsonElement>` 或 `String`。serializer 是实现，不是文件内容类型；文件内容 `Progress` 保持嵌套。
`PlayerStatistics` 的动态 stat type/value 直接使用嵌套 map 和生成式 serializer。

### region payload

```kotlin
suspend fun readTerrainChunk(position: ChunkPosition, dimension: DimensionDirectory = ...): TerrainChunk?
suspend fun writeTerrainChunk(position: ChunkPosition, value: TerrainChunk, dimension: DimensionDirectory = ...)

suspend fun readEntityChunk(position: ChunkPosition, dimension: DimensionDirectory = ...): EntityChunk?
suspend fun readPoiChunk(position: ChunkPosition, dimension: DimensionDirectory = ...): PoiChunk?

suspend fun <T> readChunkNbt(
    position: ChunkPosition,
    deserializer: DeserializationStrategy<T>,
    storage: RegionStorageDirectory,
    dimension: DimensionDirectory,
): T?

suspend fun readChunkNbtDocument(...): NbtDocument?
```

write generic serializer 与 document adapter 对称。typed convenience 根据方法名固定正确 storage。raw compressed read/write
继续存在，已知长度的写入仍是 最低内存路径。

### generated structures 与其他文件

提供 `readStructure(identifier): StructureTemplate?`、typed write、`NbtDocument` 和 decompressed source/sink 四层入口；路径按官方
namespace 规则解析，写策略完全沿用官方当前实现而不是套用 level/player 的备份策略。

`DataPackMetadata` 只处理明确的 `pack.mcmeta` JSON。datapack 目录/ZIP、PNG 和 resource ZIP 的大文件操作提供 caller-owned
raw source/sink callback；完整 `ByteArray`/`ByteString` 便利方法若保留，文档明确其内存成本。不要为任意路径增加能绕过 world
lease 或路径逃逸检查的写接口。

## 流式、内存与 IO 细节

- `NbtFormat.decodeFromSource(deserializer, source)` / `encodeToSink(serializer, value, sink)` 已经是 typed NBT
  的规范实现；新代码组合它， 不先构造 `NbtDocument`。
- standalone NBT 的 compression decorator 与 serializer 共用同一条 `kotlinx.io` pipeline。输入 source 和输出 sink 都不由
  format 关闭， 但压缩 sink decorator 必须正常 close 以写出尾部。
- JSON 使用 maintained `kotlinx-serialization-json-io` 流适配器直接编解码。typed 结果自身仍占内存；只有 raw source/sink
  转发能在不保留 逻辑值时做到近似固定内存。
- `String`、`JsonElement`、`NbtDocument`、`RegionFile`、`ByteArray` 的完整值 API 是明确便利层，不宣传为低内存路径。
- Anvil typed write 仅在不知道压缩长度时使用一个内存 `Buffer` 保存单个压缩 payload。不得同时保留未压缩 `ByteArray`、
  `NbtDocument`
  和第二份压缩拷贝；直接把 serializer 输出送入压缩 `Buffer`，再以已知 `buffer.size` commit。
- 对 level/player 临时替换、saved-data 同步 direct write、玩家 JSON final-path truncate、MCA in-place record、MCC
  temporary-and-move、structure 文件策略逐项保持现状。流式优化不得改变 durability、backup、fallback、corrupt-copy 或
  atomicity。
- 不做预扫描来计算 JSON/NBT 长度，不为“更流式”重复压缩，不把输出先写磁盘再读回。

## 协程取消与并发安全

新增 typed API 必须继承而不是旁路现有 world-io 状态机：

- 同一 logical file 的 typed、tree、text 和 raw read 共享 reader admission；所有 write 共享 exclusive admission。
- 在 admission 前或等待 admission 时取消，不打开、不截断、不创建目标文件。
- admission 后的 generated serializer、压缩和同步文件 IO 是非 suspending 同步段，不添加人工取消轮询；外部取消在下一现有挂起边界传播，
  物理 commit 和 users/handle/barrier cleanup 先完成。
- callback API 中由调用方主动抛出的异常仍按该文件原有失败策略处理；文档不得把任意会中途抛异常的自定义 callback 宣称为事务。
- 所有 `catch (Throwable)`、`catch (Exception)` 和 `runCatching` 只能在确有 cleanup/aggregation 需要时存在，且保持
  `CancellationException` 为主异常并传播；cleanup/IO 失败只能作为 suppressed 诊断，不能把取消包装成普通失败。
- `flush()` 取消后释放所有已 pin entry，不继续发起新的 flush；`close()` 在 `NonCancellable` cleanup 中完成并向同时及后续所有调用者
  发布一致终态。新 store/path 不得复制出另一套 close barrier。
- last-user cleanup failure 的报告所有权保持当前约定，不能因增加 typed extension 重复报告或丢失。

## 实施步骤

1. 运行版本任务并审计官方 JAR、默认 fixture 和条件创建文件，产出完整的世界目录文件族/`SavedDataType` 清单；对每项记录
   schema、 compression、root encoding、路径和 commit policy。
2. 更新 `world-format` build 依赖和模块边界，建立统一的 unnamed-root `NbtFormat` 配置与 JSON 配置。
3. 按最窄拥有者规则实现 JSON、level、player、saved-data payload、terrain/entity/POI、structure、pack metadata 模型；每完成一族立即添加
   format-only tests。
4. 审查自定义 serializer 清单。默认只保留 advancement root；所有其他模型使用 generated serializer 或在最窄开放字段使用 raw
   NBT。
5. 给 `RegionChunkNbtFormat`、`NbtFileStore`、`Utf8JsonFileStore` 增加 generic typed stream 重载，移除已被统一 generic API
   完全替代的 转发 helper/class。
6. 扩展 paths 和 standalone structure/opaque stream store，保持逐文件官方策略。
7. 接入 `OpenMinecraftWorld` logical-file registry，再向 `MinecraftWorldAccess` 和 `LiveMinecraftWorldReader` 暴露
   facade；最后增加各类 named convenience extensions。
8. 重命名旧的同名 String/NbtDocument API，删除冗余函数，更新全部调用点和测试；不保留只会增加歧义的 deprecated 双份 API。
9. 完成普通 KMP、确定性并发/取消、官方互操作和外部消费验证后更新 README、AGENTS 与 agent skills。

## 测试计划

### `world-format` 普通 KMP 单元测试

每个模型族至少覆盖：

- 官方最小值、完整值、optional/default、空 map/list、动态 identifier 和 NBT primitive/array 的 field-by-field decode/encode；
- `Source -> typed -> Sink`，以及 JSON tree/string、NBT tag/document 完整值适配器；
- 输入一次只提供少量字节、输出 sink 只接受小片段的 segmented source/sink，证明实现不依赖 `readByteArray()`、`readUtf8()`
  或一次性写入；
- typed decoder 遇到未知字段明确失败，raw tree round trip 则原样保留该字段；
- public compile tests 直接引用 `LevelDat.Data`、`LevelDat.Data.Version`、`TerrainChunk.Section` 等嵌套名，并确认没有对应的一次性顶层内容类；
- 自定义 serializer inventory test/审查：advancement root 的动态 key、任意字段顺序和大 map 逐项流式处理；其他模型直接调用生成的
  `serializer()`；
- 适度构造超过历史旧阈值的数据以证明没有 policy limit，不写入不必要的超大磁盘文件；
- 三类 region payload 与 storage mapping，错误 typed shortcut 不可能选择错误目录；
- Anvil typed encode 只保留一份压缩 payload，并与已知压缩长度 raw path 产生等价 record。

测试 fixture 使用 kotlinx serialization builders 或 NBT builders 构造，不能手拼 JSON 字符串。普通测试放 `commonTest`。

### `world-io` 文件与策略测试

使用 in-memory/fake filesystem 和自拥有临时目录逐项验证：

- typed/tree/raw 读取相同内容，typed 写入能由 raw decoder 读取，raw 写入能由 typed decoder 读取；
- level/player 的 temporary、backup、fallback、corrupt-copy；saved data direct synced GZIP；JSON final-path direct；structure
  的官方策略； MCA/MCC 原 commit 顺序均未改变；
- generic saved-data serializer 与每个 named extension 使用完全相同的 canonical identifier/dimension logical key；
- current/legacy player path fallback 不复制 codec，也不造成双重写；
- live reader 的每次 typed 读取仍是独立 bypass observation，不创建 coordinator、不恢复文件；
- close/flush 资源计数、failure ownership 和后续 close 终态与既有规则一致；
- 不生成额外 spool/temp 文件，不执行第二次输入读取，不增加网络/磁盘往返。

### 确定性协程并发与取消测试

JVM 测试使用 `runTest`，被测同步 IO 放在 `Dispatchers.Default`，以 `CompletableDeferred`、channel 和受控
filesystem/source/sink gate 推进关键点；需要阻塞同步调用时使用明确的 latch，由测试协程在观察到 gate 后释放。禁止
delay、sleep、概率竞争和任意 timeout 来“提高命中率”。

至少覆盖：

1. reader 已进入 typed decode 时 writer 等待，后来的 reader 不越过 waiting writer；释放 gate 后顺序确定且最终文件可解码。
2. typed writer 已取得 exclusive admission 时 raw/tree readers 等待；不同 logical file 同时通过各自 gate 前进。
3. 等待 admission 的 read/write 被取消，目标文件 metadata 和内容完全未变化，原始 `CancellationException` 传播。
4. 取消发生在 typed serializer/压缩/物理 write 的受控同步 gate 内：解除 gate 后完整 commit、durable/backup cleanup 和
   users-- 完成， 随后调用收到取消；重新打开 typed/raw 均可解码，不存在半个 JSON、半个 NBT 或半个 MCA record。
5. 对 level/player replacement、saved-data direct、JSON direct、MCA inline、MCC sidecar 和 structure write 分别覆盖其真实策略边界；不以一种
   临时文件策略的结果代替另一种。
6. 取消与 close/flush/last-user cleanup failure 同时发生时，取消保持 primary，其他异常只出现一次且作为 suppressed；所有
   entry、handle、 pin 和 close barrier 最终归零/完成。
7. 同时调用 close、close 完成后再次调用 close，所有调用者观察相同成功或失败终态。

这些测试验证库自身的 cooperative cancellation。调用方在 raw callback 中主动写一半后抛异常属于显式 callback
failure，另测原有文件策略， 不通过吞掉 `CancellationException` 伪装成功。

### 官方 generate/rewrite/reload 互操作

扩展现有单进程、多 phase 的 official fixture scenario：

1. 官方服务端生成默认世界，并通过命令或游戏行为稳定创建 conditional saved data、玩家 JSON、玩家 NBT、map、command storage、结构和
   terrain/entity/POI payload；正常停止一次。
2. world-io 对每个实际存在的文件先 typed decode，再 raw decode，对关键字段做逐项断言。
3. 仅修改官方可安全观察的字段，通过 typed API 按原策略写回；不自动改 `DataVersion`，不删除 unknown raw subtree。
4. 重新启动同一官方世界，让官方读取并再次保存；通过命令/查询验证修改，再正常停止。
5. 再次 typed/raw 读取，确认官方 rewrite 后结构仍匹配；覆盖 legacy/fallback 的场景使用独立 phase，不依赖测试方法顺序。

同一平台测试任务复用一个兼容官方进程；主机路径只在既有 `hostFilesystemTest` backdoor 中使用。浏览器/Wasm 不伪造文件系统互操作。

### 发布与独立消费测试

- 检查 `world-format` 与 `world-io` publication metadata：外部消费者只引入相应模块及已声明下层依赖即可调用 model
  serializer 和 stream API。
- 编译一个外部 consumer smoke test，分别验证只消费 `world-format` 的纯内存 typed encode/decode，以及消费 `world-io`
  的文件路径；不能依赖 repository test fixtures 或隐式初始化。
- 检查生产 runtime classpath，没有因 JSON/typed convenience 引入 protocol/session/server/fixture 模块。

## README、AGENTS 与 skill 更新

这些文档需要修改，不是可选清理：

- 根 `README.md`：把 `world-format` 描述从 Anvil-only 更新为 filesystem-independent structured world formats，并链接分层示例。
- `nbt-serialization/README.md`：给 arbitrary `@Serializable` 类型的 unnamed-root `Source`/`Sink` 直接编解码示例，解释与
  `NbtDocument`
  完整 tree 的内存差异。
- `world-format/README.md`：分别给 level/player/saved-data、玩家 JSON、terrain/entity/POI、generated structure 的关键
  model/stream 示例；明确 Anvil unknown compressed length 的单 payload 缓冲边界。
- `world-io/README.md`：给 `MinecraftWorldAccess`、`LiveMinecraftWorldReader`、standalone stores、`WorldRegionStore` 各自的
  typed read/write 示例，以及 tree/text/raw 选择指南；关键流式输入和输出各至少一个例子。
- 根 `AGENTS.md`：更新 published module architecture 对 `world-format` 的职责描述。
- `world-format/AGENTS.md`：增加版本匹配模型、nested type、generated serializer 优先、open subtree 与 stream/whole-value
  测试要求。
- `world-io/AGENTS.md`：增加新路径族、logical-file grouping、typed/raw 同协调和逐文件 commit policy；不得用统一策略抹平文件差异。
- `.agents/skills/minecraft-nbt` 与 `.agents/skills/minecraft-world-io`：若其 scope/参考流程仍把 typed world NBT 或
  structure 排除在外，按最终源码 修正。私有 skill 不要求新增示例代码，已有示例保持正确即可。

另外执行一次仓库所有 published runtime module README 的完整审计：每个公开模块都必须说明本层职责、关键入口及至少一个能独立消费的
代码示例；已有且正确的示例不改写，缺失时在所属模块补齐。私有开发模块可以没有示例，原有示例若仍正确则保留。

`nbt/README.md` 只有在 raw NBT value handoff 或公开依赖说明实际变化时才修改；其他未受影响模块不做机械性文档改写。

## 验证顺序

Gradle 命令严格串行，内存不足时统一追加合适的 `--max-workers=<count>`：

1. `./gradlew -q minecraftVersion`
2. `./gradlew :world-format:jvmTest`
3. `./gradlew :world-io:jvmTest`
4. `./gradlew :nbt-serialization:jvmTest`
5. `./gradlew :world-format:jsNodeTest`
6. `./gradlew :world-io:jsNodeTest`
7. 当前主机可用的 desktop Native tests
8. 包含 `hostFilesystemTest` 的既有官方世界互操作任务
9. 外部 consumer/publication metadata smoke test
10. 必要时 `./gradlew allTests`
11. `git diff --check HEAD`

## 完成标准

- 官方 world persistence 清单中的每个结构化文件族都有明确的 typed、generic/raw 或“不适合强类型化”结论，不只覆盖
  `.mca/.mcc`。
- level、player、全部已确认官方 saved-data payload、玩家 JSON、terrain/entity/POI 和 generated structure 均有版本匹配的
  public root model； 文件专属子结构全部使用 nested declaration，且没有 Kotlin `inner` 类。
- 除 advancement 异构 JSON 根或经重新论证的不可表达结构外，所有模型使用 compiler-generated serializer；开放局部使用 raw
  NBT value， 不扩散手写 codec。
- JSON/NBT typed input 和 output 都直接连接 `Source`/`Sink`，不经完整 String/tree/ByteArray；完整值 API 仍明确可用。
- 所有 policy maximum 已移除且未重新引入；格式固有限制有清晰说明。
- 除 Anvil 单 chunk 未知压缩长度外没有新的 payload 暂存；没有新增临时文件、磁盘 spool、二次 IO 或网络往返。
- 每个 typed API 与对应 raw API 共用正确 logical-file coordinator、取消、close、flush 和 cleanup 语义；取消测试证明状态机不泄漏、文件可重开
  且不会因 cooperative cancellation 留下截断格式。
- 普通 KMP、确定性并发/取消、官方 generate/rewrite/reload 和独立消费测试全部通过。
- 根/模块 README、AGENTS 和相关 skill 与最终边界一致，每个公开模块的 README 都能让消费者找到该层关键 API 的用法。
