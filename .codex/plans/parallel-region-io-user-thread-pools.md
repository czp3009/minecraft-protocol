# 高层并行存档 IO:active entry、共享读/排它写、调用方并发

## 完成状态

已完成。实现、文档和确定性竞态覆盖均已落地;最终验证结果:

- `:world-io:jvmTest`:156 tests,0 failures,含官方服务端 generate/rewrite/reload 互操作;
- `:world-io:jsNodeTest`:118 tests,0 failures;
- `:world-io:linuxX64Test`:118 tests,0 failures;
- `git diff --check HEAD`:通过。

## 背景与结论

改造前是两级粗锁:`OpenMinecraftWorld` 一把 world 级 `Mutex` 包住一切,`WorldRegionStore` 一把目录级
`Mutex` 包住该目录全部读写。多协程调用高层入口时一切串行,压缩/编码也发生在锁内。

仓库选中版本的官方 server JAR 参考结论:

- 单个 region 目录内由 `IOWorker` 的单任务队列彻底串行 (读优先、同 chunk 写合并);
- 跨 storage/跨维度通过多个 `IOWorker` 共享 `Util.IO_POOL` 并行;
- `RegionFile` 读写方法自带 `synchronized` 作为文件级兜底。

本计划不照搬官方内部线程池,而是把执行位置和并发度交给调用方:库只提供正确的 admission、pinning、 per-file serialization 和
close barrier,不创建线程,不接收 dispatcher,也不限制打开数量。

## 目标

1. 可变高层入口 (`MinecraftWorldAccess` / `WorldRegionStore`)支持并发调用:
   **同一逻辑文件组允许共享读,写入使用排它访问,不同逻辑文件组之间并行**。
2. 读者只在极短状态临界区登记,实际读取期间不持互斥锁;写者等待既有读者退出并阻止后来读者插队。NBT 编解码和压缩发生在
   region 文件共享读/排它写区之外,排它区只覆盖打开与容器提交。
3. 库不创建、不拥有线程或线程池,不提供 dispatcher 参数;blocking IO 和 CPU 密集工作运行在调用方 context。
4. 不设置 `maximumOpenRegions`。打开句柄数由当前 in-flight 操作决定,并发度完全由调用方决定。
5. 不保留 idle entry、idle mutex 或 idle region-store 缓存。操作结束后程序内不继续累积已经不需要的内容。
6. region 与 metadata 都使用 pinned logical-file entry;close 等待所有已 admission 的读者/写者。
7. `syncWrites = false` 时,同一 region entry 的最后一个 user 释放时执行一次最终 close/flush。
8. `LiveMinecraftWorldReader` 是独立的旁路观察者:不取得 `session.lock`,不创建 logical-file coordinator 或 per-file
   registry,对 metadata、MCA、MCC 的每次读取都独立进行,不延迟官方服务端写入。

## 非目标

- 不改变 `RegionFileStore`(单文件直连入口)的无锁契约:直接使用底层类或绕过高层协调机制的并发安全由调用方负责。
- 不提供跨文件事务或全局写入顺序;Anvil 各文件独立,单文件崩溃一致性协议保持不变。
- 不改 `world-format`:`RegionChunkNbtFormat.encode/decode` 与 `CompressionCodecs` 已是无状态纯函数。
- 不为 advancements/statistics 引入 temporary + rename。level.dat/playerdata 继续官方 temporary + backup; saved data 继续官方
  synced direct write;advancements/statistics 继续官方直接 truncate/write 最终 JSON 路径。
- 不提供流式压缩直接写入 `.mca` 的低内存路径。高层值模型仍是完整 compressed `RegionChunk`;这是为了在提交前得到 compressed
  length、sector 数和 internal/external 选择。

## 目标分层

```text
world-format      无状态纯函数:Anvil 容器字节格式 + 压缩 + chunk NBT 编解码(不动)
RegionFileStore   单个 .mca(+.mcc)的 Anvil 容器,字节级 API,无锁,无 NBT 依赖
WorldRegionStore  一个 region 目录的编排:active entry + logical-file shared/exclusive access + 编解码区外化 + close 屏障
OpenMinecraftWorld session.lock 生命周期 + active region-store registry + metadata logical-file shared/exclusive access
LiveMinecraftWorldReader 无可变状态、无 close 的独立 per-call 旁路读取
```

