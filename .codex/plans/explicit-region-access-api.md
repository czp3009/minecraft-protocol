# 显式 Region 访问与整区读写 API

## 状态与范围

- 状态：待实施。
- 本计划以当前源码和当前 `AGENTS.md` 为唯一实现基线；已完成的
  `parallel-region-io-user-thread-pools.md` 只属于历史记录，不作为本计划的依赖或实施顺序来源。
- 主要修改 `world-io`，复用 `world-format` 已有的 `RegionPosition`、`LocalChunkPosition`、
  `RegionChunk` 和 `RegionFile`。除非实现中发现现有值类型无法表达有效 Anvil 数据，否则不新增另一套 Region 容器模型。
- 目标是让调用方显式固定一个 Region 的文件句柄并在其中进行多次整区或单 Chunk 操作，同时保留现有的一次性 Chunk API。
- 本计划不引入内部线程池、调度器、空闲句柄缓存、跨进程锁或后台写回队列。并行度和调用线程仍由用户控制。

## 设计结论

该设计可行，并且与当前实现的资源和并发模型一致：

1. 当前 `RegionFileStore` 本来就以一个 `.mca` 文件及其 `.mcc` sidecar 组为打开单位；显式 Region 资源只是把当前仅存在于一次操作期间的
   `RegionEntry` 引用提升为调用方可控制的作用域引用。
2. 当前同一 Region 的共享读、独占写、写者优先、公平性说明、最后引用关闭以及 `.mca`/`.mcc`
   逻辑文件组规则全部保留。Region 资源不在整个生命周期内长期占有读锁或写锁，每次方法调用仍独立进入同一个
   `LogicalFileAccess`。
3. 因此，同一 Region 内多个不同 Chunk 的并发写入仍然是合法调用，只会在底层串行提交；不同 Region 仍可并行。显式 Region
   的性能收益来自复用句柄、已解析 Header 和 Sector allocator，而不是改变正确性规则。
4. `world-format` 已有 `RegionFile`，它就是一次读取完整逻辑 Region 的内存值。高层应补齐
   `writeRegion`，使调用方可以准备一个 `RegionFile` 并一次提交整个 Region。
5. 一次性 Region/Chunk API、作用域 Region API、无协调的精确文件 API 和 Live 只读 API 使用同一组
   `read/write/clear/exists` 动词与 `Region/Chunk` 粒度，不通过别名、兼容层或模式参数制造多套语义。

## API 分层与形状

### 1. 完整 Region 的值类型

- `RegionPosition` 是 Region 标识。
- `RegionFile` 是脱离文件句柄的完整 Region 快照，包含最多 1024 个以 `LocalChunkPosition` 为键的
  `RegionChunk`。
- `RegionChunk` 继续是单 Chunk 的已压缩值；从 `world-io` 读取时，外置 `.mcc` 内容必须已经解析，所得
  `RegionFile` 可在关闭文件句柄后安全使用。
- 整区 NBT 不新增一个虚假的单一文档类型。一个 Region 是最多 1024 个独立 NBT 流；调用方若要一次解码全部 NBT，可在读出的
  `RegionFile` 上使用 `RegionChunkNbtFormat` 映射，或在显式 Region 作用域内逐 Chunk 解码。

### 2. 一次性高层 API

`WorldRegionStore` 补齐严格对称的 Region/Chunk 方法族：

```kotlin
suspend fun readRegion(position: RegionPosition): RegionFile?
suspend fun writeRegion(position: RegionPosition, region: RegionFile)
suspend fun clearRegion(position: RegionPosition)
suspend fun doesRegionExist(position: RegionPosition): Boolean

suspend fun readChunk(position: ChunkPosition): RegionChunk?
suspend fun writeChunk(position: ChunkPosition, chunk: RegionChunk)
suspend fun clearChunk(position: ChunkPosition)
suspend fun doesChunkExist(position: ChunkPosition): Boolean
```

- 移除以 `writeChunk(position, null)` 表示删除的公开形状；删除只由 `clearChunk` 表达。
- 保留现有 Chunk 流式读写重载以及 NBT 便利方法。
- 新增整区写入不提供伪流式的单一 `Source`/`Sink` 重载：一个逻辑 Region 可能横跨 `.mca` 和多个
  `.mcc`，不能诚实地表示为一个连续文件流。
- `MinecraftWorldAccess` 和 `OpenMinecraftWorld` 以相同名称补齐上述 Region 方法，并继续附带现有的
  `storage`、`dimension` 默认参数。
