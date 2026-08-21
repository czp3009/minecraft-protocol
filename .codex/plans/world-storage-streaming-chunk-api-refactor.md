# World storage streaming Chunk API refactor

## 状态

- 目标版本：仓库所选 Minecraft 版本；实施前通过 `minecraftVersion` 任务确认。
- 状态：已完成。
- 范围：仅 Chunk Region（`.mca` 及其内部管理的 `.mcc`）、Region 内 Chunk NBT、 Chunk/Section/palette
  语义模型、完整坐标转换、相应文件生命周期，以及磁盘语义 Chunk 与初始世界网络 Chunk 的无状态双向适配。
- 模块：不新增 `world-chunk` 或其他 runtime module；主体位于 `world-format` 与 `world-io`，调用方向适配分别位于
  `protocol-server` 与 `protocol-client`。
- 不在范围内：`level.dat`、player data、saved data、成就和统计的重构，以及 gameplay/权威世界状态。

## 1. 模块边界

### 1.1 `world-format`

`world-format` 拥有所有不依赖文件系统即可表达的值、格式和纯转换：

- Region/Chunk/Section/Block 的绝对与相对坐标类型以及 floor-semantics 转换；
- Anvil header、record、sector、external marker 和 compression dispatch；
- `CompressedChunkInput`、`CompressedChunk`、`AnvilRegion` 等 detached 值；
- unnamed-root binary NBT 与 compression 的组合；
- 强类型 `Chunk`、`ChunkSection`、`PalettedContainer<T>` 和 `ChunkNbtCodec`；
- 由调用方提供的 block-state、biome 与 dimension-layout 数据契约。

它只使用普通内存值及 `kotlinx.io.Source` / `Sink`，不得接触 `Path`、`FileSystem`、`FileHandle`、文件名、 目录、锁、flush、替换或
close coordination，也不得依赖 protocol 或 vanilla-data 模块。

### 1.2 `world-io`

`world-io` 拥有：

- Okio 路径、文件系统和随机访问；
- Region 目录定位，以及作为实现细节的 `.mca` / `.mcc` 管理；
- mutable Region 的协调、写入、durability、取消和关闭屏障；
- live read-only Region 的无锁、无跨调用资源读取；
- 把 `world-format` 的 compression、NBT 和 `ChunkNbtCodec` 组合成文件 API。

世界存储依赖方向保持：

```text
world-io -> world-format -> nbt-serialization -> nbt
```

网络组合只依赖无文件系统的数据层：

```text
protocol-server -> world-format
protocol-client -> world-format
```

`protocol-server` 不依赖 `world-io`。从磁盘发送初始 Chunk 的应用自行同时引入 `world-io` 并在调用点组合二者。

调用方需要 vanilla 数据时自行引入 `protocol-vanilla-data`，再把数据适配为 `world-format` 的 registry/layout 契约；mod
或自定义数据源使用同一入口。

## 2. NBT 与强类型 Chunk

`NbtDocument` 是通用 NBT 树。合法 tag 都能保留，因此 raw tree 路径不使用“未知 NBT 字段”这一说法：

- `readChunkNbtDocument` / `writeChunkNbtDocument`：完整 NBT 树；
- `withChunkNbtSource`：完整、已解压的 unnamed-root binary NBT 流；
- `readChunkNbt(deserializer, ...)` / `writeChunkNbt(serializer, ...)`：调用方选择的 serializer；
- `readChunk(..., codec)` / `writeChunk(..., codec)`：强类型 Chunk 投影。

强类型 `Chunk` 不暗藏 raw NBT remainder，也不承诺保留未建模字段。无法解析 block-state/biome registry 值是 强类型投影错误，不是未知
NBT。需要无损保存任意扩展 tag 时使用 `NbtDocument` 路径。

## 3. 坐标与数据层级

公开层级是：

```text
world access -> RegionHandle -> compressed Chunk / NBT / Chunk -> ChunkSection -> block or biome
```