关键调整:**NBT 编解码从 `RegionFileStore` 上移到 `WorldRegionStore`**。
`RegionFileStore` 删除 `chunkNbtFormat` 构造参数与 `readChunkNbt`/`writeChunkNbt`,只保留字节级
`read`/`readAll`/`exists`/`write`/`clear`/`flush`/`close`。直连单文件的用户自行组合公开的
`RegionChunkNbtFormat`;world-io README 的 Single region-file access 示例同步更新。

## 执行位置契约

删除 `compressionDispatcher`、`ioDispatcher` 和 `dispatchOn` 设计。所有 production API 遵守:

- suspend 只用于等待内部 admission/coordination lock;
- 无任何挂起点的 live reader 公开普通同步函数,由调用方放入自己的 dispatcher/coroutine;
- blocking filesystem IO 与 NBT 编解码运行在调用方 coroutine context;
- 库不选择线程、不创建 executor、不隐藏调度;
- 调用方需要调度时在外部 `withContext(...)`,或先用公开 bytes/format API 分段组合。

KDoc 和 README 必须明确这些函数不是自动 main-safe。

## WorldRegionStore active entry

### RegionEntry 与职责不同的锁

```kotlin
private class RegionEntry {
    var store: RegionFileStore? = null
    val openMutex = Mutex()
    val fileAccess = LogicalFileAccess()
    var users = 0
    var closing = false
}
```

- `bookkeeping: Mutex` 只保护 entry map、每条 `users`、`closing`、store 的 open/sealed 状态和 closing 计数。 **临界区内不做
  IO、不等待 file lock、不执行 close**。
- `fileAccess` 使用数据库式共享读/排它写语义。读者仅登记 active-reader 计数,不持 `Mutex` 执行文件 IO;写者等待计数归零后
  取得排它权,写者等待期间的新读者不插队。这样读不会看到半提交的 header/payload/sidecar 状态,读者之间也不会互相阻塞。
- `openMutex` 仅单飞首次打开/不存在探测,不包住已经打开后的读取。
- `users` 在进入 logical-file access 之前递增,包含 encode/decode、active reader、active writer 和等待 access 的调用。
- entry 不做 LRU、不做 idle cache。最后一个 user 释放时进入 closing,并完成 `RegionFileStore.close()`。
- 一个 region logical-file group 覆盖 `r.x.z.mca` 及其所有 `c.*.*.mcc` sidecar;不能按最终路径分别协调,因为 MCA header 决定
  sidecar 的可达性。

### Admission 与 close 的边界

`users++/sealed` 在同一个 bookkeeping 临界区内线性化:

- 操作先于 close admission:操作已 `users++`,close 必须等待它完成,即使它还在 encode、等待 file lock 或读取文件;
- close 先于操作 admission:close 设置 sealed 后,新操作抛 `IllegalStateException`,即使物理文件仍可读。

NBT 操作也在 admission 之后才编码/解码:

- 写路径先 pin region entry,再在 **不持排它权** 的情况下 encode + validate,最后取得排它权打开/提交;
- 读路径取得共享读 admission 后读出不可变 compressed `RegionChunk`,立即退出共享读区再 decode,但 entry/users 保持到
  decode 完成。

这样 close barrier 覆盖完整高层操作,而不是只覆盖最后一段文件 IO。

### last-release close 与重新打开

最后一个 user 不能先移除 entry、在锁外 close,同时允许新调用立刻打开第二个 handle。必须把 closing entry
作为同一文件的排他过渡态:

1. release 在 bookkeeping 内确认 `users == 0`,置 `closing = true`,并保留一个可等待的完成信号;
2. 新 acquire 看到 closing entry 后等待完成信号,然后重新查找/创建新 entry;
3. last release 在 `openMutex` 或等价的 entry 过渡锁内取得唯一 store 并执行 `RegionFileStore.close()`;
4. close 完成后才从 map 移除 entry 并唤醒等待者。

