# World 数据分层、Codec 与 Protocol 命名重构计划

- 状态：架构边界已定案；第 9 节设计事项待确认
- 适用版本：仓库所选择的 Minecraft 官方版本
- 兼容策略：项目仍处于早期阶段，不保留 typealias、deprecated overload、旧模块转发壳或双轨实现

## 1. 目标与约束

### 1.1 标准数据流

    world folder / files
        ↕ world-io
    Anvil record / compressed NBT
        ↕ Region / compression / NBT formats
    NbtDocument
        ↕ ChunkNbtDecoder / ChunkNbtEncoder
    Chunk
        ↕ ChunkPacketDecoder / ChunkPacketEncoder
    ChunkWithLightPacket
        ↕ MinecraftPacketFormat
    packet payload bytes

持久化层、内存层和网络层只保存本层事实。转换只发生在相邻层之间；不存在 packet↔NBT、Region↔packet 的直接 codec。

磁盘和网络使用同一个非泛型 `Chunk`。网络没有发送的字段由 decoder 的显式默认策略变成客户端本地值，不使用
`DiskChunk`、`ClientChunk`、`CompleteChunk`、nullable availability 或来源标记。

### 1.2 设计规则

1. 数据值不保存 codec、连接状态或其他层的 metadata。
2. encoder/decoder 是固定逻辑与不可变配置的组合，构造时显式接收完整配置。
3. 数据自身需要的 context 与 codec context 是不同概念；二者即使类型相同也不能互相推导。
4. primitive encoder/decoder 是唯一规范实现；fluent API 只能显式配置并委托 primitive。
5. 库内实现不调用 fluent extension，也不通过 `value.context` 获取 codec 配置。
6. packet 优先使用匹配版本的官方名称；磁盘和内存类型不使用无意义的 `Protocol` 前缀。
7. codec 和 semantic storage API 只验证完成自身转换或寻址所必需的输入内在结构；跨来源一致性检查由调用方自行完成。
8. 两个独立输入携带同一事实且其中一个仅用于比较时，为该字段保留转换所需的唯一权威来源，不做交叉验证。此规则适用于位置、
   identity、layout、默认值、Section count 和 registry 映射。

本库不提供世界 tick loop、全局调度器、实体 tracking policy、权限、渲染、存档策略或完整游戏服务端。内存模型应保存继续计算所需的
数据，但计算过程和权威世界状态由使用者实现。

## 2. 数据模型

### 2.1 三层所有权

| 层     | 典型类型和内容                                                                                    | 禁止携带                                           |
|--------|---------------------------------------------------------------------------------------------------|----------------------------------------------------|
| 持久化 | NbtDocument、Anvil header/record、compression、DataVersion、Region timestamp、DataPack 各表示阶段 | ChunkContext、packet raw ID、连接状态              |
| 内存   | Chunk、Entity、EntityChunk、PoiChunk 及其语义状态                                                 | NBT 字段名、Region metadata、packet、连接 registry |
| 网络   | Packet、packet-owned values、raw ID、packed palette、bit mask、network NBT、payload bytes         | Chunk、持久化 metadata、文件系统对象               |

同一 Gradle 模块可以声明多个层的类型和连接它们的 codec；层次边界由类型与 API 保证，不要求一层对应一个模块。

### 2.2 Chunk 与 ChunkContext

`Chunk` 是纯数据型世界模型，不等同于官方带生命周期和行为的 `LevelChunk`。它至少保存：

- position、sections、Block Entities、heightmaps、lighting；
- generation status、inhabited time、light correctness；
- scheduled block/fluid ticks、structure starts/references；
- UpgradeData、blending、below-zero retrogen、carving mask、post-processing；
- 匹配版本中未完成生成 Chunk 所保存的其他语义状态。