用户不需要知道一个 Region 由哪些物理文件组成，也不接触 header、sector、external sidecar 路径或文件句柄。

### 3.1 Region 元信息

`RegionHandle` 知道自己的 `RegionPosition`，并提供：

- `hasRegion()`；
- local/absolute `hasChunk(...)`；
- `readChunkCount()`，只统计 Region header 中存在的 location；
- `readLocalChunkPositions()`，只枚举 Region header 中存在的相对坐标并返回 detached `List`；
- local/absolute `readChunkInfo(...)`；
- `readChunkInfos()`，返回 detached `List<RegionChunkInfo>`；
- local/absolute compressed、NBT document、serializer 与 strong Chunk 读取；
- mutable 路径上的对应写入、删除、Region 清空/替换与 flush。

`RegionChunkInfo` 只公开 Region 层有意义的信息：

```kotlin
class RegionChunkInfo internal constructor(
    val region: RegionPosition,
    val localPosition: LocalChunkPosition,
    val compression: Compression,
    val compressedByteCount: Long,
    val timestampEpochSeconds: Int,
) {
    val position: ChunkPosition
}
```

inline/external placement 只属于低层 Anvil/文件实现，不通过高层 `RegionChunkInfo` 暴露。

`hasChunk`、`readChunkCount` 与 `readLocalChunkPositions` 都是 Region 元信息操作，直接读取已打开时规范化的 header location
table，不读取 Chunk record header、外部内容、压缩流或 NBT，也不通过 `readChunkInfo(s)` 实现。 所有 Region 元信息返回值都是
detached snapshot；任何 Chunk 写入、删除或 Region 整体替换完成后，之前取得的 count、position list 或 `RegionChunkInfo(s)`
都可能过期，需要调用方重新读取。持有同一个 `RegionHandle` 不会让旧值 自动更新，也不会跨普通方法调用持续持锁。

mutable 路径的 Region 元信息读取、Chunk 内容读取与 read scope 共用 Region 级 shared admission；单 Chunk 写入、 删除、clear
与完整替换共用同一 Region 级 exclusive admission。单 Chunk 写入只定位写入新 Chunk storage 与固定 header，并回收旧
allocation，不替换、截断或重写整个 Region；`replaceRegion` 才替换完整逻辑 Chunk 集合。

### 3.2 Chunk 与 Section 不携带父级位置

`Chunk<B, M>` 不保存 Region position、absolute Chunk position、compression、compressed size、placement 或 timestamp。
`ChunkSection` 只保留自身的 `sectionY` 与内容。

absolute overload 必须显式接收所属 `ChunkPosition` / `SectionPosition`，先使用统一坐标工具校验与转换，再委托 local
overload；不得把父级位置写回内存模型。

`MinecraftCoordinates` 是唯一的底层计算实现；坐标值类型上的便捷属性和方法必须保留并委托给它。至少提供：

- 连续玩家/实体坐标到包含它的 `BlockPosition`，以及逐轴 floor 转换；
- `BlockPosition.chunk/section/region/localInChunk/localInSection`；
- `ChunkPosition.region/local/section/block/local(BlockPosition)`；
- `RegionPosition.chunk/local/contains`；
- `SectionPosition.block/local/contains`；
- Block、Section、Chunk、Region 各层逐轴 absolute/local 正反转换；
- Section/Chunk/Region 覆盖的绝对坐标范围；
- Region 内全部 local/absolute Chunk 坐标的惰性 `Sequence`，以及 Section 内全部 Block 坐标的惰性 `Sequence`；
- biome quart 坐标、各层 checked offset、玩家中心 Chunk 方形范围的惰性 `Sequence`；
- Chunk 与 Section 的 local/absolute block 读取和写入；
- Chunk 的 local/absolute biome 读取和写入。

所有负坐标转换使用 floor 语义。

## 4. 流式转换

API 先提供可以独立完成工作的朴素函数，再提供便利 DSL：