因此旧 handle 的 final resize/flush/close 与新 handle 的 open/read header 不会重叠。该清理一旦把 entry 标记为 closing
就不能被取消中断;cleanup 结束后必须恢复状态或完成移除,不能留下永久 sealed entry。

close/flush barrier 需要同时等待:

- 已 admission 且尚未返回的操作 (`users > 0`);
- 已开始 last-release cleanup 但尚未完成的 entry (`closing > 0`)。

### 读/写路径

raw write:

1. admission/pin region entry;
2. `files.requireWritable()`;
3. `validatedCompressedPayload`,保持非法 payload 不创建文件的既有契约;
4. 取得 logical-file 排它权,单飞打开 (如需) 并执行 `store.write(position, chunk)`;
5. release;若这是最后一个 user,执行一次 entry close。

NBT write:

1. admission/pin region entry;
2. `files.requireWritable()`;
3. 不持 logical-file 排它权,执行 `chunkNbtFormat.encode(document, compression)`;
4. validate compressed payload;
5. 取得 logical-file 排它权,打开 (如需) 并提交 bytes;
6. release,last user 触发最终 close。

NBT read:

1. admission/pin region entry;
2. 在 shared-read admission 内读出 `RegionChunk?`;
3. 立即退出 shared-read admission;
4. 锁外执行 `chunkNbtFormat.decode(chunk)`;
5. release,last user 触发最终 close。

`readRegion`/`readChunk`/`doesChunkExist` 同样 admission、进入 shared-read、执行 IO、退出 shared-read、release。多个读者可以同时
读取一个已经打开的 Okio `FileHandle`;读者也会触发 last-release close,下一次读取重新打开。

## `syncWrites` 与最终 close/flush

- `syncWrites = true`(默认):保持现有每写 durable flush 行为。
- `syncWrites = false`:payload/header 写入后不做 per-write durable flush。同一 region entry 的最后一个 user release 时执行
  `RegionFileStore.close()`,由它完成 sector alignment、durable flush 和 handle close。

涌泉式批量保存的预期形态是:

```text
调用方并发发起 N 个保存
NBT/压缩在 file lock 外并发执行
不同 region 文件并行提交
同一 region logical-file group 的写入按排它权串行提交
每个 region 的最后一个 in-flight 操作释放时只 close/flush 一次
wave 结束后没有 idle handle 或 idle entry
```

如果调用方完全串行地逐个 `await writeChunk(...)`,每个操作都是唯一 user,仍会在每次写后自动 close/flush; 批量收益需要同一
entry 上有并发/排队操作。write 正常返回后,同一高层协调实例的后续读取即可见新逻辑结果; flush/close
提供的是崩溃持久性、格式收尾和资源释放,不是普通读取可见性的前提。

`flush()` 只 pin 并处理当时仍 active 的 entry:

- 在 bookkeeping 内 snapshot 并 `users++`,避免与 last-release close 竞争;
- 逐 entry 取得排它权后 flush,完成一个 unpin 一个;
- 已经进入 closing 的 entry 等其 cleanup 完成,不需要再次 flush;
- flush 不 seal,不阻塞新写入;之后才 admission 的写入不在本次 flush 范围内; 由于新写入自身会 last-release close,这不是正确性问题。

所有写都已返回后再调用 `flush()` 通常是 no-op,因为 idle entry 已经完成最终 close/flush。

## `OpenMinecraftWorld` 与 metadata locking

### 极短 bookkeeping,不再有 world IO 锁

删除包住所有 IO 的 world `Mutex`。保留一个只保护共享内存状态的 bookkeeping/state lock:

- `closed`/closing 状态与 `checkValid`;
- active region-store registry;
- active metadata entry registry;
- users/closing 计数和 map removal。

临界区内无 IO、无 NBT 编解码、无 file lock 等待、无 store close。region 路径只在查找/pin store 时经过它; metadata 路径只在查找/pin
logical entry 时经过它。它是 registry 一致性保护,不是旧的 world IO lock。

### Region-store registry 也是 active 的