- 所有一次性方法都通过短生命周期的显式 Region 资源实现，避免一次性路径和作用域路径产生两套协调或写入逻辑。

### 3. 调用方持有的协调式 Region 资源

新增公开的 `WorldRegion`，表示一个绑定到 `WorldRegionStore`、`RegionPosition` 和一个共享
`RegionEntry` 的可关闭资源：

```kotlin
class WorldRegion {
    val position: RegionPosition

    suspend fun readRegion(): RegionFile?
    suspend fun writeRegion(region: RegionFile)
    suspend fun clearRegion()
    suspend fun doesRegionExist(): Boolean

    suspend fun readChunk(position: LocalChunkPosition): RegionChunk?
    suspend fun writeChunk(position: LocalChunkPosition, chunk: RegionChunk)
    suspend fun clearChunk(position: LocalChunkPosition)
    suspend fun doesChunkExist(position: LocalChunkPosition): Boolean

    suspend fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T?

    suspend fun writeChunk(
        position: LocalChunkPosition,
        compression: Compression,
        compressedLength: Long,
        block: BufferedSink.() -> Unit,
    )

    suspend fun readChunkNbt(position: LocalChunkPosition): NbtDocument?
    suspend fun writeChunkNbt(position: LocalChunkPosition, document: NbtDocument)
    suspend fun writeChunkNbt(
        position: LocalChunkPosition,
        document: NbtDocument,
        compression: Compression,
    )

    suspend fun flush()
    suspend fun close()
}
```

`WorldRegionStore` 提供两种取得方式：

```kotlin
suspend fun openRegion(position: RegionPosition): WorldRegion

suspend fun <T> withRegion(
    position: RegionPosition,
    block: suspend WorldRegion.() -> T,
): T
```

- `withRegion` 是普通调用的首选，负责结构化关闭；`openRegion` 面向需要跨函数或跨批次持有 Region 的调用方。
- Region 内部只接受 `LocalChunkPosition`；Region 外的一次性 API 接受 `ChunkPosition`。这样不会重复传入或校验 已由资源固定的
  Region 坐标。
- `WorldRegion` 的各方法可从多个用户协程并发调用。它不拥有线程池，也不改变调用协程的 dispatcher。
- `WorldRegion.close()` 封闭新调用，等待已接纳调用完成，以 `NonCancellable` 完成资源回收，并使关闭后的所有
  方法稳定失败。重复关闭共享同一完成结果和失败。
- `openRegion` 只固定逻辑 Region 引用；在第一次确实需要文件 I/O 前不创建或打开缺失的 `.mca`。读不存在的 Region 返回
  `null`，`doesRegionExist` 返回 `false`，读操作不得产生目录或空文件。
- `writeRegion`、`writeChunk` 才允许创建目录和 Region 文件。
- `clearRegion` 对不存在的 Region 是无副作用的成功；对存在的 Region 清空全部 Header entry 并删除属于该 Region 的外置
  sidecar，但保留一个有效的空 `.mca`。因此清空后 `readRegion` 返回空 `RegionFile`，而
  `doesRegionExist` 仍可为 `true`。该语义与 `clearChunk` 只清理内容而不删除 Region 文件一致。

`MinecraftWorldAccess`/`OpenMinecraftWorld` 也提供同形状的 `openRegion` 和 `withRegion`。世界级
`WorldRegion` 在生命周期内同时固定：

1. `(storage, dimension)` 对应的外层 `RegionStoreEntry`；
2. 具体 `RegionPosition` 对应的内层 `RegionEntry`。

关闭顺序固定为先结束内层 Region 使用，再释放外层 store 引用。世界关闭必须封闭新 Region 租约、等待所有已 打开 Region 完成，再关闭
Region store 并最终释放 `session.lock`。

### 4. 无协调的精确 Region 文件 API

当前 `RegionFileStore` 继续作为高级调用方可直接使用的、无锁且无协调的精确 `.mca` 文件原语，但收紧公开形状：

- `RegionFileStore.open(path, ...)` 只打开可写文件，缺失时创建。
- 方法命名与高层一致：`readRegion`、`writeRegion`、`clearRegion`、`readChunk`、`writeChunk`、
  `clearChunk`、`doesChunkExist`、`flush`、`close`。
