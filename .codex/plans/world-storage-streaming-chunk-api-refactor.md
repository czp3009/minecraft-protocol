# 世界区块流式 API 与 palette-backed 模型重构计划

- 状态：设计已复审，等待实施
- 最近更新：2026-08-20
- 涉及模块：新增 `minecraft-model`、`minecraft-vanilla-data`、`world-model`，重构 `world-format`、
  `world-io`、`protocol-model`、`protocol-serialization` 和 `protocol-vanilla-data`
- 兼容性：不保留旧 API、类型别名、deprecated 转发层或并行实现
- 版本原则：所有 Chunk NBT、palette 策略和 vanilla 注册表行为只对应仓库所选官方 Minecraft 版本

## 1. 结论

本次重构采用“流式存储管线 + palette-backed 语义模型 + 显式生命周期”的方案。用户可以停在任意需要的层级， 而不必为了读取一个方块先把整个
Region 或若干无关的中间字节数组读入内存：

```text
.mca + 按需打开的 .mcc
  -> 有界的压缩 Chunk Source
    -> 有界的未压缩 NBT Source
      ├─> NbtDocument
      ├─> 调用方选择的 serializable value
      └─> palette-backed Chunk
            └─> 显式请求的 dense block volume
```

`NbtDocument` 和 semantic `Chunk` 是从同一个未压缩 NBT Source 分叉出的两个拥有值，前者不是后者的必经中间层。
`ChunkNbtCodec.decodeFromSource` 应直接消费该 Source；只有为了保留尚未建模的 NBT remainder 而确实需要的 tag 子树才进入
`Chunk`，不得先构造一份完整 `NbtDocument` 再长期与 semantic model 重复持有。

反向写入沿同一分层进行。真正无法直接落盘的语义/NBT 写入只暂存“一份压缩后的 Chunk”，因为 Anvil 必须先知道 压缩长度才能分配扇区并写
record header；不得同时保留完整未压缩字节数组，也不得为了求长度重复编码或默认落临时文件。 已经压缩且长度已知的调用则直接流向
`.mca` 或自动选择的 `.mcc`。

主用户路径固定为：打开 `MinecraftWorld`，显式 `openRegion` 或 `withRegion`，在同一个 `RegionHandle` 中连续随机访问
Chunk，再在已解码的 `Chunk` 中读写方块。`RegionHandle` 是资源句柄，不是 Region 文件内容快照；对于已存在的 Region，
打开句柄就打开并固定对应 `.mca` 文件句柄，随后一直复用到关闭。缺失但可写的 Region 只固定 pending entry，并在首次写入 时创建和打开
`.mca`。外部 Chunk 的 `.mcc`、压缩方式和 palette 布局在语义 API 中透明，但低层 API 仍允许观察压缩、 存储位置和时间戳。

## 2. 目标与非目标

### 2.1 必须完成

1. 从世界目录一路走通 `.mca`/`.mcc` 定位、随机访问、压缩流读取、解压、原始 NBT 解码、Chunk 语义解码、 palette-backed
   方块读写、重新编码和落盘。
2. 读取侧以 `Source` 为 canonical path，写入侧以 `Sink` 为 canonical path；返回 `ByteArray`、`NbtDocument`、
   `CompressedChunk` 或 `Chunk` 的方法只是这些路径的拥有值适配器。
3. 保留显式 Region 生命周期，让批量访问同一个 Region 的用户只打开一次 `.mca`；同时提供会自行打开/关闭 Region 的 one-shot
   便利方法，并清楚标注其重复调用成本。
4. `readChunk` 返回独立于文件句柄的、可变的、palette-backed `Chunk`。关闭 Region 后该值继续可用；它不持有
   `Source`、`FileHandle` 或映射文件。
5. `readChunkNbt` 返回原始 `NbtDocument`；另有借用未压缩 NBT `Source` 和借用压缩 Chunk `Source` 的 API。
6. 内存中的 block-state/biome 容器遵循官方服务端的 palette 行为：普通写入复用或追加 ID，不因旧值失去引用而删除、 缩小或重排
   palette；只有容量跨档时扩容并重编码；序列化时对一个快照做紧凑 packing，不改变运行时容器。
7. 不公开可变 palette 列表、value-to-id map 或 bit storage。用户可以访问逻辑容器、逻辑值、已用值集合以及不可变的
   统计/诊断快照，但不能绕过容器不变量修改内部编号。
8. 提供显式、非默认的 dense 转换，用于确实需要顺序扫描或与数组算法互操作的调用方。
9. 统一世界坐标类型和全部常用正反转换。README 只展示扩展属性、扩展函数、`contains` 和合理的运算符重载； 标量
   `CoordinateMath` 即使保留，也只作为底层实现和专家入口。
10. 消除 `RegionFile` 同时像内存快照、文件句柄和世界 Region 的命名冲突，消除 world 与 protocol 两套不兼容坐标， 并给网络专用
    palette/section 类型明确的网络名称。
11. 保持现有 world-io 的锁、提交顺序、关闭屏障、live read-only、外部 sidecar、取消与 durability 语义。

### 2.2 明确不做

- 不实现方块 ticking、光照传播、heightmap 自动重算、方块实体业务规则、区块生成或完整服务端 World。
- 不把整个 Region 映射成常驻内存对象作为默认路径，也不增加隐式的 idle Region cache。
- 不让调用方在普通写 API 中强制选择 inline 或 external；`.mcc` 阈值和 sidecar 提交顺序仍是存储策略。
- 不让 `Chunk` 暴露官方内部类的逐字段仿制 API。对外保证的是逻辑行为和序列化结果，不是 Mojang 私有实现类布局。
- 不迁移其他 Minecraft 版本的旧 Chunk NBT。数据版本不匹配时给出明确错误，调用方仍可使用 raw NBT 路径。
- 不为旧名称保留兼容别名或 deprecated overload；仓库尚处早期阶段，最终只留下新 API。

## 3. 已锁定的设计原则

### 3.1 方法命名与所有权

- `openX`：返回调用方拥有、必须关闭的资源。
- `withX`：在回调中借出资源或流，函数返回后立即关闭/失效，不允许逃逸。
- `readX`：返回与底层资源脱离的拥有值，因而可能分配内存。
- `writeX(value)`：消费一个完整逻辑值；若还有 `writeX { sink -> ... }`，前者必须委托后者。
- `decodeFromSource` / `encodeToSink`：filesystem-independent format/codec 的 canonical 方法。
- `decode` / `encode` 动词只用于 `world-format` 的 stream/value conversion；会访问真实文件的 `world-io` 方法统一使用
  `read` / `write` / `open` / `with`，不能把文件写入伪装成 `encodeChunkNbt`。
- `hasChunk` / `hasRegion`：磁盘存在性查询；几何归属统一用 `in`。
- `removeChunk`：从 Region header 移除一个 Chunk；`clearRegion` 保留一个合法空 `.mca`，不等同于删除文件。
- `replaceRegion`：完整替换，回调中未提供的位置会被清除。不得继续用含糊的 `writeRegion` 表示该语义。
- `readCompressedRegionSnapshot`：明确表示高内存的完整 detached compressed-content snapshot；`readRegion` 不再同时承担
  “打开句柄”和“读快照”。纯 `.mca` 的格式快照始终叫 `AnvilRegion`，不能和透明解析 `.mcc` 后的存储内容快照混用。

所有借用的 Chunk `Source` 都是有界流，必须在回调内消费到 EOF；未消费完、越界读取、回调结束后继续使用或写入长度
与声明不一致都应失败。库创建的 compression decorator 必须关闭以完成 checksum/trailer，但不得关闭调用方传入的底层
`Source`/`Sink`。协调式 world API 的外层方法是 `suspend`，借用同步 `Source`/`Sink` 的回调保持非 suspend，避免持有锁和
文件流跨任意挂起点。

### 3.2 各层职责

- `minecraft-model`：跨 world/protocol 共用、与物理格式无关的 `Identifier`、`BlockState` 和坐标值。
- `minecraft-vanilla-data`：仓库所选版本的通用静态注册表和 block-state ID 映射；不含连接协商或 packet codec。
- `world-model`：可独立使用的内存语义模型，包括 `Chunk`、`ChunkSection`、逻辑
  `PalettedContainer<T>` 和 dense 显式适配器；不依赖文件系统或 NBT 二进制格式。
- `world-format`：只负责 filesystem-independent 的“流与格式类型之间的转换”，包括 Anvil 容器、compression dispatch、 通用
  `CompressedNbtFormat`、仓库所选版本的 `ChunkNbtCodec` 和 standalone world-file schema；只接触
  `kotlinx.io.Source`/`Sink`，不接触 Path、FileSystem、FileHandle、文件名或目录布局。
- `world-io`：Okio 路径、`.mca`/`.mcc` 文件句柄、随机访问、生命周期、协调、durability、world directory 和 live reader；只组合
  `world-format` codec，不重复实现 Anvil、compression、NBT schema 或 palette 算法。
- `protocol-model`：packet 负载和明确标为 network 的 Chunk/palette 表示；复用 `minecraft-model` 坐标。
- `protocol-serialization`：坐标 packed wire encoding 和 network palette 的物理编码。
- `protocol-vanilla-data`：依赖 `minecraft-vanilla-data`，保留 Configuration capture、Known Packs、feature flags、 tags 和
  protocol registry projection，不再独占通用 block-state catalogue。

`world-format` 与 `world-io` 的逐类职责固定如下：