`regionStores` 不再永久 get-or-put。按 `(storage, dimension)` 建立 active entry:

```text
operation admission -> store entry users++
-> 执行 WorldRegionStore 操作
-> release;最后一个 user 进入 closing 并调用 store.close()
-> close 完成后移除 entry
```

这避免访问大量自定义 dimension 后累积 store 对象。同一 key 的新操作在旧 store closing 时等待完成信号, 然后创建新
store;不能并发使用已 close 的 store 对象。独立显式创建的 public `WorldRegionStore` 仍由调用方持有; 其内部 region entry 仍按
last-release 自动 close。

### Metadata logical-file-group entry

Metadata 不再共用 world mutex。logical key 为:

```text
LevelData
PlayerData(playerUuid)
SavedData(dimension, canonical identifier)
Statistics(playerUuid)
Advancements(playerUuid)
```

- saved-data identifier 必须 canonicalize,例如 `foo` 与 `minecraft:foo` 是同一 key;
- `LevelData` group 覆盖 primary、backup 和 temporary;
- `PlayerData` group 覆盖 primary、`.dat_old`、temporary 和 corrupt-copy fallback;
- statistics/advancements 分别按 UUID 独立加锁;
- 同一 logical group 共享读/排它写,不同 group 并行;
- `level.dat` 与 player data 的 mutable recovery read 可能执行 promotion/corrupt-copy,按真实副作用进入排它路径;纯读取的
  saved data、statistics 和 advancements 允许共享读;
- read/write 都 admission/pin;`users == 0` 即移除 metadata entry;
- close sealed 后等待所有 metadata users/closing cleanup,最后才释放 directory lock。

写入策略本身不变:level/player temporary + backup,saved data synced direct GZIP,advancements/statistics 官方式直接写最终
JSON。

## `LiveMinecraftWorldReader` 旁路语义

`LiveMinecraftWorldReader` 不复用 `OpenMinecraftWorld` 或可变 `WorldRegionStore` 的 logical-file registry。它是给外部程序
观察正在运行的官方服务端存档用的简单入口:

- 不取得或探测 `session.lock`,也不创建任何操作系统文件锁;
- level/player/saved-data/statistics/advancements/MCA/MCC 的读取都不进入程序内 per-file coordinator;
- 同一文件的多个读取独立打开并并发推进,region handle 在单次调用结束时关闭,不跨调用保留 entry 或 handle;
- reader 不保留可变生命周期或跨调用资源,不需要 `close()`,所有调用之间没有共享 admission lock;
- 系统文件句柄允许官方服务端并发 write/delete/replace,读取不得修复、复制、提升或改写文件;
- stale/torn 数据以及由此产生的 IO、格式、NBT、解压失败属于调用方可重试结果。

## close 语义与并行读者

`close()` 的 admission 边界由 bookkeeping lock 线性化:

1. close 先取得状态锁并 seal;
2. 已通过 `users++` 的读者/写者继续执行,close 等待它们返回;
3. seal 后的新读/写抛 `IllegalStateException`;
4. close 同时等待 active users 与 closing cleanup;
5. 所有 region store、metadata entry 和 last-release cleanup 完成后才释放 session/directory lock。

特别要求:

- 正在等待 shared/exclusive access 的读者/写者也算 in-flight;close 不能只等待已经进入文件 IO 的操作;
- encode/decode 虽在 logical-file access 外,但对应 entry 仍被 pin,close 必须等待;
- 读者可能是最后一个 user,此时也执行 handle close;后续读者等待 closing 完成后重新打开;
- close 期间另一个文件的慢 IO 不阻塞本文件完成,但全局 close 要等所有 admitted 操作;
- 直连 `RegionFileStore` 或另一个不共享 registry 的实例不属于该 barrier 保护范围。

并发 `close()` 调用必须合并等待同一个完成结果,不能让第二个 close 在第一个 close 尚未释放 handle/session lock
时提前返回成功。close 聚合异常为首个异常 + `addSuppressed`,保持现有行为。close 一旦 seal,清理路径不能因调用方 cancellation
而留下“已拒绝新操作但永远不完成清理”的状态。