- 一个实例固定一个 `RegionPosition`，Chunk 方法只接收 `LocalChunkPosition`。
- 它不取得 `session.lock`、不使用 `LogicalFileAccess`，也不协调覆盖同一 Region 的其他实例。调用方必须自行 排除
  write/write、read/write 和 close/read 竞争；文档必须把这一点放在类级契约中。
- `writeRegion` 是一个真正的整区批量原语，不是在公开层循环调用 1024 次 `writeChunk`。

新增独立的公开 `LiveRegionFileReader`，而不是让一个含写方法的 `RegionFileStore` 通过运行时状态拒绝写入：

```kotlin
class LiveRegionFileReader {
    val regionPosition: RegionPosition
    val path: Path

    fun readRegion(): RegionFile
    fun readChunk(position: LocalChunkPosition): RegionChunk?
    fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T?
    fun doesChunkExist(position: LocalChunkPosition): Boolean
    fun close()

    companion object {
        fun open(regionFile: Path, fileSystem: FileSystem = systemFileSystem): LiveRegionFileReader
    }
}
```

- 它使用当前 `openLiveReadOnly` 平台能力，使匹配的官方进程仍可写、删或替换文件。
- 它不加锁、不修复文件、不缓存跨次 Header，允许观察到旧状态或撕裂状态并传播相应 I/O/格式错误。
- 它是显式持有文件句柄的底层资源；调用方负责 close 与线程安全。
- 共享读取实现放在内部组合对象中，公开类型按可写/Live 只读能力分离，不公开会在运行时抛错的写方法。

### 5. Live 世界读取的作用域 Region

`LiveMinecraftWorldReader` 保持自身无生命周期、无跨调用保留资源，但新增一次调用内固定句柄的作用域方法：

```kotlin
fun <T> withRegion(
    position: RegionPosition,
    storage: RegionStorageDirectory = RegionStorageDirectory.CHUNKS,
    dimension: DimensionDirectory = DimensionDirectory.Overworld,
    block: LiveWorldRegion.() -> T,
): T?
```

`LiveWorldRegion` 是回调期间有效的只读借用视图，提供与协调式 Region 相同的读取侧形状：

```kotlin
class LiveWorldRegion {
    val position: RegionPosition

    fun readRegion(): RegionFile
    fun readChunk(position: LocalChunkPosition): RegionChunk?
    fun <T> readChunk(
        position: LocalChunkPosition,
        block: (RegionChunkStreamInfo, BufferedSource) -> T,
    ): T?
    fun doesChunkExist(position: LocalChunkPosition): Boolean
    fun readChunkNbt(position: LocalChunkPosition): NbtDocument?
}
```

- Region 文件不存在时 `withRegion` 返回 `null` 且不调用 block。
- 回调返回后视图失效；不得把借用对象或 Chunk stream 逃逸出 block。
- `LiveMinecraftWorldReader` 现有的一次性 `readRegion`、`readChunk`、`doesChunkExist`、
  `readChunkNbt` 全部委托给 `withRegion`，因此只保留一份路径解析、文件类型验证和 Live 句柄打开逻辑。
- 需要自行持有 Live 句柄的高级调用方直接使用 `LiveRegionFileReader`；高层 reader 不再额外提供第二套手动 close 生命周期。

## 整区读取与写入语义

### 整区读取

- 在一次共享读准入内读取一个 Header，并使用同一个打开句柄遍历全部有效位置。
- `.mcc` payload 在返回前全部解析到对应 `RegionChunkPayload.External` 中；返回值不借用句柄。
- Header、inline record 或 sidecar 无效时沿用当前严格错误语义，不以空 Chunk 静默掩盖格式错误。
- 读取期间与同 Region 写入互斥；不同 Region 不互相阻塞。Live 读取不参加该协调并保留撕裂风险。

### 整区写入

`writeRegion(position, region)` 表示把该 Region 的逻辑 Chunk 集合完整替换为 `region.chunks`：

- 输入中存在的位置被写入；输入中缺失的现有位置被清空。
- 在取得独占写准入前，验证全部坐标、压缩类型、payload 已解析状态、长度和 Region 上限；验证失败不得创建文件或 留下临时
  sidecar。
- 与当前 `writeChunk` 保持相同策略：输入 `RegionChunk.timestamp` 以及 Inline/External marker 是读取到的
  表示信息，不控制高层文件提交。写入时间戳由存储层生成，inline/sidecar 由长度阈值自动选择。KDoc 必须同时写在 Region 和 Chunk
  写方法上，避免把 `RegionFile` 误解成逐字节恢复镜像。
