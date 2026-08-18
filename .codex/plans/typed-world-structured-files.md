# 现代 Minecraft 常用结构化世界文件的强类型支持（第一版）

## 状态与范围

尚未实施。本计划只覆盖仓库所选 Minecraft 版本会写出的、常用且结构相对稳定的三类世界文件：

| 文件族     | 当前版本路径                       | 物理格式                              | 第一版推荐模型       |
|------------|------------------------------------|---------------------------------------|----------------------|
| 世界元数据 | `level.dat`、`level.dat_old`       | GZIP + 带空根名的 compound binary NBT | `LevelDat`           |
| 玩家进度   | `players/advancements/<uuid>.json` | UTF-8 JSON                            | `PlayerAdvancements` |
| 玩家统计   | `players/stats/<uuid>.json`        | UTF-8 JSON                            | `PlayerStatistics`   |

`level.dat_old` 是当前版本正常保存流程维护的上一份世界元数据，不代表支持旧版 Minecraft 文件格式。它与
`level.dat` 使用同一个模型，并继续作为现有恢复策略的一部分，不提供另一套内容 API。

第一版不支持历史目录布局、历史文件名、旧压缩变体或旧 schema，也不探测或回退到历史路径。所有路径必须来自匹配版本官方
`LevelResource` 和实际生成世界；实现前与发布前运行 `./gradlew -q minecraftVersion`，公开文档不复制版本常量字面值。

以下内容不属于第一版强类型范围：

- 玩家 NBT、namespaced saved data、generated structure 和数据包内容；
- 世界图标、资源包 archive 与其他不由 JSON/NBT serializer 拥有的文件；
- `session.lock` 的内容模型；它仍只由现有世界目录锁实现管理。

不实现 Mojang DataFixer，不隐式迁移输入，不自动改写 `DataVersion`。需要读取模组字段、未来版本字段或第一版未建模文件时，调用方
继续使用现有 `NbtDocument`、`NbtTag`、`JsonElement`、文本或 raw stream API。

## 通用序列化 API 与流式约束

### serializer-first

- typed 文件 API 对任意 `T` 开放。规范入口接收 `DeserializationStrategy<T>` 或 `SerializationStrategy<T>`；
  `KSerializer<T>` 可同时用于两个方向。内置模型只是库提供的推荐 `T`，不是文件 API 唯一允许的返回值。
- 提供从已配置 format 的 `SerializersModule` 获取 serializer 的 reified 便利重载，但它只薄包装显式 serializer 入口。 不提供依赖
  JVM reflection 的 `KClass`/`KType` 核心 API。
- `Map<*, *>` 这样的星投影本身没有足够的 key/value 序列化信息。调用方应传入具体类型及 serializer，例如
  `MapSerializer(String.serializer(), MyValue.serializer())`，或使用能解析出 serializer 的具体 reified 类型。
- README 同时示范调用方自有 `@Serializable` data class、具体集合 serializer，以及库提供的
  `LevelDat.serializer()`、`PlayerAdvancements.serializer()`、`PlayerStatistics.serializer()`；三者走完全相同的路径。

### 一步编解码

文件 API 的规范路径始终直接连接文件流、压缩流和目标 serializer：

- binary NBT 使用现有 `NbtFormat.decodeFromSource`/`encodeToSink`；
- JSON 使用 `Json.decodeFromSource`/`encodeToSink`；
- GZIP 只在 `world-io` 包裹 NBT source/sink，不产生完整解压副本；
- 不允许 `Source -> ByteArray/String -> tree -> T`、`Source -> tree -> T` 或相反方向的多阶段转换。

若维护中的公开序列化 API 无法为库提供的某个模型完成直接 stream 编解码，停止并调整模型或第一版范围；不得静默改用 tree 中转。

其他高级或完整值方法遵循以下唯一规则：若已有对应的流式规范入口，就薄包装该入口；若输入本来就是完整值，则直接调用序列化库
针对该表示的一步 API。例如 NBT byte array 直接使用现有且内部包装 stream 方法的 `decodeFromByteArray`/`encodeToByteArray`
，NBT tree 直接使用 `decodeFromNbtTag`/`encodeToNbtTag`，JSON text/tree 直接使用 `decodeFromString`/`encodeToString` 或
`decodeFromJsonElement`/`encodeToJsonElement`。任何便利层都不得 stringify/reparse、二次编解码，或创建第二份完整中间表示。

