# 高层并行存档 IO:per-file 串行、跨文件并行、用户线程池

## 背景与结论

现状是两级粗锁:`OpenMinecraftWorld` 一把 world 级 `Mutex` 包住一切,`WorldRegionStore` 一把目录级
`Mutex` 包住该目录全部读写。多协程调用高层入口时一切串行,压缩 (CPU 密集)与文件 IO 也没有分离的执行位置。

官方 26.2 (本仓库选中版本,server JAR 反编译)的参考结论:

- 单个 region 目录内由 `IOWorker` 的单任务队列彻底串行 (读优先、同 chunk 写合并);
- 跨 storage/跨维度通过多个 `IOWorker` 共享 `Util.IO_POOL` 并行;
- `RegionFile` 读写方法自带 `synchronized` 作为文件级兜底。

本计划按目标状态描述,不迁就现有实现;早期项目允许直接重构公共 API。

## 目标

1. 高层入口 (`MinecraftWorldAccess` / `LiveMinecraftWorldReader` / `WorldRegionStore`)支持并发调用:
   **同一 region 文件内操作 (含读)完全串行,不同文件之间并行**。
2. 压缩/编码 (CPU 密集)与 region 文件 IO (阻塞)可分别分配到 **用户自己提供的 dispatcher**, 尽可能提高批量存档速度。
3. 库本身不创建、不拥有任何线程或线程池;执行位置完全由调用方决定。

## 非目标

- 不改变 `RegionFileStore`(单文件直连入口)的无锁契约:直接使用底层类的并发安全由调用方负责。
- 不提供跨文件事务或全局写入顺序;Anvil 各文件独立,单文件崩溃一致性协议保持不变。
- 不改 `world-format`:`RegionChunkNbtFormat.encode/decode` 与 `CompressionCodecs` 已是无状态纯函数。
- level.dat、playerdata、saved data、statistics、advancements 等元数据文件不参与并行化 (单文件原子替换、体积小,维持 world
  级互斥即可)。

## 目标分层

```
world-format      无状态纯函数:Anvil 容器字节格式 + 压缩 + chunk NBT 编解码(不动)
RegionFileStore   单个 .mca(+.mcc)的 Anvil 容器,字节级 API,无锁,无 NBT 依赖
WorldRegionStore  一个 region 目录的编排:NBT 编解码调度 + per-file 锁 + pinned LRU + flush/close 屏障
OpenMinecraftWorld session.lock 生命周期 + 元数据文件互斥 + regionStores 查找
```

关键调整:**NBT 编解码从 `RegionFileStore` 上移到 `WorldRegionStore`**。
`RegionFileStore` 删除 `chunkNbtFormat` 构造参数与 `readChunkNbt`/`writeChunkNbt`,只保留字节级
`read`/`readAll`/`exists`/`write`/`clear`/`flush`/`close`。理由:压缩必须发生在文件锁外, 而锁在 `WorldRegionStore`;
`WorldRegionStore` 本就持有 `chunkNbtFormat`,编解码属于它的职责。 直连单文件的用户自行组合公开的 `RegionChunkNbtFormat`
(world-io README 的 Single region-file access 示例同步更新)。

## WorldRegionStore 并发核心

### RegionEntry 与两把职责不同的锁

```kotlin
private class RegionEntry {
    var store: RegionFileStore? = null   // null = 尚未打开(live-read-only 下也可能是"文件不存在")
    var absent = false                   // live-read-only 探测过文件不存在
    val fileMutex = Mutex()              // 该文件的 IO 串行化 + 打开单飞
    var users = 0                        // 持有者 + 等待者数,由 bookkeeping 保护
}
```

- `bookkeeping: Mutex` 只保护 `entries: LinkedHashMap<RegionPosition, RegionEntry>`(访问序)、 每条目 `users`、总计数
  `pinned`、`sealed` 标志。 **临界区内不做任何 IO、不调用任何挂起函数**。
- `fileMutex` 在实际 IO (含打开文件、读 header)期间持有;同一文件读写共用, 因为读走 `writer.header` 内存快照,写中途的读会看到半提交状态。