最终字段以匹配版本的 `ChunkAccess`、`ProtoChunk`、`LevelChunk` 和 `SerializableChunkData` producer/consumer 审计为准。

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
- 同一配置域的 Chunk、snapshot 和 decoder 结果复用同一个 `ChunkContext` 引用。
- `Chunk.context` 是公开只读的用户便利 API；库内 codec、store、endpoint 和 extension 不读取它。
- context 变化时创建新 context 和新 Chunk，不原地修改旧配置。

`WorldChunkContexts` 按 `DimensionId` 索引每个维度的共享引用。持久化路径从 `level.dat`、world generation settings 和已解析的
dimension type 构造它；网络路径从 Configuration capture 和 Play 当前维度构造它。来源不保存在 Chunk 中。

### 2.3 持久化 metadata

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
保留并 显式写回。不要建立万能 `WorldNbtMetadata`。`LevelDat`、`PlayerData`、`SavedDataFile` 等本来就表示完整文件 schema
的类型，可以按 其职责携带格式字段。

codec 不把 DataVersion 与仓库版本比较；迁移和兼容策略属于调用方。Region timestamp、compression、sector 和 sidecar placement
继续由 Region 层保存。

### 2.4 Block Entity

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

### 2.5 网络可恢复范围

| 类别             | 内容                                                                                                                       |
|------------------|----------------------------------------------------------------------------------------------------------------------------|
| packet 精确提供  | position、全部 block states/biomes、section counters、客户端 heightmaps、当前 light layers/masks、Block Entity update view |
| 可从 packet 推导 | 当前 fluid state 等由 block state 和匹配定义决定的事实                                                                     |
| decoder 默认值   | scheduled ticks、structures、inhabited time、generation continuation 和其他未发送的逐 Chunk 状态                           |
| 仅持久化表示     | DataVersion、LastUpdate、Region metadata、Block Entity 未公开保存数据                                                      |

网络 decoder 返回普通 `Chunk`，默认值一旦采用就是客户端本地状态。`ChunkNbtEncoder` 接受该 Chunk 和调用方显式提供的
metadata， 不检查来源或“完整性”。这是一种受支持的有损地图保存用途，但不能宣传为服务端世界备份。

README 与 decoder KDoc 必须维护随匹配版本审计的字段表，区分精确提供、可推导、默认产生和纯持久化内容。

## 3. Codec 与公开 API

### 3.1 持久化 codec

```kotlin
class ChunkNbtDecoder(val chunkContext: ChunkContext) {
    fun decode(nbtDocument: NbtDocument): ChunkNbtDecodeResult
}

class ChunkNbtEncoder(val chunkContext: ChunkContext) {
    fun encode(chunk: Chunk, metadata: ChunkNbtMetadata): NbtDocument
}
```

- encoder 和 decoder 分离，不恢复只有转发作用的 `ChunkNbtCodec` facade。
- 不创建只包装一个字段的 `ChunkNbtCodecContext`。
- semantic codec 只处理 `NbtDocument`↔Chunk；binary NBT、compression、Anvil 和 filesystem 各由相邻层处理。
- decoder 将构造时注入的 `ChunkContext` 原样交给每个结果。
- decoder 从 NBT 的 `xPos`/`zPos` 解码 Chunk 位置，不接收或校验来自 Region slot 的第二份位置；调用方需要时自行比较。
- layout 由构造时注入的 context 唯一决定；持久化 `yPos` 不作为第二份 layout 输入或校验来源。
- encoder 只使用构造时显式注入的 context，不读取或校验 `chunk.context`。
- `EntityChunk`/`PoiChunk` 同样拆分定向 encoder/decoder、metadata 与 decode result；`EntityChunk` 从自身 NBT 解码位置且不接收
  expected position；POI 根 NBT 不携带 Chunk 位置，因此 Region slot 是构造 `PoiChunk` 的必要输入而不是附加校验参数。只有真实配置才进入
  构造器，不制造空 context。

`world-io` 的 dimension-bound handle/scope 在构造时绑定相应 codec。Region slot 只选择待读取的 record，不作为 expected
position 传给自带位置的 semantic decoder；读取结果保留 NBT 自身的位置，调用方可将它与所请求的 slot 比较。semantic write
使用值自身位置选择 slot，不接受第二份可能冲突的位置。