| 类/类型                                                                           | 所属模块       | 负责                                                                                                   | 明确不负责                                                       |
|-----------------------------------------------------------------------------------|----------------|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `CompressionCodec` / `CompressionCodecs`                                          | `world-format` | 把 caller-owned `Source`/`Sink` 装饰为压缩/解压流；compression dispatch 和 framing validation          | 文件、路径、sidecar、写入策略                                    |
| `CompressedNbtFormat`                                                             | `world-format` | `compressed Source/Sink ↔ NbtDocument/serializer` 的流式组合，并正确关闭 decorator                     | 查找 Chunk、选择 `.mca`/`.mcc`、timestamp                        |
| `ChunkNbtCodec`                                                                   | `world-format` | `uncompressed binary NBT Source/Sink ↔ Chunk`，包括所选版本 schema、palette packing 和 remainder       | compression、sector、文件句柄、锁                                |
| `AnvilRegionFormat`                                                               | `world-format` | `.mca Source/Sink ↔ Anvil records/snapshot`，header/record/sector/external marker 的结构校验           | sibling `.mcc` 路径解析、random-access `FileHandle`、原子提交    |
| `AnvilRegionHeader` 等 primitives                                                 | `world-format` | header、location、record prefix、sector allocation 的纯值和 byte conversion                            | 打开、resize、flush 真实文件                                     |
| `AnvilFormatException` / `CompressionFormatException` / `ChunkNbtFormatException` | `world-format` | 分别报告 Anvil structure、compression framing、selected-release Chunk schema 错误                      | 包装或伪装 filesystem failure                                    |
| `RegionFile`                                                                      | `world-io`     | 打开一个实际 `.mca`，用 positional `FileHandle` random access，并按 absolute Chunk 坐标透明打开 `.mcc` | 自己解析 Anvil bytes、自己实现 compression/NBT codec、跨实例协调 |
| `RegionStorage`                                                                   | `world-io`     | 绑定 dimension/kind 目录，并经所属 world 的共享 coordinator 打开/创建/释放同 key Region entry          | 表示 Region 内容、Chunk schema 或独立 lifecycle owner            |
| `RegionRecordHandle`                                                              | `world-io`     | 调用方拥有的协调式 compressed/raw-NBT Region record 生命周期，组合 `RegionFile` 与 format codecs       | semantic block API、kind 的运行时能力判断                        |
| `RegionHandle`                                                                    | `world-io`     | chunk-directory 专用 facade，在 record 能力上组合 `ChunkNbtCodec` 提供 semantic Chunk                  | 持有整个 Region 内容、暴露 palette backing                       |
| `MinecraftWorld`                                                                  | `world-io`     | world paths、`session.lock`、dimension facade、Region lifecycle 和 one-shot delegation                 | Anvil/NBT/compression 算法                                       |
| `LiveMinecraftWorldReader` / `LiveRegionRecordHandle` / `LiveRegionHandle`        | `world-io`     | 不加锁、不修改的文件观察与相同 format 组合                                                             | 一致性、修复、延迟官方写入                                       |

配置和上下文也不能成为跨层杂物箱：

- `ChunkNbtContext` 属于 `world-format`，只携带一次 Chunk NBT 转换所需的 layout、default values 和 registry 映射，不含
  `Path`、dimension directory 或 world lifecycle。
- `RegionStorageConfiguration` 属于 `world-io`，只组合 Region 的 `syncWrites`、默认写 compression 以及要调用的
  `CompressedNbtFormat`。后者公开其 `SerializersModule`，使 reified Kotlin 扩展可以只委托显式 serializer overload。
- `ChunkNbtContextResolver` 属于 `world-io`，负责把 `WorldDimension` 和 world configuration 解析成 `ChunkNbtContext`；它不
  解析 NBT。
- `MinecraftWorldConfiguration` 属于 `world-io`，聚合 standalone-file formats、`RegionStorageConfiguration`、
  `ChunkNbtCodec` 和 `ChunkNbtContextResolver`。generic `RegionStorage` 不携带 `ChunkNbtCodec`；只有 chunk-directory
  `RegionHandle` 由 `MinecraftWorld` 注入 semantic 能力。
- `LiveMinecraftWorldReaderConfiguration` 复用相同只读 format/codec/context 选择，但不含 `syncWrites`、默认写 compression
  或 durability 选项。

代码审查时用以下硬边界判断归属：

- 只要函数的全部行为（包括 receiver 所需状态）能够在没有 filesystem resource 的情况下完全表达为 `Source`/`Sink`、 format
  configuration 和普通值，它就属于 `world-format`。
- 只要函数需要 `Path`、`FileSystem`、`FileHandle`、文件名、目录、random access、锁、flush、replace 或 close coordination， 它就属于
  `world-io`。
- `world-io` 可以调用 `AnvilRegionHeader.decode`、`CompressedNbtFormat.withDecompressedSource` 或
  `ChunkNbtCodec.decodeFromSource`，但不得复制其 byte/tag 解析。
- format/schema/compression exceptions 穿过 `world-io` 保持原类型；`WorldIOException` 只用于真实 filesystem/path failure。
- `world-format` 可以报告 external marker，却不能猜测或打开 `c.<x>.<z>.mcc`；只有掌握目录和 absolute Chunk 坐标的
  `world-io.RegionFile` 能解析 sidecar。

按最初五步流程映射后，边界应当没有灰区：

| 流程步骤                                              | owning module                            | owning API                                                                    |
|-------------------------------------------------------|------------------------------------------|-------------------------------------------------------------------------------|
| 打开 `.mca`，命中 external 时按需打开 `.mcc`          | `world-io`                               | `RegionFile.open*`、`withCompressedChunkSource`                               |
| 由 Region local offset 做 header/sector random access | `world-io` 调度、`world-format` 解释格式 | `RegionFile` 调用 `AnvilRegionHeader` / `AnvilChunkRecordHeader` primitives   |
| 借出压缩后的 Chunk 二进制流                           | `world-io`                               | `RegionFile` / `RegionRecordHandle.withCompressedChunkSource`                 |
| 按 compression 解压成一个完整 binary NBT Source       | `world-format` 转换、`world-io` 组合     | `CompressedNbtFormat.withDecompressedSource`，由 `withChunkNbtSource` 暴露    |
| 按所选版本 palette schema 解码/编码并读写方块         | `world-format` 转换到 `world-model`      | `ChunkNbtCodec`；得到 `Chunk` 后由 `Chunk`/`PalettedContainer` 完成纯内存访问 |

命名后缀也固定表达职责：`*Format` 表示物理 stream framing/composition，`*Codec` 表示已定义格式与领域模型的映射，
`*File` 表示一个实际打开文件，`*Storage` 表示目录和多文件管理，`*Handle` 表示调用方拥有的生命周期引用，`*Scope`
表示仅在回调内有效的借用对象，`*Info` 表示只读观察，`*Snapshot` 表示完整 detached 值。

发布依赖保持单向：

```text
world-io -> world-format
world-format -> world-model -> minecraft-model
world-format -> minecraft-vanilla-data -> minecraft-model

protocol-serialization -> protocol-model -> minecraft-model
protocol-vanilla-data -> protocol-model
protocol-vanilla-data -> minecraft-vanilla-data
```

箭头指向依赖项。`world-io` 通过 `world-format` 的 public signatures 取得 `world-model`；`world-format` 依赖 NBT 层及 作为默认
registry 来源的 `minecraft-vanilla-data`。实现时应在 Gradle metadata 中按真实 public 签名复核，不因示意图 省略 `nbt`/
`nbt-serialization` 就增加反向依赖。

实现时必须核查所有 public/protected 签名并据此选择 `api`/`implementation`。不得为了方便让 `world-model` 依赖
`protocol-model`，也不得让 `world-format` 依赖 `world-io` 或 Okio。

## 4. 最终数据层级与 API 表面

每一层的 canonical stream 与拥有值必须形成固定的一对，不允许某个拥有值另起一条实现管线：

| 层级                         | canonical read/write                                                                 | 可选拥有值                             | 必要内存代价                             |
|------------------------------|--------------------------------------------------------------------------------------|----------------------------------------|------------------------------------------|
| portable `.mca`              | `AnvilRegionFormat.readRecordsFromSource` / 已知 record metadata 的 streaming encode | `AnvilRegion`                          | 仅显式 snapshot 才保留全 Region          |
| random-access stored payload | `withCompressedChunkSource` / `writeCompressedChunk(..., byteCount) { sink }`        | `CompressedChunk`                      | 拥有值保留一份 compressed bytes          |
| decompressed binary NBT      | `withChunkNbtSource` / `writeChunkNbt { sink }`                                      | `NbtDocument` 或调用方 serializer 的值 | 只为所选值分配；raw Source 不建树        |
| semantic Chunk NBT           | `world-format` 的 `ChunkNbtCodec.decodeFromSource` / `encodeToSink`                  | palette-backed `Chunk`                 | Chunk 本身及需要保留的 NBT remainder     |
| block data                   | `PalettedContainer` logical iterator/index API                                       | `DenseBlockStateVolume`                | dense 只在显式转换时分配 4096 项/section |

这里的 “streaming” 指输入输出不要求完整 payload byte array，并不表示本质上是随机访问的数据结构不占内存。
`NbtDocument`、`Chunk`、palette storage 和 dense volume 都是调用方明确选择的内存值。若 payload 大到平台 `ByteArray`
无法表示，`readCompressedChunk` 可以因值类型容量而失败，但 `withCompressedChunkSource` 不得因此增加同样的策略上限。

### 4.1 world-format 转换 API

`world-format` 的主要公开转换器固定为三组，方法名能直接判断输入输出和是否物化：