- 若用户需要严格控制 Header timestamp、external marker 或逐字节编码，应使用 `world-format` 的
  `RegionFileFormat` 产生/消费原始容器，并自行拥有目标文件替换策略；协调式 `world-io` 不混入该低层策略。
- 整区写入仍然原地更新 `.mca`，不得通过临时完整 `.mca` 替换目标文件，也不得缩小文件。

### 批量提交算法

为使整区写入不退化成“同一句柄上的 1024 次 Header/fsync”，在 `RegionFileStore` 增加内部批量事务：

1. 一次读取并复制当前 Header/allocator 状态，计算新增、替换、保留和删除位置。
2. 在旧位置仍被保留的前提下，为所有新增或变化的 inline record 和 external stub 分配新 Sector。
3. 先写完全部新 inline 数据、external 临时文件和 `.mca` stub；任何未达到声明长度的流都在 Header 提交前失败。
4. `syncWrites = true` 时，在 Header 提交前对新 `.mca` 数据执行一次耐久刷新，并按当前 sidecar 替换协议处理临时 文件的耐久性。
5. 构造完整新 Header，只提交一次 Header；必要时再执行一次耐久刷新。
6. Header 已提交后，以不可取消清理完成 external 临时文件的最终替换、旧 sidecar 删除和旧 Sector 释放；提交
   状态为主失败，清理失败作为附加上下文保留。
7. Header 提交前失败时释放尚未引用的新 Sector、删除本次临时文件，旧 Region 继续可读；Header 提交后的失败 不得谎报为“未写入”。

实现前必须重新核对仓库所选版本的官方 Region 写入和 external chunk 顺序，并扩展现有故障注入点。`.mca` 与 多个 `.mcc`
无法组成真正的跨文件原子事务，公开文档只能承诺明确的 Header 提交点和失败恢复语义，不能宣称整区 写入具有操作系统级全文件原子性。

整区写入独占该 Region 的整个批次；同 Region 的单 Chunk 读写在批次前后排队，不会观察批次中间 Header。 不同 Region 仍可并发。

## 生命周期与锁不变量

### Region 引用

- `openRegion` 为 `RegionEntry.users` 建立一个基准 pin，使连续调用之间不会关闭句柄。
- 每个 Region 方法在检查租约未关闭后建立一次操作 pin，再进入 `LogicalFileAccess`；这样 `close` 与刚被接纳、尚在 等锁的操作不会竞争。
- 操作结束先退出读/写准入，再释放操作 pin。Region close 封闭新准入、等待操作 pin 归零，最后释放基准 pin。
- 多个 `WorldRegion` 和一次性调用命中同一 `RegionPosition` 时共享同一个 `RegionEntry`、句柄、Header 和 allocator，不创建相互不协调的
  store。
- 最后一个引用释放时才关闭 `RegionFileStore`；关闭成功或失败后都从 registry 移除 entry，后续访问可创建新 entry。

### 锁顺序

- 保持运行时顺序：`fileAccess` 后 `openMutex`。
- `bookkeeping` 只管理 admission、引用和 close barrier，不跨文件 I/O 持有。
- 最后引用清理是唯一可在没有 `fileAccess` 时取得 `openMutex` 的路径；此时 `users == 0 && closing` 已排除所有 已接纳运行时路径。
- `WorldRegionStore.flush()` 对当前 entry 做快照 pin，并与整区/单 Chunk 写入使用相同独占准入。

### 取消与失败

- 等待共享读、独占写、Region close、store close 或外层 world close 时的取消不得泄漏 pin 或句柄。
- 已开始的 Header/sidecar 提交与必须完成的回滚在 `NonCancellable` 中结束；之后重新抛出取消，并把清理失败 作为 suppressed
  context。
- Region close、store close 和 world close 延续当前共享 close barrier 语义；并发 close 调用观察同一个最终 结果。

## 实施步骤

### 1. 固化现状与契约测试

- 记录当前 `RegionFileStore`、`WorldRegionStore`、`OpenMinecraftWorld` 和
  `LiveMinecraftWorldReader` 的文件打开、引用、读写准入、close barrier 和故障注入路径。
- 为“连续一次性 Chunk 调用会在无并发重叠时反复打开同一 `.mca`”增加可观察测试，以便证明显式 Region 作用域确实只打开一次，而不是仅改变
  API 表面。
