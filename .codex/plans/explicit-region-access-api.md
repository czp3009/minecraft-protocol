# 显式 Region 与统一流式 World I/O API

## 状态

- 状态：已完成并验证。
- 基线：只以实施时的源码和 `AGENTS.md` 为准；不依赖已执行的历史计划。
- 范围：`world-format` 的 Region Chunk NBT 组合，以及 `world-io` 的公开 API、内部协调、精确文件原语、Live 读取、测试和文档。
- 兼容性：项目处于早期阶段，不保留旧 API 别名或兼容层。

## 最终目标

1. 用户既能一次性按 Chunk 操作，也能显式固定一个 Region，在循环中复用同一个 `.mca` 句柄、Header 和 sector allocator。
2. 用户既能把完整 Region 读成 `RegionFile`、准备 `RegionFile` 后完整写入，也能按 Region 内独立 Chunk 流逐个处理，且批量写只提交一次
   Header。
3. Region、Chunk、level、player、saved data、statistics 和 advancements 全部以流式回调作为物理路径；完整值、文档、文本和强类型方法是其封装。
4. 可写世界、Region 目录、精确 `.mca`、Live 世界和精确 Live `.mca` 分层明确，不用模式参数或运行时拒绝写入混淆能力。
5. 同一 Region 的并发写合法并由底层串行；不同 Region 的并行度和 dispatcher 由用户决定。

## 统一 API 规则

### 命名

- NBT 完整值：`read…Document` / `write…Document`。
- JSON 完整文本：`read…Text` / `write…Text`。
- 强类型：不加值类型后缀，接受 serializer，公开 facade 另提供 reified overload。
- 流式：与强类型共用基础动词，以最后一个 `Source`/`Sink` callback 区分。
- 自然值类型：Region 和 Chunk 保留 `readRegion`/`writeRegion`、`readChunk`/`writeChunk`。
- 删除：`clearRegion` / `clearChunk`；存在性：`doesRegionExist` / `doesChunkExist`。

公开流一律是 `kotlinx.io.Source`/`Sink`。Okio 只作为 `world-io` 文件系统和 `FileHandle` 层，并通过
`kotlinx-io-okio` 转换。

### Region 不是单一字节流

一个 Region 是最多 1024 个独立 Chunk 压缩流，还可能引用多个 `.mcc`。因此不伪装成一个连续
`Source`/`Sink`，而使用：

```kotlin
class RegionReadScope {
    val chunkPositions: List<LocalChunkPosition>
    fun readChunk(position: LocalChunkPosition): RegionChunk?
    fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, Source) -> T,
    ): T?
}

class RegionWriteScope {
    fun writeChunk(position: LocalChunkPosition, chunk: RegionChunk)
    fun writeChunk(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: (Sink) -> Unit,
    )
}
```

两个 scope 都是借用对象，只在 `readRegion`/`writeRegion` callback 内有效。`writeRegion` 是完整替换：callback 未写入的位置会被清除。

## 公开层次

### 1. `MinecraftWorldAccess`

- 持有 `session.lock`，覆盖完整世界。
- Region 和 Chunk 都有一次性完整值、流式、NBT document 和强类型入口。
- 提供 `openRegion` 与 `withRegion`；世界关闭等待所有显式 Region，最后释放 `session.lock`。
- 独立文件族全部同时提供 raw stream、完整 document/text 和 serializer/reified 方法。

### 2. `WorldRegionStore`

- 绑定一个 dimension/storage 的 Region 目录。
- 一次性 API 使用 `ChunkPosition` 或 `RegionPosition`。
- `openRegion`/`withRegion` 产生协调式 `WorldRegion`。
- registry 只保留活跃引用，不是 idle handle cache。

### 3. `WorldRegion`

- 固定 `RegionPosition`，Chunk 方法只接受 `LocalChunkPosition`。
- 第一次实际 I/O 时才打开 `.mca`；随后直到 `close` 都复用同一 entry/handle。
- 每次方法独立取得共享读或独占写，不在整个对象生命周期持锁。
- 方法可并发调用；`close` 封闭新调用、等待已接纳调用，再释放 Region pin。

主要形状：

```kotlin
suspend fun readRegion(): RegionFile?
suspend fun <T> readRegion(block: RegionReadScope.() -> T): T?
suspend fun writeRegion(region: RegionFile)
suspend fun writeRegion(block: RegionWriteScope.() -> Unit)

suspend fun readChunk(position: LocalChunkPosition): RegionChunk?
suspend fun <T> readChunk(
    position: LocalChunkPosition,
    block: (RegionChunkStreamInfo, Source) -> T,
): T?
suspend fun writeChunk(position: LocalChunkPosition, chunk: RegionChunk)
suspend fun writeChunk(
    position: LocalChunkPosition,
    compression: Compression,
    compressedLength: Long,
    block: (Sink) -> Unit,
)
```

### 4. 精确文件原语

- `RegionFileStore.open(path)`：无协调的可写精确 `.mca`；缺失时创建。
- `LiveRegionFileReader.open(path)`：无协调的 Live 只读精确 `.mca`。
- 两者使用相同 Region/Chunk 读取形状；只有可写类型暴露写、clear、flush。
- 调用方负责排除同文件的冲突读写和 close。

### 5. `LiveMinecraftWorldReader`

- reader 本身无 close 生命周期、无 registry、无锁、无修复。
- 一次性调用独立打开和关闭文件。
- `openRegion` 返回调用方持有的 `LiveWorldRegion?`；`withRegion` 是其结构化关闭封装。
- `LiveWorldRegion` 在多个读取之间复用一个 Live handle；用户负责排除它的 `close` 与并发读取。
- 缺失 Region 返回 `null` 且不创建任何路径。