```kotlin
class CompressedNbtFormat {
    val serializersModule: SerializersModule

    fun <R> withDecompressedSource(
        source: Source,
        compression: Compression,
        block: (Source) -> R,
    ): R

    fun <R> withCompressingSink(
        sink: Sink,
        compression: Compression,
        block: (Sink) -> R,
    ): R

    fun decodeDocumentFromSource(source: Source, compression: Compression): NbtDocument
    fun encodeDocumentToSink(document: NbtDocument, compression: Compression, sink: Sink)
    fun <T> decodeFromSource(deserializer: DeserializationStrategy<T>, source: Source, compression: Compression): T
    fun <T> encodeToSink(serializer: SerializationStrategy<T>, value: T, compression: Compression, sink: Sink)
}

class ChunkNbtCodec {
    fun decodeFromSource(source: Source, context: ChunkNbtContext): Chunk
    fun encodeToSink(chunk: Chunk, sink: Sink, context: ChunkNbtContext)
}

sealed class AnvilRegionFormat {
    fun readRecordsFromSource(source: Source, block: (AnvilChunkRecordReadScope) -> Unit)
    fun decodeFromSource(source: Source): AnvilRegion
    fun encodeRecordsToSink(
        records: Collection<AnvilChunkRecordSource>,
        regionSink: Sink,
        externalChunkSink: AnvilExternalChunkSink,
    )
    fun encodeToSink(
        region: AnvilRegion,
        regionSink: Sink,
        externalChunkSink: AnvilExternalChunkSink,
    )
    fun encodeToSnapshot(region: AnvilRegion): EncodedAnvilRegion
    fun decodeFromSnapshot(snapshot: EncodedAnvilRegion): AnvilRegion
}

sealed interface AnvilChunkRecordReadScope {
    val position: RegionChunkOffset
    val compression: Compression
    val timestampSeconds: Int

    class Inline(
        override val position: RegionChunkOffset,
        override val compression: Compression,
        override val timestampSeconds: Int,
        val compressedByteCount: Long,
        val source: Source,
    ) : AnvilChunkRecordReadScope

    class ExternalReference(
        override val position: RegionChunkOffset,
        override val compression: Compression,
        override val timestampSeconds: Int,
    ) : AnvilChunkRecordReadScope
}

interface AnvilChunkRecordSource {
    val position: RegionChunkOffset
    val compression: Compression
    val compressedByteCount: Long
    val placement: ChunkPlacement
    val timestampSeconds: Int
    fun <R> withSource(block: (Source) -> R): R
}

interface AnvilExternalChunkSink {
    fun <R> withSink(
        position: RegionChunkOffset,
        compressedByteCount: Long,
        block: (Sink) -> R,
    ): R
}
```

- `CompressedNbtFormat` 只组合两个已有 format 层：compression decorator 与 binary NBT；document/serializer 方法分别委托
  `withDecompressedSource`/`withCompressingSink`。
- `ChunkNbtCodec` 只处理未压缩 binary NBT 和 semantic `Chunk`，不接受 `Path`、`RegionPosition` 或 compression 参数。
- `AnvilRegionFormat` 只处理完整顺序 `.mca` stream；random access 不在该类中模拟。
- 所有方法都不关闭 caller-owned 最外层 Source/Sink；借出的 decorator/record Source 只在 callback 内有效并要求完整消费。
- Chunk NBT Source/Sink 始终表示恰好一个所选版本要求 root mode 的完整 binary NBT document；不能把它当任意解压 bytes，
  也不能在一个 callback 中串接多个 document。
- `AnvilChunkRecordReadScope.Inline` 才借出有界 compressed payload Source；
  `AnvilChunkRecordReadScope.ExternalReference` 只报告 `.mca` marker，绝不借出一个会被误认成真实 payload 的空 Source。
- `AnvilChunkPayload` 明确分为 `Inline`、已解析且拥有 sidecar bytes 的 `External` 和只有 marker 的
  `ExternalReference`。`decodeFromSource` 可产生 `ExternalReference`；`encodeToSink` 要求所有 external payload 已解析。
- `encodeToSnapshot`/`decodeFromSnapshot` 是显式收集 `.mca` 和全部 sidecar byte arrays 的拥有值适配器；普通
  `encodeToSink` 始终要求调用方提供 `AnvilExternalChunkSink`，不会因方法签名偷偷收集 sidecar。
- `AnvilChunkRecordSource` 是纯格式 encoder 的 exact input，所以包含 timestamp/placement；后文供 `world-io` 普通替换使用的
  `CompressedChunkSource` 刻意不含这两项。两者不能为了减少类型数量而合并。

### 4.2 存储层值

`CompressedChunk` 只表示可重新写入的逻辑内容：

```kotlin
class CompressedChunk(
    val compression: Compression,
    bytes: ByteArray,
) {
    val compressedByteCount: Int
    fun toByteArray(): ByteArray
}

interface CompressedChunkSource {
    val position: RegionChunkOffset
    val compression: Compression
    val compressedByteCount: Long
    fun <R> withSource(block: (Source) -> R): R
}

class CompressedRegionSnapshot(
    chunks: Map<RegionChunkOffset, CompressedChunk>,
) {
    val chunks: Map<RegionChunkOffset, CompressedChunk>
    operator fun get(position: RegionChunkOffset): CompressedChunk?
}
```

构造时和导出时都做防御性复制，内容相等使用 byte-content equality。它不包含 timestamp 或 inline/external 选择；这些是
一次读取的存储元数据：

```kotlin
enum class ChunkPlacement { INLINE, EXTERNAL }

data class StoredChunkInfo(
    val compression: Compression,
    val compressedByteCount: Long,
    val placement: ChunkPlacement,
    val timestampSeconds: Int,
)
```

`readCompressedChunk` 是 `withCompressedChunkSource` 读取全部 payload 的适配器。需要 metadata 的用户使用
`readStoredChunkInfo` 或 callback 参数；写回 `CompressedChunk` 时由 world-io 重新选择 placement 并生成时间戳。
`ChunkPlacement` 是一次落盘结果，不叫 `ChunkStorage`，避免与负责目录/生命周期的 `RegionStorage` 混淆。
`CompressedChunk`、`CompressedChunkSource`、`CompressedRegionSnapshot`、`Compression` 和 `ChunkPlacement` 是不依赖 文件的
format 值，定义在 `world-format`；包含实际文件 timestamp 的 `StoredChunkInfo` 定义在 `world-io`。
`CompressedRegionSnapshot` 是防御性拥有的 local-position -> compressed-content 映射，不含 timestamp/placement；
`CompressedChunkSource` 同样不含 placement/timestamp，因而两者都可安全作为 world-io replacement 的输入，而不会让普通 写
API 绕过存储策略。

### 4.3 world-io RegionFile API

`RegionFile` 是最底层真实文件 API，方法保持同步并只处理 stored compressed payload：

```kotlin
class RegionFile {
    val position: RegionPosition
    val path: Path

    companion object {
        fun openExisting(path: Path, fileSystem: FileSystem, syncWrites: Boolean = true): RegionFile?
        fun openOrCreate(path: Path, fileSystem: FileSystem, syncWrites: Boolean = true): RegionFile
    }

    fun hasChunk(position: RegionChunkOffset): Boolean
    fun readStoredChunkInfo(position: RegionChunkOffset): StoredChunkInfo?
    fun <R> withCompressedChunkSource(
        position: RegionChunkOffset,
        block: (StoredChunkInfo, Source) -> R,
    ): R?
    fun readCompressedChunk(position: RegionChunkOffset): CompressedChunk?

    fun writeCompressedChunk(position: RegionChunkOffset, chunk: CompressedChunk)
    fun writeCompressedChunk(
        position: RegionChunkOffset,
        compression: Compression,
        compressedByteCount: Long,
        block: (Sink) -> Unit,
    )

    fun removeChunk(position: RegionChunkOffset)
    fun readCompressedRegionSnapshot(): CompressedRegionSnapshot
    fun replaceRegion(chunks: Collection<CompressedChunkSource>)
    fun replaceRegion(snapshot: CompressedRegionSnapshot)
    fun clearRegion()
    fun flush()
    fun close()
}
```

- mutable `RegionFile.openExisting(...)` 打开已经存在的 `.mca`，缺失返回 null；`openOrCreate(...)` 在缺失时立即创建合法
  header。pending 状态只存在于尚未构造 `RegionFile` 的 `RegionStorage` entry 中，高层首次写入时才调用 `openOrCreate`。
  `RegionFile` 始终表示真实已打开且可写的文件并由调用方关闭，不通过一个 runtime access-mode flag 暴露必然失败的写方法。
- 无修改能力的 exact-file 类型是 `LiveRegionFile`：它只提供上述 read/stream/snapshot 子集，`openExisting(...)` 在缺失时返回
  null。它和 `LiveMinecraftWorldReader` 一样不承诺协调一致性；若调用方能保证文件静止，也可把它作为普通只读 reader 使用。
- 它根据 `AnvilRegionHeader` 做 positional access，并透明借出 `.mcc` payload，但没有
  `withChunkNbtSource`、`readChunkNbt` 或 `readChunk`。
- 它不做跨实例同步；同一路径的 reader/writer/close 排他关系由上层 `RegionStorage` 负责，或由直接使用者自行保证。
- 完整 `readCompressedRegionSnapshot` 委托 record read stream，并透明读取所需 `.mcc`；`replaceRegion(chunks)` 是已知
  compression/length + callback Source 的 streaming replacement，并由 `RegionFile` 自动生成 timestamp/placement；
  `replaceRegion(snapshot)` 只把拥有值适配成这些 Source 后委托它。纯 format 的 `AnvilRegion` 不进入这组存储 API；任何完整
  Region 方法都不能成为单 Chunk 实现的中间步骤。

### 4.4 RegionHandle 主 API

最终主表面以如下签名族为准；实际实现可把共同逻辑提取为 internal 接口，但不得重新生成当前那种每层、每坐标、每 serializer 全排列的
overload 集合：

