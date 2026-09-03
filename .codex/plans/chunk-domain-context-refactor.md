# World 数据分层、Codec 与 Protocol 命名重构计划

- 状态：架构边界与设计事项已定案；实现前仍需完成匹配版本的逐字段官方审计
- 适用版本：仓库所选择的 Minecraft 官方版本
- 兼容策略：项目仍处于早期阶段，不保留 typealias、deprecated overload、旧模块转发壳或双轨实现

## 1. 已定案的架构

### 1.1 相邻层数据流

    world folder / files
        ↕ world-io filesystem access / fluent shortcuts
    Anvil record / compressed NBT
        ↕ Region / compression
    decompressed binary NBT Source / Sink
        ↕ ChunkNbtDecoder / ChunkNbtEncoder（组合 NbtFormat）
    Chunk
        ↕ ChunkPacketDecoder / ChunkPacketEncoder
    ClientboundLevelChunkWithLightPacket
        ↕ MinecraftPacketPayloadFormat
    packet payload bytes

`Source` 始终是 decoder 消费的读取端，`Sink` 始终是 encoder 写入的输出端；两者由调用方拥有，codec 不负责关闭，encoder 也不替调用方
flush。`NbtDocument` 是 binary NBT 与语义值之间的显式检查/保真支线，不是普通 Chunk 持久化路径中额外强制经过的数据层。

转换只发生在相邻层之间；不存在 packet↔NBT、Region↔packet 的直接 codec。磁盘和网络转换共享同一个非泛型 `Chunk`，不引入
`DiskChunk`、`ClientChunk`、`CompleteChunk`、nullable availability 或来源标记。

### 1.2 表示层所有权

| 层     | 典型类型和内容                                                                                                            | 禁止携带                                                                  |
|--------|---------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| 持久化 | binary NBT Source/Sink、NbtDocument、Anvil header/record、compression、DataVersion、Region timestamp、DataPack 各表示阶段 | ChunkContext、packet raw ID、连接状态                                     |
| 内存   | Chunk、Entity、EntityChunk、PoiChunk 及其语义状态                                                                         | 仅服务于持久化表示的版本/时间字段、Region metadata、packet、连接 registry |
| 网络   | Packet、packet-owned values、raw ID、packed palette、bit mask、network NBT、payload bytes                                 | Chunk、持久化 metadata、文件系统对象                                      |

同一 Gradle 模块可以声明多个层的类型和连接它们的 codec；层次边界由类型与 API 保证，不要求一层对应一个模块。

### 1.3 跨层规则

1. 所有方向名称都以内存 domain value 为唯一基准，不按调用流程、数据最初来源或 client/server 视角命名：从内存值转换到磁盘数据流或
   网络 packet 都称为 encode，从磁盘数据流或网络 packet 转换回内存值都称为 decode。同一表示层内部的双向物理 `Format` 不因此拆成
   encoder/decoder。
2. 每个定向 encoder/decoder 在构造时显式接收按自身命名的完整 context。codec context 可以包含 domain context；decoder 可以把该
   domain context 的同一引用交给结果，encoder 不能从 `value.context` 获取配置。
3. client/server 在转换契约和输入相同时复用 codec、context 与共享逻辑值；只有字段、策略或生命周期输入确实不同时才拆分。
4. 朴素 API 是唯一规范路径：用户先用完整的方向专属 context 构造 encoder/decoder，再调用它完成一次相邻层转换。库内
   production
   实现和测试基线只依赖这条路径。
5. fluent API 是用户便利层，只能接收已经构造好的 encoder/decoder，或接收完整 context 后在内部构造它，再委托一个或多个相邻朴素
   API/physical format；不得自己实现转换、补入隐藏配置或成为库内反向依赖。它可以提供 end-to-end orchestration，但不能因此新增一套
   packet↔NBT 等跨层 codec 或绕过中间 domain value。
6. `value.context`、raw `NbtDocument`、palette snapshot 和 Region diagnostics 等支线只供用户显式读取、检查或保真处理。它们不反向
   决定朴素 API，库内跨层转换和 orchestration 不读取这些便利入口作为配置。
7. codec 与 semantic storage API 只验证自身转换或寻址需要的内在结构。多个输入重复携带同一事实时只保留操作所需的权威来源；
   identity、position、layout、默认值、Section count 和 registry mapping 的额外一致性策略由调用方决定。
8. 存档兼容性优先。官方持久化 schema 中由世界状态决定的绝对值必须由调用方通过 encoder context 提供，库不得从系统时钟、全局状态、
   默认常量或输入值的 domain context 捏造。
9. 匹配版本官方 producer/consumer 的数据划分、容器 shape 和术语是默认基线。仅当官方结构会把本库不拥有的
   runtime、lifecycle、I/O 或
   mutable owner 耦合带入当前层，违反表示层边界，或存在明确的 Kotlin/API correctness 问题时才偏离，并记录官方对应物与理由。

### 1.4 非目标

本库不提供世界 tick loop、全局调度器、实体 tracking policy、权限、渲染、存档策略或完整游戏服务端。内存模型保存用户计算所需的
状态，但不拥有计算过程和权威世界状态。

## 2. 领域模型与表示差异

内存模型尽量把已知且稳定的结构建模为强类型字段；Block Entity data 等由游戏内容动态决定、允许 mod 扩展且无法封闭枚举的子树可以显式保留
`NbtCompound`。这种 opaque/dynamic NBT subtree 是领域内容的开放扩展点，不代表内存对象可以任意混入固定磁盘 schema；已经提升为
强类型的结构字段不得在动态 compound 中重复。

### 2.1 Chunk 与 ChunkContext

`Chunk` 是纯数据型世界模型，不等同于官方带生命周期和行为的 `LevelChunk`。它至少保存：

- position、sections、Block Entities、heightmaps、lighting；
- status、inhabited time、light correctness；
- scheduled block/fluid ticks、structure starts/references；
- 完整生成 Chunk 为计算和匹配版本持久化所需的其他状态。

最终字段以匹配版本的 `ChunkAccess`、`ProtoChunk`、`LevelChunk` 和 `SerializableChunkData` producer/consumer 审计为准。

本库对 `Chunk` 的计算契约只覆盖完整生成值，但类型仍携带磁盘 `status`，使 NBT decoder 可以返回非终态记录供调用方识别、拒绝或转交。
非终态结果不是可继续生成的 `ProtoChunk`，也不是本库承诺支持的计算输入；本库不提供按 status 补全数据的 API。需要无损保留
generation-stage entities 或其他生成期字段时使用 raw `NbtDocument`，这些字段不进入普通 `Chunk` API。

`Chunk`、`ChunkContext` 和 `ChunkSection` 不保留 block/biome/来源泛型。标准 palette 值为：

```kotlin
data class BlockState(
    val name: String,
    val properties: Map<String, String> = emptyMap(),
)

@JvmInline
value class BiomeId(val value: String)
```

`ChunkContext` 只保存多个 Chunk 共享且 Chunk 自身查询或不变量需要的事实：

```kotlin
class ChunkContext(
    val dimensionId: DimensionId,
    val dimensionTypeLayout: DimensionTypeLayout,
    val defaultBlockState: BlockState,
    val defaultBiome: BiomeId,
) {
    val chunkLayout: ChunkLayout
        get() = dimensionTypeLayout.chunkLayout
}
```