`pinned` 是唯一的在途/占用计数 (恒等于所有条目 `users` 之和),同时承担两个职责:
逐出保护 (条目 `users > 0` 不可逐出)与 flush/close 屏障 (`pinned == 0` 表示无任何持有者和 等待者)。不维护第二个计数器。

### 核心算法 withRegion

```kotlin
private suspend fun <R> withRegion(position: RegionPosition, block: suspend (RegionFileStore?) -> R): R {
    val entry = bookkeeping.withLock { acquireLocked(position) }   // touch LRU、users++/pinned++、超限时收集受害者
    try {
        return entry.fileMutex.withLock {
            val store = entry.store ?: withContext(ioDispatcher) { openLocked(position) }
                ?.also { entry.store = it }                          // IO 在 fileMutex 内
            withContext(ioDispatcher) { block(store) }
        }
    } finally {
        bookkeeping.withLock { releaseLocked(entry) }                // users--/pinned--、归零时唤醒 drain、必要时 trim
    }
}
```

- **打开单飞不需要额外原语**:`fileMutex` 本身就是单飞。两个协程同时首次访问同一文件, 一个在锁内打开并填充 `store`
  ,另一个等到锁后直接看到非 null。
- `users` 在 **锁前**于 `bookkeeping` 下递增,因此"已注册但还没拿到 fileMutex"的协程也计入在用, 不会被逐出后拿到已关闭的
  store (这正是 pinned 语义,不需要独立于 fileMutex 的第二套引用计数)。
- live-read-only 的存在性探测 (`metadataOrNull`)在 `fileMutex` 内、`ioDispatcher` 上执行; 探测失败置 `absent = true`
  ,读路径返回 null/空 `RegionFile`,后续访问不再重复探测。 写路径在只读 `WorldFileAccess` 上仍由 `requireWritable`
  拒绝,absent 条目永远不会被写。

### pinned LRU

自实现,不引入缓存库:所需语义是"只逐出空闲条目、逐出与重新打开严格互斥", 没有维护库提供 (官方实现靠整体 synchronized
规避了这个问题);实现就是
`LinkedHashMap` 访问序 + `users` 计数 + 一个扫描函数,几十行私有代码。

- 容量语义:`maximumOpenRegions` 限制的是缓存条目数, **软上限**。插入后与 `users` 归零时触发 trim:从最旧端扫描,跳过
  `users > 0` 的条目,移出 map 后在 `bookkeeping` 外 close (阻塞 IO 不进临界区)。全部条目在用时允许临时超限,不阻塞、不强关。
- `store == null` 的空条目同样计数,逐出时无需 close。
- 移出 map 与 close 的顺序保证:先在 `bookkeeping` 内移出 (此后新访问不可能找到它), 且此刻 `users == 0`
  (不存在持有者/等待者),再在锁外 close。

### 正确性不变量 (实现与评审以此为准)

1. **条目从 map 移除恰好发生一次,且发生在 `bookkeeping` 临界区内;只有移除者关闭它的 store。**
   这是整个设计的正确性核心,三条路径都满足:
    - acquire 侧 trim:受害者在 `acquireLocked` 临界区内移出,且该操作此刻已计入 `pinned`, close 的 drain 会等它在锁外把受害者关完;
    - release 侧 trim:`users--`、`pinned--`、受害者移出 map 在 **同一临界区**内完成,drain 唤醒 必然发生在该临界区之后,close
      在 drain 之后拍的快照永远不含 trim 受害者;
    - drain 之后:`sealed` 拒绝新 acquire,没有在途 release,不可能再发生任何 trim, close 的收割是排他的。 因此不依赖
      `RegionFileStore.close()` 的幂等性兜底,时序本身排他。
2. **`users` 在拿 `fileMutex` 之前于 `bookkeeping` 内递增**:已注册但还没拿到锁的等待者也计入 在用,不会被逐出后拿到已关闭的
   store。
3. **`bookkeeping` 临界区内无 IO、无挂起**:只做 O (1)/O (容量) 的纯内存操作,不成为跨文件 并行瓶颈;drain 的唤醒建立了
   happens-before,共享状态无需额外同步原语。