- 重新检查仓库所选官方实现中整区重写、Header 更新时间、external sidecar 替换和 durable flush 顺序；将只影响 实现策略的结论写入相邻
  KDoc/测试，不把发行版字面量复制到文档。

### 2. 整理精确文件原语

- 抽出 `RegionFileStore` 与 `LiveRegionFileReader` 共享的内部 Header/record/sidecar 读取实现。
- 将精确文件 Chunk 参数改为 `LocalChunkPosition`，把绝对坐标只在 sidecar 路径构造时通过固定的
  `regionPosition.chunk(local)` 计算。
- 把现有 `readAll/read/exists/write/clear` 重命名成完整的 Region/Chunk 方法族，直接更新内部调用方和测试，不保留 旧别名。
- 让可写 open 与只读 Live open 在类型层分离；删除 `writer == null` 决定写方法运行时失败的公开模式。
- 区分“只读打开已存在文件”和“写入时打开或创建文件”，消除读操作创建空 Region 的副作用。

### 3. 实现整区批量写原语

- 在 `RegionFileStore` 增加 `writeRegion(RegionFile)` 和 `clearRegion()`。
- 将单 Chunk 写的 sector 分配、payload 定长校验、Header 提交、external 临时文件和失败清理拆成可复用的内部 staging/commit
  单元，避免复制协议。
- 批量验证全部输入后再开始任何物理变化；批量 stage 后只提交一次 Header。
- 保持单 Chunk `writeChunk` 的现有提交粒度和性能，不强迫它构造完整 `RegionFile`。
- 保持文件不整体替换、不缩小、external threshold 自动选择和 `syncWrites` 配置语义。

### 4. 增加协调式 `WorldRegion`

- 将当前私有 `RegionEntry` 获取/释放逻辑扩展为可创建长生命周期 pin 的内部接口。
- 新增 `WorldRegion`，实现租约 admission、并发操作计数、关闭屏障以及 Local/absolute 坐标转换边界。
- 所有 Region 方法调用现有 `LogicalFileAccess` 和同一个 `RegionFileStore`；不在租约构造时取得长期读锁或写锁。
- 使 `WorldRegionStore` 的一次性 Region/Chunk 方法全部通过内部短租约委托。
- 补齐 Region read/write/clear/exists、Chunk read/write/clear/exists、流式 Chunk 和 NBT 方法。

### 5. 贯通世界级访问

- 在 `OpenMinecraftWorld` 的 `(storage, dimension)` registry 上增加能够跨调用固定外层 entry 的 Region 租约路径。
- 在 `MinecraftWorldAccess` 添加相同的 `openRegion`、`withRegion` 和整区一次性方法，并保持参数顺序和默认值与 Chunk 方法一致。
- 把 Region 租约纳入 world close/`session.lock` 释放屏障，覆盖 close、取消和清理失败的组合。

### 6. 增加 Live Region 读取

- 新增公开 `LiveRegionFileReader`，直接验证精确 `r.<x>.<z>.mca` 文件名并解析同目录 sidecar。
- 新增借用式 `LiveWorldRegion` 和 `LiveMinecraftWorldReader.withRegion`；一次 block 只打开一个句柄。
- 让所有现有 Live Region/Chunk 一次性读取委托到该作用域路径。
- 保持 Live API 同步、无锁、无 dispatcher、无修复且不阻挡官方写入；测试并文档化对象/stream 不得逃逸。

### 7. 文档与不变量更新

- 更新 `world-io/README.md`，分别给出：
    - 一次性 Chunk 读写；
    - `withRegion` 中顺序或并发处理多个 Chunk；
    - 一次读出/准备并一次写入 `RegionFile`；
    - 直接 `RegionFileStore`；
    - Live `withRegion` 和直接 `LiveRegionFileReader`。
- 明确性能边界：显式 Region 复用句柄和解析状态，但同 Region 写仍串行；用户线程池决定不同 Region 的并行度。
- 更新 `world-io/AGENTS.md` 中 Region entry 生命周期、显式租约、Live 借用作用域、整区批量提交点和 close 不变量。
- 如果公开签名变化影响根 README 的模块说明，同步更新根 `README.md`；不修改已完成的旧计划文件。

## 测试计划

### `world-io` commonTest