- `openRegion` / suspend `RegionHandle.close` 是基本生命周期；suspend `RegionHandle.use` 是唯一的结构化关闭封装， 不再保留与
  `openRegion(...).use` 等价的 `withRegion` 别名；
- existence、metadata、detached compressed/NBT/strong Chunk 及单 Chunk 写入都有普通函数；
- `withCompressedChunkSource` / `withChunkNbtSource` 使用 callback，是因为 Source 必须被限制在内部锁和文件资源
  有效期内，不能安全地作为普通返回值逃逸；
- batch/read scope 只用于确实需要一次内部 admission/header snapshot 的场景，其中的操作仍建立在相同的基础 Chunk primitive
  上；
- 不公开裸 `lock()` / `unlock()`。它会把 writer preference、取消、异常、close barrier 和锁顺序暴露给用户，
  显著增加死锁与资源逃逸风险，而不会增加无法由 handle/scope 表达的有效能力。

每层只有一条 canonical 管线，完整值只是流式 primitive 的适配器：

| 层               | canonical API                                                       | detached adapter                                      |
|------------------|---------------------------------------------------------------------|-------------------------------------------------------|
| Anvil container  | `AnvilRegionFormat.decodeRecordsFromSource` / `encodeRecordsToSink` | `AnvilRegion`                                         |
| compressed Chunk | `withCompressedChunkSource` / known-length Sink writer              | `CompressedChunk`                                     |
| decompressed NBT | `withChunkNbtSource` / compressed writer                            | `NbtDocument` / serializer value                      |
| strong Chunk     | `ChunkNbtCodec.decodeFromSource` / `encodeToSink`                   | `Chunk<B, M>`                                         |
| palette          | indexed/iterator API                                                | compact snapshot / 显式原地 compact / 显式 dense copy |

`CompressedChunk.writeTo(sink)`、`EncodedAnvilRegion.writeTo(sink)`、`CompressedNbtFormat.encodeToSink`、
`ChunkNbtCodec.encodeToSink` 和 Region known-length writer 必须可以直接组合，不能先 `toByteArray()` 再复制。

Anvil 分配前必须知道压缩后长度，因此 NBT/strong Chunk 写入允许暂存一份完整的 compressed Chunk；不得同时 暂存完整未压缩
bytes、编码两遍或默认绕经临时文件。调用方已知压缩长度时直接写入 Region sink。

### 4.1 网络 Chunk 数据适配

`world-format` 不依赖 protocol。由调用方向的高层模块提供 receiver-oriented 扩展：

- `protocol-server`：`Chunk.toMinecraftChunkSnapshot(...)`、`Chunk.toChunkDataAndUpdateLightPacket(...)`，并允许 已有
  packet 无复制地包装为 snapshot；
- `protocol-client`：协商后把 `ProtocolRegistryContext` 转为 `ChunkDataRegistries`，并通过
  `ChunkDataAndUpdateLightPacket.toChunk(...)` 还原 positionless strong Chunk；
- packet 的 x/z 保留在 packet/上层变量中，不写进 `Chunk`；
- palette 适配直接使用 compact palette 与 ID 数组，禁止先展开 4096 项 dense value list；
- 服务端的 non-air/fluid count 需要调用方 vanilla/mod 数据提供 `isAir`/`hasFluid`，不得从 registry ID 猜测；
- 网络 packet 不携带的 data version、status、ticks 等持久化字段由客户端调用方显式提供 metadata template；
- heightmap、light 与 block entity 的可表达内容双向映射，类型/ID/位宽/section 数量错误必须失败而不是静默丢弃。

从磁盘到发送的组合路径必须完整可达：

```text
MinecraftWorldAccess -> RegionHandle -> CompressedChunk/NBT/Chunk
  -> MinecraftChunkSnapshot -> MinecraftInitialWorld -> synchronizeInitialWorld
```

