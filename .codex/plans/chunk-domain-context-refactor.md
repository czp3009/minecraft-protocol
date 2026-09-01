# Region 语义值、Chunk 上下文与网络投影重构计划

- 状态：设计已确认，待实施
- 记录日期：2026-09-02
- 适用版本：仓库所选择的 Minecraft 官方版本；本计划不改变版本选择
- 主要模块：`world-format`、`world-io`、`protocol-datapack`、`protocol-client`、`protocol-server`
- 验证模块：`protocol-model`、`protocol-serialization`、`protocol-datapack-vanilla`、`demo/web-map`

## 1. 最终结论

本次重构采用“数据只保存本层事实，上下文只保存在转换器或临时 view 中”的设计。

1. 标准领域 Chunk 统一为 `Chunk<BlockStateValue, BiomeValue>`。`BlockStateValue` 只保存持久化名称与 properties，
   `BiomeValue` 只保存持久化名称；两者都不保存协议 raw ID、alias 集合或 protocol default 标记。
2. 保留 `Chunk<B, M>` 泛型以及 caller-supplied registries，作为自定义运行时值和 mod 的逃生窗口；但仓库提供的标准磁盘、服务端和
   客户端路径不得再实例化 `Chunk<ProtocolBlockState, ProtocolRegistryEntry>`。
3. `Chunk` 不持有 `ChunkContext`，也不持有 `ChunkLayout`、默认方块或默认 biome。不可变且可复用的
   `ChunkContext<B, M>` 由 `ChunkNbtCodec`、packet encoder/decoder、factory 或临时 `ChunkView` 持有。
4. 磁盘独有状态与网络/领域公共状态使用两个名义层次：
   `StoredChunk<B, M>(chunk, storageMetadata)` 是可持久化结果，`Chunk<B, M>` 是协议无关的可计算 core。网络解码只返回
   `Chunk`，绝不虚构 `StoredChunk`。
5. 网络 numeric IDs 只存在于当前连接的 `ProtocolRegistryContext`、显式 `ChunkProtocolMapping` 和官方 packet model 中。
   packet encoder/decoder 捕获一个协商 epoch 的 context；Chunk 本身在 data-pack reload、registry reorder 或重协商之间保持稳定。
6. `EntityChunk<E>` 与 `PoiChunk` 已经基本遵守这个边界：保留逐记录的持久化事实，不加入协议 ID 或 Chunk layout/default。
   本计划会补齐 API 对称性、上下文所有权、测试和文档，但不会为了形式统一制造无意义的 `EntityChunkContext` 或
   `PoiChunkContext`。
7. 删除与官方 Chunk packet 一对一重复的 `MinecraftChunkSnapshot`；网络层直接使用
   `ChunkDataAndUpdateLightPacket`。`MinecraftEntitySnapshot` 暂时保留，因为它是一个 Entity 到多条 pairing packets 的必要投影，
   并非单个官方 packet 的同构副本。
8. 不提供旧 API typealias、deprecated overload 或双轨 adapter。本仓库处于早期阶段，实施时一次性迁移源码、测试、README 和
   `AGENTS.md`。

## 2. 最终数据流架构

### 2.1 普通 Chunk Region

```text
region/.mca + optional .mcc
  │  Region container context: position, timestamp, compression, sidecar placement
  ▼
compressed Region record
  │  world-io decompression + ChunkNbtCodec(ChunkContext, NbtFormat)
  ▼
StoredChunk<BlockStateValue, BiomeValue>
  ├─ chunk: Chunk<BlockStateValue, BiomeValue>       协议无关的稳定领域 core
  └─ storageMetadata: ChunkStorageMetadata           只属于持久化 NBT
            │
            │  只把 storedChunk.chunk 交给网络投影
            ▼
MinecraftChunkPacketEncoder(
    ChunkContext,
    ChunkProtocolMapping from the active negotiated registry snapshot,
    block semantics and Block Entity update-tag policy,
)
  ▼
ChunkDataAndUpdateLightPacket                         只含官方 packet 字段和 wire IDs
  │  MinecraftProtocolFormat(active ProtocolRegistryContext)
  ▼
wire bytes
  │  client MinecraftProtocolFormat(the same negotiated epoch)
  ▼
ChunkDataAndUpdateLightPacket
  │  MinecraftChunkPacketDecoder(ChunkContext, ChunkProtocolMapping)
  ▼
Chunk<BlockStateValue, BiomeValue>                   与服务端使用同一领域类型和语义
```

这里必须明确一个信息论边界：官方 Chunk packet 不包含 `DataVersion`、生成状态、timestamps、ticks、structures、升级/混合数据和
其他持久化字段；它还可能只携带 client heightmaps 和 Block Entity update tag。因此客户端返回的只能是同一 `Chunk` core
类型，不能是 与磁盘解码结果相同的 `StoredChunk`。本计划所说的“客户端与服务端一致”具体保证：

- 两端使用完全相同的 `Chunk<BlockStateValue, BiomeValue>` 名义类型；
- block-state、biome、坐标、Section 逻辑值、可在线表达的 lighting/heightmap/Block Entity 投影语义一致；
- 一致性按逻辑值比较，不要求保留磁盘 local palette 的闲置条目、palette ID 排列或 alias 拼写；
- packet 没有携带的持久化事实不会被默认值冒充，也不参与这一相等性承诺。

### 2.2 Entity Region

```text
entities/r.<x>.<z>.mca
  │  world-io decompression + EntityChunkNbtCodec(EntityDataRegistry, NbtFormat)
  ▼
EntityChunk<E>
  ├─ Chunk position and DataVersion
  └─ root Entity<E> trees with persisted common state and subtype data
```

Entity Region 没有对应的单个官方“Entity Chunk packet”。服务端只能把其中的单个 `Entity<E>` 再结合运行时 entity
ID、tracking、metadata、 attributes、equipment 和关系状态投影为一组 pairing packets；客户端也只能从这些 packets 恢复线上可见的
Entity 状态。因此：

- `EntityChunk` 停留在持久化/服务端世界层，不进入 packet model；
- `Entity` 可以作为两端共享的公共模型，但客户端值不冒充完整 subtype persistent NBT；
- 连接 entity ID、registry raw ID、metadata indices 和 tracking state 继续只在网络 encoder/snapshot context 中；
- `EntityDataRegistry<E>` 继续由 `EntityChunkNbtCodec` 捕获，不放进 `Entity` 或 `EntityChunk`。

### 2.3 POI Region

```text
poi/r.<x>.<z>.mca
  │  world-io decompression + PoiChunkNbtCodec(NbtFormat)
  │  Region slot supplies ChunkPosition because POI NBT has no x/z root field
  ▼
PoiChunk
  ├─ ChunkPosition and DataVersion
  └─ Section validity and PoiRecord values
```

POI 没有普通客户端 Chunk 同步包。debug subscription 中的 POI 数据只是另一种有限网络投影，不能当作 `PoiChunk` codec。POI
type 本来就是 持久化字符串，所以默认 codec 不需要 registry context；modded type 自然保留。需要不同 schema 的调用方继续使用
raw NBT 或自定义 codec。

## 3. 每层数据所有权与禁止项

| 层                           | 允许保存                                                                               | 明确禁止保存                                                                                                        |
|------------------------------|----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| Region container             | local position、timestamp、compression ID、inline/external placement、compressed bytes | block/biome 领域对象、协议 registry、dimension defaults                                                             |
| `ChunkContext<B, M>`         | dimension layout、默认 B/M、磁盘名称与 B/M 的双向 mapper                               | Chunk position、Section、timestamp、DataVersion、协议 raw ID                                                        |
| `StoredChunk<B, M>`          | 一个 `Chunk` 引用和该记录真实的 `ChunkStorageMetadata`                                 | codec/context、Region compression/timestamp、连接 registry                                                          |
| `Chunk<B, M>`                | position、Section palettes、Block Entities、公共 heightmap/lighting 数据               | `ChunkContext`、layout/default 副本、storage nullable 分支、`ProtocolBlockState`/`ProtocolRegistryEntry` 的标准实例 |
| `EntityChunk<E>`             | position、DataVersion、root entity trees                                               | Entity codec/registry、dimension layout、连接 entity ID、protocol raw ID                                            |
| `PoiChunk`                   | position、DataVersion、POI sections/records                                            | POI codec、dimension layout/default、protocol raw ID、Region timestamp/compression                                  |
| `ChunkProtocolMapping<B, M>` | 当前协商快照中的 value↔wire-ID lookup、registry sizes、aliases                         | Chunk 内容、持久化 metadata、Region 信息                                                                            |
| official packet models       | 官方字段、官方 numeric IDs、官方 packed palettes/masks                                 | 磁盘 metadata、domain context、library-only snapshot wrapper                                                        |