```kotlin
class RegionHandle {
    val position: RegionPosition
    val configuration: RegionStorageConfiguration

    suspend fun hasChunk(position: RegionChunkOffset): Boolean
    suspend fun readStoredChunkInfo(position: RegionChunkOffset): StoredChunkInfo?

    suspend fun <R> withCompressedChunkSource(
        position: RegionChunkOffset,
        block: (StoredChunkInfo, Source) -> R,
    ): R?

    suspend fun readCompressedChunk(position: RegionChunkOffset): CompressedChunk?

    suspend fun <R> withChunkNbtSource(
        position: RegionChunkOffset,
        block: (Source) -> R,
    ): R?

    suspend fun readChunkNbt(position: RegionChunkOffset): NbtDocument?
    suspend fun <T> readChunkNbt(
        position: RegionChunkOffset,
        deserializer: DeserializationStrategy<T>,
    ): T?

    suspend fun readChunk(position: RegionChunkOffset): Chunk?

    suspend fun writeCompressedChunk(position: RegionChunkOffset, chunk: CompressedChunk)
    suspend fun writeCompressedChunk(
        position: RegionChunkOffset,
        compression: Compression,
        compressedByteCount: Long,
        block: (Sink) -> Unit,
    )

    suspend fun writeChunkNbt(
        position: RegionChunkOffset,
        document: NbtDocument,
        compression: Compression = configuration.writeCompression,
    )

    suspend fun writeChunkNbt(
        position: RegionChunkOffset,
        compression: Compression = configuration.writeCompression,
        block: (Sink) -> Unit,
    )

    suspend fun <T> writeChunkNbt(
        position: RegionChunkOffset,
        serializer: SerializationStrategy<T>,
        value: T,
        compression: Compression = configuration.writeCompression,
    )

    suspend fun writeChunk(
        position: RegionChunkOffset,
        chunk: Chunk,
        compression: Compression = configuration.writeCompression,
    )

    suspend fun removeChunk(position: RegionChunkOffset)
    suspend fun flush()
    suspend fun close()
}
```

`RegionRecordHandle` 使用完全相同的名称和委托链，但 API 截止在 compressed/raw NBT：它包含 `hasChunk`、stored info、
compressed stream/value、NBT stream/document/typed value、replacement、`removeChunk`、`flush` 和 `close`，不包含 semantic
`readChunk`/`writeChunk`。`RegionHandle` 直接暴露该 record 能力并增加 semantic 两个方法，用户不需要写
`region.raw...`；实现通过 internal delegation 共享代码，不通过复制 method body 保持“看起来一致”。

绝对 `ChunkPosition` overload 只在它显著改善可读性的位置作为扩展提供，并统一验证 `position in region.position`； Region
receiver 的核心成员只接收 `RegionChunkOffset`。这样既不会重复所有实现，也能让“已经算好 Region local index”的 高性能调用方避免重复换算。
`writeChunk(offset, chunk)` 还必须验证 `chunk.position == region.position + offset`，不能悄悄把 semantic Chunk 重定位。

读取实现链固定为：

- `readCompressedChunk` 调用 `withCompressedChunkSource` 并只分配压缩 bytes。
- `withChunkNbtSource` 调用 `withCompressedChunkSource`，再委托
  `CompressedNbtFormat.withDecompressedSource` 根据 `StoredChunkInfo.compression` 借出解压 Source；world-io 自己不实现
  decompressor。
- `readChunkNbt` 调用 `withChunkNbtSource` 和 `NbtFormat.decodeDocumentFromSource`。
- typed `readChunkNbt` 调用 `withChunkNbtSource` 和调用方 deserializer。
- `readChunk` 调用 `withChunkNbtSource` 和 `ChunkNbtCodec.decodeFromSource`，不先构造 `CompressedChunk`。

写入实现链固定为：

- 长度已知的 `writeCompressedChunk { sink }` 是最终随机写 canonical primitive。
- `writeCompressedChunk(value)` 只把持有 bytes 转发给该 primitive。
- `writeChunkNbt { sink }` 借给用户的是未压缩 NBT sink；world-io 委托
  `CompressedNbtFormat.withCompressingSink`，把 compressor 输出一次写入一个 growable buffer，关闭 decorator 后取得精确
  压缩长度，再把 buffer 流给 `writeCompressedChunk`。
- `writeChunkNbt(document)`、typed `writeChunkNbt` 和 `writeChunk` 分别把 document、serializer 或 `ChunkNbtCodec` 输出
  接到上述未压缩 NBT sink；不得先创建完整 plain-NBT `ByteArray`。

需要严格限制峰值内存的调用方可以先用 `CompressedNbtFormat.withCompressingSink` 流到自己选择的 storage，取得长度后再用
`writeCompressedChunk(..., compressedByteCount) { sink -> ... }` 回放；高层 API 不偷偷创建临时文件，也不把这种策略强加给
普通调用方。

serializer 便利调用作为扩展而不是核心成员的 overload 笛卡尔积提供：

```kotlin
suspend inline fun <reified T> RegionHandle.readChunkNbtAs(position: RegionChunkOffset): T?

suspend inline fun <reified T> RegionHandle.writeChunkNbt(
    position: RegionChunkOffset,
    value: T,
    compression: Compression = configuration.writeCompression,
)
```

`readChunkNbtAs<T>` 使用 `As` 是为了避免与无额外参数的 `readChunkNbt(position): NbtDocument?` 成员发生 Kotlin
member-over-extension shadowing。便利扩展只从 `configuration.compressedNbtFormat.serializersModule` 取得 serializer，再委托
显式 strategy overload。 相同原则用于 `RegionRecordHandle`、`MinecraftWorld` one-shot 和 live 读取扩展；不得复制 codec 或
I/O 实现。

### 4.5 MinecraftWorld 与 Region 生命周期

`RegionStorage` 表示一个已经确定 dimension directory 和 `RegionDataKind` 的多文件 store。它不表示任何 Region 内容：

```kotlin
class RegionStorage {
    val dimension: WorldDimension
    val dataKind: RegionDataKind

    suspend fun hasRegion(position: RegionPosition): Boolean
    suspend fun openRegion(position: RegionPosition): RegionRecordHandle
    suspend fun <R> withRegion(
        position: RegionPosition,
        block: suspend RegionRecordHandle.() -> R,
    ): R
}
```

它是所属 `MinecraftWorld` 拥有的轻量 facade；同 key reader/writer coordination、entry table、pending-file 状态和最终 close
都由 world 的共享 coordinator 持有，`RegionRecordHandle` 只拥有一次 caller-visible pin。storage 在 world 关闭后失效，自己
不提供 `close`，因此不会出现“关闭一个 facade 意外关闭其他 dimension”的所有权歧义。`RegionStorage` 不提供
`readChunk`，也不把 `RegionDataKind` 继续传入每个 handle 方法。

主 world 生命周期 API：

```kotlin
suspend fun <R> withMinecraftWorld(
    root: Path,
    configuration: MinecraftWorldConfiguration = MinecraftWorldConfiguration(),
    block: suspend MinecraftWorld.() -> R,
): R

class MinecraftWorld private constructor() {
    val configuration: MinecraftWorldConfiguration

    companion object {
        fun open(
            root: Path,
            configuration: MinecraftWorldConfiguration = MinecraftWorldConfiguration(),
        ): MinecraftWorld
    }

    fun regionStorage(
        dataKind: RegionDataKind,
        dimension: WorldDimension = WorldDimension.Overworld,
    ): RegionStorage

    suspend fun openRegion(
        position: RegionPosition,
        dimension: WorldDimension = WorldDimension.Overworld,
    ): RegionHandle

    suspend fun <R> withRegion(
        position: RegionPosition,
        dimension: WorldDimension = WorldDimension.Overworld,
        block: suspend RegionHandle.() -> R,
    ): R

    suspend fun readChunk(
        position: ChunkPosition,
        dimension: WorldDimension = WorldDimension.Overworld,
    ): Chunk?

    suspend fun readChunkNbt(
        position: ChunkPosition,
        dimension: WorldDimension = WorldDimension.Overworld,
    ): NbtDocument?

    suspend fun writeChunk(
        position: ChunkPosition,
        chunk: Chunk,
        dimension: WorldDimension = WorldDimension.Overworld,
        compression: Compression = configuration.regionStorage.writeCompression,
    )

    suspend fun writeChunkNbt(
        position: ChunkPosition,
        document: NbtDocument,
        dimension: WorldDimension = WorldDimension.Overworld,
        compression: Compression = configuration.regionStorage.writeCompression,
    )

    suspend fun close()
}
```

`MinecraftWorld.open(root, configuration)` 是调用方拥有资源的工厂；`withMinecraftWorld` 只是在 `finally` 中调用其
`close` 的 Kotlin 便利封装。两者不得维护不同的初始化路径。
`regionStorage(dataKind, dimension)` 只返回共享 world coordinator 的轻量目录 facade，不打开文件；真正的 `.mca` 生命周期
仍从该 facade 或 world convenience 的 `openRegion`/`withRegion` 开始。

README 的首要示例应能保持如下形状：

```kotlin
withMinecraftWorld(root) {
    val target = BlockPosition(-17, 64, 33)

    withRegion(target.region) {
        val chunkOffset = target.chunk.offsetInRegion
        val chunk = readChunk(chunkOffset) ?: return@withRegion

        val previous = chunk[target]
        chunk[target] = BlockState(Identifier("stone"))
        writeChunk(chunkOffset, chunk)
    }
}
```

坐标和纯内存容器适合 `+`、`in`、`get`、`set` 等运算符；磁盘 I/O 不定义 `region[offset]` 之类会隐藏挂起、失败和分配成本的
operator，继续使用 `readChunk`、`withChunkNbtSource`、`writeChunk` 等具名方法。这也是 Kotlin 风味的一部分：语法糖只用于
没有意外副作用且含义唯一的操作。

- `openRegion` 在协调式可写 world 中即使文件尚不存在也返回句柄；首次读取返回 null，首次写入才创建目录和 `.mca`。
- 句柄 pin 住一个内部 Region entry；现有 `.mca` 在 `openRegion` 时打开并复用，关闭前不进入 idle cache；缺失文件仅在
  首次写入时创建。
- `withRegion` 永远在 `finally` 中关闭句柄。README 的批量示例以它为主。
- world 级 `readChunk`/`writeChunk` 和 `readChunkNbt`/`writeChunkNbt` 是 one-shot 拥有值便利方法，只接收绝对坐标，并明确
  委托一次 `withRegion`。compressed Source/Sink 和批量 scope 不在 world 级复制一整套 overload；需要它们时显式固定 Region。