`protocol-server` README 展示该完整路径并明确应用需要额外引入 `world-io`；`protocol-client` README 展示收到的 registry 到
`ChunkDataRegistries`，以及初始世界 Chunk packet 到 strong Chunk 的路径。

## 5. mutable 路径

### 5.1 公开结构

```text
MinecraftWorldAccess (持有 session.lock)
  ├─ listRegionPositions(dimension): List<RegionPosition>
  ├─ hasRegion(position, dimension)
  └─ openRegion(position, dimension): RegionHandle
       ├─ Region/Chunk metadata reads
       ├─ compressed/NBT/strong Chunk reads
       └─ Chunk-granular writes, remove, clear/replace and flush
```

- Chunk Region API 不接受 `RegionStorageDirectory`；它始终定位 dimension 的 `region/` 目录。
- entities 与 POI 将来使用各自的强类型入口，不能用一个 enum 把不同数据类型混入 Chunk API。
- `openRegion` 不做文件 I/O；即使 Region 尚不存在也返回 handle。读取结果为 false/null/empty，第一次写入才创建 目录和 Region
  文件。
- 用户侧文件写入入口只存在于 `RegionHandle`。世界对象只负责获得/结构化关闭 handle 以及枚举 Region。
- 同一 Region 可以创建多个 handle；它们共享该 Region 的协调状态。

### 5.2 内部结构

```text
OpenMinecraftWorld
  └─ RegionStorage (每个 dimension 一个 coordinator)
       └─ RegionState (每个活跃 Region 一个)
            ├─ writer-preferring shared-read/exclusive-write admission
            ├─ lazy mutable FileHandle + Header + allocator
            ├─ users / closing / cleanup completion
            └─ 被多个 RegionHandle 引用
```

- 唯一的运行期写锁粒度是整个逻辑 Region；`.mca` 和它关联的全部 `.mcc` 不再细分锁。
- 不增加 `RegionCoordinator`、`RegionIo`、`RegionBackend`、mode flag 或抽象基类。
- mutable 的文件句柄、header、allocator、锁、引用计数、close barrier 与取消恢复只在此路径内部存在。
- `RegionHandle.close()` 封闭新操作，等待已进入操作完成，再释放 Region pin；世界 close 等待所有 handle 和操作排空 后才释放
  `session.lock`。
- 不存在公开的 `RegionFile`、`RegionEntry` 或其他 `.mca` exact-file resource。

## 6. live read-only 路径

### 6.1 公开结构

```text
LiveMinecraftWorldAccess (不持有 session.lock，也无需 close)
  ├─ listRegionPositions(dimension): List<RegionPosition>
  ├─ hasRegion(position, dimension)
  └─ openRegion(position, dimension): LiveRegionHandle
       └─ 与 RegionHandle 对齐的只读方法子集
```

- `openRegion` 同样不做 I/O，并且始终返回 `LiveRegionHandle`。
- `LiveRegionHandle` 只保存不可变的 filesystem/path/position/format 上下文；它没有 close、registry、引用计数、锁、 retained
  FileHandle 或跨调用 cache。
- live 路径不提供无生命周期意义的 `withRegion`；`openRegion` 已经是完整、无资源负担的基础函数。
- 每次公开读取自行打开并关闭所需文件；一次 streaming callback 可以在 callback 生命周期内持有一次读取资源。
- missing Region 的结果为 false/null/empty；live 路径从不创建、修复或修改文件。
- 外部进程可并发写入、删除或替换文件；stale/torn input 与相应 I/O/format failure 是公开契约的一部分。
- 不存在公开 `LiveRegionFile`，也不通过 mutable 文件对象实现 live 读取。

## 7. mutable/live 复用边界

公开名称、参数、返回值和转换能力尽量对称，但内部控制流不要求对称。

允许两条路径复用的只有无资源所有权、无生命周期的下层能力：

- `world-format` 坐标值与转换；
- detached header/record/compressed/NBT/Chunk/Section/palette 值；
- compression registry、NBT format 与 Chunk codec；
- Region 文件名解析、record prefix/header 解码、长度校验和地址计算等纯函数；
- 接收调用方提供的 `FileHandle`/Source/Sink 但自身不保存它们的窄小无状态 helper。