`DataVersion` 即使常常相同，也必须留在每个存储记录中：文件本身逐记录保存它，真实存档可以混有不同版本，codec 不得拿一个世界级默认值覆盖。
`ChunkPosition` 也不是可复用 context：它随 Region slot 改变，是记录身份和绝对坐标操作所必需的值。POI 的 position 虽不在 NBT
root 中，仍是 由 Region container 提供的记录身份，而不是 dimension context。

## 4. 当前实现的切实审计

### 4.1 普通 Chunk 的问题

当前 `ChunkModels.kt`、`ChunkNbtCodec.kt` 和 client/server projection 存在这些交叉污染或重复：

1. `Chunk<ProtocolBlockState, ProtocolRegistryEntry>` 把一次 registry snapshot 带进磁盘领域值。
    - 磁盘 block palette 只有 `Name` 与 `Properties`；`ProtocolBlockState.id` 和 `isDefault` 不在文件里。
    - 磁盘 biome palette 只有名称；`ProtocolRegistryEntry.rawId` 与 `aliases` 不在文件里。
    - registry reorder 会改变对象 equality 和 palette 去重，即使磁盘内容完全没变。
    - alias 可在磁盘 decode 时命中 registry，随后 encode 却写回 canonical ID，产生不必要的跨层规范化。
2. 每个 `Chunk` 重复保存 `chunkLayout`、`defaultBlockState` 和 `defaultBiome`，而 `ChunkNbtCodec` 的
   `ChunkCodecContext` 已经保存相同事实。packet encoder/decoder 又各保存一份相同 context。
3. `ChunkMetadata.chunkStorageMetadata: ChunkStorageMetadata?` 把两个能力不同的来源塞进一个可空字段。磁盘 decode
   总有该值，packet decode 永远无法得到它，而 disk encoder 又拒绝 null。
4. `BlockEntity.persistentData` 在 packet decoder 中实际接收的可能只是 update tag。名字把一个有限网络投影误称为完整持久化数据；计划中要
   改成显式 persistent/update provenance variants，并让 `StoredChunk`/disk codec 验证“可持久化完整性”。
5. 磁盘 Chunk 可稀疏保存 Section，packet 却总含 dimension 高度内的完整 Section 列表。当前 server encoder 会补默认
   Section，client decoder 会保留所有 Section，因此两端虽是同一 class，却没有统一 canonical sparse 规则。
6. `MinecraftChunkContext` 同时保存 `dimensionId`、`DimensionTypeLayout`、`ProtocolRegistryContext`、协议值 registries、
   `ChunkCodecContext`、`ChunkNbtCodec`、`defaultBlockId` 和 `defaultBiomeId`。其中多项互相可导出，后两个字段当前没有生产读取者。
7. `ResolvedMinecraftWorld.dimensions` 的 map key 已经是 `DimensionId`，value 中再保存同一个 ID 是重复身份。
8. `MinecraftChunkSnapshot` 与 `ChunkDataAndUpdateLightPacket` 字段一一对应，只多一次包装和 `.packet()` 转换，应删除。

### 4.2 Entity Chunk 审计

`EntityChunkModels.kt` 与 `EntityChunkNbtCodec.kt` 的默认路径没有协议污染：

- `EntityChunk` 只保存 NBT root 的 `DataVersion`、`Position` 和 `Entities` 对应语义；
- `Entity.type`、UUID、Pos、Motion、Rotation、Passengers 都来自文件；structural fields 会从 subtype `persistentData` 中剥离，避免重复；
- 默认 `NbtEntityDataRegistry` 原样保留其他 subtype/mod NBT；自定义 `EntityDataRegistry<E>` 是显式逃生窗口；
- codec 捕获 registry 和 NBT format，解码结果不保存它们；
- root Entity 的当前 position 必须属于 `EntityChunk.chunkPosition`，passenger 可以按自己的位置落在其他 Chunk；
- empty Entity Chunk 写入会清除 Region slot，这是 store policy，不应成为 EntityChunk 字段。

应保留的看似重复字段：root `EntityChunk.chunkPosition` 与每个 Entity 的 position 不能互相替代。前者是 Region record
identity，后者是实体实际 坐标；空 Entity Chunk 和跨 Chunk passenger 也使推导不可行。

需要补强的边界：网络只投影单个 Entity 的线上公共状态，不能构造带 `DataVersion` 和完整 root/passenger 持久化树的
`EntityChunk`。
`MinecraftEntitySnapshot` 中的 connection entity ID 和 pairing state 属于网络投影层，不能反向移动到 `Entity`。

### 4.3 POI Chunk 审计

`PoiChunkModels.kt` 与 `PoiChunkNbtCodec.kt` 同样没有协议污染：

- NBT root 只含 `DataVersion` 与 `Sections`；`PoiChunk.chunkPosition` 正确地由 Region slot 注入；
- Section Y 是持久化 compound key；record 的绝对 `pos` 也是真实持久化字段。空 Section 仍可携带 `Valid`，所以不能从 records
  推导并删除 Section Y；
- `type` 和 `free_tickets` 原样保存，没有 registry raw ID；
- codec 只捕获 unnamed-root `NbtFormat`，没有值得抽成数据字段的上下文；
- mutable/live POI handles 已经内置一个 codec，读写时从对象 position 选择 Region slot。

需要保留的逃生窗口是 raw NBT/document/source APIs。当前 POI codec 对未知 schema 字段是严格的；mod 如果扩展了
root/section/record schema， 应显式选择 raw path 或自定义 `RegionValueCodec<PoiChunkLike>`，而不是让标准 `PoiChunk`
悄悄吞掉未知数据。

### 4.4 world-io Region API 审计

三类 handle 的容器职责目前是正确分开的：普通、Entity、POI 分别使用 `RegionReadScope`、`EntityRegionReadScope` 和
`PoiRegionReadScope`，并共享更低层的 Anvil/decompression/NBT stream 能力。还需要统一以下语义：

- 普通 Chunk typed read/write 改为 `StoredChunk<B, M>`，因为它是唯一需要同时表达 network-shared core 和 NBT-only metadata
  的类型；
- `DecodedChunkRegionReadScope` 捕获一个 `ChunkNbtCodec`，调用 `readChunk(position)` 时不再重复传 context；
- Entity 的默认 NBT registry、caller-supplied codec 和 decoded scope 继续沿用现有模式；
- POI codec 继续由 handle/scope 捕获，不增加无意义参数；
- read 时 Region slot position 必须传给 codec：普通/Entity 用于校验 NBT 自带 position，POI 用于补全记录身份；
- semantic write 继续从 value 自带 position 选择 slot，不再同时要求一个可能冲突的目标 position；raw NBT write 仍显式指定
  slot；
- `RegionChunkInfo.timestampEpochSeconds`、compression 和 sidecar 信息不进入任何 semantic Chunk。需要 timestamp 的 web map
  继续从 Region metadata 读取并与 semantic result 在应用层组合。

## 5. 目标领域类型

以下声明是目标 API 的设计草图，具体文件拆分可在实现时按现有 package 约定调整；语义和依赖方向不得改变。

### 5.1 稳定 block-state 与 biome

```kotlin
data class BlockStateValue(
    val name: String,
    val properties: Map<String, String> = emptyMap(),
)

@JvmInline
value class BiomeValue(val name: String)
```

- `BlockStateDescriptor` 重命名为 `BlockStateValue`，因为它不只是 codec descriptor，而是标准领域 palette value；
- `String` biome 包装为 `BiomeValue`，防止与任意字符串混用，并让 `Chunk` 的两个泛型角色清晰；
- 两者保存磁盘和跨端都稳定的 namespaced text，不依赖 `protocol-model.Identifier`，保持 `world-format` 依赖方向；
- 默认只验证所选版本持久化 schema 的内在要求。modded 名称是否允许额外形式由 caller registry 决定；不要用当前连接 registry
  决定磁盘 值是否合法。

保留并调整现有逃生接口：

```kotlin
interface BlockStateRegistry<B : Any> {
    val defaultValue: B

    fun resolve(blockStateValue: BlockStateValue): B?

    fun describe(value: B): BlockStateValue?
}

interface BiomeRegistry<M : Any> {
    val defaultValue: M

    fun resolve(biomeValue: BiomeValue): M?

    fun describe(value: M): BiomeValue?
}
```