- `MinecraftWorld.close` 先封住新调用，等待已经 admitted 的 world/region 操作，按现有 failure-combining 和
  `NonCancellable` 清理规则释放资源。
- live 模式对应 `LiveMinecraftWorldReader`、通用 `LiveRegionRecordHandle` 和 chunk-directory 专用
  `LiveRegionHandle`。它们共享相同读取层级和命名，但没有写方法、`session.lock` 或一致性承诺；缺失的 live Region 仍返回 null。

对于 `entities` 和 `poi` Region，不在 `RegionHandle.readChunk` 中加入运行时 kind 判断。语义 Chunk API 只面向
`WorldDimension` 的 `region/` 目录；通用低层目录访问通过 `MinecraftWorld.regionStorage(RegionDataKind, dimension)`
取得 `RegionStorage`，其 `openRegion`/`withRegion` 返回 `RegionRecordHandle`，只暴露 compressed/raw NBT 层。
`MinecraftWorld.openRegion` 返回的 `RegionHandle` 是 chunk-directory 专用 facade，在相同 record 能力上增加 semantic
`readChunk`/`writeChunk`。两种 handle 可以共享 internal implementation，但不通过 kind 检查让错误能力出现在 public API。
`RegionDataKind` 取代把路径细节泄漏到每个高层方法的 `RegionStorageDirectory`。

### 4.6 批量和快照 API

保留比单 Chunk 调用更低的批量控制，但命名必须暴露代价和替换语义：

```kotlin
class RegionRecordHandle {
    suspend fun <R> withReadScope(block: RegionRecordReadScope.() -> R): R
    suspend fun replaceRegion(block: RegionRecordReplacementScope.() -> Unit)
    suspend fun readCompressedRegionSnapshot(): CompressedRegionSnapshot
    suspend fun replaceRegion(snapshot: CompressedRegionSnapshot)
}

class RegionHandle {
    suspend fun <R> withReadScope(block: RegionReadScope.() -> R): R
    suspend fun replaceRegion(block: RegionReplacementScope.() -> Unit)
    suspend fun readCompressedRegionSnapshot(): CompressedRegionSnapshot
    suspend fun replaceRegion(snapshot: CompressedRegionSnapshot)
}
```

- `RegionRecordHandle.withReadScope { ... }`：在一次协调读取和一个 header snapshot 中读取多个位置，receiver 为
  `RegionRecordReadScope`，提供 compressed/raw-NBT 读取梯级。
- `RegionHandle.withReadScope { ... }`：receiver 为 `RegionReadScope`，复用 record 能力并增加 semantic `readChunk`。
- `RegionRecordHandle.replaceRegion { ... }`：receiver 为 `RegionRecordReplacementScope`，提供 compressed/raw-NBT
  streaming writes；每个位置最多写一次，未写位置清除。
- `RegionHandle.replaceRegion { ... }`：receiver 为 `RegionReplacementScope`，在 record replacement 上增加 semantic
  `writeChunk`；所有新 record staged 后一次提交完整 header。
- `RegionRecordHandle`/`RegionHandle.readCompressedRegionSnapshot()`：显式读出 detached
  `CompressedRegionSnapshot`；它透明解析 `.mcc`，但不把 timestamp/placement 变成可写策略。这是诊断、复制或批量变换 API， 不出现在
  README 主流程。
- 两种 handle 的 `replaceRegion(snapshot: CompressedRegionSnapshot)`：完整值适配器，委托对应的 streaming replacement
  scope。纯格式 `AnvilRegion` 只由 `world-format.AnvilRegionFormat` 消费，不能在这里成为第二种含义不同的 snapshot。

四个 scope 都只在 callback 内有效；`withReadScope` 和 `replaceRegion` 的外层是 `suspend`，但它们借出的 receiver callback
保持非 suspend，避免把 shared/exclusive file admission 带过任意挂起点。semantic scope 通过 delegation 扩展 record scope，
不复制 method body。positions 使用 lazy `Sequence<RegionChunkOffset>` 或 `forEachChunk`，不先建立额外 List。内部始终从同一个
`FileHandle` random access， 不会因“批量”把 Region 内容整体读入内存。

第一版不新增长期持有锁的公开 `RegionEditSession`。多次增量写已经复用同一个 Region handle；每次 header commit 保留明确
durability 边界。若以后有“一次 header commit、未提及位置保持不变”的真实需求，应新增语义明确的 `editRegion`，而不是 改变
`replaceRegion`。

## 5. semantic Chunk 与 palette

### 5.1 类型

`BlockState` 属于 `minecraft-model`，其余核心类型属于 `world-model`：

```kotlin
data class BlockState(
    val block: Identifier,
    val properties: Map<String, String> = emptyMap(),
)

class PalettedContainer<T> : Iterable<T> {
    val size: Int
    operator fun get(index: Int): T
    operator fun set(index: Int, value: T)
    override fun iterator(): Iterator<T>
    fun copy(): PalettedContainer<T>
    fun forEachDistinctValue(block: (T) -> Unit)
    fun distinctValues(): Set<T>
}

class ChunkSection {
    val position: SectionPosition
    val blockStates: PalettedContainer<BlockState>
    val biomes: PalettedContainer<Identifier>

    operator fun get(position: SectionBlockOffset): BlockState
    operator fun set(position: SectionBlockOffset, value: BlockState)
    fun toDenseBlockStates(): DenseBlockStateVolume
}

class Chunk {
    val position: ChunkPosition
    val layout: ChunkLayout
    val defaultBlockState: BlockState
    val defaultBiome: Identifier
    val sections: Collection<ChunkSection>

    operator fun get(position: SectionPosition): ChunkSection?
    operator fun get(position: BlockPosition): BlockState
    operator fun set(position: BlockPosition, value: BlockState)
    operator fun get(position: ChunkBlockOffset, y: Int): BlockState
    operator fun set(position: ChunkBlockOffset, y: Int, value: BlockState)
    fun section(sectionY: Int): ChunkSection?
    fun getOrCreateSection(sectionY: Int): ChunkSection
}
```

`BlockState.block` 明确表示方块 identifier，不能命名为 `id`，以免和 global block-state numeric ID 或 palette ID 混淆。
构造时复制 property map 并使用稳定、不可变的逻辑值；`Chunk` 和 `PalettedContainer` 是调用方拥有的可变值，不承诺 内部线程安全。
`Chunk[absoluteBlock]` 和 `Chunk[absoluteSection]` 必须验证其水平位置属于 Chunk。`ChunkNbtContext` 在 decode 时把
layout/default block/default biome 作为普通值交给 `Chunk`，因此 `world-model` 不反向依赖 `world-format`。 缺失 section 的
block 读取使用 `Chunk.defaultBlockState`；写入会创建最小需要的 section，但必须遵守 `Chunk.layout`。 不会发生分配的 section
查询可以用 operator；会创建 section 的操作必须保留 `getOrCreateSection` 具名方法，不能藏在
`get` 中。

`Chunk` 必须覆盖仓库所选版本中写回一个合法官方 Chunk 所需的所有官方字段。对于未建模但合法的扩展 tag，
`world-format` 的 `ChunkNbtCodec` 把它们保存在一个不公开的 remainder 中，并在写回时合并；提取出来的 block-state/biome
palette 不得以第二份 完整 section NBT 重复保留。generic kotlinx serializer 仍保持 unknown-field strict，`readChunkNbt`
则始终是完整无损 raw 路径。 错误数据版本不自动迁移；异常应指出 semantic decode 不支持，并引导使用 raw NBT。

Chunk 的垂直范围不能默认为 overworld。新增 `ChunkLayout`（最小 Y、高度且按 section 对齐）和窄的
`ChunkNbtContext` 和 `ChunkNbtContextResolver`：内置 dimension 使用仓库所选版本的生成数据，自定义 dimension 由 world
configuration 或调用方显式提供。`ChunkNbtContext` 属于 `world-format`，resolver 属于 `world-io`；坐标和值模型不依赖
protocol registry context。

### 5.2 官方 palette 行为

实现前以匹配官方 server JAR 再确认 exact strategy/configuration，并把结果固定在测试和生成数据中。已确认的基础行为是：

1. `LevelChunkSection` 在内存中持有 `PalettedContainer<BlockState>`，不是 `BlockState[4096]`。
2. container data 由 strategy/configuration、bit storage 和 palette 组成；读取通过 storage ID 查 palette。
3. `set` 命中既有值时只改对应 packed ID；加入新值时追加 palette entry。
4. palette 达到当前容量上限才切换 bits/实现并重编码现有 ID。普通替换不会删除现在未被引用的旧 entry，也不会缩小。
5. 官方 block-state 默认策略经过 single value、4-bit linear、5–8-bit hash，再进入全局 registry 映射；具体阈值和 biome
   策略不得手写猜测。
6. 序列化 `pack` 创建“仅含当前实际使用值”的新 palette/storage 快照，不回写运行时 data。

`PalettedContainer<T>` 因此只公开逻辑 index API。内部实现至少包含 single、small linear、hash 和 registry/direct storage；
在调用方没有提供能够覆盖所有值的全局 registry 时，允许使用宽 local palette 作为 lossless fallback，不能丢弃 custom/modded
值。默认 vanilla context 必须走官方同等策略。diagnostic API 只能返回不可变 snapshot，并明确区分：

- `distinctValues()`：扫描逻辑 entries 后得到实际仍被引用的值；
- `forEachDistinctValue { ... }`：同一逻辑扫描的 callback 形式，`distinctValues()` 只是收集为 `Set` 的拥有值适配器；
- `iterator()`：按逻辑 index 惰性遍历值，不暴露 palette ID 或 backing storage；
- 可选 `storageStatistics()`：bits、逻辑大小、已分配 palette entry 数等数值；
- 不提供 `palette: MutableList<T>`、raw IDs、mutable bit array 或“删除 palette entry”的方法。