“流式”表示解析期间不额外物化完整文本、byte array 或 tree；返回的目标值本身仍必须存在于内存中。调用方选择 `Map` 时会得到完整
`Map`，库承诺的是不再额外构造完整 `JsonElement`/`NbtDocument`。需要更低驻留内存时，继续使用 raw stream callback 或自定义
serializer/目标类型只保留所需状态。

该保证止于库把 streaming encoder/decoder 直接交给 serializer：调用方选择的 serializer 及其触发的 format 特殊语义不由库控制。
README 明确说明，自定义或多态 serializer 仍可能物化 tree；本项目三个内置模型所使用的生成式、集合及自定义 serializer 必须满足
上述直接 stream 约束。

## 建模规则

### 内置模型只表达匹配版本的官方 schema

- 以匹配版本官方 server/client JAR 的生产者、消费者和 codec 为首要证据，并用官方实际生成文件验证。
- 不为旧版兼容保留 nullable、默认值、别名字段或兼容 serializer 分支。匹配版本写文件时必然存在的字段必须是非空且无“缺失”默认值；
  只有官方在匹配版本确实可能省略或写 null 的字段才 nullable/default。当前版本必写的 `DataVersion` 因而是必填 `Int`。
- 文件专属子结构声明在最窄根模型内，例如 `LevelDat.Data`、`LevelDat.Data.Version` 和
  `PlayerAdvancements.Progress`；使用 Kotlin nested declaration，不使用 `inner`。
- 默认使用 `@Serializable` data class、`@SerialName`、标准集合和 compiler-generated serializer。只有 JSON 根对象的物理形状
  无法由生成式 serializer 诚实表达时，才增加一个紧邻模型的 internal serializer。
- 动态 identifier 使用 `Map<String, T>`。由 registry、数据包或模组决定且无法封闭的 `level.dat` 局部子树使用最窄范围的
  `NbtCompound`/`NbtTag`，不为开放集合生成 enum，也不复制其他模块的模型。
- typed decoder 默认不忽略未知字段，防止 typed read/write 静默删除数据。需要无损保留未知字段时使用 tree/raw API。
- 每次修改 `MinecraftTarget.MINECRAFT_VERSION` 或执行完整版本对齐，都必须逐一复审这三个根模型、所有 nested model、自定义
  serializer、字段名称、类型、可空性、默认值和动态子树边界；模型与测试必须随匹配版本更新，不保留旧 schema 分支。

### `LevelDat`

模型覆盖匹配版本 `level.dat` 的完整官方根结构，根 compound 中的 `Data` 映射为 `LevelDat.Data`。固定字段和固定嵌套结构强类型化；
只有经官方 codec 审计确认属于开放 registry/data-pack 内容的最小子树保留 raw NBT 值。

binary format 直接复用 `nbt-serialization` 的 `NbtFormat`，配置为写入空根名的 `NbtRootEncoding.UNNAMED`；GZIP 只在
`world-io` 文件层组合，不另写 NBT codec。

### `PlayerAdvancements`

当前官方 JSON 根对象同时包含 `DataVersion` 和任意 advancement identifier，因此只为根层提供定制 serializer：

```kotlin
@Serializable(with = PlayerAdvancementsSerializer::class)
data class PlayerAdvancements(
  val dataVersion: Int,
  val advancements: Map<String, Progress>,
) {
  @Serializable
  data class Progress(
    val criteria: Map<String, String>,
    val done: Boolean,
  )
}
```

serializer 使用 map-kind descriptor 和 composite encoder/decoder 交替处理 key/value，并根据刚读到的 key 选择 `Int` 或
`Progress`
serializer。不得调用 `JsonDecoder.decodeJsonElement()`、`JsonEncoder.encodeJsonElement()`，也不得先构造完整 `JsonObject` 或
`Map<String, JsonElement>`。criteria 的时间字符串保持官方磁盘表示，不引入平台相关日期时间类型。

### `PlayerStatistics`

statistics 根结构固定，stat type 和 stat identifier 动态，使用生成式 serializer：

```kotlin
@Serializable
data class PlayerStatistics(
  val stats: Map<String, Map<String, Int>>,
    @SerialName("DataVersion")
    val dataVersion: Int,
)
```

不把当前 registry 内容固化成 enum；typed model 表达文件结构，而不是限制可出现的官方或数据包 identifier。

## 模块与 API

### `world-format`