标准实现是开放映射 `BlockStateValueRegistry` 和 `BiomeValueRegistry`；custom/mod callers 可继续把磁盘值映射为自己的
B/M。标准实现不得查询
`ProtocolRegistryContext`。

### 5.2 codec-owned `ChunkContext<B, M>`

```kotlin
class ChunkContext<B : Any, M : Any>(
    val dimensionTypeLayout: DimensionTypeLayout,
    val blockStates: BlockStateRegistry<B>,
    val biomes: BiomeRegistry<M>,
) {
    val chunkLayout: ChunkLayout
        get() = dimensionTypeLayout.chunkLayout
}
```

选择保存 `DimensionTypeLayout` 而不是同时保存它和 `ChunkLayout`：disk codec 使用 `chunkLayout` getter，packet codec 还可使用
`hasSkyLight`；没有两份可能失配的 layout state。context 是普通 class，registry/strategy 保持 identity
semantics，不生成会意外复制旧策略的
`copy()`。

硬约束：

- `ChunkContext` immutable，可在同一 dimension 的所有 Chunk codec、views 和 packet projectors 之间按引用复用；
- `ChunkContext` 不得成为 `Chunk` constructor property，`Chunk.snapshot()` 也不复制或保留它；
- codec/view 构造时捕获 context，逐次 encode/decode 不重复传 layout/default/registries；
- 不以 context identity 作为数据相等性。encoder 通过 context 验证 Chunk 的 Section Y、Block Entity Y 和可表示值；
- persistent TAG_Byte Section-Y 限制由 `ChunkNbtCodec` 校验，不污染可供自定义非 NBT codec 使用的 `ChunkContext`。

### 5.3 context-free `Chunk<B, M>`

```kotlin
class Chunk<B : Any, M : Any>(
    val chunkPosition: ChunkPosition,
    chunkMetadata: ChunkMetadata,
    sections: Collection<ChunkSection<B, M>> = emptyList(),
    blockEntities: Collection<BlockEntity> = emptyList(),
)
```

删除 `chunkLayout`、`defaultBlockState`、`defaultBiome` 和任何 context 引用。`Chunk` 自身只执行不需要 dimension context
的操作：

- position/XZ membership、显式 Section/Block Entity lookup；
- 已存在 Section 内 palette 的读写；
- palette snapshot/compact；
- metadata 和 Block Entity 的逐记录操作；
- detached snapshot。

依赖缺省语义或 layout 的操作迁入 context-bound view：

```kotlin
val chunkView: ChunkView<B, M> = chunkContext.bind(chunk)
val blockState: B = chunkView.block(blockPosition)
chunkView.setBlock(blockPosition, replacement)
val biome: M = chunkView.biome(blockPosition)
```

`ChunkView` 只是 `(ChunkContext, Chunk)` 的临时操作 facade：不序列化、不进入 packet、不作为 Chunk property、不拥有
snapshot。它在 bind 时验证 所有 Section 和 Block Entity 的 vertical membership。`getOrCreateSection`、absent-section
default reads、写入默认值时的 Section pruning 等操作 都由 view 完成。

### 5.4 sparse canonical 规则

为使 disk decode、server Chunk 和 client packet decode 使用同一逻辑表示，`ChunkContext` 定义统一规范化规则：

1. block palette 全为 context default、biome palette 全为 context default 且没有 Section lighting 时，语义 Section 省略；
2. 内容全为 defaults 但有 lighting 时，只在 `ChunkMetadata.lightOnlySections` 保存 lighting；
3. 其他 Section 保存一个 `ChunkSection`；
4. disk decoder 与 packet decoder 都执行这一规则；context-bound mutation 在一个 Section 回到默认状态时可显式 prune；
5. encoder 接受非 canonical caller-created Chunk，但先创建非突变 compact/canonical view，或在严格模式下报告；不得悄悄修改
   caller Chunk。

这样 absent Section 的含义只由绑定 context 解释，Chunk 不需要保存 defaults。内部 local palette IDs 只是
`PalettedContainer` 的压缩实现，不是 protocol IDs；它们不参与逻辑 equality。mutation 期间允许暂存未使用 palette
entries，disk/network encode 使用 non-mutating compact snapshot。

### 5.5 `StoredChunk<B, M>` 与 metadata

```kotlin
data class ChunkMetadata(
    val heightmaps: NbtCompound = NbtCompound(emptyMap()),
    val lightOnlySections: Map<Int, SectionLighting> = emptyMap(),
)

interface BlockEntityData

data class PersistentBlockEntityData(
    val nbtCompound: NbtCompound,
) : BlockEntityData

data class BlockEntityUpdateData(
    val updateTag: NbtCompound?,
) : BlockEntityData

class BlockEntity(
    val type: String,
    val blockPosition: BlockPosition,
    data: BlockEntityData,
) {
    var data: BlockEntityData = data
}

data class StoredChunk<B : Any, M : Any>(
    val chunk: Chunk<B, M>,
    val storageMetadata: ChunkStorageMetadata,
) {
    init {
        require(chunk.blockEntities.all { blockEntity -> blockEntity.data is PersistentBlockEntityData })
    }
}
```

`ChunkStorageMetadata` 保留当前逐记录字段：`DataVersion`、status、last/inhabited time、`isLightOn`
、upgrade/blending/retrogen、carving mask、ticks、post-processing、legacy entities 和 structures。它不保存 context、compression
或 timestamp。

- `ChunkNbtCodec.decode...` 返回 `StoredChunk<B, M>`；
- `ChunkNbtCodec.encode...` 只接受 `StoredChunk<B, M>`；
- typed Region read/write 使用相同类型；
- packet encoder 接受 `Chunk<B, M>`；packet decoder 返回 `Chunk<B, M>`；
- 不提供从 packet Chunk 自动生成 `StoredChunk` 的 convenience。调用方必须显式提供全部 storage metadata，并负责 packet 未携带的
  heightmaps；所有 Block Entity 还必须是 `PersistentBlockEntityData`；
- `StoredChunk` 不复制 Sections 或 metadata common fields，只引用一个 core Chunk。

Block Entity payload 不再只靠一个中性 NBT 字段和文档约定区分来源。disk decoder 构造 `PersistentBlockEntityData`，packet
decoder 构造
`BlockEntityUpdateData`；nullable update tag 与 empty compound 继续保持不同。`BlockEntityData` 故意是非 sealed
interface，mod 可以增加自己的 payload variant，并通过自定义 disk codec 或 `BlockEntityPacketProjector` 解释它。标准
`StoredChunk` constructor 先做 provenance 检查；由于 Chunk 和 Block Entity 是 mutable values，`ChunkNbtCodec.encode`
必须在每次写入边界重新检查，不能依赖一次性的 constructor validation。这样 packet Chunk 即使被调用方强行包装，也不会把
update tag 当成完整 subtype NBT 写回磁盘。

### 5.6 Entity 与 POI 目标模型

`EntityChunk<E>`、`Entity<E>`、`EntityDataRegistry<E>`、`PoiChunk`、`PoiSection` 和 `PoiRecord` 的主要 shape 保持。只做以下收口：

- 将可复用的默认 `NbtEntityDataRegistry` 实例化一次并由 handle/read scope 捕获；不放进 value；
- 保留 explicit `EntityChunkNbtCodec<E>` 入口以及 decoded read scope；
- POI handle/read scope 继续捕获一个 codec，不增加每次 read 参数；
- 若增加通用 `RegionValueCodec<T>` 以统一 world-io typed scopes，`ChunkNbtCodec`、`EntityChunkNbtCodec`、`PoiChunkNbtCodec`
  可实现它， 但接口只表达 `Source + ChunkPosition ↔ T`，不得塞入 filesystem、compression 或 protocol 概念；
- Entity/POI 的 raw NBT、document、serializer 和 compressed paths 全部保留，作为未知 mod schema 的 lossless escape hatch。

## 6. 网络投影上下文

### 6.1 `ChunkProtocolMapping<B, M>`

```kotlin
interface ChunkProtocolMapping<B : Any, M : Any> {
    val blockStateRegistrySize: Int

    val biomeRegistrySize: Int

    fun blockStateId(value: B): Int?

    fun blockState(id: Int): B?

    fun biomeId(value: M): Int?

    fun biome(id: Int): M?

    fun blockEntityTypeId(type: String): Int?

    fun blockEntityType(id: Int): String?
}
```

标准 factory：

```kotlin
val chunkProtocolMapping: ChunkProtocolMapping<BlockStateValue, BiomeValue> =
    protocolRegistryContext.toChunkProtocolMapping()
```