- `DimensionTypeLayout` 是 minY、height、logicalHeight、hasSkyLight、hasCeiling 和派生 section layout 的唯一来源。
- 缺失 Section 虚拟读取默认 block/biome，不因读取而物化；写入非默认值才创建 Section。
- 同一配置域的 Chunk 复用同一个 `ChunkContext` 引用；decoder 按第 3 节规则把构造期引用交给结果。
- `Chunk.context` 是公开只读的用户便利 API，不是 codec 配置来源。
- context 变化时创建新 context 和新 Chunk，不原地修改旧配置。

`WorldChunkContexts` 按 `DimensionId` 索引每个维度的共享引用。持久化路径从 `level.dat`、world generation settings 和已解析的
dimension type 构造它；网络路径从 Configuration capture 和 Play 当前维度构造它。来源不保存在 Chunk 中。

### 2.2 持久化 metadata 与时间

DataVersion、LastUpdate 和 Region metadata 不属于 Chunk 计算语义：

```kotlin
data class ChunkNbtMetadata(
    val dataVersion: Int,
    val lastUpdateTime: Long,
)

data class ChunkNbtDecodeResult(
    val chunk: Chunk,
    val metadata: ChunkNbtMetadata,
)
```

decode result 是短生命周期的转换边界结果，不是新的数据层，也不能成为 packet 或 Chunk 的字段。

同一规则适用于表示无关的 `EntityChunk` 和 `PoiChunk`：从语义对象移除 `dataVersion`，由各自的 NBT metadata/decode result
保留并显式写回。不要建立万能 `WorldNbtMetadata`。`LevelDat`、`PlayerData`、`SavedDataFile` 等本来就表示完整文件 schema
的类型，可以按其职责携带格式字段。

codec 不把 DataVersion 与仓库版本比较；迁移和兼容策略属于调用方。Region timestamp、compression、sector 和 sidecar placement
继续由 Region 层保存。

`lastUpdateTime` 表示 Chunk NBT `LastUpdate` 使用的 Minecraft world game time，不是 Unix wall-clock timestamp。它和
`dataVersion` 都是调用方在构造本次 `ChunkNbtEncoderContext` 前确定的普通不可变值；不使用 callback、provider 或额外 save
snapshot。需要另一个保存时间时，调用方构造新的 encoder context/encoder。Region header timestamp 属于 Region 层的 Unix epoch
seconds，不能从 `lastUpdateTime` 推导。

匹配版本的官方 Chunk NBT 把 `LastUpdate` 保存为绝对 world game time，而 `block_ticks`/`fluid_ticks` 中每项的 `t` 是 `Int`
相对延迟，列表顺序参与恢复同 tick 内的 sub-tick order。Chunk 的 typed scheduled-tick 值保留该相对延迟与顺序；NBT encoder 不把
`t` 重新解释为绝对时间，也不从 `lastUpdateTime` 推导它。官方保存路径可以在一次操作中以同一个 game-time sample 产生两者，但它们
仍是两个不同的持久化语义。匹配版本若还有其他绝对时间字段，同样由对应 encoder context 接受调用方值。

### 2.3 Block Entity

磁盘完整数据和网络 update tag 使用同一个中性模型：

```kotlin
data class BlockEntity(
    val type: BlockEntityTypeId,
    val position: BlockPosition,
    val data: NbtCompound,
)
```

结构字段不在 `data` 中重复。packet encoder 通过显式 `BlockEntityUpdateTagEncoder` 生成 update tag；网络来源写盘时只保存客户端已知
内容，不因缺少服务端私有字段拒绝整个 Chunk。

`data` 原样保留调用方或 mod 添加的未知属性；codec 只提取或写入自己拥有的结构字段，不建立封闭的 Block Entity payload 类型全集。

### 2.4 Entity 与 pairing

`Entity<E : Any>` 和 `EntityChunk<E : Any>` 保留 caller-selected subtype 泛型。稳定的公共 Entity 字段由 `world-format`
强类型建模；
vanilla/mod subtype 内容由显式 registry/adapter 转换为 `E`，并提供 `Entity<NbtCompound>` 原样保留未知内容。不能为了消除泛型建立
封闭的 vanilla subtype 全集。

网络初次同步所需的 runtime entity ID、乘客、vehicle、leash 等逐 Entity/连接事实使用独立的 `EntityPairingData`，不进入持久化
Entity，也不伪装成它的完整快照。tracking、可见性和跨 Entity/Chunk 的连接级顺序仍由 endpoint 决定。

### 2.5 网络可恢复范围

| 类别             | 内容                                                                                                                       |
|------------------|----------------------------------------------------------------------------------------------------------------------------|
| packet 精确提供  | position、全部 block states/biomes、section counters、客户端 heightmaps、当前 light layers/masks、Block Entity update view |
| 可从 packet 推导 | 当前 fluid state 等由 block state 和匹配定义决定的事实                                                                     |
| decoder 默认值   | status、scheduled ticks、structures、inhabited time 和其他未发送的逐 Chunk 状态                                            |
| 仅持久化表示     | DataVersion、LastUpdate、Region metadata、Block Entity 未公开保存数据                                                      |

网络 decoder 返回普通 `Chunk`。所有 packet 缺失字段都由调用方构造 `ChunkPacketDecoderContext` 时显式提供；默认值一旦采用就是客户端
本地状态。`ChunkNbtEncoder` 按自身内在约束编码该 Chunk，并使用构造期 context 中由调用方显式提供的 metadata，不根据来源区别对待。
因此客户端可以立即保存结果，但这是受支持的有损地图保存用途，不能宣传为服务端世界备份。

本计划只承诺把完整 `ClientboundLevelChunkWithLightPacket` 解码成 `Chunk` 后按当时内容直接写盘；不维护客户端世界镜像，也不应用随后到达的
`ClientboundBlockUpdatePacket`、`ClientboundSectionBlocksUpdatePacket`、`ClientboundChunksBiomesPacket`、
`ClientboundLightUpdatePacket`、`ClientboundBlockEntityDataPacket` 或 `ClientboundForgetLevelChunkPacket`。这些增量包的状态应用涉及
客户端模拟和业务策略，留给调用方或后续独立计划。

`protocol-world` 不额外判断光照数据在游戏语义上是否合理，只完成完整 Chunk packet 与内存值之间的投影。物理 packet decoder
仍只
承担读取官方 wire representation 所必需的字段边界、长度和格式内在约束；连接 framing 仍属于 `protocol-transport`，更高层的光照
一致性属于调用方业务校验。

README 与 decoder KDoc 必须维护随匹配版本审计的字段表，区分精确提供、可推导、默认产生和纯持久化内容。

## 3. Codec 与公开 API

### 3.1 持久化 codec

```kotlin
class ChunkNbtDecoderContext(
    val chunkContext: ChunkContext,
    val nbtFormat: NbtFormat,
)

class ChunkNbtEncoderContext(
    val chunkContext: ChunkContext,
    val chunkNbtMetadata: ChunkNbtMetadata,
    val nbtFormat: NbtFormat,
)

class ChunkNbtDecoder(val context: ChunkNbtDecoderContext) {
    fun decode(source: Source): ChunkNbtDecodeResult
    fun decodeDocument(nbtDocument: NbtDocument): ChunkNbtDecodeResult
}

class ChunkNbtEncoder(val context: ChunkNbtEncoderContext) {
    fun encode(chunk: Chunk, sink: Sink)
    fun encodeDocument(chunk: Chunk): NbtDocument
}
```