不得跨 mutable/live 复用：

- FileHandle/Source/Sink owner；
- mutable RegionState、header/allocator cache、锁、registry、引用计数和 close barrier；
- live 单次读取的临时资源；
- cancellation/cleanup/lifecycle 控制器；
- stateful `RegionIo`、共同 backend、mode object 或通过条件分支切换行为的基类。

两条路径内部可以各自把重复操作委托给自己的 canonical private method。少量文件打开、错误归一化或 callback
编排重复优于引入共享的有状态抽象；只有在逻辑完全纯且语义一致时才抽取 helper。

## 8. Region 快照枚举

mutable 与 live 世界入口都提供：

```kotlin
fun/suspend fun listRegionPositions(
    dimension: DimensionDirectory = DimensionDirectory.Overworld,
): List<RegionPosition>
```

- 对目标 `region/` 目录执行一次 filesystem `list`，立即解析并物化 detached List；
- 只接受 canonical `r.<x>.<z>.mca`，忽略 `.mcc`、临时文件与非法名称；
- 缺失目录返回 empty List；
- 返回值按 Region position 稳定排序，避免依赖 filesystem 顺序；
- KDoc 明确警告这是 O (n) 全目录快照，世界很大时可能很慢并耗尽内存；
- 快照只保证返回 List 不再依赖后续目录遍历，不承诺与并发文件变化构成事务一致视图。

## 9. 名称

### `world-format`

| 名称                                 | 职责                                                       |
|--------------------------------------|------------------------------------------------------------|
| `Compression`                        | compression 标识                                           |
| `CompressionCodec`                   | 单个 compression 的 Source/Sink decorator                  |
| `CompressionRegistry`                | built-in/custom dispatch                                   |
| `CompressedNbtFormat`                | compression + unnamed binary NBT composition               |
| `CompressedChunkInput`               | compression、精确长度与 `writeTo(Sink)` 契约               |
| `CompressedChunk`                    | detached compressed bytes                                  |
| `MinecraftCoordinates`               | 全套 floor-semantics 坐标、范围与惰性枚举的 canonical 实现 |
| `RegionChunkInput`                   | replacement 时的 local position + compressed content       |
| `AnvilRegion` / `EncodedAnvilRegion` | detached Anvil 容器值                                      |
| `AnvilRegionFormat`                  | filesystem-independent Anvil container codec               |
| `ChunkNbtCodec<B, M>`                | NBT 与 strong Chunk 的映射                                 |

### `world-io`

| 名称                            | 职责                                                   |
|---------------------------------|--------------------------------------------------------|
| `MinecraftWorldAccess`          | 持有 session.lock 的 mutable world access              |
| `LiveMinecraftWorldAccess`      | 无锁、无 close 的 live read-only world access          |
| `RegionHandle`                  | caller-owned mutable/coordinated logical Region handle |
| `LiveRegionHandle`              | 无跨调用资源的 live read-only logical Region handle    |
| `RegionChunkInfo`               | 高层可观察的 Chunk 存储元信息                          |
| `RegionStorage` / `RegionState` | internal mutable coordinator/state                     |

公开方法中的 `read` 表示真实 filesystem 读取；format codec 使用 `decode...FromSource` / `encode...ToSink`。

## 10. 实施顺序

1. 更新本计划和 `world-format` / `world-io` 模块约束，删除与最终设计冲突的已完成声明。
2. 审计并补齐 world-format 坐标、Chunk/Section absolute overload 与流式 adapter。
3. 隐藏 mutable exact-file primitive；把 Region 级锁、lazy file state 和生命周期集中进
   `RegionStorage -> RegionState -> RegionHandle`。