### 5.3 Dense 显式适配

`DenseBlockStateVolume` 是固定 16×16×16、按 `SectionBlockOffset.index` 排列的独立可变值。仅提供显式转换：

- `ChunkSection.toDenseBlockStates()`：分配并展开 4096 个逻辑引用；
- `DenseBlockStateVolume.toPalettedContainer(registry)` 或 `toChunkSection(...)`：重新建 palette；
- Chunk 级批量调用接受 section/range 并逐 section copy，不增加默认 `readDenseChunk` I/O 方法。

这样用户仍可选择“去掉 palette 后处理”，但不会误以为它是磁盘读取的必要阶段，也不会为未加载的垂直范围分配巨大数组。

## 6. 坐标 API

### 6.1 单一类型来源

以下类型全部移到 `minecraft-model`，world 和 protocol 共同使用：

- 绝对坐标：`BlockPosition(x, y, z)`、`SectionPosition(x, y, z)`、`ChunkPosition(x, z)`、
  `RegionPosition(x, z)`；
- 容器内 offset：`SectionBlockOffset(x, y, z)`（各轴 0..15）、`ChunkBlockOffset(x, z)`（0..15）、
  `RegionChunkOffset(x, z)`（0..31）、`RegionBlockOffset(x, z)`（0..511）。

Chunk 和 Region 是垂直列，因此其 block offset 只含水平坐标，绝对 Y 始终单独、显式传入；不创建“x/z 相对但 y 绝对”的 混合
`LocalBlockPosition`。`LocalChunkPosition` 被 `RegionChunkOffset` 完全替代。

共享 `BlockPosition` 不再携带 protocol 26/12/26-bit 范围限制、scalar serializer 或 `packed()`。共享坐标允许完整 `Int`；
packed wire codec 移到 `protocol-serialization`，只在实际编码/解码 packet 时验证官方 bit range。`SectionPosition` 同理。

### 6.2 Kotlin 风味表面

公开转换以扩展属性、扩展函数和有明确量纲的运算符实现：

```kotlin
val block = BlockPosition(-17, 64, 33)

val chunk: ChunkPosition = block.chunk
val section: SectionPosition = block.section
val region: RegionPosition = block.region

val inChunk: ChunkBlockOffset = block.offsetInChunk
val inSection: SectionBlockOffset = block.offsetInSection
val inRegion: RegionBlockOffset = block.offsetInRegion
val chunkInRegion: RegionChunkOffset = chunk.offsetInRegion

val sameChunk = region + chunkInRegion
val sameBlock = section + inSection
val sameBlockFromChunk = chunk.block(inChunk, y = block.y)
val sameBlockFromRegion = region.block(inRegion, y = block.y)

check(block in chunk)
check(block in section)
check(chunk in region)
```

完整扩展集合至少包括：

- `BlockPosition.chunk/section/region`；
- `BlockPosition.offsetInChunk/offsetInSection/offsetInRegion`；
- `SectionPosition.chunk/region/originBlock`；
- `ChunkPosition.region/offsetInRegion`、`chunk.section(sectionY)`、`chunk.block(offset, y)`；
- `RegionPosition.originChunk`、`region.chunk(offset)`、`region.block(offset, y)`；
- `RegionPosition + RegionChunkOffset` 和 `SectionPosition + SectionBlockOffset`；
- `block in section/chunk/region`、`section in chunk/region`、`chunk in region`；
- `RegionPosition.chunkPositions()`，顺序与 Anvil header index 一致；
- `RegionChunkOffset.index/fromIndex`；
- `SectionBlockOffset.index/fromIndex`，固定使用 `(y * 16 + z) * 16 + x` 的 palette 顺序。

不得为了展示“使用了 operator”而定义量纲不清的加减法，例如两个绝对位置相加。需要 Y 的反向换算继续使用命名函数， 不引入半成品
`BlockPosition`。

### 6.3 算术不变量

所有换算共用一份经过测试的 scalar 实现。优先使用 Kotlin 标准库中跨目标可用的 floor division/modulo；只有确认标准 API
不满足时才保留 internal `CoordinateMath`。即使把它作为 public expert API，也不在 README 主文或普通 KDoc 示例出现。

必须对负坐标成立：

```text
block.chunk.block(block.offsetInChunk, block.y) == block
block.section + block.offsetInSection == block
block.region.block(block.offsetInRegion, block.y) == block
chunk.region + chunk.offsetInRegion == chunk
```

从大尺度坐标反算 origin 时使用 checked multiplication/addition；不能悄悄发生 `Int` wraparound。正向 floor conversion 对 完整
Int 输入有效，反向结果超出 Int 时抛出清楚的 `ArithmeticException`/`IllegalArgumentException`。边界、-1、-15、-16、
-17、-31、-32、-33 以及 Int 两端都进入 portable tests。

## 7. 名称迁移表

### 7.1 world-format / world-io

| 当前名称                                   | 最终名称                                                    | 含义                                                                                    |
|--------------------------------------------|-------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `RegionFile`（world-format）               | `AnvilRegion`                                               | detached 的完整 Anvil 快照                                                              |
| `RegionChunk`                              | `AnvilChunkRecord`                                          | pure `.mca` record，含 external marker 和 timestamp                                     |
| `RegionChunkPayload`                       | `AnvilChunkPayload`                                         | `Inline`、resolved `External` 或 `ExternalReference`，不再用 nullable bytes 混合状态    |
| `EncodedRegionFile`                        | `EncodedAnvilRegion`                                        | 编码后的 `.mca` bytes 与 sidecar payload 集合                                           |
| `RegionFileFormat`                         | `AnvilRegionFormat`                                         | filesystem-independent `.mca` codec                                                     |
| `RegionFileChunkStreamInfo`                | `AnvilChunkRecordReadScope`                                 | 顺序解码时的 callback-lifetime inline Source 或 external reference                      |
| `RegionLocation`                           | `AnvilChunkLocation`                                        | header 中 sector offset/count                                                           |
| `RegionHeader`                             | `AnvilRegionHeader`                                         | 8 KiB location/timestamp header                                                         |
| `RegionSectorAllocator`                    | `AnvilSectorAllocator`                                      | sector allocator                                                                        |
| `RegionChunkRecordHeader`                  | `AnvilChunkRecordHeader`                                    | length/compression/external record prefix                                               |
| `EncodedRegionChunkRecord`                 | `EncodedAnvilChunkRecord`                                   | 已编码 record                                                                           |
| `regionSectorsForBytes`                    | `anvilSectorsForByteCount`                                  | 纯 Anvil 标量计算                                                                       |
| `RegionFormatException`                    | 拆为 `AnvilFormatException` 与 `CompressionFormatException` | 分开 `.mca` structure 和 compression framing                                            |
| 无                                         | `ChunkNbtFormatException`                                   | 所选版本 Chunk NBT schema/data-version 错误                                             |
| `RegionChunkNbtFormat`                     | `CompressedNbtFormat`                                       | 只组合 compression 与任意 binary NBT stream，不再声称拥有 Region record 或 Chunk schema |
| 无                                         | `ChunkNbtCodec`                                             | 所选版本 uncompressed NBT stream 与 semantic `Chunk` 的映射                             |
| 无                                         | `CompressedChunk`                                           | compression + detached compressed bytes，不含文件 metadata                              |
| 无                                         | `CompressedChunkSource`                                     | compression/length + callback-scoped Source，不含 placement/timestamp                   |
| 无                                         | `CompressedRegionSnapshot`                                  | detached local-position -> compressed-content 映射，不含 placement/timestamp            |
| `RegionChunkPayload.isExternal` 等观察     | `ChunkPlacement`                                            | 一次 read 的 inline/external format 位置                                                |
| `RegionFileStore`（world-io）              | `RegionFile`                                                | 一个打开的 `.mca` 及透明 `.mcc` random-access primitive                                 |
| `RegionChunkStreamInfo`                    | `StoredChunkInfo`                                           | filesystem read metadata                                                                |
| `WorldRegionStore`                         | `RegionStorage`                                             | 一个 dimension/kind 的 Region directory 与协调 entry 管理                               |
| `WorldRegionStoreConfiguration`            | `RegionStorageConfiguration`                                | Region I/O 策略                                                                         |
| 无                                         | `RegionRecordHandle`                                        | 任意 Region data kind 的 coordinated compressed/raw-NBT handle                          |
| `WorldRegion`                              | `RegionHandle`                                              | 调用方拥有的协调式 Region 引用/句柄                                                     |
| `RegionReadScope`（当前 compressed scope） | `RegionRecordReadScope`                                     | coordinated header snapshot 上的 compressed/raw-NBT read scope                          |
| `RegionWriteScope`                         | `RegionRecordReplacementScope`                              | 未提供位置会清除的 compressed/raw-NBT replacement scope                                 |
| 无                                         | `RegionReadScope`                                           | 在 record read scope 上增加 semantic Chunk 的 scope                                     |
| 无                                         | `RegionReplacementScope`                                    | 在 record replacement scope 上增加 semantic Chunk 的 scope                              |
| `MinecraftWorldAccess`                     | `MinecraftWorld`                                            | 持有 `session.lock` 的可写 world lifecycle                                              |
| `MinecraftWorldAccessConfiguration`        | `MinecraftWorldConfiguration`                               | world codec/I/O 配置                                                                    |
| 无                                         | `LiveRegionRecordHandle`                                    | 任意 Region data kind 的 non-locking compressed/raw-NBT handle                          |
| `LiveWorldRegion`                          | `LiveRegionHandle`                                          | 非协调 live read-only Region 句柄                                                       |
| `LiveRegionFileReader`                     | `LiveRegionFile`                                            | 非协调的 exact region file reader                                                       |
| `RegionStorageDirectory`                   | `RegionDataKind`                                            | `CHUNKS`、`ENTITIES`、`POINTS_OF_INTEREST`，不伪装成 Path                               |
| `DimensionDirectory`                       | `WorldDimension`                                            | builtin 或 namespaced custom dimension                                                  |
| `LocalChunkPosition`                       | `RegionChunkOffset`                                         | Region 内 0..31 offset                                                                  |