- encoder 和 decoder 保持独立，不恢复 `ChunkNbtCodec` facade，也不建立合并两个方向的 context 或公共 base context。
- `decode(Source)` 和 `encode(Chunk, Sink)` 是持久化方向的朴素 API。它们读写一份已解压、使用官方 unnamed compound root 的完整
  binary Chunk NBT，内部通过 context 中的 `NbtFormat` 组合 binary NBT 与 Chunk 语义转换；compression、Anvil framing 和
  filesystem
  仍由相邻层处理。
- codec 只消费或写入一个值，不关闭 caller-owned `Source`/`Sink`，也不 flush `Sink`。`decodeDocument`/`encodeDocument` 是显式的
  tree-level
  支线，与流入口复用同一份私有语义实现；普通持久化路径不为方便而先物化 document 或 byte array。只有调用方保留并经 raw API
  原样写回
  `NbtDocument` 时才能保全未建模字段，一旦投影为 `Chunk` 就遵循 typed model 的有损边界。
- decoder 将 `context.chunkContext` 的同一引用交给结果；encoder 只使用自身 context，不读取或校验 `chunk.context`。
- Chunk 和 Entity Chunk 从 NBT 解码自身位置，Region slot 只负责选择 record，不作为 `expectedPosition` 参数；调用方需要时自行比较。
  POI 根 NBT 不携带 Chunk 位置，因此 Region slot 是构造 `PoiChunk` 的必要输入，而不是额外一致性检查。
- layout 由构造时注入的 context 唯一决定；持久化 `yPos` 不作为第二份 layout 输入或校验来源。
- `EntityChunk`/`PoiChunk` 同样使用独立的定向 encoder/decoder、metadata 与 decode result；只有真实配置才进入 context，不制造空
  context。

`world-io` 的 typed filesystem API 是 fluent 层，必须按配置的真实生命周期索要 codec：

- 常规 Chunk 读取在同一维度共用 `ChunkContext`、NBT 配置和 decoder，因此在选择 typed dimension view 时接收已经构造的
  `ChunkNbtDecoder`；相应的 context overload 只负责构造 decoder 后绑定。之后的逐 Chunk read 不重复索要同一 decoder。确需逐
  Chunk
  改变 decoder 的调用方使用独立的 unbound/per-read 入口，两种生命周期不通过 nullable decoder 或隐式 override 混在一个签名中。
- 每次 Chunk 写入所需的 `ChunkNbtMetadata` 可能不同，因此 typed write 接收本次已经构造的 `ChunkNbtEncoder`；相应的 context
  overload
  只构造本次 encoder 后委托。变化的 `LastUpdate` 不固化在长生命周期 handle 中。若调用方明确让一批写入共享同一
  metadata，可以显式
  构造并复用同一个 encoder。
- 某种 codec 的配置若确实逐值变化，就在对应 read/write 操作接收；若在 world、dimension、connection epoch 或一次 batch 内稳定，就在
  最窄且能够完整覆盖该生命周期的 view/scope 创建处绑定，不在更内层重复传递，也不提升到更长生命周期缓存。
- world-io 打开 Region record、处理 compression 后，把解压的 NBT `Source` 交给 decoder；写入时让 encoder 写入连接
  compression 的
  `Sink`。typed shortcut 直接委托上述朴素 API，不经 `NbtDocument` 中转。
- raw Region、compressed payload、NBT document 和 filesystem API 继续不要求 semantic codec；semantic write 使用值自身位置选择
  slot，
  不接受第二份可能冲突的位置。

Entity Chunk 和 POI 的 typed shortcut 沿用同一生命周期规则。POI root 不包含的 Chunk 位置是该次 decode 的必要操作输入，保留在
read
调用中；它不是 decoder context 中伪造的维度级常量。

### 3.2 Domain↔packet codec

```kotlin
class ChunkPacketEncoderContext(
    val chunkContext: ChunkContext,
    val packetCodecContext: PacketCodecContext,
    val blockStateClassifier: ChunkPacketBlockStateClassifier,
    val blockEntityUpdateTagEncoder: BlockEntityUpdateTagEncoder,
)

class ChunkPacketDecoderContext(
    val chunkContext: ChunkContext,
    val packetCodecContext: PacketCodecContext,
    val chunkPacketDefaultProvider: ChunkPacketDefaultProvider,
)

class ChunkPacketEncoder(val context: ChunkPacketEncoderContext) {
    fun encode(chunk: Chunk): ClientboundLevelChunkWithLightPacket
}

class ChunkPacketDecoder(val context: ChunkPacketDecoderContext) {
    fun decode(packet: ClientboundLevelChunkWithLightPacket): Chunk
}

fun interface ChunkPacketDefaultProvider {
    fun defaults(chunkPosition: ChunkPosition): ChunkPacketDefaults
}
```

- 两个方向配置不同，不建立万能 `ChunkPacketCodecContext` 或公共 base context。
- `PacketCodecContext` 是当前连接 epoch 的 registry/raw-ID 配置，不保存 Chunk layout 或 section count。
- section count、height 和 skylight 只来自 `ChunkContext.dimensionTypeLayout`。
- block/biome/Block Entity raw ID 都通过同一个 `PacketCodecContext`；不复制 mapping。
- `ChunkPacketDefaults` 是只包含第 2.5 节“decoder 默认值”字段的方向专属 aggregate，最终字段随官方 inventory 定稿。decoder
  对每个完整
  Chunk packet 只调用一次 `chunkPacketDefaultProvider.defaults`，传入从 packet 解出的权威位置；每个缺失字段仍由调用方在构造
  context 时
  显式设置。库和 endpoint factory 不提供隐藏默认值，provider 不从 packet 猜测未发送事实，也不在不同结果间共享可变默认对象。固定策略
  可以返回同一个完全不可变 defaults value，需要逐位置策略或 fresh mutable 子对象时再由 provider 产生。
- 显式 mapping 无法表示 Chunk 值时失败，不回退到 `Chunk.context` 或隐藏 registry。
- 本次 codec context 是 layout、默认值和 registry mapping 的权威来源；codec 不与 `Chunk.context`、旧连接 context 或其他平行输入
  交叉比较，调用方需要时自行验证它们的一致性。

Entity 网络转换遵循同一 context 规则。registry mapping 和 subtype adapter 等稳定配置属于 `EntityPacketEncoderContext`；
`EntityPacketEncoder` 接受语义 Entity 与逐次 `EntityPairingData`，并负责匹配版本规定的单个 Entity pairing packet 内部顺序。
反向转换使用 `EntityPacketDecoderContext`；packet 未提供但产出 Entity 必需的字段，包括 subtype data，全部由调用方通过该
context 的
provider/adapter 提供，decoder 不从隐藏 vanilla 默认、后续增量 packet 或全局客户端状态猜测。

`protocol-world` 拥有这些朴素 codec 及其所需的策略接口，不拥有 endpoint policy 或默认实例。client/server factory 可以转交
调用方提供的 context，或协助构造 encoder 侧的匹配版本 adapter，但不能替调用方选择 decoder 缺失字段的默认值。