### 3.2 网络 codec

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
    fun encode(chunk: Chunk): ChunkWithLightPacket
}

class ChunkPacketDecoder(val context: ChunkPacketDecoderContext) {
    fun decode(packet: ChunkWithLightPacket): Chunk
}
```

- 两个方向配置不同，不建立万能 `ChunkPacketCodecContext` 或公共 base context。
- `PacketCodecContext` 是当前连接 epoch 的 registry/raw-ID 配置，不保存 Chunk layout 或 section count。
- section count、height 和 skylight 只来自 `ChunkContext.dimensionTypeLayout`。
- block/biome/Block Entity raw ID 都通过同一个 `PacketCodecContext`；不复制 mapping。
- default provider 为每个 Chunk 生成需要独立修改的集合，不能共享可变默认对象。
- 显式 mapping 无法表示 Chunk 值时失败，不回退到 `Chunk.context` 或隐藏 registry。
- packet codec 不把已解码 packet 的 Section 数量、`PacketCodecContext` 中的旧 Section count 或输入 Chunk 携带的
  layout/default 与自身配置交叉比较；codec context 是本次转换的权威配置，调用方需要时自行验证不同来源是否一致。

`protocol-world` 只声明和执行这些策略接口；release-matched 默认策略由 client/server factory 选择并显式注入。

`ChunkWithLightPacket` 只保存官方网络字段，Section payload 使用网络 `ByteString`/等价值，不包含 Chunk、ChunkContext 或
decoder。
`MinecraftPacketFormat` 只负责 packet model↔payload bytes，并显式接收 `MinecraftPacketFormatConfiguration`。

每个连接 epoch 和当前维度复用一组 context/codec；reconfiguration 或换维度创建新对象。对应
`MinecraftPacketFormatConfiguration` 与 Chunk packet codec 必须引用同一个 `PacketCodecContext` 快照。

### 3.3 三类公开 API

1. **Primitive API**：应用和库内实现显式构造 encoder/decoder；这是测试和文档首先展示的规范路径。
2. **Fluent API**：面向用户的扩展函数显式接收完整配置，在内部构造并调用 primitive；可以组合连续的相邻转换。
3. **开放细节 API**：`value.context`、palette snapshot、raw NBT、Region diagnostics 等只读 escape hatch；仅供用户使用。

Primitive 路径示意：

```kotlin
val chunkNbtDecoder = ChunkNbtDecoder(serverChunkContext)
val chunkPacketEncoder = ChunkPacketEncoder(serverChunkPacketEncoderContext)
val serverPacketFormat = MinecraftPacketFormat(serverPacketFormatConfiguration)
val clientPacketFormat = MinecraftPacketFormat(clientPacketFormatConfiguration)
val chunkPacketDecoder = ChunkPacketDecoder(clientChunkPacketDecoderContext)