在不引入文件系统依赖的前提下拥有上述三个逻辑模型及其 serializer：

- 应用根工程已声明的 Kotlin serialization 插件；
- 公共签名使用的 serialization 与 NBT 依赖按 ABI 需要声明为 `api`；
- 不为三个模型各造一套 format；NBT 直接使用现有通用 `NbtFormat`，JSON 直接使用维护中的
  `kotlinx.serialization` JSON 与 IO API；
- compiler-generated serializer 使用标准 encoder/decoder；advancement 根 serializer 只依赖 JSON streaming encoder/decoder
  支持的标准 map/composite 操作，不依赖 JSON tree API；
- 如保留 format 层便利方法，它们必须是通用 serializer API 的薄包装，或直接调用序列化库对应表示的一步完整值 API；
- 不新增聚合模块，也不依赖 protocol、session、server 或 fixture 模块。

### `world-io`

`world-io` 只组合当前路径、压缩、文件策略和已有协调状态机：

- `NbtFileStore` 增加 serializer/deserializer 重载，并在现有 GZIP source/sink 内直接调用 `NbtFormat` stream 方法；
- `Utf8JsonFileStore` 已有的通用 serializer 重载是 JSON 规范 typed 入口；`JsonElement` 方法继续以
  `JsonElement.serializer()` 薄包装它，text/raw callback 保持直接 I/O；
- `OpenMinecraftWorld`、`MinecraftWorldAccess` 和 `LiveMinecraftWorldReader` 以通用 serializer 重载暴露 typed 读取；可写
  facade 对称暴露通用 serializer 写入。内置模型不获得一条绕过这些入口的专用编解码路径；
- 同一逻辑文件的 typed、tree、text 和 raw 操作必须共用现有 writer-preferring coordinator；live reader 仍不加锁、不恢复、 不修改；
- level 文件继续使用当前 temporary、backup、fallback 和 corrupt-copy 策略；两个玩家 JSON 文件继续直接 truncate/write
  最终路径。typed 支持不得统一或改变这些策略。

目标 API 以显式 serializer 为基线，具体命名和参数顺序可按现有风格微调：

```kotlin
suspend fun <T> readLevelData(deserializer: DeserializationStrategy<T>): T
suspend fun <T> writeLevelData(serializer: SerializationStrategy<T>, value: T)

suspend fun <T> readAdvancements(
  playerUuid: String,
  deserializer: DeserializationStrategy<T>,
  json: Json = Json,
): T
suspend fun <T> writeAdvancements(
  playerUuid: String,
  serializer: SerializationStrategy<T>,
  value: T,
  json: Json = Json,
)

suspend fun <T> readStatistics(
  playerUuid: String,
    deserializer: DeserializationStrategy<T>,
  json: Json = Json,
): T
suspend fun <T> writeStatistics(
  playerUuid: String,
  serializer: SerializationStrategy<T>,
  value: T,
  json: Json = Json,
)
```

可选的 reified 方法仅负责从相应 format 的 `SerializersModule` 取得 serializer 后调用上述方法。README 中推荐模型的用法是
`readLevelData(LevelDat.serializer())`、`readAdvancements(uuid, PlayerAdvancements.serializer())` 和
`readStatistics(uuid, PlayerStatistics.serializer())`；调用方模型或具体 `Map<K, V>` 的用法完全相同。

写入的 tree/text/raw 方法与读取对称。NBT document 方法直接使用现有 document stream codec；JSON tree 方法委托
`JsonElement.serializer()` 的通用入口；text/raw 方法直接读写原表示。现有返回 `String` 或 `NbtDocument` 的同名便利 API
可重命名为 显式后缀；不保留会造成返回类型歧义的双份重载。

## IO 与取消约束

- 不增加最大文件、解压输出、集合、树深或分配大小等策略限制，只保留格式本身的固有限制。
- format 不关闭调用方拥有的 `Source`/`Sink`；压缩 decorator 必须正常结束以写出 GZIP 尾部。
- 不为了流式处理增加临时文件、磁盘 spool、预扫描、二次读取或二次编码。
- 等待 logical admission 时取消不得打开、创建或截断文件；同步编码或物理 commit 已开始后，按现有策略完成 commit 和资源清理，
  再传播原始 `CancellationException`。
- broad catch 只能用于必要 cleanup/aggregation；取消保持 primary，清理失败作为 suppressed context。

## 实施步骤