Chunk packet codec 的 registry mapping 在一个 connection epoch 内稳定，layout/defaults 在当前维度内稳定，因此 endpoint 的
fluent
factory 在 Configuration 完成或换维度时接收 context 或预构造 codec，并在相应 epoch/dimension view
中复用；reconfiguration、respawn
或维度事实变化时整体替换。逐 Entity 的 `EntityPairingData` 不进入这个稳定 context，而是在每次 encode 时传入。client/server
只有在
实际所需字段、策略或生命周期不同的地方才拆成 `Clientbound`/`Serverbound` codec/context；方向标签本身不构成拆分理由。

### 3.3 Packet model 与 payload format

`ClientboundLevelChunkWithLightPacket` 完整沿用匹配版本官方 packet 名，只保存官方网络字段，不包含 Chunk、ChunkContext 或
decoder。
其中 `ClientboundLevelChunkPacketData` 使用一个 `ByteString` 保存连续的 Section payload bytes，不包含 wire length prefix；
`MinecraftPacketPayloadFormat` 按官方格式处理该字段的 VarInt byte length、payload bytes 和匹配版本上限。这样保持官方将所有
Section
编码为一个有界 raw byte array 的表示。packet model 不保存 typed Section 列表，也不需要 Chunk layout 才能完成物理 packet
解码。

`ChunkPacketDecoder` 使用自己的 `ChunkPacketDecoderContext` 把 raw Section payload 解析为内存 Chunk；`ChunkPacketEncoder`
反向生成该
payload。跨模块组合所需的 typed packet Section 是 `protocol-model` 的公开显式低层 value；它不能重新成为 packet 字段，也不代表
world `ChunkSection`。其最终名称按官方审计规则确定。
`MinecraftPacketPayloadFormat` 只负责 packet model↔payload bytes，并显式接收
`MinecraftPacketPayloadFormatConfiguration`。它是网络表示层内部的双向物理 format，不因以内存层为基准的定向 codec 命名规则而拆成
两个公开类型；需要固定配置的 wrapper 命名为 `ConfiguredMinecraftPacketPayloadFormat`。

为避免 `protocol-serialization` 反向依赖 world model，同时不让 `protocol-world` 重写 packet primitive，Section payload
的实现边界固定
如下：`protocol-serialization` 公开提供只理解 packet-layer palette/container 与 `Source`/`Sink` 的窄
`MinecraftChunkSectionPayloadFormat`，其 `MinecraftChunkSectionPayloadFormatConfiguration` 显式保存 `PacketCodecContext`
和当前维度的
section count。它不接收 `ChunkContext` 或 `Chunk`，也不处理外层 VarInt byte length。这里是网络表示层内部的双向物理格式，因此和
`MinecraftPacketPayloadFormat` 一样使用一个 `Format`/`Configuration`，不伪装成内存 domain↔packet 的第二组
encoder/decoder。

`protocol-world` 独占该低层 format 的普通生产使用，从 `ChunkContext` 导出 section count，在构造外层 Chunk packet codec
时一并构造并
绑定 format，并在 packet-layer 中间值与 `ChunkSection` 之间投影；`MinecraftPacketPayloadFormat` 仍负责 packet 字段的
length prefix
和 raw `ByteString`。packet-layer 中间值在阶段 A 依据匹配版本官方对应物命名并记录必要 exception；该 value 与 format 都归类为第
3.4
节的显式低层 API，而不是第二套 Chunk 转换入口。

每个连接 epoch 和当前维度复用一组 context/codec；reconfiguration 或换维度创建新对象。对应
`MinecraftPacketPayloadFormatConfiguration` 与 Chunk packet codec 必须引用同一个 `PacketCodecContext` 快照。server/client
所需的
配置结构相同时使用同一 configuration 类型，各自实例仍可持有不同连接 epoch 的快照。

### 3.4 三类公开 API

1. **朴素 API（Plain API）**：应用显式用方向专属 context 构造 encoder/decoder，再把相邻表示交给它；这是唯一规范实现，也是测试和
   文档首先展示的路径。
2. **Fluent API**：面向用户的 extension/facade 接收已经构造的 encoder/decoder，或接收完整 context 并只在内部构造一次
   codec，然后
   调用朴素 API；可以减少样板并组合连续的相邻转换。
3. **显式低层/检查 API**：`value.context` 提供只读检查，palette snapshot、raw NBT 和 Region diagnostics 等保留为用户可显式选择的
   escape hatch；它们不影响朴素 API 的输入或 context 设计，库内转换不把它们当作隐式配置来源。

朴素 API 的统一性体现在命名和构造模型：`[Domain][Representation]Encoder` 配
`[Domain][Representation]EncoderContext`，decoder 对称命名，先构造 codec 再调用 `encode`/`decode`。不建立万能泛型
`Encoder<I, O, C>`、公共 base context 或双向 facade；流式持久化、单 packet、packet sequence 等输出形状保留各自自然签名。表示层内部的
physical `Format` 则继续使用 `[Scope]FormatConfiguration` 和 `encodeTo…`/`decodeFrom…` 命名。

朴素路径示意：

```kotlin
val chunkNbtDecoder = ChunkNbtDecoder(serverChunkNbtDecoderContext)
val chunkPacketEncoder = ChunkPacketEncoder(serverChunkPacketEncoderContext)
val serverPacketPayloadFormat = MinecraftPacketPayloadFormat(serverPacketPayloadFormatConfiguration)
val clientPacketPayloadFormat = MinecraftPacketPayloadFormat(clientPacketPayloadFormatConfiguration)
val chunkPacketDecoder = ChunkPacketDecoder(clientChunkPacketDecoderContext)
val clientMapChunkNbtEncoder = ChunkNbtEncoder(clientMapChunkNbtEncoderContext)

val serverChunk = chunkNbtDecoder.decode(serverChunkNbtSource).chunk
val packet = chunkPacketEncoder.encode(serverChunk)
serverPacketPayloadFormat.encodeToSink(ClientboundLevelChunkWithLightPacket.serializer(), packet, packetPayloadSink)
val receivedPacket = clientPacketPayloadFormat.decodeFromSource(
    ClientboundLevelChunkWithLightPacket.serializer(),
    packetPayloadSource,
    payloadLength
)
val clientChunk = chunkPacketDecoder.decode(receivedPacket)
clientMapChunkNbtEncoder.encode(clientChunk, clientMapChunkNbtSink)
```

Fluent 路径对同一朴素 codec 只增加接线便利；以下示例选择复用预构造 codec，等价 overload 也可以接收相应 context 并在一次调用内构造
codec：

```kotlin
val serverChunk = serverChunkNbtSource.toChunk(chunkNbtDecoder).chunk
val packet = serverChunk.toClientboundLevelChunkWithLightPacket(chunkPacketEncoder)
val clientChunk = packet.toChunk(chunkPacketDecoder)
clientChunk.writeNbtTo(clientMapChunkNbtSink, clientMapChunkNbtEncoder)
```

Fluent API 不得读取 receiver.context、使用进程全局 mutable context、吞掉朴素 API 错误、改变资源所有权、绕过相邻转换或把多步
orchestration 冒充为新的跨层 codec。除 fluent adapter 自身为实现委托所作的调用外，库内 production component 不调用 fluent
API。现有
Chunk/EntityChunk/PoiChunk conversions、client/server extensions 和 world-io shortcuts 全部按此审计；byte-array helper
也只能是流式
朴素 API 之上的便利或检查支线。