## 对用户的语义契约 (写入 KDoc 与 README)

- 可变高层入口可安全并发调用;同一逻辑文件组共享读/排它写,不同逻辑文件组并行;
- live 高层入口对所有文件执行无锁旁路读取,不参与可变入口的 logical-file coordination;
- 并发度由调用方 launch 数/外部 dispatcher 决定;库没有线程池、dispatcher 或 `maximumOpenRegions`;
- blocking IO 与编解码运行在调用方 context,API 不自动 main-safe;
- 跨文件无全局顺序;单次写入的文件内原子性与崩溃一致性协议不变;
- 没有 idle region/metadata/store 缓存;last release 会关闭对应资源;
- `syncWrites = false` 时,每个 region 的最后 in-flight 操作执行一次最终 close/flush;
- `flush()`/`close()` 没有内部超时,可能等待慢磁盘、mock IO 和已 admission 操作;
- write 返回后,同一协调实例的后续读取可见新逻辑结果;flush/close 主要提供崩溃持久性和资源收尾;
- 同一目录创建多个 `WorldRegionStore` 实例 (或与 `RegionFileStore.open` 直连混用)仍属调用方责任。

## 涉及文件

- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/RegionFileStore.kt`(字节级化)
- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/WorldRegionStore.kt`(active entry 重写)
- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/OpenMinecraftWorld.kt`
- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/LiveMinecraftWorldReader.kt`
- `world-io/src/commonMain/kotlin/com/hiczp/minecraft/world/io/MinecraftWorldAccess.kt`(KDoc 与配置清理)
- `world-io/AGENTS.md` 与 `world-io/README.md`(可变协调、live 旁路、执行位置、last-release close)
- 测试:`world-io/src/commonTest/`,必要的 JVM 并发 oracle 与既有官方互操作场景

## 测试计划

1. 两个不同 region 文件分别停在真实提交点,确认同时到达后再放行:全部落盘、header 合法、可重新读取;
2. 多协程并发写同一文件:排它串行、无损坏、每个位置内容为某次完整写入的结果;
3. 并发首读同一文件:只打开一次 (`openMutex` 单飞,用计数 filesystem/handle 断言 open 次数),打开后读者并行;
4. `R→R` 共享、`R→W` 写等待读者、`W→R` 读等待后一起恢复、`W→W` 排它,且等待写者先于后来读者;
5. 所有操作完成后:active map 为空、FakeFileSystem 没有打开 handle,反复访问大量 region 不累积 entry;
6. last-release close 与立即重新打开:旧 close 和新 open 不重叠,无 double-close/use-after-close;
7. `syncWrites = false`:同一 region 的多个排队写只在最后一个 release 时 close/flush 一次;
8. 读者是最后一个 user 时也触发 close;后续读者等待 closing 完成后重新读取新状态;
9. close 边界:已 admission 但还在等待 shared/exclusive access 的调用会被 close 等待;seal 后新调用失败;
10. close 边界:encode/decode 仍在进行时 close 等待;close 完成前不释放 session lock;
11. 并发 `close()` 合并等待同一完成结果,不出现第二个 close 早退或 session lock 提前释放;
12. metadata 同一 logical group 共享读/排它写、不同 group 并行;`foo` 与 `minecraft:foo` 落同一 saved-data entry;
13. metadata close barrier 等待 read/write;seal 后新操作失败;
14. advancements/statistics 保持直接最终路径写入,不产生 temporary/rename;
15. 既有契约回归:非法 payload 在创建 region 文件之前被拒绝;
16. NBT encode/decode 不占用 shared/exclusive 文件访问,可与其他操作并行;
17. MCA header 与对应 MCC sidecar 落在同一个 region logical-file group,sidecar 慢读会阻止 header 重写;
18. 官方互操作 generate/rewrite/reload 场景保持绿色;
19. live reader 对 level/player/saved-data/statistics/advancements/MCA/MCC 的同文件读取均可同时到达 IO gate;
20. live 慢读不阻塞服务端风格的直接写入/替换,反复 missing read 不保留句柄;
21. 可选基准:(a) 原全局串行、 (b) 共享读+同文件排它写、 (c) 多文件并行提交,三者对比。