1. 审计匹配版本 `LevelResource`、`LevelStorageSource`、`PlayerAdvancements`、`ServerStatsCounter`、相关 codec 与官方实际文件，
   固定三个文件族的路径、字段、根编码和写入策略。
2. 在 `world-format` 实现三个根模型及其 serializer；仅保留 advancement 根 serializer 这一项预期例外，不增加按模型绑定的
   format 抽象。
3. 在 `world-io` 接入 serializer-first typed API，所有 tree/text/raw/完整值便利方法按一步编解码规则复用已有 store、logical
   key、 恢复、并发、取消与 close/flush 语义。
4. 更新受影响的 README 和模块 `AGENTS.md`；文档示范调用方类型、具体集合 serializer 和内置模型，明确第一版范围、raw fallback、
   目标对象仍会物化，以及完整值 API 的额外内存成本。
5. 更新 `minecraft-world-io` 与 `minecraft-release-update` skill，使完整版本对齐必审内置模型、serializer、字段可空性和默认值。
6. 完成普通 KMP、受控并发/取消、官方 generate/rewrite/reload 和发布依赖验证。

## 测试与验证

### Format 测试

- 用 serialization/NBT builders 构造匹配版本最小值、完整值、缺省值、空集合和动态 identifier fixture，不手拼 JSON。
- 对内置模型、调用方自有 data class、自定义 serializer 和具体 `Map<K, V>` serializer 覆盖通用 API；确认文件 API 不依赖三个
  内置模型。
- 覆盖 typed 与 JSON tree/text、NBT document/tag 的双向等价，并确认每个高级方法只触发一次对应 serializer/format 操作。
- 用 segmented source/sink 证明文件 typed 路径不先读取完整文本/tree/byte array；对 byte array、text 和 tree 入口分别验证它们调用
  序列化库对应表示的一步 API，不互相转换。
- 审查 advancement 根 serializer 不调用 JSON tree API；用大规模动态 key、任意字段顺序和小分段 source/sink 验证其 composite
  事件路径。
- 验证缺失必填 `DataVersion`、未知字段、错误 NBT 根和 malformed JSON 明确失败；raw tree round trip 保留未知字段。
- 对官方必写字段逐个验证缺失时失败且模型属性非空、无兼容默认；直接测试 advancement 根 serializer 的动态 key、字段顺序和大
  map，确认其他模型调用生成的 `serializer()`。

### Filesystem 与互操作测试

- 在 FakeFileSystem 上验证三个当前路径，且不创建或读取任何历史路径。
- 验证 level primary/previous/recovery 行为及 JSON final-path 写入策略未变。
- typed/tree/text/raw 对同一 logical file 共享 admission；用显式 gate 覆盖 writer preference、等待取消、同步 commit
  后取消和清理失败。
- 官方 fixture 让匹配版本实际生成玩家进度与统计文件；typed/raw 解码后只修改安全字段，重新启动官方服务端并确认其读取、保存、
  再次加载成功。
- 检查发布 metadata 和外部 consumer 编译，不引入无关运行时模块。

Gradle 命令严格串行：

1. `./gradlew -q minecraftVersion`
2. `./gradlew :world-format:jvmTest`
3. `./gradlew :world-io:jvmTest`
4. NBT 行为发生变化时运行 `./gradlew :nbt:jvmTest :nbt-serialization:jvmTest`
5. `./gradlew :world-format:jsNodeTest`
6. `./gradlew :world-io:jsNodeTest`
7. 当前主机适用的 desktop Native tests 和既有官方世界互操作任务
8. `git diff --check HEAD`

## 完成标准

- 第一版只提供 `LevelDat`、`PlayerAdvancements` 和 `PlayerStatistics` 三个推荐公开根模型，但通用 API 可接收调用方为这些文件选择的
  任意具体 serializer；路径全部匹配仓库所选版本。
- 不存在历史路径 fallback、旧 schema 容错或隐式 DataFixer/DataVersion 改写。
- 内置模型只表达匹配版本，官方必写字段非空且无缺失默认；版本升级 skill 明确要求逐一复审模型和 serializer。
- 文件 typed JSON/NBT 直接连接 stream；高级方法只薄包装 stream 或调用对应表示的一步序列化 API；tree/text/raw 无损入口仍可用。
- 三种表示共享正确的 world-io 文件策略、协调、取消与清理语义。
- focused KMP、官方互操作和独立消费验证通过，文档与最终 API 一致。