## 4. 模块边界

本项目中的 `world` 指一个 Minecraft 存档文件夹及其文件系统无关表示，不指官方可运行程序中的 Level/World runtime。

不新增 `world-model`：如果它只包含 Chunk，会错误排除 EntityChunk、PoiChunk 和其他存档值；如果包含全部解码结果，则只会与
`world-format` 形成机械拆分。`world-format` 统一拥有存档模型、持久化表示和 semantic codecs，`world-io` 拥有实际文件夹 I/O。

### 4.1 会变化的模块

| 模块                           | 重构后职责                                                                                                          |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------|
| world-format                   | Chunk/EntityChunk/PoiChunk、standalone schemas、DataPack、坐标、NBT Source/Sink↔semantic codecs、Anvil、compression |
| world-io                       | Okio 路径、世界/维度目录、文件、lease、Region store、锁、替换与恢复                                                 |
| protocol-model                 | packet payload、packet-owned values、wire annotations                                                               |
| protocol-serialization         | packet payload↔bytes、packet registry 和通用 wire primitives                                                        |
| protocol-world                 | 有官方网络表示的纯 Play world-value↔packet 朴素 codec                                                               |
| protocol-configuration         | DataPack→Configuration projection，以及 Configuration capture→registry/layout lookup                                |
| datapack-vanilla               | 匹配版本的官方 raw/parsed DataPack、内建 pack 集合与 vanilla stack completion                                       |
| protocol-configuration-vanilla | 匹配版本的静态 registry、Configuration defaults、capture/projector                                                  |
| protocol-client                | 客户端协商、生命周期、调用方 decoder 默认策略接线和 codec factory                                                   |
| protocol-server                | 服务端协商、生命周期、有限 initial view、tracking/排序策略和 codec factory                                          |

表中未列出的 subproject 保持现有主边界；`buildSrc` 继续作为私有构建层，不计入 runtime 模块图。

### 4.2 关键依赖

    world-format -> nbt, nbt-serialization
    world-io -> world-format

    protocol-serialization -> protocol-model, nbt-serialization
    protocol-world -> world-format, protocol-model, protocol-serialization
    protocol-configuration -> world-format, protocol-model

    datapack-vanilla -> world-format
    protocol-configuration-vanilla -> protocol-configuration, world-format, protocol-serialization

    protocol-client/server -> protocol-world, protocol-configuration
    vanilla Configuration endpoint factories -> protocol-configuration-vanilla

- `world-format`/`world-io` 不依赖 protocol 模块。
- `protocol-world` 不依赖 world-io、Ktor、auth、session、endpoint 或 vanilla singleton。
- `protocol-configuration` 不拥有普通 Play Chunk/Entity codec，也不依赖 `protocol-serialization`；只有需要把生成的
  Configuration packet
  payload bytes 解码为 packet model 的 `protocol-configuration-vanilla` 保留该物理格式依赖。
- `datapack-vanilla` 不依赖 protocol 模块；`protocol-configuration-vanilla` 不依赖 `datapack-vanilla`。
- `demo:web-map` 是示例应用，可以按功能同时依赖 world、protocol 和 vanilla provider 模块；不为消除 demo 的 protocol 依赖而移动
  runtime 类型或生成数据的所有权。自身 browser/server 消息仍使用 demo-owned resource identifier。
- 同时读取 vanilla 存档并启动网络服务的应用显式组合 DataPack stack completion 与 Configuration projection。

Gradle exposure 按普通用户实际使用的公开契约决定，不以“尽量少透传”为独立目标：依赖类型出现在 public/protected ABI，或使用该模块的
正常工作流必然需要直接构造、接收或调用这些类型时使用 `api`；只在模块内部调用且不泄漏为 caller contract 时使用
`implementation`。自然的下层类型无需为隐藏依赖而再包一层。迁移后的预期基线是：

- `world-format` 对 `nbt`、`nbt-serialization` 和公开 Source/Sink 所需的 `kotlinx-io` 使用 `api`，因为 NBT 支线、codec
  context 与朴素
  流式签名直接公开这些类型。
- `world-io` 对 `world-format` 使用 `api`，因为 typed store/shortcut 必然公开 world value、encoder/decoder 与 context；其公开
  Path、
  FileSystem、FileHandle 契约同理透传 Okio。
- `protocol-world` 对 `world-format`、`protocol-model` 使用 `api`；只要第 3.3 节的低层 physical codec 不出现在它的公开签名，
  `protocol-serialization` 使用 `implementation`。
- `protocol-configuration` 对公开结果所使用的 `world-format`、`protocol-model` 使用 `api`；vanilla provider、client/server
  再按其 factory
  返回值和参数是否公开相应类型决定 exposure，而不是机械地把所有直接依赖设成同一种 configuration。

阶段 A 以最终公开签名复核这些 configuration。即使某条无害依赖比最低必要范围透传得更宽，也不因此增加 wrapper 或阻塞重构；重点是
任何公开 API 都不能依赖 consumer 无法解析的 implementation-only 类型。

### 4.3 protocol-world 的范围

只有同时满足以下条件的转换才进入 `protocol-world`：

1. 一端是稳定的 `world-format` 语义值，另一端是 packet 或 detached packet state；
2. 转换不需要文件系统、session、endpoint 或进程全局状态；
3. tracking、可见性、runtime ID 分配和时序可由调用方提前处理；
4. 官方有限 view 被明确建模为单向/有损 projection，而不是伪装成完整 round trip。

| world value                             | 网络关系                                                                                   | 归属                                                                                    |
|-----------------------------------------|--------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| Chunk                                   | initial full Chunk packet 可向两个方向转换；网络解码由调用方补足未发送字段                 | protocol-world 的两个定向朴素 codec；不承诺无损 round trip，不处理后续增量              |
| Entity                                  | 以单个 Entity 和 spawn/metadata/attributes/equipment/passengers/leash pairing packets 表达 | protocol-world 朴素 codec；endpoint 负责 tracking/runtime ID 和跨对象 ordering          |
| EntityChunk                             | 网络没有 Region slot、DataVersion 或 root tree 边界                                        | 不建立 EntityChunk packet codec                                                         |
| PoiChunk                                | 无普通完整同步；debug view 有限                                                            | 无整体 codec；需要时提供明确单向有损 projector                                          |
| DataPack、registry、tags、feature flags | Configuration 阶段专门投影                                                                 | protocol-configuration                                                                  |
| LevelDat、world generation settings     | 无整体 packet，只提供配置和 bootstrap 事实                                                 | world-format resolver + world-io orchestration；网络 resolver 在 protocol-configuration |
| PlayerData                              | 多组连接期状态的有限视图                                                                   | 不建整体 codec；逐功能 projector 需单独证据                                             |
| MapData、ScoreboardData                 | snapshot/delta 或 ordered packet view                                                      | 纯 projector 可进 protocol-world，订阅和顺序归调用方                                    |
| advancements、statistics                | 仅部分对应 clientbound updates                                                             | 只建立证据支持的单向/delta projector                                                    |
| raids、tickets、random sequences 等     | 无通用客户端等价表示                                                                       | 不建立网络转换                                                                          |
| Anvil、compression、DataVersion         | 纯持久化表示                                                                               | 永不进入 protocol-world                                                                 |