factory 捕获 exact `ProtocolRegistryContext` snapshot，进行以下转换：

- `ProtocolBlockState.id ↔ BlockStateValue(name, properties)`；
- biome `ProtocolRegistryEntry.rawId ↔ BiomeValue(name)`；
- block-entity-type raw ID ↔ persisted type name；
- loader aliases 只参与 lookup；packet decode 返回 context 的 canonical name；
- block-state/biome registry sizes 从同一个 snapshot 取得，供 direct palette bit width 使用，不从 global vanilla constants
  推断；
- Section count 由 `ChunkContext.dimensionTypeLayout` 提供，并在 factory/connection boundary 与 active physical format
  context 校验。

`ChunkProtocolMapping` 不公开或要求保存整个 `ProtocolRegistryContext`。标准实现只保留上述三类 registry 的必要 view、两个
size 和所需 inverse index，不复制无关 registries；这些 lookup/index 是 encoder/decoder context 的实现状态，不进入 Chunk 或
packet。physical
`MinecraftProtocolFormat` 仍持有完整 active context，mapping 和 format 必须从同一个 negotiation epoch 创建。

`ProtocolBlockState` 与 `ProtocolRegistryEntry` 继续保留在 `protocol-model`，因为它们准确描述协议 registry
snapshot；只是不能再作为标准 world Chunk palette values。

### 6.2 packet encoder/decoder

目标 constructors：

```kotlin
class MinecraftChunkPacketEncoder<B : Any, M : Any>(
    val chunkContext: ChunkContext<B, M>,
    val chunkProtocolMapping: ChunkProtocolMapping<B, M>,
    val blockSemantics: ChunkBlockSemantics<B>,
    val blockEntityPacketProjector: BlockEntityPacketProjector,
)

class MinecraftChunkPacketDecoder<B : Any, M : Any>(
    val chunkContext: ChunkContext<B, M>,
    val chunkProtocolMapping: ChunkProtocolMapping<B, M>,
)
```

`ChunkBlockSemantics<B>` 提供 `isAir` 与 `hasFluid`；`BlockEntityPacketProjector` 提供 update tag 策略。它们是
application/game-content policy，协议 raw ID 本身无法推导，继续显式注入。为保持现有轻量用法，可提供 lambda overload，但核心
constructor 必须完整 caller-constructible。

encoder：

- 用 `chunkContext` 解释 sparse defaults、layout 和 skylight；
- 用 mapping 解析 numeric IDs；
- 只把官方 client heightmaps 与 Block Entity update tags 投影进 packet；
- 返回 `ChunkDataAndUpdateLightPacket`，不返回平行 snapshot；
- 遇到磁盘合法但当前连接 registry 不可表示的 mod value 时，在这一边界给出带 position/palette value 的错误，不在 disk
  decode 提前拒绝。

decoder：

- 用 packet 中的 IDs 经 mapping 还原稳定 B/M；
- 用 context layout/defaults 执行 sparse canonicalization；
- block-entity type ID 还原为名称，tag 明确标为 update payload；
- 返回普通 `Chunk<B, M>`，不产生 storage metadata。

physical `MinecraftProtocolFormat` 仍使用相同 epoch 的 `ProtocolRegistryContext` 解释 Section count、palette widths 和其他
registry-aware wire values。semantic packet adapter 不接触 Source/Sink，physical format 不接触 world Chunk。

## 7. 上下文从哪里取得

### 7.1 服务端磁盘 context：world metadata + data-pack 编排

标准 stored-world 路径继续使用两个真实输入：

1. `WorldGenSettingsData` 给出存档声明的 dimensions 以及 referenced/inline dimension-type holder；
2. `WorldDataPackLoadResult -> DataPackStack -> ResolvedProtocolData` 给出启用 pack 后的完整 dimension-type registry
   data。

`resolveMinecraftChunkContexts`/`resolveMinecraftWorld` 只用这些数据解析 `DimensionTypeLayout`，然后创建开放的 stable
`ChunkContext<BlockStateValue, BiomeValue>`。它不得再调用
`completeProtocolRegistryContext.toChunkDataRegistries()` 把 block/biome raw IDs 注入 disk context。

输出建议调整为：

```kotlin
data class ResolvedMinecraftWorld(
    val protocolData: ResolvedProtocolData,
    val chunkContexts: Map<DimensionId, ChunkContext<BlockStateValue, BiomeValue>>,
) {
    fun chunkContext(dimensionId: DimensionId): ChunkContext<BlockStateValue, BiomeValue>
}
```

map key 是唯一 dimension identity，value 不再重复保存 `dimensionId`。server-negotiable `resolveMinecraftWorld` 仍额外验证
referenced dimension type 能在 synchronized registry 中取得 raw ID；disk-only `resolveMinecraftChunkContexts` 继续允许
inline holders。

默认 air/plains 是 resolver 的显式可覆盖参数，并被解析为 stable values，而不是 Protocol objects。mod 可通过以下方式替换：

- 自定义 `DataPackProtocolProjector`/`DataPackRegistryProjector` 编排 mod dimension-type registry；
- 直接提供 caller-created `ChunkContext<B, M>`；
- 对不能走标准 schema 的存档使用 raw NBT/custom codec。

### 7.2 服务端网络 context：完成协商后的当前连接

服务端 packet mapping 不能从长期保存的 disk context 或假定的 vanilla registry 创建。标准来源是：

```text
ResolvedMinecraftWorld.protocolData
  -> Configuration packets
  -> ServerNegotiationProfile.resolveProtocolRegistryContext(...)
  -> MinecraftServerConnection.protocolRegistryContext (authoritative active snapshot)
  -> ChunkProtocolMapping
```

`MinecraftServerNegotiationResult.minecraftDimensionContext` 验证所选 dimension identity/layout 与该 snapshot
一致；真正编码前仍读取
`minecraftServerConnection.protocolRegistryContext`，因为后续 reconfiguration 可替换它。packet encoder 构造时验证它的
`ChunkContext.dimensionTypeLayout` 与 negotiation dimension layout 一致。

### 7.3 客户端网络 context：Configuration capture + Play Login

客户端标准来源是现有协商链：

```text
RegistryDataPacket sequence + local StaticRegistrySchema
  -> ProtocolData.resolveSynchronizedRegistryContext(...)
  -> ClientNegotiationProfile.resolveProtocolRegistryContext(...)
  -> MinecraftClientConnection.protocolRegistryContext

PlayLoginPacket + synchronized dimension_type registry
  -> MinecraftDimensionLayout
  -> MinecraftDimensionContext
  -> stable ChunkContext selected by the application
```

client 创建 `ChunkContext` 时只从 `MinecraftDimensionContext` 取得 `DimensionTypeLayout`，默认 B/M 由应用或标准 stable
factory 选择；IDs 仍由 connection 的 current `ProtocolRegistryContext` 创建 mapping。这样 loader profile 已经应用的 remote
registry、alias、override 和 blocked entry 都能进入 mapping，而不会进入 Chunk。

### 7.4 context epoch 与失效规则

- `ChunkContext` 的生命周期跟 dimension layout/default/mapping policy 一致；普通 registry raw-ID reorder 不使它失效；
- `ChunkProtocolMapping` 和 packet encoder/decoder 的生命周期跟 exact active `ProtocolRegistryContext` epoch 一致；
- Configuration/reconfiguration、loader remap 或 registry reorder 后必须创建新的 mapping 和 packet codecs；
- 已加载的 stable Chunk 无需重读、改写或 remap；下一次发送时使用新 mapping；
- dimension 切换但 layout/defaults 相同也要显式选择对应 context，不能从 Chunk 猜 dimension；
- 如果 data-pack reload 改变 dimension layout 或应用选择的 defaults，则创建新 `ChunkContext`，并由 application 决定如何处理旧
  loaded Chunks；
- 一个已经包含 numeric IDs 的 packet 只属于创建它的 registry epoch。不要跨 reconfiguration 缓存并重发；也不要为了标记
  epoch 给官方 packet 添加 library-only 字段。

## 8. 理想状态示例代码

以下代码描述重构完成后的目标 API。每个外部值都通过参数或前置 producer 获得；示例不依赖隐藏全局默认或 repository-only
initialization。

### 8.1 从存档和 data packs 创建每维度 disk contexts