4. **锁顺序单一、无环**:`bookkeeping` 与 `fileMutex` 永不嵌套持有 (逐出的 close 在
   `bookkeeping` 之外),world 级锁只包 store 查找 (先取先放),store 屏障不在 world 锁内等待。
5. **取消安全**:`Mutex.withLock` 可安全取消,`finally` 保证 release;阻塞 IO 中途取消时 调用在 IO 线程跑完后于挂起点传播取消,store
   状态保持一致。

### 读/写路径分段

写路径 (`writeChunkNbt`):

1. `files.requireWritable()`;
2. `withContext(compressionDispatcher)` 内 `chunkNbtFormat.encode(document, compression)`,不持任何锁;
3. `validatedCompressedPayload`(便宜,保持在打开文件之前,维持"非法写入不创建文件"契约);
4. `withRegion` 内持 `fileMutex`、`withContext(ioDispatcher)` 执行 `store.write(position, chunk)`。

`writeChunk(RegionChunk?)` 入参已是压缩字节:第 1、3、4 步,无编码段。

读路径 (`readChunkNbt`):

1. `withRegion` 内持 `fileMutex` 读出 `RegionChunk?`(不可变值);
2. 释放全部锁后 `withContext(compressionDispatcher)` 内 `chunkNbtFormat.decode(chunk)`。

`readRegion`(`readAll`)与 `doesChunkExist` 只有第 1 步,无解码。

## flush / close 屏障 (与 pin 共用同一机制)

- `close()`:在 `bookkeeping` 内置 `sealed = true`(`pinned` 已为 0 则立即完成 drain)→ 挂起等待 `pinned == 0` → **此刻**在
  `bookkeeping` 内快照全部 store 并清空 map → 在锁外逐个 close。由不变量 1,此刻无任何持有者/等待者且不可能再出现,收割排他。重复
  close 直接返回 (第二个 close 可能在第一个完成收割前返回,与现有语义一致)。
- `flush()`:在 `bookkeeping` 内 checkOpen、快照条目并逐个 `users++`(即 pin,同时使 close 的 drain 等待自己)→ 逐条目
  `fileMutex` 内 flush,完成一个 unpin 一个 → 全部 unpin 后若已 sealed 且 `pinned == 0` 则唤醒 drain。flush 不阻塞新读写,靠
  per-file 锁保证安全; sealed 之后开始的 flush 抛 `IllegalStateException`。
- close 聚合异常为首个异常 + `addSuppressed`,保持现有行为。

## OpenMinecraftWorld 收缩

world 级只剩一把小 `Mutex`,范围:**`closed` 标志与 `checkValid`(含目录锁有效性)、
`regionStores: Map<RegionStoreKey, WorldRegionStore>` 的 get-or-create、全部元数据文件操作**。
元数据都是单文件原子替换且罕见,串行无损失;region chunk 路径只在查找 store 时短暂经过它, 之后完全在 `WorldRegionStore`
的并发模型内运行。

`flush()`/`close()`:置 `closed`(close 时)→ 快照 regionStores → 释放 world 锁 → 逐 store 执行其屏障 → close 时最后释放
`directoryLock`。不在 world 锁内挂起等待 store 屏障。

## dispatcher 配置

`WorldRegionStoreConfiguration` 新增两个可空参数,经 `MinecraftWorldAccessConfiguration` 透传:

```kotlin
data class WorldRegionStoreConfiguration(
    // ...现有参数...
    /** 运行 NBT 编码/解码与压缩(CPU 密集);null 表示在调用方上下文执行,不跳转。 */
    val compressionDispatcher: CoroutineDispatcher? = null,
    /** 运行 region 文件阻塞 IO(含打开与 header 读);null 表示在调用方上下文执行,不跳转。 */
    val ioDispatcher: CoroutineDispatcher? = null,
)
```

默认 `null` 的理由:common 代码没有统一 `Dispatchers.IO`,库不替用户决定执行位置;null 保持现状 语义
(阻塞调用跑在调用方线程),单线程调用方行为完全不变。用户按平台自行提供池:JVM 传
`Dispatchers.IO` 或 `limitedParallelism` 派生池,Native 传自建 dispatcher,JS 天然单线程。