阶段 A 建立 world projection inventory，将每项标为 `paired-directional-codecs`、`one-way-projector`、
`endpoint-orchestration` 或 `no-network-representation`；第一类表示两个独立方向都存在，不表示无损 round trip 或合并 codec。

### 4.4 DataPack 与 Configuration 拆分细节

`protocol-datapack` 改为 `protocol-configuration`。当前 `protocol-datapack-vanilla` 拆成：

- `datapack-vanilla`：`VanillaDataPacks`、官方 DataPack archive/parsed payload、core/built-in packs 和 stack completion；
- `protocol-configuration-vanilla`：`VanillaRegistryData`、`VanillaConfigurationData`、静态 registry、Configuration defaults
  和 projector。

通用 `DataPackArchive`、`DataPack`、`DataPackStack`、`ResolvedDataPackStack` 和 `WorldDataPackLoadResult` 仍在
`world-format`。两个 vanilla provider 不互相依赖，也不共享生成输出。

`WorldChunkContexts` 的持久化 adapter 属于 `world-format`；Configuration evidence adapter 属于 `protocol-configuration`。
`ResolvedMinecraftWorld` 拆为 `WorldChunkContexts`、`ResolvedConfigurationData` 和连接期 context，不保留同职责的改名聚合物。

## 5. 命名重构

### 5.1 总规则

- `Protocol` 只用于网络协议、协议侧 module/package、协议版本或正式上游扩展名；被协议实现使用、位于 protocol module 或可编码进
  packet 都不足以采用该词。下载、JSON、进程、世界、DataPack、asset 和测试设施使用各自领域名称。
- 大小写不敏感地审计 module、package、文件、代码 identifier、测试、agent skill、Gradle wiring、生成目录和输出路径中的
  `protocol`。package/module 跟随语义所有者；非网络实现移出 `com.hiczp.minecraft.protocol.*`，网络
  projection、Configuration、
  packet model/codec、session、transport 和 endpoint 留在 protocol 侧。
- `Packet` 只用于网络 packet model；所有 packet class 以 `Packet` 结尾。
- NBT、Anvil、Region、DataPack、ResourcePack、KnownPack、Configuration 等采用官方术语。
- 不把官方 runtime 容器名机械带入纯数据模型，因此保留 `Chunk` 而不是 `LevelChunk`。
- 项目自有 format/codec 类型先审计匹配版本官方实现中最接近的职责和名称，再决定保留或改名；没有直接官方对应物时不机械套用某个
  官方 buffer/codec 名称。

### 5.2 已确定的 rename map

模块与 package：

| 当前                                            | 目标                                                                                                       |
|-------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| protocol-datapack                               | protocol-configuration                                                                                     |
| `com.hiczp.minecraft.protocol.datapack`         | `com.hiczp.minecraft.protocol.configuration`                                                               |
| protocol-datapack-vanilla                       | datapack-vanilla + protocol-configuration-vanilla                                                          |
| `com.hiczp.minecraft.protocol.datapack.vanilla` | `com.hiczp.minecraft.world.format.datapack.vanilla` + `com.hiczp.minecraft.protocol.configuration.vanilla` |

运行时 API：

| 当前                                                                                      | 目标                                                                       |
|-------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| MinecraftProtocolFormat                                                                   | MinecraftPacketPayloadFormat                                               |
| MinecraftProtocolFormatConfiguration                                                      | MinecraftPacketPayloadFormatConfiguration                                  |
| ConfiguredMinecraftProtocolFormat                                                         | ConfiguredMinecraftPacketPayloadFormat                                     |
| ChunkDataAndUpdateLightPacket                                                             | ClientboundLevelChunkWithLightPacket                                       |
| WorldEventPacket                                                                          | ClientboundLevelEventPacket                                                |
| MinecraftEntitySnapshot                                                                   | EntityPairingData + EntityPacketEncoder                                    |
| MinecraftEntityPassengersSnapshot                                                         | EntityPairingData 内的 endpoint-resolved passenger relation                |
| ProtocolRegistryContext / installProtocolRegistryContext / resolveProtocolRegistryContext | PacketCodecContext / installPacketCodecContext / resolvePacketCodecContext |
| ProtocolRegistry / ProtocolRegistryEntry                                                  | RegistryIdMap / RegistryIdMapping                                          |
| ProtocolBlockState                                                                        | BlockStateIdMapping                                                        |
| ProtocolSampleProfile / MinimalProtocolValueDecoder / protocolValue                       | PacketSampleProfile / MinimalPacketValueDecoder / packetSampleValue        |
| ProtocolData / ResolvedProtocolData / VanillaProtocolData                                 | ConfigurationData / ResolvedConfigurationData / VanillaConfigurationData   |
| toProtocolData / toVanillaProtocolData                                                    | toConfigurationData / toVanillaConfigurationData                           |
| DataPackProtocolProjector / vanillaDataPackProtocolProjector                              | DataPackConfigurationProjector / vanillaDataPackConfigurationProjector     |
| ProtocolSurfaceChunkProjector                                                             | WorldSurfaceChunkProjector                                                 |
| MinecraftClientProtocol / MinecraftServerProtocol                                         | MinecraftClientNegotiation / MinecraftServerNegotiation                    |

构建与开发设施：

| 当前                                                                            | 目标                                                                            |
|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| MinecraftProtocolTarget / minecraftProtocolTarget / readMinecraftProtocolTarget | OfficialMinecraftTarget / officialMinecraftTarget / readOfficialMinecraftTarget |
| MinecraftProtocolToolSupport.kt / MinecraftProtocolToolSupportTest              | MinecraftToolSupport.kt / MinecraftToolSupportTest                              |
| protocolJson                                                                    | buildLogicJson                                                                  |
| protocolRef / `protocol-reference`                                              | minecraftArtifactsRoot / `minecraft-artifacts`                                  |
| ProtocolHttp                                                                    | DownloadHttp                                                                    |
| `.agents/skills/minecraft-protocol-vanilla-data`                                | `.agents/skills/minecraft-vanilla-data`                                         |

对当前 checked-in Kotlin/Java 声明做过一次大小写不敏感的 type-level 扫描。满足“名称含 `Protocol`，但职责并非网络协议且没有上游命名理由”
的本地类型没有遗漏：

| 当前类型/测试类型                  | 非网络职责                                               | 已定处理                              |
|------------------------------------|----------------------------------------------------------|---------------------------------------|
| `MinecraftProtocolTarget`          | 整个官方目标，包含 Minecraft、protocol、world、Java 版本 | 改为 `OfficialMinecraftTarget`        |
| `ProtocolHttp`                     | build logic 的通用下载与重试                             | 改为 `DownloadHttp`                   |
| `MinecraftProtocolToolSupportTest` | 上述官方目标、下载、进程及文件工具的综合测试             | 随文件改为 `MinecraftToolSupportTest` |
| `ProtocolSurfaceChunkProjector`    | demo 的存档 Chunk 表面投影，不编码或传输 packet          | 改为 `WorldSurfaceChunkProjector`     |

其他本地 type-level 命中项分为两类：

- 虽然仍按职责精确化改名，但确实处于网络边界：`MinecraftProtocolFormat` 类型族、`ProtocolRegistry`/`ProtocolRegistryEntry`/
  `ProtocolRegistryContext`/`ProtocolBlockState`、`ProtocolData`/`ResolvedProtocolData`/`VanillaProtocolData`、
  `DataPackProtocolProjector`、`ProtocolSampleProfile` 和 `MinimalProtocolValueDecoder`。它们已经全部在上表或运行时 rename
  map 中覆盖，
  对应测试按派生名称同步修改。