```kotlin
suspend fun resolveStoredServerWorld(
    minecraftWorldAccess: MinecraftWorldAccess,
): ResolvedMinecraftWorld {
    val worldGenSettingsData = checkNotNull(
        minecraftWorldAccess.data.read<SavedDataFile<WorldGenSettingsData>>(
            SavedDataId("world_gen_settings"),
        ),
    ).data
    val worldDataPackLoadResult = minecraftWorldAccess.dataPacks.readEnabled()
    val resolvedProtocolData = worldDataPackLoadResult.toVanillaProtocolData()
    return resolvedProtocolData.resolveMinecraftWorld(worldGenSettingsData)
}

val resolvedMinecraftWorld = resolveStoredServerWorld(minecraftWorldAccess)
val chunkContext = resolvedMinecraftWorld.chunkContext(dimensionId)
val chunkNbtCodec = ChunkNbtCodec(chunkContext)
```

复核：

- `worldGenSettingsData` 来自该存档；layout 不是 hardcode；
- `resolvedProtocolData` 来自该存档启用的 pack stack；referenced dimension type 可解析；
- `chunkContext` 只含 layout、stable defaults 和 disk value mappers，不含 protocol raw IDs；
- `chunkNbtCodec` 捕获 context，后续每次 read 不重复传 layout/defaults；
- `dimensionId` 只作为 map key/选择条件，不复制进 context value。

### 8.2 使用 LiveMinecraftWorldAccess 读取三类 Region

```kotlin
fun readLiveRegionValues(
    liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    dimensionId: DimensionId,
    regionPosition: RegionPosition,
    chunkNbtCodec: ChunkNbtCodec<BlockStateValue, BiomeValue>,
    entityChunkNbtCodec: EntityChunkNbtCodec<NbtCompound>,
): Triple<StoredChunk<BlockStateValue, BiomeValue>?, EntityChunk<NbtCompound>?, PoiChunk?> {
    val liveMinecraftDimension = liveMinecraftWorldAccess.dimensions[dimensionId]
    val localChunkPosition = LocalChunkPosition(0, 0)

    val storedChunk = liveMinecraftDimension.openRegion(regionPosition).use { liveRegionHandle ->
        liveRegionHandle.withReadScope(chunkNbtCodec) {
            readChunk(localChunkPosition)
        }
    }
    val entityChunk = liveMinecraftDimension.openEntityRegion(regionPosition).use { liveEntityRegionHandle ->
        liveEntityRegionHandle.withReadScope(entityChunkNbtCodec) {
            readChunk(localChunkPosition)
        }
    }
    val poiChunk = liveMinecraftDimension.openPoiRegion(regionPosition).use { livePoiRegionHandle ->
        livePoiRegionHandle.withReadScope {
            readChunk(localChunkPosition)
        }
    }
    return Triple(storedChunk, entityChunk, poiChunk)
}
```

复核：

- ordinary scope 捕获 `ChunkNbtCodec`，所以 read 只需要 record position；
- Entity scope 捕获 `EntityChunkNbtCodec`，`EntityDataRegistry` 不进入 Entity values；
- POI scope 自己拥有 codec，因为没有额外 semantic context；
- Region position/local position 是每条记录身份，不是重复 dimension context；
- timestamp/compression 仍在 Region scope。如果调用方需要 timestamp，应另读 `RegionChunkInfo` 并在应用 DTO 中组合，不修改
  semantic values。

### 8.3 读取磁盘 Chunk 并发送给一个已协商客户端

```kotlin
suspend fun sendStoredChunk(
    minecraftServerConnection: MinecraftServerConnection,
    minecraftServerNegotiationResult: MinecraftServerNegotiationResult,
    chunkContext: ChunkContext<BlockStateValue, BiomeValue>,
    storedChunk: StoredChunk<BlockStateValue, BiomeValue>,
    chunkBlockSemantics: ChunkBlockSemantics<BlockStateValue>,
    blockEntityPacketProjector: BlockEntityPacketProjector,
) {
    require(
        chunkContext.dimensionTypeLayout ==
                minecraftServerNegotiationResult.minecraftDimensionContext.minecraftDimensionLayout.dimensionTypeLayout,
    )
    val protocolRegistryContext = minecraftServerConnection.protocolRegistryContext
    val chunkProtocolMapping = protocolRegistryContext.toChunkProtocolMapping()
    val minecraftChunkPacketEncoder = MinecraftChunkPacketEncoder(
        chunkContext = chunkContext,
        chunkProtocolMapping = chunkProtocolMapping,
        blockSemantics = chunkBlockSemantics,
        blockEntityPacketProjector = blockEntityPacketProjector,
    )
    val chunkDataAndUpdateLightPacket = minecraftChunkPacketEncoder.encode(storedChunk.chunk)
    minecraftServerConnection.outgoing.send(chunkDataAndUpdateLightPacket)
}
```

复核：

- packet encoder 只接收 `storedChunk.chunk`，storage metadata 没有机会进入 packet；
- mapping 来自该 connection 的 authoritative negotiated context，不来自文件或 Chunk；
- air/fluid 和 update-tag policy 有明确 producer，不从 numeric ID 猜；
- output 已是官方 packet model，没有 `MinecraftChunkSnapshot` 中间副本；
- outgoing physical format 已安装同一 connection context。实现必须保证 encode/enqueue 不跨 reconfiguration epoch。

### 8.4 客户端把官方 packet 解码为相同领域 Chunk

```kotlin
fun createClientChunkDecoder(
    minecraftClientConnection: MinecraftClientConnection,
    minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
): MinecraftChunkPacketDecoder<BlockStateValue, BiomeValue> {
    val minecraftDimensionContext = minecraftClientNegotiationResult.minecraftDimensionContext
    val chunkContext = minecraftDimensionContext.createStableChunkContext()
    val protocolRegistryContext = minecraftClientConnection.protocolRegistryContext
    val chunkProtocolMapping = protocolRegistryContext.toChunkProtocolMapping()
    return MinecraftChunkPacketDecoder(chunkContext, chunkProtocolMapping)
}

val minecraftChunkPacketDecoder = createClientChunkDecoder(
    minecraftClientConnection,
    minecraftClientNegotiationResult,
)
val chunk: Chunk<BlockStateValue, BiomeValue> =
    minecraftChunkPacketDecoder.decode(chunkDataAndUpdateLightPacket)
```

复核：

- layout 来自 Configuration + Play Login 验证后的 dimension context；
- IDs 来自 connection 当前协商 registry snapshot；
- decoder 返回与服务端 core 相同的 stable generic specialization；
- returned Chunk 不保存两个 context，也没有 nullable storage metadata；
- 若随后 reconfiguration，丢弃 decoder 并用新的 connection context 重建；`chunk` 本身继续有效。

### 8.5 使用 context-bound view 计算和修改 Chunk

```kotlin
fun replaceBlock(
    chunkContext: ChunkContext<BlockStateValue, BiomeValue>,
    chunk: Chunk<BlockStateValue, BiomeValue>,
    blockPosition: BlockPosition,
    replacement: BlockStateValue,
): BlockStateValue {
    val chunkView = chunkContext.bind(chunk)
    val previous = chunkView.block(blockPosition)
    chunkView.setBlock(blockPosition, replacement)
    return previous
}
```

复核：defaults/layout 只在 `chunkContext` 中；Chunk 数据没有重复字段。view 是调用期间的能力对象，不进入 storage/network
data。

### 8.6 mod 客户端/服务端替换上下文

```kotlin
val modChunkContext = ChunkContext(
    dimensionTypeLayout = modDimensionTypeLayout,
    blockStates = modBlockStateRegistry,
    biomes = modBiomeRegistry,
)
val modChunkNbtCodec = ChunkNbtCodec(
    chunkContext = modChunkContext,
    nbtFormat = modRegionNbtFormat,
)

val loaderAdjustedProtocolRegistryContext =
    modNegotiationProfile.resolveProtocolRegistryContext(baseProtocolRegistryContext)
val modChunkProtocolMapping = ModChunkProtocolMapping(
    blockStateRegistrySize = loaderAdjustedProtocolRegistryContext.blockStateRegistrySize,
    biomeRegistrySize = requireNotNull(loaderAdjustedProtocolRegistryContext.biomeRegistrySize),
    blockStateMapping = modBlockStateMapping,
    biomeMapping = modBiomeMapping,
    blockEntityTypeMapping = modBlockEntityTypeMapping,
)
val modPacketDecoder = MinecraftChunkPacketDecoder(
    chunkContext = modChunkContext,
    chunkProtocolMapping = modChunkProtocolMapping,
)
```