4. 收缩世界级 Chunk Region surface：移除 `RegionStorageDirectory` 参数，增加快照枚举，保证 `openRegion` 不触发 I/O。
5. 重写 live Region 路径：删除 retained handle/close 和 mutable file-object 复用，每次调用独立打开读取。
6. 对齐 mutable/live 只读 API，补齐 local/absolute、compressed/NBT/serializer/strong Chunk 转换。
7. 更新 README、测试与 public dependency/ABI 审计。
8. 在 client/server 增加无状态双向 Chunk packet 适配，并验证磁盘数据链可组合到 initial-world 同步。

## 11. 验证

Gradle 命令不得并发，依次运行：

1. `:world-format:jvmTest`
2. `:world-io:jvmTest`
3. `:protocol-client:jvmTest`
4. `:protocol-server:jvmTest`
5. 若 compression/portable format 实现变化：`:world-format:jsNodeTest`、`:world-format:wasmJsNodeTest` 与 host Native test
6. live/Node filesystem 行为变化：`:world-io:jsNodeTest`
7. 受影响模块的 `outgoingVariants`
8. `git diff --check` 及旧公开 Region 名称/依赖方向静态审计

## 12. 完成标准

- 没有新增 runtime module 或 world -> protocol 依赖；client/server 只向下依赖 `world-format`，不依赖 `world-io`。
- raw `NbtDocument` 与 strong Chunk 的职责清楚，vanilla/mod registry 均由调用方传入。
- Region -> Chunk -> Section -> block 层级完整，local/absolute 转换对称且负坐标正确。
- mutable 只有 Region 粒度状态和锁；多个 handle 共享同一 RegionState，missing open 不创建文件。
- live 不持有任何跨调用文件资源或 mutable 生命周期，不复用 mutable RegionState/文件对象。
- 用户 API 不暴露 `.mca`/`.mcc` 组合、sidecar path、sector、header 或 exact-file handle。
- 两条路径都有 detached Region-position snapshot，且文档说明时间/内存风险与非事务性。
- compressed、NBT document、serializer、strong Chunk、palette/dense 之间具备直接且无多余完整副本的转换。
- `MinecraftCoordinates` 覆盖连续坐标、biome quart、各层 absolute/local 正反转换、checked offset、范围与惰性 枚举，便捷属性委托同一实现。
- 磁盘 strong Chunk 可直接投影为 initial-world snapshot/packet，客户端收到的 registry/packet 可还原为
  `ChunkDataRegistries`/strong Chunk。
- focused JVM 与适用平台测试通过，发布依赖元数据保持独立可消费。

## 13. 实施结果

- 未新增 runtime module；强类型 Chunk、Section、palette、坐标与 codec 均位于 `world-format`，且没有 protocol/vanilla-data 依赖。
- `MinecraftCoordinates` 已成为坐标公式唯一实现；原有 `BlockPosition.chunk`、`ChunkPosition.region` 等便捷 API
  保留并委托，新增连续坐标、biome quart、Block/Section/Chunk/Region 正反转换、checked offset、覆盖范围、 Section/Region 完整枚举及
  `ChunkPosition.positionsAround` 惰性坐标序列。存储、客户端和服务端桥接中不再维护 第二套 4/16/32 边界公式。
- `protocol-server` 与 `protocol-client` 已各自向下依赖 `world-format`：前者提供 strong Chunk 到 snapshot/packet 的
  fluent 投影，后者提供协商 registry 到 `ChunkDataRegistries` 以及 packet 到 strong Chunk 的 fluent 投影； 两者均不依赖
  `world-io`。
- `ChunkLayout` 不提供版本级默认值，因为高度布局由服务端同步的 dimension type 决定。客户端协商结果保留初始 Play 维度的
  `MinecraftDimensionLayout`，并直接公开其 `chunkLayout`；完整 registry NBT 使用服务端值，Known Packs 省略条目 NBT
  时使用本次协商所传入的匹配 `ProtocolDataSet` 回退。客户端从协商结果直接取得布局， 服务端提供
  `MinecraftDimensionLayout.toChunkLayout()` 适配。