- 有明确理由保留 `Protocol`：生成的 `MinecraftProtocol` 及 `GenerateMinecraftProtocolSourceTask` 表示实际网络 protocol
  revision；
  `ProtocolModelProcessor`/`ProtocolModelProcessorProvider` 处理 packet/wire model；`FabricProtocol`、`ForgeProtocol`、
  `NeoForgeProtocol`、
  各自的 `ProtocolLimits`、`NeoForgeConnectionProtocol` 及对应 codec tests 表示真实 loader network protocol；
  `ProtocolModelContractTest`/`ProtocolModelInvariantTest` 测试网络 protocol model；`WebMapProtocolTest` 测试 demo 自己的
  RPC/JSON wire
  contract。Fixture Host 中的 `ProtocolInfo`、`HandshakeProtocols` 等是匹配版本官方类，`MCProtocolLib` 是正式上游项目名，也不重命名。

生成类型也已按生成器定义和当前 `build/generated` 产物双向核对：

- 唯一名称包含 `Protocol` 的本地生成类型是 public `MinecraftProtocol`。它持有仓库所选 Minecraft release 对应的实际网络
  `PROTOCOL_VERSION` 及其 Minecraft version label，位于 `protocol-model` 并直接参与握手、状态和 Known Packs，因此保留名称；拥有它的
  `GenerateMinecraftProtocolSourceTask` 同样保留。
- 本仓库 KSP 固定生成 `GeneratedPacketDefinitions` 与 `GeneratedDataComponentSerializers`；官方数据生成器生成
  `MinecraftWorldFormat`、`VanillaConfigurationPacketPayloads`、`VanillaRegistryDataPayloads` 和 `VanillaDataPackPayload*`
  ；当前外部 KSP
  产物也没有额外的 `Protocol` 类型名。
- 生成类型名本身没有新增 rename 项。生成 package、输出目录和 owning module 中不再成立的 `protocol` 名称仍随
  `protocol-datapack-vanilla` 拆分及第 5.2 节的 package/module 迁移处理，不能因为类型 simple name 合理就保留错误的生成路径。

这只是类型声明审计。函数、属性、参数、文件、package、module、skill、Gradle configuration、生成输出及 diagnostics 仍按阶段 A 的完整
case-insensitive inventory 处理。

上述改名同步应用到派生的参数、字段、局部变量、helper、测试与 diagnostics；不保留仅反映旧实现历史的 `protocol*` 名称。
module/package/skill 行同时覆盖手写源码、generated package、Android namespace、skill metadata、source output path、Gradle
project dependency 和 publication。

匹配版本的官方名称已经是 `Identifier`，因此不改名为旧称 `ResourceLocation`。该类型继续由 `protocol-model` 拥有；
`world-format`
保留 `DimensionId`、`DimensionTypeId`、`SavedDataId` 等职责明确的领域 ID，protocol adapter 在边界转换。不能让
`protocol-model` 为复用
一个基础值而反向依赖整个 `world-format`，也不新增只有该值才能证明存在意义的宽泛 core 模块。

### 5.3 packet 官方名称审计

所有 `@PacketInfo` 声明都与匹配版本的官方 packet report、class、producer 和 consumer 对照，不只审计 Chunk packet。

默认命名规则：

1. 默认完整使用官方 Java simple name，包括 `Clientbound`/`Serverbound`、`Level` 和状态限定词。
2. packet-owned nested value、record component 和稳定字段同样默认使用官方名称、声明顺序与嵌套关系；Kotlin 关键字使用反引号，不为本库
   风格统一而擅自缩写、展开或替换术语。
3. 类型、nullability 和 conditional shape 默认映射官方 producer/consumer 与 codec 能表达的语义。使用 `ByteString` 替代官方
   mutable
   byte array、用 sealed value 表达官方 discriminated branch 等 Kotlin/KMP 表示调整可以保留，但必须维持 wire 和字段语义。
4. 只有本库有真实特殊需要时才使用共享或不同名称，例如两个方向刻意共享同一逻辑/实现类型、Kotlin 名称冲突、强类型聚合，或官方
   runtime/container 类型会引入当前层不拥有的耦合；不能只为名称更短或看起来更整齐而偏离官方。
5. 每项偏离都记录对应官方 class/member、偏离原因和影响范围，形成可审计 exception；内存模型排除官方 runtime 容器名的理由不能自动
   用于 packet。

扩展官方 packet class report。KSP/生成测试只自动保证每个本地 packet 有官方 identity、名称符合规则或存在有效
exception、exception
无失效项，以及 source coverage/dispatch 表完整。字段名称、类型、顺序、nullability 和 conditional codec 必须逐 packet 对照匹配版本的
官方 producer、consumer 与 codec，由人工或 agent 审计并留下可复查清单；KSP 无法从 Kotlin 声明本身证明这些事实。官方 codec
oracle
和 round-trip 测试只验证所覆盖样本的 wire 行为，不能替代字段审计。

## 6. 实施顺序

已定案的跨模块规则先写入 root 与相关模块 `AGENTS.md`，作为后续迁移约束。每个阶段开始改源码前再次核对最近的 `AGENTS.md`；该阶段
改变所有权或不变量时，同阶段同步校正源码、测试与对应局部指南，不能把 agent guidance 一律拖到阶段 G 才处理。

### A. 建立审计清单

1. 固定当前模块依赖、跨层 conversion、大小写不敏感的 `protocol` module/package/file/identifier/agent skill/Gradle 名称和
   `@PacketInfo` 清单。
2. 为每个 `protocol` 名称记录语义所有者并定稿 rename map；保留项必须直接表示 wire、连接状态、协议版本/ID/table、protocol-side
   boundary 或可定位的上游名称，所有例外均进入可审计清单。
3. 扩展官方 packet class report，并建立人工/agent 字段审计清单，逐项记录名称、类型、顺序、nullability 和 conditional codec 的
   producer/consumer/codec 证据。
4. 建立 world projection inventory。

### B. 收敛 world-format

1. 不新增 `world-model`，按 chunk/entity/poi/saveddata/datapack/anvil 子域整理现有文件。
2. 建立已定形的 `ChunkContext`、`WorldChunkContexts`、标准 `BlockState`/`BiomeId` 和非泛型 Chunk。
3. 把 ticks、structures 和统一 Block Entity data 纳入 Chunk；保留 status 供磁盘数据检查，但不建立 generation continuation
   API 或
   generation-stage Entity 模型。
4. 从 Chunk/EntityChunk/PoiChunk 分离纯持久化 metadata；完整文件 schema 不做机械拆分。
5. 保持 DataPack 表示阶段和 Anvil/container 边界不变。

### C. 重建持久化路径

1. 拆出 Chunk、EntityChunk、PoiChunk 的定向 NBT encoder/decoder、方向专属 context 和 decode result；以
   `decode(Source)`/`encode(value, Sink)` 为朴素 API，并让 document 支线复用同一语义实现。
2. `world-io` 按真实生命周期接收 codec：维度稳定的 Chunk decoder 在 typed dimension view 创建时绑定，逐值变化的 codec 在
   read/write
   操作传入；typed write 接受任意合法语义值和本次 encoder/context，不缓存变化的 metadata。