示例中的每个 mod value 都由调用方参数或 loader negotiation producer 提供。标准 factory 是 convenience，显式 constructors
是完整替换窗口。 custom mapping 可以处理 loader aliases、非连续 remote IDs 或额外 registry policy，但必须向 physical
protocol format 提供与之匹配的
`ProtocolRegistryContext`；mapping 本身只保存 Chunk projection 所需的 registry views/sizes，不嵌套整个 context。

### 8.7 raw schema 逃生窗口

```kotlin
val nbtDocument = liveRegionHandle.readChunkNbtDocument(chunkPosition)
val customValue = liveRegionHandle.withChunkNbtSource(chunkPosition) { _, source ->
    customRegionValueCodec.decode(source, chunkPosition)
}
```

当 mod 改变 root schema，而不只是增加 block/biome/entity type 时，调用方不应被迫通过标准 semantic model。现有
compressed、NBT source、
`NbtDocument`、explicit serializer 路径全部保留；自定义 codec 可以直接复用 Region/decompression 层。

## 9. 示例代码的无冗余复核

| 示例变量                        | 来源                                                         | 它拥有的唯一事实                                     | 没有携带的事实                                         |
|---------------------------------|--------------------------------------------------------------|------------------------------------------------------|--------------------------------------------------------|
| `worldGenSettingsData`          | 存档 root saved data                                         | dimension declarations/holders                       | protocol IDs、Chunk contents                           |
| `resolvedProtocolData`          | enabled data-pack stack + explicit projectors                | server Configuration projection和完整 registry order | Region bytes、loaded Chunks                            |
| `chunkContext`                  | resolved dimension layout + selected stable defaults/mappers | 跨该 dimension Chunks 可复用的解释规则               | position、Sections、DataVersion、raw IDs               |
| `chunkNbtCodec`                 | `chunkContext` + unnamed-root NBT format                     | disk NBT↔semantic conversion                         | Region filesystem policy、connection context           |
| `storedChunk`                   | one Region record decoded by the codec                       | one core Chunk + one record's storage metadata       | codec、compression、timestamp、protocol IDs            |
| `storedChunk.chunk`             | stored semantic core                                         | stable per-Chunk logical content                     | storage-only metadata、layout/default/context、raw IDs |
| `protocolRegistryContext`       | completed negotiation/active connection                      | this epoch's wire registry facts                     | disk metadata、Chunk values                            |
| `chunkProtocolMapping`          | exact `protocolRegistryContext` + optional mod policy        | stable values↔wire IDs                               | Chunk contents、disk codec                             |
| `minecraftChunkPacketEncoder`   | `chunkContext` + mapping + content policy                    | one epoch's projection capability                    | persistent storage metadata、filesystem                |
| `chunkDataAndUpdateLightPacket` | encoder output                                               | only official packet payload fields                  | contexts、disk metadata、snapshot wrapper              |
| client `chunk`                  | packet decoder output                                        | stable client-visible Chunk core                     | numeric IDs、codec refs、invented storage metadata     |

逐箭头复核：

1. Region → disk codec：position 从 Region slot 进入一次调用；跨记录稳定的 layout/default/mappers 已被 codec 捕获。
2. disk codec → `StoredChunk`：只产出 NBT/record 内容；context、NbtFormat、compression 不随结果移动。
3. `StoredChunk` → packet encoder：只取 `.chunk`；storage metadata 在类型上被截断。
4. packet encoder → packet：numeric IDs 在这一刻产生并且只保存在官方 wire fields；mapping 不放进 packet。
5. packet → physical bytes：format 使用 connection 已安装 context，不给 packet 增加字段。
6. bytes → client packet：同一 epoch context 决定 physical decoding；packet 仍是官方模型。
7. packet → client Chunk：numeric IDs 立即还原为 stable values；decoder context 不放进结果。
8. Chunk calculation：需要 defaults/layout 时显式 bind context；不为了方便把 context 永久塞回 Chunk。

结论：目标示例不存在同一个 layout/default/raw-ID/context 在多个数据对象中逐层复制的问题。唯一的组合 wrapper 是
`StoredChunk`，它组合两个 互斥来源能力而不复制 core；唯一的临时组合是 `ChunkView`，它是操作 facade 而不是数据层。

## 10. 逃生窗口设计

高层默认路径与低层替换路径必须同时存在，并沿用仓库现有“zero-configuration vanilla convenience + explicit constructible
core”模式。

### 10.1 磁盘值与 schema

- `BlockStateRegistry<B>` / `BiomeRegistry<M>`：替换 stable value representation、defaults 和双向持久化 mapping；
- `EntityDataRegistry<E>`：替换 Entity subtype data；
- `ChunkNbtCodec` / `EntityChunkNbtCodec` 的 explicit `NbtFormat`；
- raw compressed chunk、NBT Source/Sink、`NbtDocument` 和 serializer overloads；
- registered CUSTOM Region compression 保持不变；
- 可选小型 `RegionValueCodec<T>` 只统一 semantic codec shape，不强制所有 mod 使用仓库 model。

### 10.2 data-pack 与 dimension 编排

- `DataPackProtocolProjector` 完整构造器继续允许替换 base、registry projectors、merge mode、Known Packs、flags 和 static
  schema；
- `DataPackRegistryEntryProjector` 继续负责 disk JSON → synchronized NBT 的显式有损边界；
- `resolveMinecraftChunkContexts` 提供 stable default overrides；
- caller 可绕过 resolver，直接以自有 `DimensionTypeLayout` 和 registries 构造 `ChunkContext<B, M>`；
- inline dimension types 保留在 disk/custom endpoint branch。

### 10.3 协议与 loader

- `ClientNegotiationProfile.resolveProtocolRegistryContext` 与
  `ServerNegotiationProfile.resolveProtocolRegistryContext` 继续作为 Fabric/Forge/NeoForge 等 loader 的 registry hook；
- `ProtocolRegistryContext.withRegistries`、`withStaticRegistryResolution`、size/section-count overlays 保留；
- 标准 `toChunkProtocolMapping()` 与 explicit `ChunkProtocolMapping<B, M>` 并存；
- `ChunkBlockSemantics<B>` 与 `BlockEntityPacketProjector` caller-supplied；
- packet encoder/decoder constructors public 且完整，不要求 vanilla module；
- `protocol-model` packet constructors 和 `MinecraftProtocolFormat` explicit configuration 仍可直接使用，供完全自定义
  endpoint 跳过 semantic adapter。

### 10.4 逃生窗口的责任边界

标准路径保证无协议污染；泛型/custom 路径允许用户故意选择 `ProtocolBlockState` 或包含额外 runtime state 的 B/M/E，库不应禁止。但
API 和测试 必须区分：

- library-owned convenience 永远返回 stable standard values；
- generic constructors 是 caller-owned representation，caller 负责其 equality、可逆性和 lifecycle；
- 无法映射的值在当前 conversion boundary 失败，不能 silent fallback 到默认值；
- raw path 明确放弃 semantic schema validation，但不放弃 Region/NBT intrinsic corruption checks。

## 11. 模块与文件级改造

### 11.1 `world-format`

`ChunkModels.kt`：

- `BlockStateDescriptor` → `BlockStateValue`；`NamedBiomeRegistry` 的 String value → `BiomeValue`；
- `ChunkDataRegistries`/`ChunkCodecContext` 合并并明确命名为 codec-owned `ChunkContext`；
- `Chunk` constructor 移除 layout/default/context；
- 增加 `ChunkView` 或等价 context-receiver facade；
- `ChunkMetadata` 移除 nullable storage field；新增 `StoredChunk`；
- Block Entity data 改用 caller-extensible 的显式 persistent/update provenance variants；
- snapshot 只复制 data，不携带 context。

`ChunkNbtCodec.kt`：

- 持有 `ChunkContext`；decode/encode 改为 `StoredChunk`；
- standard stable registries接受所有合法持久化 names；
- decode 与 packet decoder 共用 canonical sparse helper；
- encode validation 使用 codec context，不比较 Chunk 内不存在的 layout/default；
- error 明确区分 unknown caller mapping、invalid layout 和持久化 schema。

`ChunkConversions.kt`：

- compressed/NBT receiver extensions 使用 `StoredChunk` 名义；
- 命名应明确 `toStoredChunk`，避免暗示 packet-derived Chunk 可直接写回；
- 保留 explicit codec/format overload。

Entity/POI files：

- 不做无必要的 model rewrite；
- 如引入 `RegionValueCodec<T>`，让三个 codec 以同一 position-aware shape 实现；
- 为 default/custom/raw paths 增加契约测试；
- 更新 README/AGENTS，删除“Chunk 内保存 context/default”以及 nullable storage metadata 的旧规则。