## 写入与内存语义

### 完整写入封装流式写入

- `RegionFile` 写入遍历 `RegionWriteScope`。
- `RegionChunk` 写入将已压缩 payload 交给定长 Chunk sink。
- `NbtDocument`、强类型 NBT、JSON text/tree/typed 写入都把对应 format 直接连接到基础 sink callback。
- level/player 的临时文件与备份、saved data 的直接同步写、JSON 的 final-path truncate 等既有文件策略不变。

### Anvil 强类型写入

Anvil 在分配 sector 和写 record header 前需要压缩后长度。强类型/`NbtDocument` Chunk 写入因此允许保留一个压缩后的
Chunk，再走基础流式写入；已知 压缩长度的调用方使用 raw stream overload，可避免保存完整 payload。此限制必须透明记录，不能宣称强类型
Chunk 写完全零缓冲。

### 整区批量提交

1. 在一个 Region 独占准入中 stage 每个 Chunk。
2. 校验每个 callback 实际写入字节数与 `compressedLength` 完全一致。
3. 新 inline 数据和 external 临时文件完成后构造完整新 Header。
4. Header 只提交一次；随后清理旧 sector 和 sidecar。
5. Header 前失败释放新 sector、删除本批临时文件，旧 Region 保持可读。
6. `.mca` 与多个 `.mcc` 不具备跨文件系统事务，API 不承诺操作系统级跨文件原子性。

## 生命周期与并发不变量

- 缺失 Region 的 read/exists/clear 不创建目录或 `.mca`；只有 write 创建。
- 清空已有 Region 保留一个有效的空 `.mca`。
- 同一 Region 的共享读可重叠；写独占并保持写者优先的既有 admission 规则。
- 不同 Region 使用不同 entry，可以在用户 dispatcher 上并行。
- 显式 Region 是基准 pin；每个方法另有对象级 operation admission，确保 close 不会越过刚接纳的方法。
- 关闭顺序：`WorldRegion` 内层 entry -> 外层 `(storage, dimension)` store entry -> world close -> `session.lock`。
- 最后引用关闭文件；一次性连续调用仍是轻量短引用，显式 Region 才主动延长复用周期。
- 取消不打断同步物理提交；必要清理在 `NonCancellable` 中完成，取消保持主失败，清理失败作为 suppressed context。

## 实施清单

- [x] 为 `RegionChunkNbtFormat` 增加 serializer-driven Source/Sink 与内存 adapter。
- [x] 固定 Region Chunk NBT 为官方 `UNNAMED` 根，使 typed/document 字节互通。
- [x] 新增 `RegionReadScope`、`RegionWriteScope` 和 `RegionChunkStreamInfo`。
- [x] 重构 `RegionFileStore` 为 Local Chunk 坐标，并补齐完整/流式 Region 与 Chunk 方法。
- [x] 实现整区 staging 与单 Header commit；完整 `RegionFile` 写入封装它。
- [x] 区分 mutable create、mutable existing-only read 和 Live read-only open。
- [x] 新增 `WorldRegion`，并贯通 `WorldRegionStore`、`OpenMinecraftWorld`、`MinecraftWorldAccess`。
- [x] 新增 `LiveRegionFileReader`、`LiveWorldRegion`、Live `openRegion`/`withRegion`。
- [x] 将 level/player/saved NBT 与 statistics/advancements JSON 统一为 stream/document-or-text/typed 三层。
- [x] 更新现有调用方、官方互操作场景和缺失 Region 不落盘的测试假设。
- [x] 增加整区完整/流式、Header 单提交、失败回滚、scope 失效、句柄复用测试。
- [x] 增加显式 Region 并发写串行、close barrier、world lock 最后释放和 Live handle ownership 测试。
- [x] 更新 `world-io/README.md` 与 `world-io/AGENTS.md`。
- [x] 审计公开边界：独立可用的 Region、stream scope、精确文件与独立文件 store 公开，协调状态保持内部。
- [x] 完整 JVM、适用 Node/Native 编译测试与公开 API/发布依赖审计。

## 验证结果

- `./gradlew :world-format:jvmTest :world-io:jvmTest --max-workers=2`：通过，包含真实官方服务端世界互操作。
- `./gradlew :world-io:jsNodeTest --max-workers=2`：通过，包含 Node 文件系统与官方互操作。
- `./gradlew :world-io:linuxX64Test --max-workers=2`：通过，包含 Native 文件系统与官方互操作。
- `./gradlew :world-io:compileKotlinMetadata :world-io:allMetadataJar --max-workers=2`：通过。
- 公开签名依赖审计：`nbt`、`nbt-serialization`、`world-format`、Okio、JSON serialization 与
  `kotlinx-io-core` 均为 `api`；Okio/kotlinx-io adapter、协程与 JSON I/O 实现保持 `implementation`。

## 完成标准

- 用户可选一次性 Chunk、显式 Region 循环、完整 `RegionFile` 或 Region 内 Chunk streams，而无需学习第二套命名。
- 完整写入确实委托流式核心；Region 完整替换确实只提交一次 Header。
- 同 Region 并发写合法且物理串行；不同 Region 无全局锁或内部线程池。
- 可写、Live、精确文件和整世界的所有能力边界由类型表达。
- 缺失文件、副作用、流 ownership、close、取消和跨文件非原子边界均有文档与测试。