`Compression`、`CompressionCodec` 和 `CompressionCodecs` 位于 `world.format` 包后已经足够明确，可保留短名称；但它们的
异常不能再叫 `RegionFormatException`。`LevelDat`、player advancements/statistics 等不参与本次名称替换，除非迁移依赖时
发现独立的一致性问题。

### 7.2 protocol 冲突类型

| 当前 protocol-model 名称 | 最终名称                               | 原因                                   |
|--------------------------|----------------------------------------|----------------------------------------|
| `BlockPosition`          | 复用 `minecraft-model.BlockPosition`   | 只有 wire codec 是 protocol-specific   |
| `SectionPosition`        | 复用 `minecraft-model.SectionPosition` | 同上                                   |
| `ChunkSection`           | `NetworkChunkSection`                  | 与运行时/磁盘语义 `ChunkSection` 区分  |
| `PalettedContainer`      | `NetworkPalettedContainer`             | 它表示 wire variants，不是可变逻辑容器 |
| `ChunkData`              | `ChunkPacketData`                      | 明确是 packet payload 的一部分         |
| `BlockEntityInfo`        | `ChunkPacketBlockEntity`               | 避免与 world block entity 模型混淆     |

网络重命名不得改变字段顺序、annotation 或实际 bytes。packed position serializer 迁到 `protocol-serialization` 后，packet
model 仍通过 wire annotation/format-owned serializer 得到相同编码；禁止让 shared model 自带“所有格式都编码成 Long”的
serializer。

## 8. Anvil 与 filesystem 边界

### 8.1 pure format 层

`AnvilRegionFormat` 在只有一个 `.mca` `Source` 时不可能解析 sibling `.mcc`，因此它是唯一正常暴露 external marker 的层：

- `readRecordsFromSource(source, block)` 是顺序、sector-aware canonical read；每个 inline payload 通过
  `AnvilChunkRecordReadScope.Inline` 借出有界 Source，external record 则是没有伪 payload 的 `ExternalReference`。
- `decodeFromSource(source): AnvilRegion` 是完整值适配器，external record 明确保持
  `AnvilChunkPayload.ExternalReference`。
- canonical encode 接受预先给出的 record metadata（包括精确 compressed byte count）和逐 record payload Source/callback，
  先规划 header，再把 inline payload 增量写入 `.mca` Sink，并把 external payload 增量交给调用方提供的 sidecar Sink
  callback；
  `world-format` 不为求长度暂存 payload。
- `encodeToSink(region, regionSink, externalChunkSink)` 是上述 streaming encoder 的结构值适配器；它接受
  `Inline`/已解析的 `External`，拒绝 `ExternalReference`。
- `encodeToSnapshot`/`decodeFromSnapshot` 才显式物化 `EncodedAnvilRegion` 中的 `.mca` bytes 和 sidecar byte arrays。
- header、record、location、sector allocator 仍可作为专家级低层类型，但全部以 `Anvil` 命名。

streaming encoder 使用明确的 `AnvilChunkRecordSource`（暴露开始写 header 前已知的 record metadata 和 callback-scoped
payload Source）及
`AnvilExternalChunkSink`（按 local position、精确 byte count 借出 sidecar Sink）。入口命名为
`encodeRecordsToSink(records, regionSink, externalChunkSink)`；它要求 metadata 在开始写 header 前已知，但 payload 本身不
提前物化。`encodeToSink(region, regionSink, externalChunkSink)` 只负责把 `AnvilRegion` 适配成这些 source。

### 8.2 exact RegionFile 层

world-io 的 `RegionFile` 拥有目录、absolute `RegionPosition` 和一个打开的 `.mca` `FileHandle`，因此能从
`c.<chunkX>.<chunkZ>.mcc` 透明解析 external record。它不负责跨实例协调：调用方不得同时用多个写实例操作同一文件；
`RegionStorage`/`MinecraftWorld` 提供安全协调。

必须保留：

- header random access、位置和 record length 校验；
- missing read 不创建文件，first write 创建标准空 header；
- inline/external 阈值自动选择；
- external 写入先完整 staging sidecar，再提交 `.mca` stub/header，再 replace sidecar；
- inline 写新 sectors，必要时 durable flush，提交完整 header，最后删除旧 sidecar并释放旧 sectors；
- close 补齐 sector 边界但不 shrink；
- clear/replace 保留合法空 `.mca`；
- timestamps 为存储生成的 signed 32-bit epoch seconds，不进入 `CompressedChunk` 内容。

## 9. 实施阶段

### 阶段 0：固定证据和 public API compile contract

1. 重新运行 `minecraftVersion`，记录但不把版本字面量复制进文档或源码常量。
2. 对匹配 official server JAR 检查 Chunk NBT codec、`LevelChunkSection`、`PalettedContainer`、palette strategy、disk pack、
   block-state/biome registry、外部 Chunk stream 和所选版本 section schema。服务端读写两侧都要看。
3. 把确定性 registry/block-state 数据继续交给现有 official-analysis + Gradle generator；不得手抄生成 payload。
4. 先写仅编译的新 API usage tests，固定本计划中的主要名称、所有权和坐标表达式；旧 API 不进入新测试。
5. 明确所选版本 Chunk 官方字段、data version、custom dimension layout 输入以及 unknown-tag preservation 规则。

此阶段的待确认项是官方实现事实，不是新的用户设计选择；若 evidence 与本文具体阈值冲突，以 official 行为修正文档和测试。

### 阶段 1：抽出共享 minecraft-model 和坐标

1. 新建独立发布的 `minecraft-model`，移动 `Identifier`，建立共享 `BlockState`，以及八个坐标/offset 类型和扩展 API。
2. 完成 floor/offset/index/contains/origin/checked inverse 测试，包括负数和 Int 边界。
3. 让 `protocol-model`、`world-format` 的迁移分支依赖该模块，删除两套旧坐标源。
4. 将 packed Block/Section position 的范围和编码完全移入 `protocol-serialization`，加 byte-for-byte regression tests。
5. 检查 public dependency metadata，确保仅使用 `minecraft-model` 不会拉入 NBT、protocol 或 world stack。

### 阶段 2：拆出 minecraft-vanilla-data

1. 实施前先读 `buildSrc/AGENTS.md`，调整生成 task 的唯一 owning module 和输出 package。
2. 把通用静态 registry、`BlockState` catalogue/ID map 及其生成 source 移到 `minecraft-vanilla-data`。
3. `protocol-vanilla-data` 改为消费该模块并生成/持有 protocol Configuration 专属数据；不得复制 block-state payload。
4. 为新模块增加 standalone consumer 和生成任务 cache/configuration-cache 验证。

### 阶段 3：建立 world-model

1. 实现通用 indexed registry adapter、`PalettedContainer<T>`、bit storage 和官方 strategy configuration。
2. 实现 palette set/grow/direct/fallback/pack snapshot；所有 mutable implementation type 保持 internal/private。
3. 实现 `ChunkSection`、`Chunk`、`ChunkLayout`、absolute/local block access 及 sparse section creation。
4. 实现 `DenseBlockStateVolume` 的显式往返。
5. 用 common tests 固定 logical equality、copy independence、palette stale entry、跨档 resize、pack 不改 live data 和 dense
   顺序。

### 阶段 4：重构 world-format

1. 先机械重命名 Anvil 类型并更新测试，再进行行为变更；不留 typealias。
2. 把 compression framing failure 从 Anvil structural failure 分离。
3. 将 `RegionChunkNbtFormat` 收敛为通用 `CompressedNbtFormat`；其 stream methods 组合 compression 和 caller-selected NBT
   document/serializer，但不出现 Region position、record、Chunk schema 或文件概念。
4. 实现 `ChunkNbtCodec.decodeFromSource` / `encodeToSink`，从所选版本 NBT palette 直接建立 palette-backed section， 写出时使用
   packed snapshot。
5. 实现官方字段校验、data version、Chunk position 一致性、layout 和 unknown extension tag preservation。
6. 为 `AnvilRegionFormat` 增加 metadata-first streaming encode，并让 `AnvilRegion`/`EncodedAnvilRegion` snapshot adapters
   委托它。
7. 保留 pure Anvil sequential stream、complete-value adapter 和 malformed-container coverage。

如果实现 Chunk codec 需要给 NBT 层新增事件式读取或 compound remainder 支持，必须同时使用 `minecraft-nbt` 指南，把通用能力放在
`nbt`/`nbt-serialization`；不得在 `world-format` 复制 binary NBT parser。

### 阶段 5：重构 exact world-io primitives

1. `RegionFileStore` 改为 `RegionFile`，其 canonical read/write 改用 `withCompressedChunkSource` 和
   `writeCompressedChunk(..., byteCount) { sink }`。
2. 让 `.mcc` 只在命中 external record 时按需打开，并在 callback 后关闭；对所有更高层保持透明。
3. 实现 `CompressedChunk`/`CompressedRegionSnapshot`/`StoredChunkInfo` adapters，删除普通 writable model 中的
   `isExternal` 和 timestamp。
4. 重命名 snapshot/read/replace/remove API，保持现有提交和 rollback 顺序。
5. 把所有 header/record byte conversion 委托 `AnvilRegionHeader`/`AnvilChunkRecordHeader`；`RegionFile` 只决定从哪个 file
   offset 创建 bounded Source、向哪个新 allocation 写 Sink，以及何时提交。
6. 用 tiny-chunk Source/Sink、故障注入和 exact byte-count tests 证明没有偷偷依赖大块读写。
7. 用源码边界审查确认 `world-io` 没有 Anvil magic/bit mask、compression framing 或 Chunk NBT tag 名称；路径字符串和
   sidecar filename 反过来不得出现在 `world-format`。