```kotlin
val access = MinecraftWorldAccess.open(
    root,
    MinecraftWorldAccessConfiguration(
        regionStoreConfiguration = WorldRegionStoreConfiguration(
            compressionDispatcher = compressionPool,
            ioDispatcher = ioPool,
            maximumOpenRegions = 1024,
            syncWrites = false, // 批量保存时最后统一 flush
        ),
    ),
)
```

`RegionFileStore.open` 直连入口不接收 dispatcher:直连用户自己选择调用上下文,KDoc 说明其阻塞性质。
`withContext(null)` 不合法,需要一个私有助手:

```kotlin
private suspend fun <R> dispatchOn(dispatcher: CoroutineDispatcher?, block: suspend () -> R): R =
    if (dispatcher == null) block() else withContext(dispatcher) { block() }
```

## 对用户的语义契约 (写入 KDoc 与 README)

- 高层入口可安全并发调用;同一文件的操作 (含读)完全串行,不同文件并行;
- 跨文件无全局顺序;单次写入的文件内原子性与崩溃一致性协议不变;
- `maximumOpenRegions` 是软上限,并发批量保存建议调大;
- `flush`/`close` 是 barrier,会等待在途操作;
- 同一目录创建多个 `WorldRegionStore` 实例 (或与 `RegionFileStore.open` 直连混用)仍属调用方责任。

## 涉及文件

- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/RegionFileStore.kt`(字节级化)
- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/WorldRegionStore.kt`(并发核心重写)
- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/OpenMinecraftWorld.kt`
- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/MinecraftWorldAccess.kt`(配置透传与 KDoc)
- `world-io/README.md`(并发章节、Single region-file access 示例改为字节级 + 手动组合编解码)
- 测试:`world-io/src/commonTest/`(新增并发测试),官方互操作场景保持通过

## 测试计划

1. 多协程并发写 N 个不同 region 文件:全部落盘、header 合法、可重新读取;
2. 多协程并发写同一文件:串行化、无损坏、每个位置内容为某次完整写入的结果;
3. 并发首读同一文件:只打开一次 (fileMutex 单飞,用计数文件系统探针断言 open 次数);
4. 并发写入量超过 `maximumOpenRegions`:无 use-after-close,空闲后容量回落;
5. 全部条目在用时的写入:临时超限不抛错、不阻塞;
6. 在途写入未完成时 `close()` / `flush()`:先完成/先 flush 再关闭,`flush` 期间的新写入不损坏; 并发 `close()` + 逐出风暴:
   无双重关闭、无 use-after-close (验证不变量 1);
7. 既有契约回归:非法 payload 在创建 region 文件之前被拒绝;
8. 一个文件批量写入期间读取另一个文件:不被阻塞 (活性断言);
9. dispatcher 生效性:encode/decode 与 IO 分别运行在指定 dispatcher (测试用记录线程的 dispatcher 断言);
10. 官方互操作 generate/rewrite/reload 场景保持绿色;
11. 可选基准:(a) 现状串行、 (b) 并行编码+串行写、 (c) per-file 并行编码+并行写,三者对比。

## 实施步骤

1. `RegionFileStore` 字节级化:移除 NBT 方法与 `chunkNbtFormat` 参数,更新直连示例;
2. `WorldRegionStore` 重写:`RegionEntry` + `bookkeeping` + `fileMutex` + pinned LRU + 屏障 (暂不加 dispatcher),编解码上移;
3. `OpenMinecraftWorld` 锁收缩与 `flush`/`close` 编排;
4. 引入两个 dispatcher 配置,落实读/写路径分段;
5. KDoc、README、并发测试与 (可选)基准。

## 风险与备注

- 条目锁跨阻塞 IO 持有 (与现状一致);per-file 粒度下竞争罕见。
- 读写共用文件锁使同文件读吞吐受写影响,换取内存快照一致性;不同文件互不影响。
- `syncWrites = true`(默认)下每 chunk 一次 durable flush,fsync 才是批量保存的主要串行点; 批量保存推荐
  `syncWrites = false` + 末尾 `flush()`。
- 并发度由调用方驱动;单线程顺序调用方行为与现状完全一致。