### 11.2 `world-io`

以下普通 Chunk API 从 `Chunk<B, M>` 迁移为 `StoredChunk<B, M>`：

- `RegionFileStore`、`CoordinatedRegionStore`；
- mutable `RegionHandle` 与 live `LiveRegionHandle`；
- `RegionReadScope`、`DecodedChunkRegionReadScope`；
- one-shot read/write、bound read scopes、Region replacement semantic callbacks。

Entity/POI API 保持各自结果类型，逐项复核 mutable/live、local/absolute position、explicit/default codec overload 对称性。不得改变
admission、header snapshot、sidecar、compression、replacement、session lock 或 live consistency 行为。

可以保留 per-call codec overload 作为混合 codec 场景；批量/普通路径优先使用捕获 codec 的 decoded scope。不要把 semantic
codec 放进 world access configuration，因为不同 dimension 需要不同 context，且 world-io 不应依赖 protocol/data-pack
resolution。

### 11.3 `protocol-datapack`

`MinecraftWorldChunkAdapters.kt`：

- 删除 `ProtocolRegistryContext.toChunkDataRegistries()` 作为标准 disk mapping；
- 新增 `ProtocolRegistryContext.toChunkProtocolMapping()`，只服务网络边界；
- stable value ↔ protocol value/ID 的 alias-aware mapping 在此模块实现；
- generic mapping constructor 保持 caller-supplied。

`MinecraftChunkContext.kt`：

- 删除当前混合对象，或将其收敛为不重复 map key、不保存 protocol registry、且不同时保存 codec/context derived copies 的极薄
  convenience；
- 首选直接公开 `ChunkContext<BlockStateValue, BiomeValue>`，由 `ChunkNbtCodec(chunkContext)` 创建 codec；
- 删除 `defaultBlockId`、`defaultBiomeId`、`protocolRegistryContext` 和协议值 `ChunkCodecContext` fields。

`ResolvedMinecraftWorld.kt`：

- map value 改为 stable `ChunkContext`；
- resolver 只从 projected dimension-type data 取得 layout，不用协议 registries 验证每个 disk palette value；
- server branch 仍验证 dimension-type synchronized identity；disk branch 保留 inline holder；
- aggregate failures 和 no-partial-result contract 不变。

更新 `protocol-datapack/AGENTS.md` 和 README，使“disk context raw-ID-free”成为真实结构而非注释承诺。

### 11.4 `protocol-model` 与 `protocol-serialization`

- `ProtocolBlockState`、`ProtocolRegistryEntry`、`ProtocolRegistryContext` 保留；它们是正确的协议 registry model；
- `ChunkDataAndUpdateLightPacket`、nested `ChunkData`、network `ChunkSection`、network `PalettedContainer` 和
  `BlockEntityInfo` 字段不改变；
- 不给 packet 添加 context、epoch、storage metadata 或 stable values；
- physical palette widths 继续来自 format configuration 的 current `ProtocolRegistryContext`；
- 增加 exact-byte/oracle regression，证明 semantic adapter rewrite 没有改变官方 bytes；
- 不修改 KSP/generated packet registry output。

### 11.5 `protocol-server`

- `MinecraftWorldChunkProjection.kt` 泛型化到 stable B/M + `ChunkProtocolMapping`；标准 overload 使用 stable values；
- `encodePacket` 接受 `Chunk`，不接受 `StoredChunk`，从类型上阻止 storage metadata 泄漏；
- 删除 `MinecraftChunkSnapshot.kt`，`MinecraftInitialWorld.chunks` 使用官方 packets 或在 send boundary 使用 semantic
  chunks + encoder；不得再保留 与 packet 同构的 snapshot；
- flat initial-world convenience 先创建 stable semantic Chunk 再走同一 encoder，或直接返回 official packet；不能另建
  raw-ID-only Chunk domain；
- `MinecraftEntitySnapshot` 和 Entity pairing projection 保留，明确它需要 application runtime state；
- negotiation result/context 与 connection active context 的 epoch 验证写入测试。

### 11.6 `protocol-client`

- `MinecraftChunkPacketDecoder` 返回 `Chunk<BlockStateValue, BiomeValue>`；
- constructor 接收 stable `ChunkContext` + mapping，不依赖 disk codec bundle；
- packet Block Entity tag 不再伪称完整 persistent data；
- decode 使用 canonical sparse rule；
- negotiation result 提供从 validated dimension layout 创建 stable `ChunkContext` 的 convenience；
- reconfiguration/dimension-change 调用方必须重建 decoder，现有 Chunks 保留；
- Entity packet decoder 继续只返回线上可恢复的 `Entity`，不构造 `EntityChunk`。

### 11.7 `protocol-datapack-vanilla` 与 `demo/web-map`

`protocol-datapack-vanilla`：

- 更新默认 factories，使 stable disk context 与 protocol mapping 分开产生；
- 修 generator 仅当生成声明需要变化；绝不手改 generated payload source；
- vanilla defaults 仍是 convenience，显式 projector/mapping 路径不依赖本模块。

`demo/web-map`：

- `LiveSurfaceRegionReader` 使用 `StoredChunk<BlockStateValue, BiomeValue>`；
- generation status 从 `storedChunk.storageMetadata.isFullyGenerated` 读取，surface 从 `storedChunk.chunk` 读取；
- 删除从 `ProtocolBlockState` 反向组装 `BlockStateDescriptor` 的当前转换；
- timestamp 继续来自 `RegionChunkInfo`，不塞入 Chunk；
- context map value 改为 stable `ChunkContext`，批量 read scope 捕获 `ChunkNbtCodec`。

## 12. 实施顺序

### 阶段 A：world-format 领域边界

1. 引入 `BlockStateValue`、`BiomeValue`、codec-owned `ChunkContext`。
2. 重构 `Chunk` constructor 与 context-bound view；建立 canonical sparse helper。
3. 拆分 `ChunkMetadata`/`StoredChunk`，实现 Block Entity persistent/update payload provenance。
4. 迁移 `ChunkNbtCodec` 和 conversions。
5. 逐项验证 Entity/POI，无必要不改 shape；只增加共同 codec interface 时再迁移。
6. 更新 `world-format` tests、README、AGENTS。

这是破坏性 API 变更的根阶段。后续模块在同一 change set 直接迁移，不引入临时 alias。

### 阶段 B：world-io 三类 Region API

1. 普通 Region typed API 全部迁移 `StoredChunk`。
2. 对照 mutable/live、store/handle/scope、local/absolute overload matrix。
3. 对照 Entity default/custom codec paths。
4. 对照 POI built-in codec paths。
5. 验证 empty Entity clearing、POI position injection、ordinary/Entity position mismatch rejection。
6. 更新 world-io README 的 simplest LiveMinecraftWorldAccess 示例。

### 阶段 C：data-pack context 与 protocol mapping 分离

1. 重写 stable context resolvers。
2. 新增标准和 custom `ChunkProtocolMapping`。
3. 精简/删除 `MinecraftChunkContext` 混合对象。
4. 迁移 `ResolvedMinecraftWorld`、dimension context conveniences 和 vanilla factories。
5. 加入 loader alias、registry reorder、inline dimension 和 aggregate failure tests。

### 阶段 D：server/client packet projection

1. server encoder 接受 stable Chunk + mapping/context policies。
2. client decoder返回 stable Chunk并共用 sparse canonicalization。
3. 删除 `MinecraftChunkSnapshot`，迁移 initial-world composition。
4. 保持 Entity pairing 与 POI debug projection边界独立。
5. 覆盖 initial negotiation、dimension selection、reconfiguration epoch。

### 阶段 E：调用方、文档与全面清理

1. 迁移 web-map 和所有 tests/fixtures/examples。
2. 删除所有标准路径中的 `Chunk<ProtocolBlockState, ProtocolRegistryEntry>`。
3. 删除旧 `BlockStateDescriptor`、String biome convenience、nullable storage metadata 和 stale context fields。
4. 更新涉及模块 README/AGENTS，确保当前 source 是唯一事实。
5. 检查 module dependencies，确认 `world-format`/`world-io` 没有新增 protocol dependency。

## 13. 测试矩阵

### 13.1 `world-format`

- stable block/biome disk NBT round trip，包括 mod names、properties、single/indirect palettes；
- disk decode 不需要 `ProtocolRegistryContext`，也不因当前协议 registry 缺少合法 persisted value 而失败；
- `Chunk` constructor/snapshot 不接收或保留 context/layout/defaults；
- `ChunkContext.bind` 验证 layout、Block Entity Y 和 position membership；
- absent Section default reads、default writes、create/prune behavior；
- disk 与 packet helper 对 default-only、light-only、non-default Section 产生同一 canonical shape；
- `StoredChunk` storage metadata mandatory，plain Chunk 不能调用 persistent encode；constructor 与每次 encode 都拒绝
  update-only Block Entity；