- mutable 公共路径收敛为 `MinecraftWorldAccess -> RegionHandle`；物理 Region 文件对象、`RegionStorage` 与
  `RegionState` 均为 internal。普通 open/read/write/remove/clear/flush/close 方法构成完整能力面，suspend `use`
  提供结构化关闭；其他 callback 只用于借用流/一致读视图和完整 Region replacement。
- live 公共路径收敛为 `LiveMinecraftWorldAccess -> LiveRegionHandle`；无锁、无 close、无无效的 `withRegion`
  包装，每次读取独立打开并关闭资源。
- mutable/live 的只读名称、local/absolute overload、compressed/NBT/serializer/strong Chunk 转换已对齐；两条
  路径只共享无状态坐标、格式值、codec 与窄解析 helper。
- `CompressedChunkInput`、`CompressedChunk`、`NbtDocument` 与 strong `Chunk` 已补齐 receiver-oriented 扩展， 用户可从当前值通过
  IDE 补全继续转换；跨 `nbt` / `world-format` 边界使用扩展方法，底层仍委托既有 format/codec 流式原语。通用 `NbtDocument`
  serializer/二进制输出扩展由 `nbt-serialization` 所有，Chunk 专属扩展由 `world-format` 所有。Region handle 另提供
  `readCompressedChunkTo` 与 `readChunkNbtTo`，直接复制 到调用方 Sink 而不物化中间值。
- 两种 world access 都提供 canonical、排序、detached 的 Region position 快照枚举，并在 KDoc/README 中明确 O (n)
  、耗时、内存与非事务一致性风险。
- `world-io/README.md` 已按 mutable 常用读取链路、Region/Chunk 元信息、block/palette、压缩内容 -> 通用 NBT -> strong Chunk
  的降层路径、写入、受限 scope、Region 枚举、live 路径和其他 world 文件的顺序重写；
  `world-format/README.md` 与根 README 同步更新。README 不展开反向转换矩阵，反向能力由 fluent API 与 KDoc 提供。
- `protocol-client/README.md` 明确说明 `ChunkLayout` 来自 Configuration dimension-type registry 与 Play Login
  的选择，而不是固定默认值；`protocol-server/README.md` 只保留“内存 Chunk -> packet”和“磁盘 Chunk -> 内存 Chunk ->
  packet”两条常用路径，并说明真实服务端通常全生命周期持有 `MinecraftWorldAccess`、优先发送内存 Chunk、仅在缺失时回源磁盘。
- `PalettedContainer` 默认保留稳定 ID；公开 `compactSnapshot()` 供无副作用检查，公开 `compact()` 供用户显式原地 整理，Chunk
  编码仍仅使用紧凑快照而不修改内存模型。
- 已通过 `:nbt:jvmTest`、`:nbt-serialization:jvmTest`、`:nbt-serialization:jsNodeTest`、
  `:nbt-serialization:wasmJsNodeTest`、`:world-format:jvmTest`、`:world-format:jsNodeTest`、
  `:world-format:wasmJsNodeTest`、`:world-format:mingwX64Test`、`:world-io:jvmTest`、`:world-io:jsNodeTest`、
  `:protocol-model:jvmTest`、`:protocol-client:jvmTest` 与 `:protocol-server:jvmTest`。`world-format`、
  `protocol-client` 和 `protocol-server` 的 JS/Wasm 编译通过，四个受影响模块的 `outgoingVariants` 与依赖方向
  已审计；最终坐标公式、README import、旧名称静态检查和 `git diff --check` 无错误。
- Chunk layout 后续澄清另通过 `:protocol-vanilla-data:jvmTest`、`:protocol-vanilla-data:jsNodeTest`、
  `:protocol-client:jvmTest`、`:protocol-server:jvmTest`，以及 client/server JS、Wasm 编译；覆盖完整同步 NBT、 Known Packs 省略
  NBT 和自定义 dimension 高度。