3. 让 world-io 的 typed shortcut 直接把 compression 两侧的 Source/Sink 交给朴素 codec；审计并重写现有 conversions，raw
   NBT/compressed/Region API 保留为 escape hatch。

### D. 重建网络 model 与 protocol-world

1. 完成 `PacketCodecContext`、packet 命名迁移，以及 `MinecraftPacketPayloadFormat` 类型族命名迁移。
2. 把 `ChunkDataAndUpdateLightPacket` 改为纯网络 `ClientboundLevelChunkWithLightPacket`；其嵌套 chunk data 保存
   raw `ByteString` Section payload，由 physical serializer 处理其 VarInt length prefix，并移除依赖 Chunk layout 的 typed
   Section 分支。
3. 在 `protocol-serialization` 建立不依赖 world model 的窄 Section payload 低层 format/configuration，并由 exact-byte
   测试证明其与外层
   `ByteString`/VarInt length 的责任边界。
4. 新建 `protocol-world`，实现独立的 `ChunkPacketEncoder`/`ChunkPacketDecoder` 及各自 context，只由这里组合 Section 低层
   format 与
   world projection。
5. 保留 `Entity<E>`/`EntityChunk<E>` subtype 泛型和 raw NBT fallback；迁移 client/server 中可复用的 Entity pairing
   conversion，
   EntityChunk、POI 和其他 world value 按 inventory 处理。
6. 删除 `MinecraftChunkSnapshot`；以 `EntityPairingData`、`EntityPacketEncoderContext` 和 `EntityPacketEncoder` 取代
   `MinecraftEntitySnapshot`，每个网络 decoder 的缺失字段只从其 caller-supplied decoder context 获得。

### E. 拆分 Configuration 与 vanilla providers

1. 完成 `protocol-configuration` 类型/package 迁移，移出普通 Play world codec。
2. 拆出 `datapack-vanilla` 与 `protocol-configuration-vanilla`，重新归属生成器及其唯一输出。
3. 保持 DataPack stack completion 与 Configuration projection 为两个显式步骤。

### F. 收敛 endpoint

1. client/server 只调用 `protocol-world` 朴素 codec，不保留可复用的 Chunk/Entity projection 实现。
2. endpoint 保留 negotiation、epoch/dimension context factory、tracking、runtime ID、可见性、packet ordering 和 enqueue。
3. reconfiguration/respawn 原子切换后续 codec context，已开始的 flow 使用旧快照。
4. vanilla encoder adapter 可以由 endpoint factory 选择，mod override 仍显式；decoder 所有缺失字段默认策略由调用方提供并经
   factory
   原样接线。

### G. 重建便利 API 与文档

1. 朴素 API 稳定并通过测试后再实现 fluent extensions/facades。
2. 每个 fluent 入口显式接收预构造 codec 或完整 context，并以等价测试证明其只委托朴素 API；库内其他 production component
   不调用
   fluent 入口。
3. 清理跨层 shortcut、隐式默认、旧 module/package、兼容 alias 和未使用 naming exception。
4. 更新 settings、publication、README、demo、测试和相关 `.agents` skills；对已经提前写入的 AGENTS 规则做最终实现一致性复核，README
   只描述已经实现的 API。

## 7. 验证与完成标准

### 7.1 重点测试

| 范围                           | 必须证明                                                                                                                                                                                                     |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| world-format world values      | context 引用复用、sparse Section、不必要 Chunk 泛型消失、Entity subtype 泛型、ticks/structures 语义、非终态 status 可识别但无 generation continuation API、EntityChunk/PoiChunk 不含 DataVersion             |
| world-format persistence       | Source/Sink 朴素路径、NBT schema、encoder context 中的 metadata 无损写入、网络 Chunk 可写盘、Entity/POI 定向 codec、codec 配置权威性、stream ownership、document/Anvil/compression/raw escape hatch          |
| world-io                       | codec 按真实生命周期绑定、每维度 decoder、逐次 encoder、typed shortcut 与朴素路径等价、Region slot 只选择 record、自带位置的 NBT 不做隐式 placement 校验、sidecar/timestamp 边界、写入恢复、无 protocol 依赖 |
| protocol-model/KSP             | 完整官方 packet class 名、identity/exception、source coverage 与 dispatch 完整；字段清单由人工/agent 审计                                                                                                    |
| protocol-serialization         | 已审计 schema 的 exact-byte/branch/boundary 测试、raw Chunk Section payload 及其 VarInt length prefix、PacketCodecContext 无 layout、官方 codec oracle                                                       |
| protocol-world                 | palettes、heightmaps、lighting、Block Entity、caller-supplied decoder defaults、Entity pairing、projection inventory、无 endpoint/IO 依赖                                                                    |
| protocol-configuration/vanilla | Known Packs、registry order、WorldChunkContexts/PacketCodecContext resolution、两个 vanilla provider 无反向依赖                                                                                              |
| protocol-client/server         | epoch 和维度切换、无跨来源 layout/default/Section count 校验、initial world、Entity tracking 边界、官方 interoperability                                                                                     |
| fluent/escape hatch            | 预构造 codec/context 两类入口、结果与错误等价、资源所有权不变、库内 production source 不调用或读取便利 API                                                                                                   |

### 7.2 验证命令

Gradle invocation 不并发，先运行最窄 JVM 任务：

```shell
./gradlew :world-format:jvmTest
./gradlew :world-io:jvmTest
./gradlew :protocol-model:jvmTest
./gradlew :protocol-serialization:jvmTest
./gradlew :protocol-world:jvmTest
./gradlew :protocol-configuration:jvmTest
./gradlew :datapack-vanilla:jvmTest
./gradlew :protocol-configuration-vanilla:jvmTest
./gradlew :protocol-client:jvmTest
./gradlew :protocol-server:jvmTest
```

涉及 build logic、KSP、官方 endpoint 或全平台时继续运行：

```shell
./gradlew -p buildSrc test
./gradlew :minecraft-test-fixture-host:test jvmTest
./gradlew allTests
```

模块/source-set wiring 变更还需验证 configuration-cache store 与 reuse。

### 7.3 完成标准

- 三层类型只保存本层事实；以内存值为基准，去磁盘流和 packet 都是 encode，回到内存值都是 decode；转换只经过相邻朴素
  codec，每个方向
  使用自己的显式 context，encoder 不读取 `value.context`，除 adapter 自身外库内不调用 fluent API。
- 磁盘和网络使用同一个 Chunk；非终态磁盘 status 只用于识别而不承诺继续生成；客户端全部默认值、仅完整包即时保存和有损写盘行为有明确文档。
- `world-format`、`world-io`、`protocol-world` 和 `protocol-configuration` 形成计划中的单向依赖；不存在 `world-model`
  ，client/server 不复制
  Chunk encoder/decoder 或 Entity pairing conversion，所有 world value 都完成 projection inventory 分类。
- packet 默认采用完整官方 class 名并通过 identity/exception 审计；完整 Chunk packet 保存 raw Section payload，`Identifier`
  留在
  `protocol-model`，所有其他 `protocol` 名称均已分类或改名。
- 所有受影响的 subproject、publication、文档、生成器和 skills 均反映新边界。
- 目标平台测试、官方 codec oracle 和官方 endpoint fixtures 通过。