竞态测试使用 gate-controlled hanging `ForwardingFileSystem`/fake `FileHandle`,不依赖 sleep、真实磁盘延迟或
概率调度。“永远不返回”必须可由测试释放,并在 finally 打开 gate,避免遗留永久阻塞 worker。需要真实并行 blocking oracle 的用例放在
JVM 测试 dispatcher 上执行,仍以外层 `runTest` 组织与断言。

## 已完成覆盖矩阵

| 边界                        | 确定性覆盖                                                                                           |
|-----------------------------|------------------------------------------------------------------------------------------------------|
| `R→R`                       | 两个 reader 都到达显式 gate 后统一放行;common coordinator 与真实 region handle 各覆盖一次            |
| `R→W` / `R,R→W`             | writer 在一个或全部 reader 释放前不能进入;另一个 region 文件仍可完成写入                             |
| `W→R,R`                     | 两个 reader 都在 writer commit 后同时到达 read gate,且 writer 的排它权已经释放                       |
| `W→W` / 多 writer           | 同一 region 的真实 handle 最大并发写为 1,每个 chunk 保持一次完整提交结果                             |
| `R→W→R` / writer preference | 等待 writer 阻止后来 reader;多个已登记 writer 全部先于后来 reader                                    |
| cancellation / failure      | 等待和 active reader/writer 分别取消,read/write block 分别抛错,均释放 admission 与 entry pin         |
| close admission             | close 等待 active、waiting、encode、decode 与 last-release cleanup;seal 后拒绝新调用;并发 close 合并 |
| final release / reopen      | active map 回到空、handle 全关;旧 close 完成前新 open 不发生;大量文件访问不累积 entry                |
| `syncWrites`                | false 在最后 reader/writer 引用释放时一次 final flush/close;true 每次 commit flush 且仍延迟 close    |
| logical groups              | MCA+全部 MCC、level、player、canonical saved data、statistics、advancements 分别按真实路径组覆盖     |
| live bypass                 | 全部 metadata 类型及 MCA/MCC 同文件 reader 同时到达 IO;慢读不阻塞直接写;missing read 不留 handle     |
| official peer               | repository-selected 官方服务端完成 generate/rewrite/reload,并验证 `session.lock` 互斥与 live 旁路    |

## 实施步骤

1. `RegionFileStore` 字节级化:移除 NBT 方法与 `chunkNbtFormat` 参数,更新直连示例;
2. 重写 `WorldRegionStore`:active entry、shared-read/exclusive-write、open single-flight、last-release closing、bookkeeping
   barrier,编解码上移;
3. 重写 `OpenMinecraftWorld`:active region-store registry、metadata logical-file entry、统一 close barrier;
4. 将 `LiveMinecraftWorldReader` 与可变协调完全拆开,实现无锁、无缓存的 per-call 旁路读取;
5. 删除 `maximumOpenRegions` 与 dispatcher 相关计划/API,KDoc/README 说明调用方执行位置契约;
6. 增加 mock 并发测试、close/reader 边界测试、metadata 测试与 live 全文件类型旁路测试;
7. 运行 `:world-io:jvmTest`,再按变更范围运行 Node/desktop Native 标准任务与官方互操作门禁。

## 风险与备注

- 读者不跨阻塞 IO 持有 Mutex,但 active-reader 计数会阻止写者;忘记退出 shared admission 会永久阻塞写入,必须用不可取消的
  finally 清理并测试取消路径。
- 写者在文件提交期间持有 logical-file 排它权;同组读写互斥是 MCA header/payload/sidecar 一致性的代价,不同文件互不影响。
- pinned-only 模式会让顺序逐个调用时反复 open/close。批量性能来自并发保存或将来显式 user-managed lease, 不来自隐藏 idle
  cache。
- `flush`/`close` 可能无限期等待底层 IO;库不提供内部 timeout。
- close/cleanup 状态机比 LRU 更复杂,必须以 admission、users、closing 和 close barrier 的不变量测试驱动。