val serverChunk = chunkNbtDecoder.decode(nbtDocument).chunk
val packet = chunkPacketEncoder.encode(serverChunk)
serverPacketFormat.encodeToSink(ChunkWithLightPacket.serializer(), packet, sink)
val receivedPacket = clientPacketFormat.decodeFromSource(ChunkWithLightPacket.serializer(), source, payloadLength)
val clientChunk = chunkPacketDecoder.decode(receivedPacket)
val clientMapDocument = ChunkNbtEncoder(clientChunkContext).encode(clientChunk, clientMapChunkNbtMetadata)
```

Fluent 路径只减少对象构造样板：

```kotlin
val serverChunk = nbtDocument.toChunk(serverChunkContext).chunk
val packet = serverChunk.toChunkWithLightPacket(serverChunkPacketEncoderContext)
val clientChunk = packet.toChunk(clientChunkPacketDecoderContext)
val clientMapDocument = clientChunk.toNbtDocument(clientChunkContext, clientMapChunkNbtMetadata)
```

Fluent API 不得读取 receiver.context、使用进程全局 mutable context、吞掉 primitive 错误、改变资源所有权或创建非相邻层
direct conversion。现有 Chunk/EntityChunk/PoiChunk conversions、client/server extensions 和 world-io shortcuts 全部按此审计。

## 4. 模块边界

本项目中的 `world` 指一个 Minecraft 存档文件夹及其文件系统无关表示，不指官方可运行程序中的 Level/World runtime。

不新增 `world-model`：如果它只包含 Chunk，会错误排除 EntityChunk、PoiChunk 和其他存档值；如果包含全部解码结果，则只会与
`world-format` 形成机械拆分。`world-format` 统一拥有存档模型、持久化表示和 semantic codecs，`world-io` 拥有实际文件夹 I/O。

### 4.1 会变化的模块

| 模块                           | 重构后职责                                                                                              |
|--------------------------------|---------------------------------------------------------------------------------------------------------|
| world-format                   | Chunk/EntityChunk/PoiChunk、standalone schemas、DataPack、坐标、NBT semantic codecs、Anvil、compression |
| world-io                       | Okio 路径、世界/维度目录、文件、lease、Region store、锁、替换与恢复                                     |
| protocol-model                 | packet payload、packet-owned values、wire annotations                                                   |
| protocol-serialization         | packet payload↔bytes、packet registry 和通用 wire primitives                                            |
| protocol-world                 | 有官方网络表示的纯 Play world-value↔packet projection primitives                                        |
| protocol-configuration         | DataPack→Configuration projection，以及 Configuration capture→registry/layout lookup                    |
| datapack-vanilla               | 匹配版本的官方 raw/parsed DataPack、内建 pack 集合与 vanilla stack completion                           |
| protocol-configuration-vanilla | 匹配版本的静态 registry、Configuration defaults、capture/projector                                      |
| protocol-client                | 客户端协商、生命周期、默认策略选择和 codec factory                                                      |
| protocol-server                | 服务端协商、生命周期、有限 initial view、tracking/排序策略和 codec factory                              |

其余 12 个 subprojects 保持主边界：`nbt`、`nbt-serialization`、`protocol-transport`、`protocol-session`、
`distribution-metadata`、`account-auth`、`protocol-auth`、`protocol-symbol-processor`、`minecraft-test-support`、
`minecraft-test-fixture-host`、`demo:launcher`、`demo:web-map`。目标共 22 个 Gradle subprojects；`buildSrc` 不计入。

### 4.2 关键依赖

    world-format -> nbt, nbt-serialization
    world-io -> world-format

    protocol-serialization -> protocol-model, nbt-serialization
    protocol-world -> world-format, protocol-model, protocol-serialization
    protocol-configuration -> world-format, protocol-model, protocol-serialization

    datapack-vanilla -> world-format
    protocol-configuration-vanilla -> protocol-configuration, world-format, protocol-serialization

    protocol-client/server -> protocol-world, protocol-configuration
    vanilla endpoint defaults -> protocol-configuration-vanilla

- `world-format`/`world-io` 不依赖 protocol 模块。
- `protocol-world` 不依赖 world-io、Ktor、auth、session、endpoint 或 vanilla singleton。
- `protocol-configuration` 不拥有普通 Play Chunk/Entity codec。
- `datapack-vanilla` 不依赖 protocol 模块；`protocol-configuration-vanilla` 不依赖 `datapack-vanilla`。
- `demo:web-map` 的世界读取、surface projection 和 asset 处理不依赖 Minecraft protocol 模块；它使用 world/data-pack 所有者提供的
  release 与 domain values，自身 browser/server 消息使用 demo-owned resource identifier。
- 同时读取 vanilla 存档并启动网络服务的应用显式组合 DataPack stack completion 与 Configuration projection。

### 4.3 protocol-world 的范围

只有同时满足以下条件的转换才进入 `protocol-world`：

1. 一端是稳定的 `world-format` 语义值，另一端是 packet 或 detached packet state；
2. 转换不需要文件系统、session、endpoint 或进程全局状态；
3. tracking、可见性、runtime ID 分配和时序可由调用方提前处理；
4. 官方有限 view 被明确建模为单向/有损 projection，而不是伪装成完整 round trip。

| world value                             | 网络关系                                                                                   | 归属                                                                                    |
|-----------------------------------------|--------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| Chunk                                   | full Chunk payload 可双向转换，但部分字段由客户端默认                                      | protocol-world 双向 codec                                                               |
| Entity                                  | 以单个 Entity 和 spawn/metadata/attributes/equipment/passengers/leash pairing packets 表达 | protocol-world primitive；tracking/runtime ID/ordering 留在 endpoint                    |
| EntityChunk                             | 网络没有 Region slot、DataVersion 或 root tree 边界                                        | 不建立 EntityChunk packet codec                                                         |
| PoiChunk                                | 无普通完整同步；debug view 有限                                                            | 无整体 codec；需要时提供明确单向有损 projector                                          |
| DataPack、registry、tags、feature flags | Configuration 阶段专门投影                                                                 | protocol-configuration                                                                  |
| LevelDat、world generation settings     | 无整体 packet，只提供配置和 bootstrap 事实                                                 | world-format resolver + world-io orchestration；网络 resolver 在 protocol-configuration |
| PlayerData                              | 多组连接期状态的有限视图                                                                   | 不建整体 codec；逐功能 projector 需单独证据                                             |
| MapData、ScoreboardData                 | snapshot/delta 或 ordered packet view                                                      | 纯 projector 可进 protocol-world，订阅和顺序归调用方                                    |
| advancements、statistics                | 仅部分对应 clientbound updates                                                             | 只建立证据支持的单向/delta projector                                                    |
| raids、tickets、random sequences 等     | 无通用客户端等价表示                                                                       | 不建立网络转换                                                                          |
| Anvil、compression、DataVersion         | 纯持久化表示                                                                               | 永不进入 protocol-world                                                                 |

阶段 A 建立 world projection inventory，将每项标为 `bidirectional-codec`、`one-way-projector`、
`endpoint-orchestration` 或 `no-network-representation`。

## 5. DataPack 与 Configuration 拆分

`protocol-datapack` 改为 `protocol-configuration`。当前 `protocol-datapack-vanilla` 拆成：

- `datapack-vanilla`：`VanillaDataPacks`、官方 DataPack archive/parsed payload、core/built-in packs 和 stack completion；
- `protocol-configuration-vanilla`：`VanillaRegistryData`、`VanillaConfigurationData`、静态 registry、Configuration defaults
  和 projector。

通用 `DataPackArchive`、`DataPack`、`DataPackStack`、`ResolvedDataPackStack` 和 `WorldDataPackLoadResult` 仍在
`world-format`。 两个 vanilla provider 不互相依赖，也不共享生成输出。

`WorldChunkContexts` 的持久化 adapter 属于 `world-format`；Configuration evidence adapter 属于 `protocol-configuration`。
`ResolvedMinecraftWorld` 拆为 `WorldChunkContexts`、`ResolvedConfigurationData` 和连接期 context，不保留同职责的改名聚合物。

## 6. 命名重构

### 6.1 总规则

- `Protocol` 只用于网络协议本身、协议侧 module/package、协议版本或上游正式命名的扩展机制。
- 大小写不敏感地审计 `protocol`，覆盖 module、package、文件、类型、方法、参数、字段、局部变量、测试、agent skill、Gradle
  task/configuration、生成目录和输出路径；不能只检查公开类型或 `Protocol` 前缀。
- 一个值会被协议实现使用、当前位于 protocol module，或最终能够编码进 packet，都不足以让它使用 `Protocol` 命名；名称必须描述
  该声明自身拥有的网络语义。通用下载、JSON、进程、世界、DataPack、asset 和测试样本设施使用各自领域名称。
- package 与 module 按声明的语义所有者命名。非网络实现从 `com.hiczp.minecraft.protocol.*` 移出；真正连接 world value 与
  packet 的 projection、Configuration stage、packet model/codec、session、transport 和 endpoint 仍属于 protocol 侧。
- `Packet` 只用于网络 packet model；所有 packet class 以 `Packet` 结尾。
- NBT、Anvil、Region、DataPack、ResourcePack、KnownPack、Configuration 等采用官方术语。
- 不把官方 runtime 容器名机械带入纯数据模型，因此保留 `Chunk` 而不是 `LevelChunk`。

### 6.2 已确定的模块与 identifier 改名

| 当前                                                                                      | 目标                                                                                                       |
|-------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| protocol-datapack                                                                         | protocol-configuration                                                                                     |
| `com.hiczp.minecraft.protocol.datapack`                                                   | `com.hiczp.minecraft.protocol.configuration`                                                               |
| protocol-datapack-vanilla                                                                 | datapack-vanilla + protocol-configuration-vanilla                                                          |
| `com.hiczp.minecraft.protocol.datapack.vanilla`                                           | `com.hiczp.minecraft.world.format.datapack.vanilla` + `com.hiczp.minecraft.protocol.configuration.vanilla` |
| MinecraftProtocolFormat / MinecraftProtocolFormatConfiguration                            | MinecraftPacketFormat / MinecraftPacketFormatConfiguration                                                 |
| ConfiguredMinecraftProtocolFormat                                                         | ConfiguredMinecraftPacketFormat                                                                            |
| ProtocolRegistryContext / installProtocolRegistryContext / resolveProtocolRegistryContext | PacketCodecContext / installPacketCodecContext / resolvePacketCodecContext                                 |
| ProtocolRegistry / ProtocolRegistryEntry                                                  | RegistryIdMap / RegistryIdMapping                                                                          |
| ProtocolBlockState                                                                        | BlockStateIdMapping                                                                                        |
| ProtocolSampleProfile / MinimalProtocolValueDecoder / protocolValue                       | PacketSampleProfile / MinimalPacketValueDecoder / packetSampleValue                                        |
| ProtocolData / ResolvedProtocolData / VanillaProtocolData                                 | ConfigurationData / ResolvedConfigurationData / VanillaConfigurationData                                   |
| toProtocolData / toVanillaProtocolData                                                    | toConfigurationData / toVanillaConfigurationData                                                           |
| DataPackProtocolProjector / vanillaDataPackProtocolProjector                              | DataPackConfigurationProjector / vanillaDataPackConfigurationProjector                                     |
| MinecraftProtocolTarget / minecraftProtocolTarget / readMinecraftProtocolTarget           | OfficialMinecraftTarget / officialMinecraftTarget / readOfficialMinecraftTarget                            |
| MinecraftProtocolToolSupport.kt / MinecraftProtocolToolSupportTest                        | MinecraftToolSupport.kt / MinecraftToolSupportTest                                                         |
| protocolJson                                                                              | buildLogicJson                                                                                             |
| protocolRef / `protocol-reference`                                                        | minecraftArtifactsRoot / `minecraft-artifacts`                                                             |
| ProtocolHttp                                                                              | DownloadHttp                                                                                               |
| ProtocolSurfaceChunkProjector                                                             | WorldSurfaceChunkProjector                                                                                 |
| `.agents/skills/minecraft-protocol-vanilla-data`                                          | `.agents/skills/minecraft-vanilla-data`                                                                    |
| MinecraftClientProtocol / MinecraftServerProtocol                                         | MinecraftClientNegotiation / MinecraftServerNegotiation                                                    |

类型族改名同步应用到由类型派生的参数、字段、局部变量、helper、测试与 diagnostics；不保留仅反映旧实现历史的 `protocol*` 名称。
module/package/skill 行同时覆盖手写源码、generated package、Android namespace、skill metadata、source output path、Gradle
project dependency 和 publication。
`demo:web-map` 随 world/data-pack 重构移除为 `Identifier`、`MinecraftBlockIds`、`MinecraftProtocol.MINECRAFT_VERSION` 和
`VanillaRegistryData` 引入的 protocol-module 依赖。

### 6.3 packet 官方名称审计

所有 `@PacketInfo` 声明都与匹配版本的官方 packet report、class、producer 和 consumer 对照，不只审计 Chunk packet。

默认命名规则：

1. 使用官方 Java simple name，去掉 `Clientbound`/`Serverbound`。
2. 去掉方向后冲突时保留方向；跨状态仍冲突时增加最小状态前缀。
3. 字段采用官方 record component/稳定字段名和顺序；Kotlin 关键字使用反引号。
4. 偏离官方名称只能因为冲突、Kotlin 限制、强类型聚合或刻意排除 runtime 容器概念，并记录 exception。

`ChunkDataAndUpdateLightPacket` 推荐改为 `ChunkWithLightPacket`：保留官方 `WithLight`，省略方向和不适用于本库模型的
`Level`。
`WorldEventPacket` 同样是允许的已说明偏离。其他 packet 由生成的 rename map 和 exception 清单决定，不在计划中维护手工样例列表。

扩展官方 packet class report，并让 KSP/测试保证：每个本地 packet 有官方对应、名称符合规则或存在有效 exception、exception
无失效项、 字段审计完成。

## 7. 实施顺序

### A. 建立审计清单

1. 固定当前模块依赖、跨层 conversion、大小写不敏感的 `protocol` module/package/file/identifier/agent skill/Gradle 名称和
   `@PacketInfo` 清单。
2. 为每个 `protocol` 名称记录语义所有者；非网络项进入 rename map，保留项必须直接表示 wire、连接状态、协议版本/ID/table、
   protocol-side boundary 或有可定位上游名称。
3. 扩展官方 packet class report，生成 packet rename 建议并审查 exception。
4. 建立 world projection inventory。
5. 在迁移公开类型前确定最终 rename map，并让后续新增 `protocol` 名称接受同一语义检查。

### B. 收敛 world-format

1. 不新增 `world-model`，按 chunk/entity/poi/saveddata/datapack/anvil 子域整理现有文件。
2. 建立四项 `ChunkContext`、`WorldChunkContexts`、标准 `BlockState`/`BiomeId` 和非泛型 Chunk。
3. 把 ticks、structures、generation continuation 和统一 Block Entity data 纳入 Chunk。
4. 从 Chunk/EntityChunk/PoiChunk 分离纯持久化 metadata；完整文件 schema 不做机械拆分。
5. 保持 DataPack 表示阶段和 Anvil/container 边界不变。

### C. 重建持久化路径

1. 拆出 Chunk、EntityChunk、PoiChunk 的定向 NBT encoder/decoder 和 decode result。
2. `world-io` handle/scope 在构造时绑定 codec，typed write 接受任意合法语义值和显式 metadata。
3. 审计并重写现有 conversions；raw NBT/compressed/Region API 保留为 escape hatch。

### D. 重建网络 model 与 protocol-world

1. 完成 `PacketCodecContext`、`MinecraftPacketFormat` 和 packet 命名迁移。
2. `ChunkWithLightPacket` 改为纯网络 payload，移除依赖 Chunk layout 的 physical serializer 分支。
3. 新建 `protocol-world`，实现 Chunk 双向 primitive 和不对称 context。
4. 迁移 client/server 中可复用的 Entity pairing conversion；EntityChunk、POI 和其他 world value 按 inventory 处理。
5. 删除 `MinecraftChunkSnapshot`；`MinecraftEntitySnapshot` 改成有独立网络语义的名称并迁移，或由 primitive encoder 取代。

### E. 拆分 Configuration 与 vanilla providers

1. 完成 `protocol-configuration` 类型/package 迁移，移出普通 Play world codec。
2. 拆出 `datapack-vanilla` 与 `protocol-configuration-vanilla`，重新归属生成器及其唯一输出。
3. 保持 DataPack stack completion 与 Configuration projection 为两个显式步骤。

### F. 收敛 endpoint

1. client/server 只调用 `protocol-world` primitive，不保留可复用的 Chunk/Entity projection 实现。
2. endpoint 保留 negotiation、epoch/dimension context factory、tracking、runtime ID、可见性、packet ordering 和 enqueue。
3. reconfiguration/respawn 原子切换后续 codec context，已开始的 flow 使用旧快照。
4. vanilla 默认策略由 endpoint factory 选择，mod override 仍显式。

### G. 重建便利 API 与文档

1. Primitive API 稳定并通过测试后再实现 fluent extensions。
2. 每个 extension 显式接收配置并以等价测试证明其委托 primitive。
3. 清理跨层 shortcut、隐式默认、旧 module/package、兼容 alias 和未使用 naming exception。
4. 更新 settings、publication、README、AGENTS、demo、测试和相关 `.agents` skills；README 只描述已经实现的 API。

## 8. 验证与完成标准

### 8.1 重点测试

| 范围                             | 必须证明                                                                                                                                 |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| world-format world values        | context 引用复用、sparse Section、不必要泛型消失、ticks/structures 非空语义、EntityChunk/PoiChunk 不含 DataVersion                       |
| world-format persistence         | NBT schema、metadata 无损保留、网络 Chunk 可写盘、Entity/POI 定向 codec、codec 配置权威性、Anvil/compression/raw escape hatch            |
| world-io                         | 每维度 codec 绑定、Region slot 只选择 record、自带位置的 NBT 不做隐式 placement 校验、sidecar/timestamp 边界、写入恢复、无 protocol 依赖 |
| protocol-model/serialization/KSP | 官方 packet identity/name/field、纯 Chunk packet payload、PacketCodecContext 无 layout、官方 codec oracle                                |
| protocol-world                   | palettes、heightmaps、lighting、Block Entity、default provider、Entity pairing、projection inventory、无 endpoint/IO 依赖                |
| protocol-configuration/vanilla   | Known Packs、registry order、WorldChunkContexts/PacketCodecContext resolution、两个 vanilla provider 无反向依赖                          |
| protocol-client/server           | epoch 和维度切换、无跨来源 layout/default/Section count 校验、initial world、Entity tracking 边界、官方 interoperability                 |
| fluent/escape hatch              | 显式配置、结果等价、资源所有权不变、库内 production source 不调用或读取便利 API                                                          |

### 8.2 验证命令

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

### 8.3 完成标准

- 三层类型只保存本层事实，所有转换只经过相邻 primitive codec。
- 磁盘和网络使用同一个 Chunk；客户端默认值和有损写盘行为有明确文档。
- codec 始终使用显式配置，库内不读取 `Chunk.context` 或调用 fluent API。
- 不存在 `world-model`；`world-format`、`world-io`、`protocol-world` 和 `protocol-configuration` 依赖方向符合本计划。
- Chunk/Entity/POI 和其他 world values 的网络归属全部进入 projection inventory，不存在为对称而制造的 codec。
- client/server 不复制 Chunk codec 或 Entity pairing conversion。
- 所有大小写形式的 `protocol` module、package、文件、代码 identifier、agent skill、Gradle wiring 和生成路径均已分类并处理；
  剩余名称直接表示网络协议语义或有可定位的正式上游名称，packet 名称符合官方规则或存在有效 exception。
- 22 个目标 subprojects、publication、文档、生成器和 skills 均反映新边界。
- 目标平台测试、官方 codec oracle 和官方 endpoint fixtures 通过。

## 9. 待确认设计事项

1. Chunk 各字段的最终 typed model，以及 `ChunkPacketDefaultProvider` 对应默认值，尤其 scheduled tick 时间基准、structure
   start 和 generation-stage entities。
2. Entity 的 subtype 泛型、网络缺失字段默认策略，以及 `MinecraftEntitySnapshot` 的最终替代形态。
3. `ChunkWithLightPacket` 和 packet 方向前缀规则的最终确认。
4. 是否把现有 `Identifier` 提升为层无关 `ResourceLocation`；不能因此引入反向依赖或宽泛 core 模块。