### 阶段 6：重构协调式 RegionStorage/RegionHandle

1. 将 entry pin、writer preference、shared-read/exclusive-write、close barrier 和 failure precedence 搬到新名称，不先重写算法。
2. 建立 `RegionRecordHandle`，按 compressed -> NBT source -> document/typed 的单向委托链增加 API；每一步只组合前一步和 一个
   `world-format` converter。
3. 建立 chunk-directory 专用 `RegionHandle`，复用 record API 并只增加 `ChunkNbtCodec` 驱动的 semantic
   `readChunk`/`writeChunk`。
4. `withReadScope` 一次取得 header snapshot；`replaceRegion` 保持完整替换和一次 header commit。
5. semantic/NBT write 只 stage 一份 compressed Chunk，再进入 exact streaming primitive。
6. absolute-position extensions 统一经坐标 API 校验；成员实现只保留 Region local offset。
7. close/cancellation tests 必须先观察 admission/drain/commit gate，再 cancel，禁止 sleep/delay 猜顺序。

### 阶段 7：重构 MinecraftWorld 和 live reader

1. `MinecraftWorldAccess` 改为 `MinecraftWorld`，保留 `session.lock`、standalone stores 和 world-level close 语义。
2. 实现 `withMinecraftWorld`、`openRegion`、`withRegion` 以及少量 absolute one-shot convenience。
3. 将 dimension/kind 从 overload 笛卡尔积收敛为 `WorldDimension` 和 `regionStorage(RegionDataKind, ...)`。
4. live reader 使用同一 compressed/NBT/semantic decode 组合，但保持 non-locking、read-only 和同步调用线程语义。
5. 删除 `OpenMinecraftWorld`/旧 facade 的重复转发层和所有旧 public 名称。

### 阶段 8：protocol 名称迁移

1. 将 network `ChunkSection`、`PalettedContainer`、`ChunkData`、block entity info 改成名称表中的明确名称。
2. 更新 packet declarations、KSP validation/dispatch、protocol serialization 和 vanilla-data consumers。
3. 只允许 import/name 变化，不允许 wire fields、nullability、palette bits 或 packet order 顺手改变。
4. 运行现有 packet byte fixtures 和 selected-release codec oracle，证明 wire output 不变。

### 阶段 9：文档、模块指南和清理

1. 更新根 README 的模块图、依赖选择和 world 示例；主要示例只用 Kotlin 风味坐标扩展。
2. 增加“数据层级与内存成本”表、“借用流所有权”说明、“为什么 semantic Chunk 仍有 palette”以及 “何时使用 dense conversion”。
3. README 首要流程展示 `withMinecraftWorld -> withRegion -> readChunk -> chunk[block]`，raw NBT 和 compressed stream
   放在进阶部分。
4. 更新根和各模块 `AGENTS.md`、相关 world/protocol skill，使新的 schema/data ownership 与代码一致。
5. 删除旧文件、旧测试 helper、旧 overload、旧包 import 和兼容桥；用 `rg` 证明名称只在 migration note 中出现。

## 10. 关键测试矩阵

### 10.1 坐标

- 16/32/512 每个边界两侧和典型负坐标；
- 四条 round-trip 恒等式和全部 `contains`；
- Region header index 与 section palette index 的顺序；
- absolute overload 对错误 Region 的拒绝；
- checked inverse overflow；
- protocol packed Block/Section position 的合法极值和越界失败，bytes 与重构前一致。

### 10.2 palette/world-model

- single value 读写；写已有值不增加 palette；写新值追加；旧值归零引用后仍保留 allocated entry；
- linear/hash/direct 各次跨档只重编码一次，4096 个 block 全部保持；
- custom value 无 global ID 时走 lossless local fallback；
- `pack` 只含实际使用值、不修改 live palette/storage；
- `copy` 完全独立；dense 往返按 `(y,z,x)` index 不变；
- Chunk absolute/local access、缺失 section、layout 上下界、跨 section Y 和错误 Chunk position。

### 10.3 Chunk NBT/world-format

- `CompressedNbtFormat` 的 document/serializer API 与 decorator callback API 结果一致，且不关闭 caller-owned endpoint；
- official generated Chunk 读取后每个 sampled block/biome 与官方结果相同；
- read -> encode -> official load，以及 official save -> library load；
- palette single/multi/direct、最小 bits、packed long 边界、空/缺失 section；
- data version、错误 palette index、错误 packed length、重复/缺失关键 tag、坐标不一致；
- unknown extension tags 在 semantic block 修改后逻辑保留；raw `NbtDocument` 始终完整；
- GZIP/ZLIB/NONE/LZ4/custom dispatch，truncated/trailing/corrupt checksum；
- metadata-first `AnvilRegionFormat.encodeRecordsToSink` 和结构值适配器 `encodeToSink` 在 1–7 byte Sink 上工作，external
  payload 直接进入 `AnvilExternalChunkSink`，不建立 sidecar byte array；只有 `encodeToSnapshot` 显式收集；
- `readRecordsFromSource` 对 inline 借出有界 Source，对 external 返回无 Source 的明确 reference；
  `decodeFromSnapshot` 能解析该 reference，缺失/多余 sidecar 均明确失败；
- `AnvilRegionFormat` external marker 在 pure stream 层 unresolved，world-io 层正确解析 sidecar。

### 10.4 world-io

- stream API 与 `CompressedChunk`/`CompressedRegionSnapshot` complete-value adapter 的 byte/logical equality；
- 使用一次只给 1–7 bytes 的 Source/Sink 证明所有路径真正可增量处理；
- repeated reads on one `RegionHandle` 只打开一个 `.mca` handle；one-shot world reads 可观察到重复打开；
- `.mcc` 命名使用 absolute Chunk 坐标，inline/external 迁移不残留错误 sidecar；
- missing read 不创建、first write 创建、remove/clear 保留合法空 header；
- staged write、header commit、sidecar move、durable flush 各故障点的旧/新状态和 suppressed failure；
- concurrent readers、writer preference、close admission/drain、world close 等待 Region、live torn-read failure；
- callback 逃逸、未消费 Source、少写/多写 compressed byte count 均明确失败；
- semantic write 的探针证明最多保留一个 compressed Chunk，且没有第二次 encode 或默认临时文件。
- production source boundary 检查证明 `world-format` 没有 Okio/Path/FileHandle/文件名，`world-io` 没有手写 Anvil byte
  framing、compression framing 或 Chunk NBT tag schema。

### 10.5 发布与官方互操作

- 新增三个模块各自做 external-consumer smoke test，核查 POM/Gradle metadata 的 `api`/`implementation`；
- 通过 Fixture Host 让官方服务端生成世界，库读取/修改一个方块并关闭，再由官方服务端加载和保存；随后库重新读取确认；
- 同一个支持的平台 test task 复用一个官方进程并按 phase 排序，host path 只在既有 `hostFilesystemTest` backdoor 中使用；
- 不新增 browser gate、私有 launcher、CLI 或测试结果文件。

## 11. 验证顺序

Gradle invocation 必须串行。先用 JVM 最短反馈环，再扩展平台：

```powershell
.\gradlew.bat :minecraft-model:jvmTest
.\gradlew.bat :minecraft-vanilla-data:jvmTest
.\gradlew.bat :world-model:jvmTest
.\gradlew.bat :world-format:jvmTest
.\gradlew.bat :world-io:jvmTest
.\gradlew.bat :protocol-model:jvmTest :protocol-serialization:jvmTest :protocol-vanilla-data:jvmTest
.\gradlew.bat :minecraft-test-fixture-host:test :world-io:jvmTest
```

JVM 稳定后运行受影响模块的 JS/wasm/desktop Native 标准任务，最后按机器内存使用合适的 `--max-workers` 运行
`allTests`。涉及 generator/build wiring 时另外验证：

- configuration cache 连续运行；
- unchanged rerun 复用；
- generated source JAR 包含新 owning module 的输出；
- production runtime classpath 不含无关 protocol/world/test/fixture 模块；
- external consumer 能只依赖 `world-model`，或依赖 `world-io` 并获得其公开签名所需下层依赖。

## 12. 验收标准

完成必须同时满足：

1. README 示例能用一套 API 完成打开 world、固定 Region、读取 semantic Chunk、读写指定 block、写回和关闭。
2. 同一 Region 的一批 Chunk 不会反复打开 `.mca`；读取一个 Chunk 不会加载完整 Region。
3. `.mcc`、compression 和 palette 对 semantic 用户透明，compressed/raw NBT 专家入口仍完整可用。
4. 所有 complete-value API 均有明确 canonical streaming delegate；测试能证明 bounded/partial streams 工作。
5. semantic Chunk 在内存中保持 palette-backed，普通 block mutation 不缩小或重排 palette，序列化 packing 不改变 live 值。
6. 内部 mutable palette/storage 无 public escape；dense 展开只能显式请求。
7. 正负坐标的全部正反换算一致，README 不出现 `CoordinateMath`。
8. pure `AnvilRegion`、storage-level `CompressedRegionSnapshot`、filesystem `RegionFile`、coordinated `RegionHandle`、
   semantic `Chunk` 和 network Chunk 类型一眼可区分。
9. 官方服务端 generate -> library edit -> official reload -> library reread 全链路通过。
10. 旧 API 和重复坐标类型全部删除，发布依赖保持独立、单向、无环，没有兼容 shim。
11. `world-format` 的公开工作全部可在 caller-supplied `Source`/`Sink` 和普通值上测试；所有真实文件、路径、random access、
    `.mcc` 定位、锁和 durability 行为只出现在 `world-io`。

本计划没有遗留需要用户先决策的 API 分歧。实施中仍需由匹配官方 JAR 决定的只是具体 Chunk schema、palette strategy
阈值和生成数据事实；这些按仓库 evidence precedence 处理，不改变上述分层、生命周期和用户 API 原则。