- Region/Chunk API 对称性及默认 storage/dimension 路由。
- 缺失 Region 的 read/exists/clear 不创建目录或文件；首次写入才创建。
- `readRegion` 一次返回全部 inline/external、混合压缩和负坐标 Chunk。
- `writeRegion` 从空文件创建、完整替换、清除输入中缺失位置、写入空 Region、覆盖 external/inline 边界。
- Region 输入中未解析 external payload、非法长度或非法压缩在物理修改前失败。
- `writeRegion` 与 `writeChunk` 对 timestamp 和 external marker 使用相同自动策略。
- `withRegion` 内多次顺序 Chunk 读写只打开/关闭一次 `.mca`；退出 block 后最后引用关闭。
- 两个显式 Region、一次性操作和 `flush` 命中同一位置时共享 entry 与句柄。
- `WorldRegion.close` 的幂等性、关闭后拒绝调用、block 失败时结构化关闭以及关闭失败传播。
- 单 Chunk 流式 block 未消费完、写入不足/超长和 block 抛错时仍维持现有失败语义。
- 整区批量提交前后的故障注入：inline 写、durable flush、Header 写、sidecar move/delete、Sector 回收和 close。
- Header 提交前失败保持旧逻辑 Region；提交后清理失败报告已提交状态且无资源泄漏。

### `world-io` JVM 并发测试

- 同一显式 Region 的多个共享读可重叠。
- 同一 Region 的 Chunk 写、整区写和流式读按共享读/独占写规则排队，写者优先语义不变。
- 同一 Region 不同 Chunk 的并发写合法且最终都可读；底层同时最多一个写提交。
- 不同 Region 在用户提供的 dispatcher 上真实并行。
- Region close 与等锁、已接纳、正在提交和刚完成的操作分别竞争时无死锁、无提前关句柄、无 pin 泄漏。
- store/world close 等待显式 Region；`session.lock` 仅在所有 Region 清理完成后释放。
- 在观测到确切 admission/wait/commit/cleanup gate 后取消，验证 `NonCancellable` 清理和主/附属失败顺序。

### Live 测试

- `LiveMinecraftWorldReader.withRegion` 一次 block 只开一个 Live 句柄，缺失/非普通文件语义明确。
- 同一作用域连续读取多个 Chunk 和整个 Region，外置 sidecar 正确解析。
- 官方风格 writer 同时写、删、替换时 Live reader 不阻塞 writer，并允许得到旧值、新值或已声明的撕裂错误。
- block 返回或抛错后句柄总是关闭；借用视图和 stream 在作用域外稳定拒绝使用。
- 直接 `LiveRegionFileReader` 的手动 close、重复 close 和 close/read 由调用方协调的契约得到覆盖。

### 官方互操作与平台验证

- 扩展现有 `hostFilesystemTest` 单一有序场景：库整区写入包含官方支持压缩组合及 external chunk 的 Region，官方
  服务端加载、保存并正常停止，库重新打开并逐 Region/Chunk 验证；反方向读取官方生成的整区文件。
- 首轮运行 `./gradlew :world-io:jvmTest`。
- JVM 稳定后运行适用的 Node 和桌面 Native 标准任务，最后按仓库约定运行 `./gradlew allTests`；Gradle wrapper 调用不得并发。
- 检查 `world-io` 发布元数据和公开签名，确认只暴露已有向下依赖类型且外部消费者可独立使用 Region API；若元数据 无法证明，再增加最小
  external-consumer smoke test。

## 完成标准

- 用户可以在高层一次读出 `RegionFile`，也可以准备 `RegionFile` 后一次完整写入一个 Region。
- 用户可以通过 `withRegion/openRegion` 在连续 Chunk 操作之间稳定复用同一底层 `.mca` 句柄。
- 一次性 Region 和 Chunk API 具备统一的 read/write/clear/exists 形状，且全部复用同一实现路径。
- 同一 Region 的并发写仍被允许并在底层串行；不同 Region 的并行度完全由调用方线程/协程调度决定。
- `RegionFileStore` 提供无协调的整区与单 Chunk 原语，`LiveRegionFileReader` 提供类型安全的无锁只读精确文件 原语。
- `LiveMinecraftWorldReader` 能在一次借用作用域内复用一个 Region 句柄，同时自身仍不保留跨调用资源。
- 整区写入是单次批量 Header 提交，不是 1024 次公开 Chunk 写循环，也不替换或缩小完整 `.mca`。
- 最后引用关闭、锁顺序、取消、close barrier、sidecar 清理和 `session.lock` 生命周期全部有确定测试证明。