- storage fields、heightmaps、lighting、Block Entities round trip；
- packet update payload 不被标成完整 persistent payload；
- palette local IDs/unused entries 不影响 logical equality，encode 不突变 input；
- custom `BlockStateRegistry<B>`/`BiomeRegistry<M>` 双向 round trip；
- Entity default raw NBT registry、自定义 registry、passenger、empty root、mixed positions；
- POI empty Section/Valid、position from Region、mod type、strict unknown fields；
- raw NBT paths 可保留标准 semantic codec 不认识的字段。

### 13.2 `world-io`

- `RegionFileStore`、coordinated store、mutable/live handles、decoded scopes 返回 `StoredChunk`；
- ordinary/Entity NBT position 与 Region slot mismatch 被 semantic codec 拒绝；
- POI 使用 Region slot 注入 position；
- semantic writes 从 value position 选 slot并验证 Region membership；
- Entity empty Chunk 清除 slot；POI empty value按现有 official policy写入；
- timestamp、compression、external sidecar 只通过 Region APIs 可见，不出现在 semantic values；
- mutable/live overload matrix 和 source-level KMP call compilation；
- live header snapshot、coordination、replacement、stream ownership 行为无回归。

### 13.3 `protocol-datapack`

- referenced/inline dimension layouts 都生成 stable context；server branch 仍拒绝没有 synchronized identity 的 inline
  type；
- map key 与 context value 不重复 dimension ID；
- resolved context 不含 `ProtocolRegistryContext`、raw IDs 或 ready codec duplicate；
- `toChunkProtocolMapping()` 双向映射 block state、biome、block entity type；
- alias lookup decode/encode canonicalization明确；
- registry reorder 生成不同 IDs，但同一 stable Chunk 不变；
- missing value 只在 packet mapping/encode boundary 失败；
- custom mapping、custom registries、custom data-pack projectors 可替换标准路径；
- `MinecraftWorldResolutionException` 继续聚合所有 dimensions。

### 13.4 `protocol-client` / `protocol-server`

- disk stable Chunk → packet → client stable Chunk 的 block/biome dense logical values一致；
- sparse/default/light-only canonical shape一致；
- official packet fields、palette thresholds、direct/indirect/single branches不变；
- unknown current-registry value 的 encode failure 包含 Chunk position/value；
- server-only storage metadata、extra heightmaps 和 full Block Entity data 没有出现在客户端；
- client result 不能 persistent encode；caller 即使显式构造 `StoredChunk`，也必须补齐 storage metadata、缺失 heightmaps 和所有
  Block Entity persistent payload，并通过 encode-time validation；
- initial world 不再经过 `MinecraftChunkSnapshot`；
- reconfiguration 后新 mapping 使用新 IDs，旧 stable Chunks 无需改写；
- stale packet codec 不被自动复用；
- Fabric/Forge/NeoForge negotiation profile adjusted contexts 可创建 custom mapping；
- Entity pairing 仍要求 runtime context，不能错误创建 EntityChunk；
- POI debug packets 不被接到 PoiChunk codec。

### 13.5 `protocol-serialization`

- `ChunkDataAndUpdateLightPacket` ordinary exact-byte sample；
- block/biome single、indirect threshold、direct palette branches；
- section count 与 registry sizes 来自 active format context；
- block entity type IDs、heightmaps、light masks/arrays；
- truncation、invalid ID/length/mask、trailing bytes；
- matching official codec oracle decode/re-encode；
- semantic adapter changes 不要求或导致 packet model extra fields。

### 13.6 escape-hatch tests

- custom B/M registries生成可写回 disk values；
- custom Entity data registry；
- custom `ChunkProtocolMapping` 使用非 vanilla order/aliases；
- negotiation profile 替换 registry context 后 standard/custom mapping 都读取最终 snapshot；
- custom NBT format 和 raw document/source APIs；
- custom Region codec 可复用 decompression/position path；
- vanilla convenience 与 explicit constructor 产生等价标准行为，但 explicit path 不依赖 vanilla module。

## 14. 验证命令

按最窄 JVM gate 顺序执行，Gradle wrapper 不并发：

```shell
gradlew.bat :world-format:jvmTest
gradlew.bat :world-io:jvmTest
gradlew.bat :protocol-datapack:jvmTest
gradlew.bat :protocol-model:jvmTest :protocol-serialization:jvmTest
gradlew.bat :protocol-client:jvmTest :protocol-server:jvmTest
gradlew.bat :protocol-datapack-vanilla:jvmTest :demo:web-map:jvmTest
```

再运行受影响的 portable targets：

```shell
gradlew.bat :world-format:jsNodeTest :world-io:jsNodeTest
gradlew.bat :protocol-datapack:jsNodeTest :protocol-client:jsNodeTest :protocol-server:jsNodeTest
gradlew.bat :demo:web-map:jsNodeTest
```

最终 JVM gate：

```shell
gradlew.bat :minecraft-test-fixture-host:test jvmTest
```

官方 world interoperability 要覆盖普通 Chunk、Entity Chunk 和 POI 的 generate/read/rewrite/reload；官方 client/server peer
scenarios 覆盖 Configuration registry、Play Login、Chunk packet 和 reconfiguration。若实现实际修改 physical codec，再按
serialization workflow 扩充官方 oracle fixtures；否则仍运行现有 oracle 作为 bytes 不变证明。

## 15. 完成标准

1. 标准磁盘结果为 `StoredChunk<BlockStateValue, BiomeValue>`，其中 core 是 context-free
   `Chunk<BlockStateValue, BiomeValue>`。
2. `Chunk` constructor/public state 没有 context、layout、defaults、storage nullable branch、protocol objects 或 numeric
   IDs。
3. 需要 defaults/layout 的计算只通过 `ChunkContext.bind(chunk)` 或显式 codec 完成。
4. 同一 dimension 的 codecs/views 复用 immutable context；context 不随每个 Chunk 复制。
5. disk codec 接受当前协议 registry 不认识的合法 persisted mod values；失败推迟到具体 packet mapping boundary。
6. packet encoder/decoder 捕获当前 negotiation epoch 的 mapping；reorder/reconfiguration 不修改 loaded stable Chunks。
7. official packet model 没有 library-only fields，`MinecraftChunkSnapshot` 已删除。
8. packet-derived Chunk 不会被 disk encoder 当作完整 persisted value；`StoredChunk` storage metadata non-null，disk encode
   还逐次验证所有 Block Entity payload 都是 persistent variant。
9. Block Entity update tag 与 full persistent payload 由 caller-extensible 的显式 provenance types 区分。
10. disk/client decoders按同一 sparse canonical rule产生逻辑相同的 Sections；palette内部 IDs 不进入相等性承诺。
11. EntityChunk 与 PoiChunk 不含 codec/protocol context；逐记录 `DataVersion` 和 position 正确保留。
12. EntityChunk 不被伪装为单个网络包，PoiChunk 不被伪装为 debug POI packet。
13. Region timestamp/compression/sidecar 只在 Region layer；semantic values不重复保存。
14. vanilla高层 factory、generic custom context/mapping、raw NBT三档逃生窗口都经过测试。
15. `world-format`/`world-io` module graph 仍无 protocol dependency；所有 README/AGENTS 与 source 同步。
16. focused tests、official world reload、official codec oracle、client/server peer scenarios 和最终 JVM gate通过。

## 16. 不随本计划实施

- 修改所选 Minecraft release、NBT schema revision、Anvil framing、compression ID 或 sidecar policy；
- 实现 DataFixer、历史 Chunk schema compatibility 或跨版本迁移；
- gameplay、world ticking、Chunk cache/loading、entity tracking 或权限系统；
- 自动迁移 data-pack reload 后 layout 已改变的 loaded Chunks；
- 把全部 Entity persistent subtype data 编排成客户端 runtime state；
- 把 POI debug subscription 设计成持久化 POI 同步协议；
- 重构所有 `Protocol*` 命名；这些类型在协议层仍然准确；
- 改变 Known Packs、feature flags、tags、Login/Configuration/Play sequencing；
- 处理与本链无关的 `DataPackMetadata` parsed/raw duplicate representation；
- 为旧 Chunk API 添加 compatibility shims、deprecated aliases 或双轨实现。
