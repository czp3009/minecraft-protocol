# world-io 使用 Okio 复刻官方存档 I/O

## 目标与状态

本计划记录 `world-io` 从 `kotlinx.io.files` 迁移到 Okio，并按 Minecraft Java Edition 官方服务端的业务顺序读写存档文件的实施基线。计划基于
2026-08-06 的当前工作树、仓库选定版本 Minecraft `26.2`，以及官方运行时 JAR
`build/protocol-reference/26.2/mojang-server/runtime/server.jar`。实施时以该 JAR 为最高优先级证据；不要把本计划中出现的数字重新手抄到多个模块。

目标不是把 Mojang 的 Java 类逐个翻译成 Kotlin，而是复刻会影响磁盘内容、恢复结果和跨进程互操作的行为：

- `world-io` 的路径、文件系统和随机访问句柄统一改用 Okio；公开 API 尚不稳定，不保留 `kotlinx.io.files.Path`/`FileSystem`
  兼容层。
- `.mca` 更新必须改为官方的扇区级原位写入，不能再生成完整临时 `.mca` 后整体替换。
- `level.dat`、玩家 NBT、维度 `data/*.dat`、玩家 JSON 分别采用官方各自的提交策略，不能共用一个无差别的“原子整文件写”策略。
- 保留 `world-format` 的文件系统无关边界；NBT、压缩和 Anvil 物理格式仍使用 `kotlinx.io.Source`/`Sink`，只有真实文件访问由
  Okio 负责。
- 复刻官方的提交顺序和恢复边界，但不复刻 JVM 临时文件随机名称、日志文本、线程池实现或 JFR 事件。
- 官方上层常把存储异常记日志后继续运行；作为库，`world-io` 的底层方法仍把异常返回/抛给调用方，由服务器编排层决定是否降级。异常传播方式可以不同，但失败时磁盘状态必须落在官方对应边界。
- 不增加日志式事务、MCA 压缩整理、就地覆盖旧扇区等官方没有的机制。失败后允许出现与官方相同类别的孤立旧扇区或未引用临时文件；本库可以尽力删除自己尚未提交的临时文件，因为这不改变任何已提交存档状态。

## 已审计证据

IDEA 能直接用 Fernflower 读取 JAR 内的 `.class`，无需把 class 解压到 `temp/`。本次逐方法核对了以下官方类；实施或版本升级时应重新核对同一职责，而不是依赖类名永远不变：

- `net.minecraft.nbt.NbtIo`
- `net.minecraft.util.Util`、`DirectoryLock`
- `net.minecraft.world.level.storage.LevelResource`
- `LevelStorageSource`、`LevelStorageSource$LevelStorageAccess`
- `PlayerDataStorage`、`SavedDataStorage`
- `PlayerAdvancements`、`ServerStatsCounter`、`PlayerList`
- `RegionFile`、`RegionBitmap`、`RegionFileVersion`、`RegionFileStorage`
- `IOWorker`、`SimpleRegionStorage`、`SectionStorage`、`EntityStorage`
- `DimensionType`、`PoiManager`、`ChunkMap`、`ServerChunkCache`、`ServerLevel`、`MinecraftServer`
- `DedicatedServer`、`DedicatedServerProperties`

Okio 结论来自 [lysine-dev/okio](https://github.com/lysine-dev/okio) 当前源码和稳定版文档。实施时固定稳定版
`com.squareup.okio:okio:3.18.1`，不依赖 `3.19.0-SNAPSHOT`。已确认：

| 能力                                              | Okio 状态                                                                                                                      | 本计划中的处理                                                                                      |
|---------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| JVM、Android 和当前全部 Native 目标的系统文件系统 | `FileSystem.SYSTEM` 可用                                                                                                       | 与 `world-io` 当前目标集合匹配；不因此新增 JS/Wasm 文件系统目标                                     |
| 随机读写                                          | `openReadOnly`、`openReadWrite` 和 `FileHandle.read/write/size/resize` 均在公共代码可用                                        | 足以实现 MCA 位置读写和文件扩展                                                                     |
| 同目录替换                                        | `FileSystem.atomicMove` 可用，当前 JVM/Native 系统实现支持替换目标                                                             | 用作官方同目录 rename/move 的跨平台实现；移动前仍按官方顺序显式删除或备份目标                       |
| 持久化刷新                                        | JVM 是 `FileDescriptor.sync`/`FileChannel.force(true)`，Windows Native 是 `FlushFileBuffers`；Unix Native 3.18.1 只有 `fflush` | Unix Native 不能据此宣称与官方 `SYNC`/`DSYNC` 等价，增加一个仅按路径执行 `fsync` 的最小内部平台原语 |
| 文件锁                                            | Okio 未提供                                                                                                                    | 为 `session.lock` 增加最小内部平台锁原语；所有普通数据 I/O 仍走 Okio                                |
| 与 `kotlinx.io` 串流互操作                        | 没有现成桥接                                                                                                                   | 增加两个内部、定长缓冲的 `Source`/`Sink` 适配器，不把完整独立文件复制进内存                         |

没有找到能覆盖本仓库 JVM、Android、Windows、Linux 和 Apple Native 目标且语义等同官方独占文件锁的维护中 KMP API。平台原语只允许覆盖
Okio 确实没有的锁与 Unix durable-sync 两个窄能力，并按标准共享 source set 组织；不得借此另造一套文件系统。

## 模块边界

### `world-format`

继续拥有所有不依赖路径或打开文件的 Anvil 规则：

- 4096 字节扇区、8192 字节头、location/timestamp 表的编码与校验；
- location 的 24 位扇区号和 8 位扇区数、坐标到 1024 个槽位的映射；
- 首个连续空闲区分配、释放和 header 两扇区预留；
- chunk record、外部 stub、压缩 ID/外部标志、外部阈值的构造与解析；
- GZIP、ZLIB、NONE、LZ4 的压缩分派及完整 chunk NBT 的编解码；
- 供纯公共测试和调用方使用的完整内存 `RegionFile` 表示。完整镜像编码可以保留，但不能再作为 `world-io` 的更新实现。

建议把当前 `RegionFileFormat` 中可复用的纯逻辑收敛为少量内部/公开值类型，例如 location、header 和 sector allocator；不要让
`world-format` 知道 Okio。

### `world-io`

拥有所有可观察的文件行为：

- Okio `Path`、`FileSystem`、`FileHandle`；
- 目录创建、文件类型检查、临时文件、备份、替换和恢复顺序；
- 打开的 region 句柄、LRU、互斥、刷新、补齐扇区和关闭；
- `.mcc` 生命周期；
- `level.dat`、玩家文件、维度 saved-data 和玩家 JSON 的不同写入策略；
- 可选的高层世界访问租约及 `session.lock`。

`nbt-serialization`、`world-format` 和 `compression` 继续使用 `kotlinx.io`。`world-io` 仅在内部桥接 Okio 流与 `kotlinx.io`
流。迁移完成后，`world-io` 不再引用 `kotlinx.io.files`，但仍可把 `kotlinx-io-core` 作为实现依赖；Okio 的 `Path`/`FileSystem`
出现在公开签名中，因此 Okio 是 `api` 依赖。

其他已发布运行时模块没有需要一并迁移的直接磁盘文件访问。`buildSrc`、KSP、Fixture Host 和测试基础设施的主机文件访问属于开发层，不纳入本次改动。

## Minecraft 26.2 存档布局

26.2 的正常维度根一律由资源标识符解析为 `dimensions/<namespace>/<path>`，包括主世界：

```text
<world>/
├── level.dat
├── level.dat_old
├── session.lock
├── players/
│   ├── data/<uuid>.dat
│   ├── data/<uuid>.dat_old
│   ├── advancements/<uuid>.json
│   └── stats/<uuid>.json
└── dimensions/<namespace>/<dimension-path>/
    ├── region/r.<rx>.<rz>.mca
    ├── entities/r.<rx>.<rz>.mca
    ├── poi/r.<rx>.<rz>.mca
    └── data/<identifier>.dat
```

因此 `MinecraftWorldPaths` 改用 `okio.Path` 后应继续保留当前 26.2 路径。`DIM-1`、`DIM1`、根级 `region`/`playerdata`
等旧路径可以作为显式 legacy 选择保留，但不能成为 26.2 默认写入目标。saved-data 标识符和自定义维度路径必须做词法归一化与根目录包含检查，不能通过
`..` 逃逸。

## 官方独立文件行为

### 共同 NBT 物理格式

官方 `NbtIo` 写的是 unnamed root：一个 tag type 字节、空 UTF root name、随后是完整 compound payload。路径版压缩和非压缩写入都用
`SYNC + WRITE + CREATE + TRUNCATE_EXISTING`；GZIP 是整文件压缩。由此得到两个重要结论：

- 独立 NBT 文件的保存总是重新编码整个根 compound，不存在随机修改 GZIP 文件内部某个 tag 的做法。
- `SYNC` 是写文件本身的持久化属性，不代表所有调用方都使用临时文件。临时文件、备份和替换是更高层按文件类型决定的。

本库继续让 NBT 层决定 unnamed-root 字节与压缩流，只在文件层复刻提交策略。DataFixer、注册表 codec 和具体游戏对象到 NBT
的转换不属于 `world-io`。

### `level.dat`

读取与转换：

- 正常加载顺序解压并解析整个 GZIP NBT，然后取根 compound 的 `Data` compound。
- 世界列表的轻量读取仍顺序扫描压缩流，但用 visitor 跳过 `Data.Player` 和 `Data.WorldGenSettings`，避免构造这两个大子树；它不是文件偏移随机读取。
- 修改世界名等操作读取完整根 compound，只修改 `Data` 内字段，再重写整个文件。

正常保存顺序：

1. 在世界根创建唯一临时文件。
2. 把根 `{Data: <完整世界数据>}` 以 GZIP NBT 写入临时文件并 durable-sync。
3. 若 `level.dat` 存在，最多尝试 10 次：删除旧 `level.dat_old`，把 `level.dat` 移到 `level.dat_old`，确认备份是普通文件。
4. 最多尝试 10 次删除残留目标并确认 `level.dat` 不存在。
5. 最多尝试 10 次把临时文件移到 `level.dat` 并确认它是普通文件。
6. 第 5 步失败时，把 `level.dat_old` 移回 `level.dat`；不引入额外 journal。

每组重试立即进行，不 sleep。临时文件必须和目标同目录；随机名称算法由本项目自行实现，并用 `mustCreate=true` 加碰撞重试，测试不匹配具体名称。

回退加载：先读 `level.dat`；遇到 NBT/IO 失败才读 `level.dat_old`。旧文件成功后，官方把现有主文件移到带时间戳的 corrupted
文件，再把 `level.dat_old` 移为 `level.dat`，此恢复路径不回滚。计划应保留“何时回退、何时提升旧文件、何时保留损坏副本”的行为，corrupted/临时文件的随机或时间字符串格式不要求和
JVM 相同。

### 玩家 NBT、统计与进度

玩家 `<uuid>.dat` 与 `level.dat` 相同，先写同目录 GZIP 临时文件，再用 `<uuid>.dat_old` 执行带备份替换。加载主文件失败或缺失时：

1. 若主文件仍是普通文件，复制为带 `_corrupted_...` 的副本，并保留属性；复制失败不阻止回退。
2. 尝试读取 `.dat_old`。
3. 不自动把 `.dat_old` 提升为主文件。

其中“复制并保留属性”是官方调用 `COPY_ATTRIBUTES` 的原始行为；Okio 的公共复制 API
不承诺复制时间、权限或扩展属性。本计划把损坏内容副本和不阻塞回退视为业务兼容要求，不为仅供诊断的副本再增加第三套平台元数据适配层；系统实现能保留的属性可以尽力保留，但测试不依赖它。

玩家保存的调用顺序是 NBT 数据、统计 JSON、进度 JSON。统计和进度都创建父目录后，直接用 UTF-8 writer 截断目标文件；没有临时文件、
`.dat_old`、durable-sync 或回滚。统计 JSON 根包含 `stats` 与 `DataVersion`；进度由其 codec 生成。`world-io` 只实现
UTF-8/JSON 文件策略，JSON 值的业务 codec 留给调用方并使用 `kotlinx.serialization.json`，不手写转义。

### 维度 `data/*.dat`

`SavedDataStorage` 对每个维度使用该维度根下的 `data` 目录。读取前看前两个字节的 GZIP magic `1f 8b`，所以同时兼容当前 GZIP
和旧的未压缩 NBT；随后读取 `DataVersion`（缺失默认 1343）并在上层做 DataFixer。当前写入的根是
`{data: <业务值>, DataVersion: <当前版本>}`。

保存时先在内存中冻结所有 dirty 数据并清 dirty 标记，再等待上一批写入，最后可并行写不同文件。每个文件直接
`SYNC + CREATE + TRUNCATE_EXISTING` 写最终路径，没有临时文件、备份或回滚。关闭会再调度一次保存并等待全部写入。第一版不复制
Mojang 的线程数分桶；用结构化协程或同步批次保证“上一批完成后再开始下一批、close 等待完成”即可，且不同目标文件可以并行。

### `session.lock`

官方创建世界访问对象时先创建根目录，打开 `session.lock` 为 `CREATE + WRITE`，从文件开头写 UTF-8 雪人 `☃`
（不截断已有尾部），force，然后尝试独占锁。锁对象在整个世界访问期间持有；每次高层操作先验证锁仍有效，关闭世界最后释放锁和
channel，但不删除 lock 文件。探测锁时，文件不存在表示未锁，访问被拒绝视为已锁。

Okio 没有能持有 OS 文件锁的 API。实施一个内部 `WorldDirectoryLock` 平台原语，在 JVM/Android 使用 `FileChannel.tryLock`
，Unix 使用等价的进程级 advisory lock，Windows 使用对应独占锁；它只暴露 acquire/isValid/close/isLocked。通用
`WorldRegionStore` 仍允许对注入的 `FileSystem` 独立测试，而要求官方式排他访问的高层 `MinecraftWorldAccess`
只在系统文件系统上获取锁，不能对 fake filesystem 假装已经跨进程锁定。

## 官方 MCA 行为

### 文件结构和打开

- 扇区 4096 字节；头固定占扇区 0、1，共 8192 字节。
- 前 4096 字节是 1024 个 big-endian location int，后 4096 字节是 1024 个 big-endian epoch-second timestamp int。
- slot 是 `localX + localZ * 32`。location 高 24 位是首扇区，低 8 位是扇区数；0 表示不存在。
- chunk record 是 `4-byte length + 1-byte compression/flags + compressed NBT`。length 包含 compression 字节和 payload，不包含
  length 自己。
- 压缩 ID：1 GZIP、2 ZLIB/deflate、3 none、4 LZ4；bit `0x80` 表示 payload 在 `.mcc`。127 的自定义压缩入口在 26.2 实际不接受任何实现。

打开 region 时创建缺失的目录和 `.mca`，即使调用最初只是读 chunk。内存先把头两扇区标记为已用，然后在 offset 0 最多读取 8192
字节；短头的剩余字节视为 0。对每个非零 location：

- 首扇区小于 2、扇区数为 0、或首扇区起点严格大于文件长度时，只在内存 header 中清零该项并报告损坏；不会立即修复磁盘 header。
- 合法项直接把范围标记为已用。官方不检查两个 header 项是否重叠，也不检查分配末端是否越过 EOF。
- 分配器从最低空闲位开始找第一个足够长的连续范围。

实现应复刻这些兼容性判断，不在打开时擅自“修复”、压缩或截断文件。结构错误可以通过现有异常/结果模型报告，但不得改变后续
allocator 看到的有效范围。

### 读取一个 chunk

1. 从内存 header 取得 location；0 直接返回不存在。
2. 从 `sector * 4096` 位置读取该 allocation 的扇区，不读取整个 `.mca`。
3. 至少需要 5 字节。length 为 0 表示 allocation 存在但流缺失。
4. 内部记录校验 `length - 1` 非负且不超过已读 allocation 的剩余内容，然后按压缩 ID 解压完整 unnamed-root NBT。
5. 外部记录总是优先读取同目录 `c.<chunkX>.<chunkZ>.mcc`；`.mcc` 必须是普通文件，内容只有 compressed payload，没有 record
   长度和压缩字节。即使 stub 声称同时带内部 payload，官方也忽略内部内容。

`doesChunkExist` 只位置读取 5 字节 header，不解压 NBT：内部记录检查压缩 ID、非零 length 及 `length - 1` 范围；外部记录只检查去掉
`0x80` 后的压缩 ID 和 `.mcc` 是普通文件，不校验 stub 声明的 length。NBT field visitor 可以减少对象构造，但仍从 chunk
压缩流顺序解压；它不是在压缩 payload 内按 tag 偏移随机跳转。

### 写入一个内部 chunk

官方从不在旧 allocation 上覆盖，即使新记录大小完全相同：

1. 完整序列化并压缩 chunk NBT 到内存 record，前 5 字节为 length 和选定压缩 ID。
2. 读取旧 location，但继续把旧扇区视为占用。
3. 按完整 record 大小计算扇区数，并从最低位置分配一段新的连续扇区。
4. 把 record 位置写到新扇区；未占满的扇区尾部不要求清零。
5. 若 `syncWrites=true`，确保 record 在进入 header 提交前已经 durable-sync，以对应官方 `DSYNC`。
6. 更新内存 location 和当前 epoch-second timestamp，把完整 8192 字节 header 写到 offset 0；同步模式下再次 durable-sync。
7. 删除该 chunk 可能残留的 `.mcc`。
8. 只有以上步骤成功后，才在内存 allocator 中释放旧扇区。磁盘上的旧 payload 不擦除，文件不缩小。

### 写入一个外部 chunk

若包含 5 字节 header 的 record 需要至少 256 个扇区，8 位 sector count 无法表示，官方自动使用外部文件；调用方不能手工选择
external：

1. 在 region 目录创建临时文件，只写 compressed payload（跳过 record 的前 5 字节），关闭临时文件。
2. 在旧 allocation 仍占用时分配一个新扇区。
3. 向新扇区写 5 字节 stub：length 为 1，compression ID 加 `0x80`。
4. 更新 timestamp/location 并写完整 8192 字节 header。
5. header 成功后，才把临时文件以替换目标的方式移动成 `c.<x>.<z>.mcc`。
6. 最后释放旧 allocation。

外部临时文件必须和 `.mcc` 同目录；名称算法自行实现。不要把 `.mcc` 先提交再写 MCA header，因为那会改变官方失败恢复边界。 即使
`syncWrites=true`，官方也只以普通 write/close 写这个 `.mcc` 临时文件；`DSYNC` 只属于 `.mca` handle，不能把它误记为 sidecar
自身的持久化保证。

### 删除、刷新和关闭

删除 chunk 时先把 location 清零并更新时间戳，写完整 header，然后删除 `.mcc`，最后释放旧扇区；不清零 payload，不截短 `.mca`。

每个 `region`/`entities`/`poi` store 各自维护最多 256 个打开的 region，命中后提升为 MRU，超过上限关闭 LRU。flush 对所有打开
handle 做 durable-sync。关闭每个 `.mca` 时先把文件补到下一个完整 4096 字节边界，再 force 并关闭；即使补齐失败也仍尝试 force
和 close，并聚合多个 close 异常。新建但从未写入的 region 可以保持 0 字节直到关闭，不预写空 8192 字节头。

官方 dedicated server 的 `sync-chunk-writes` 默认是 `true`，同时作用于 terrain、entities 和 POI region。Okio 没有 `DSYNC`
打开选项，因此同步模式必须在 MCA record/stub 与 header 边界显式 force；不能只在整个操作结束时 flush。JVM/Android 和 Windows
Native 可用 `FileHandle.flush()`，Unix Native 通过最小 `fsync(path)` 原语补足。非同步模式只在显式 flush、LRU eviction 和
close 时 force。和官方一样，不额外 fsync `.mcc` 临时文件或父目录，也不为 rename 建事务日志。

### 官方失败边界

计划中的 fault-injection 测试必须固定以下结果：

| 失败位置                          | 官方可见结果                                                                |
|-----------------------------------|-----------------------------------------------------------------------------|
| 新 record/stub 完成前             | 旧 header 仍指向旧 allocation；新空间未被提交                               |
| 新 record 完成、header 前         | 旧 chunk 仍可读；重开后未引用新扇区会重新变为空闲                           |
| header 已提交、内部 `.mcc` 删除前 | 新内部 chunk 可读，旧 `.mcc` 只是无害残留                                   |
| header 已提交、外部临时文件移动前 | header 已指向 external；最终 `.mcc` 可能缺失或仍是旧内容，官方不回滚 header |
| 删除 header 已提交、`.mcc` 删除前 | chunk 已不存在，残留 `.mcc` 不再被引用                                      |
| 任意成功更新后                    | 旧扇区只在内存中释放，字节保留且文件不收缩                                  |

不要用“写临时完整 MCA 后替换”掩盖这些结果，也不要添加与官方不同的回滚协议。

## 并发、保存和生命周期

官方 `IOWorker` 用每个存储目录一个顺序执行器：相同 chunk 的多个未落盘写入合并为最新值，读操作优先看到 pending
值，后台按插入顺序每次落盘一个；`synchronize(false)` 等待已有写入，`synchronize(true)` 再 flush 所有 region。POI、terrain 和
entities 是三个独立 worker，因此不同目录之间可以并行。

第一版不复制优先级执行器、线程池和 1024-entry old-chunk blender cache。采用更简单且不改变磁盘结果的契约：

- `WorldRegionStore` 的一次 `writeChunk` 在返回前完成该 chunk 的官方提交序列；每个 store 用结构化 `Mutex` 串行化
  header/cache 变更。
- 同步写天然提供 read-your-write，不需要额外 pending map；`flush` 等待当前互斥操作后 force 全部 handle，`close` 拒绝新操作并按顺序
  flush/close。
- 如果以后实测需要异步合并，再增加一个结构化 pending map；它必须复刻“同坐标取最新值、读先看 pending、close 等待”，不得先引入
  Mojang 的优先级框架。

官方整体保存顺序作为高层组合 API 的参考，而不是让 `world-io` 变成服务器：

1. 保存所有在线玩家：player NBT，然后 stats JSON、advancements JSON。
2. 对每个维度保存 saved-data、terrain/POI 和 entities；flush 保存会等待并 force，普通 autosave 只排入/执行必要写入。
3. 保存根 `level.dat`，执行 `.dat_old` 备份替换。
4. flush 保存等待 saved-data；停止时再次完成各维度保存，再依次关闭 terrain/POI、entities 和 saved-data。
5. 所有存储关闭后，最后释放世界 `session.lock`。

`world-io` 提供清楚的 `flush`/`close`/世界访问生命周期即可，不实现玩家管理、自动保存间隔、chunk ticket 或服务器停止循环。

## 目标 API 与代码迁移

具体命名可在实现时按现有风格调整，但职责必须保持以下最小集合：

1. 在版本目录加入 Okio 3.18.1 alias；`world-io` 以 `api` 依赖 Okio，把直接 `kotlinx-io-core` 降为只在确有内部桥接需要时的
   `implementation`。检查发布的 KMP metadata 和外部消费者编译。
2. 把 `MinecraftWorldPaths`、`NbtFileStore`、`WorldRegionStore` 及配置中的路径/文件系统类型一次性迁为 `okio.Path`/
   `FileSystem`；删除旧公开兼容重载。
3. 用两个固定大小 ByteArray 缓冲适配器桥接 Okio 与 `kotlinx.io`。适配器负责短读、EOF、flush、异常保留和关闭所有权；不要复制另一套
   Buffer API。
4. 在 `world-format` 提取纯 `RegionHeader`/location/sector allocator/record helper，并保留现有完整镜像 codec
   作为内存能力。所有阈值只在该所有者定义一次。
5. 在 `world-io` 增加内部 `OpenRegionFile`：持有 Okio handle、内存 header、allocator、路径和 mutex，实现
   open/read/exists/write/clear/flush/close 的上述顺序。
6. 把 `WorldRegionStore` 改为每个具体 `(dimension, storage directory)` 各自管理一个 256-entry region LRU，key 只含 region
   position，不能把所有维度和三类目录挤进一个全局 256-entry cache。`readChunk`/`writeChunk` 是主要变更
   API；删除或降级当前会暗示整文件替换的 `writeRegion`。需要 snapshot/import 时，逐 chunk 调用官方 mutation，不得直接替换完整
   MCA。
7. `writeChunkNbt` 自动使用配置的官方写压缩（默认 ZLIB；允许 NONE/LZ4），自动写当前时间戳，自动决定 `.mcc`。移除调用方传入
   timestamp 和 `external` 的参数；GZIP 保持可读，但不作为官方 `region-file-compression` 写选项。
8. 把当前通用 `writeAtomically` 拆成语义明确的内部操作：同目录唯一临时文件、direct truncate+sync、带旧文件备份替换、损坏主文件回退。
   `NbtFileStore` 只负责物理 NBT 流；由 `LevelDataStore`、`PlayerDataStore`、`SavedDataFileStore` 选择正确策略，避免布尔参数组合。
9. 为统计/进度提供直接 UTF-8 JSON 文件策略；只有实际 API 需要 JSON 元素时才增加 `kotlinx-serialization-json` 依赖，不在路径类里塞
   codec。
10. 增加系统文件系统专用 `MinecraftWorldAccess`/`WorldDirectoryLock`，高层写操作可要求有效租约；raw stores 继续可注入 Okio
    fake filesystem。
11. 更新根和模块 `AGENTS.md`/README：`world-io` 改为拥有 Okio paths/filesystem-backed stores；`world-format` 与其他流式模块仍使用
    `kotlinx.io`。不要把“全仓库换成 Okio”写成规则。

## 实施阶段

### 阶段 1：依赖、路径和桥接

- 加入并验证 Okio 稳定依赖及所有现有 target variant。
- 一次性迁移 `MinecraftWorldPaths` 和注入的 filesystem/path API。
- 完成 Okio ↔ `kotlinx.io` 串流桥接及短读/短写/异常关闭测试。
- 此阶段先保持功能等价，不再扩展旧 `kotlinx.io.files` API。

### 阶段 2：纯 Anvil 原语

- 在 `world-format` 提取 header/location/allocator/record/stub 逻辑。
- 为截断头、无效 location、重叠 allocation、负坐标 slot、255/256 扇区边界和所有压缩 ID 加公共测试。
- 确认完整 `RegionFileFormat` 与新原语产生相同合法字节。

### 阶段 3：原位 Region I/O

- 实现 `OpenRegionFile` 和 256-entry LRU，改写 `WorldRegionStore`。
- 先完成内部 chunk 写、读、clear、flush、pad/close，再完成 `.mcc` 临时提交。
- 移除完整 `.mca` 临时替换路径、调用方 timestamp/external 控制和任意 512 MiB 全文件读取需求。随机访问后不应为了 size limit
  把整个 region 读进内存；NBT 解压限制继续由相应 codec/configuration 控制。
- 加入操作顺序和每个失败点的 fault-injection 测试。

### 阶段 4：独立文件策略与世界锁

- 实现 `level.dat`、玩家 `.dat` 的备份替换和各自不同的 fallback。
- 实现 saved-data 的 GZIP magic 兼容读取和 direct synced write。
- 实现 stats/advancements 的 direct UTF-8 write。
- 实现 `session.lock` 与 Unix durable-sync 最小平台原语，并在真实系统临时目录做跨进程/双实例测试。

### 阶段 5：生命周期与官方互操作

- 组合清晰的 flush/close/世界租约顺序，不引入常驻后台 scope。
- 扩展现有 Fixture Host 官方世界场景：官方进程必须先同步关闭，再由 JVM 和 desktop Native 测试在同一 Host
  工作目录原位读写；测试结束后仍由资源所有者清理。
- 用官方服务端重新打开库写出的世界，并让本库读取官方更新后的世界，覆盖 terrain、entities、POI、level、player 和 saved-data。

## 验证矩阵

### 纯格式与 fake filesystem

- 1024-entry header round trip、big-endian 字节和 location 公式。
- 开文件只读 header/目标扇区；spy filesystem 证明单 chunk 更新没有读取或重写完整 MCA。
- 更新同一个 chunk 时旧 allocation 不参与本次分配；成功后才可供下一次写使用。
- first-fit、文件扩展、不清零旧字节、不收缩、close 补齐 4096。
- record 处于 255 扇区时内部写，达到 256 扇区时自动 `.mcc`；`.mcc` 只含 compressed payload。
- clear 和 internal/external 互相切换的 header、sidecar、timestamp 顺序。
- fault injection 覆盖 record、header、sidecar move/delete、backup move 和 rollback 各边界。
- level/player/data/JSON 每种策略分别断言临时文件、备份、直接截断和 fallback 行为；临时名称只断言同目录、唯一和可清理。

### 真实平台

- JVM 首轮执行 `./gradlew :world-format:jvmTest :world-io:jvmTest`。
- JVM 与每个可执行 desktop Native 目标验证 Okio positional read/write、resize、LRU close、durable-sync 和双实例
  `session.lock`；移动/替换用真实同目录文件。
- Android host 继承公共格式/策略测试，不重复 JVM-hosted 官方文件场景；iOS/watchOS/tvOS 至少完成编译，系统文件测试只在仓库现有可执行测试目标运行。
- 变更 source-set/依赖发布边界后检查 production runtime classpath、KMP metadata，并用外部消费者 smoke test 证明只消费
  `world-io` 及其向下依赖即可。

### 官方 26.2 互操作

一个有序场景至少覆盖：

1. 官方创建并正常停止世界；本库读取 `level.dat`、三类 MCA、外部 chunk（构造达到阈值的 fixture）、维度 data 和玩家文件。
2. 记录现有 MCA header/location 与文件 identity，使用本库更新一个已有 chunk；断言同一 MCA 被扩展/位置写而非整文件替换，旧
   payload 仍在，新 header 指向新 allocation。
3. 使用本库在 internal/external 间切换并删除 chunk；官方重开世界后能读取/忽略相应内容。
4. 官方更新 terrain、entity 和 POI 后关闭；本库按对应目录读取完整 chunk NBT。
5. 本库保存 `level.dat` 后检查 `.dat_old`，模拟主文件损坏后验证 old fallback 和 corrupted 保留；玩家 fallback 不能自动提升
   old。
6. 官方或第二个测试进程持锁时，本库拒绝获取世界写租约；释放后可以重新获取。

成功的官方场景不留下独立结果文件；诊断字节仅在断言失败时进入测试报告。不要把反编译输出或官方存档样本提交为源码/fixture。

## 完成标准

- `world-io/src/*Main` 不再 import `kotlinx.io.files`，所有公开文件路径和文件系统类型均为 Okio。
- `world-format` 不依赖 Okio，也没有 filesystem/path 行为。
- 单 chunk 写入从未创建临时 `.mca` 或整体替换 region；更新顺序、阈值、timestamp、旧扇区和 `.mcc` 与上述官方证据一致。
- `level.dat`、玩家 NBT、saved-data、stats/advancements 使用各自正确策略，fallback 结果有故障测试固定。
- `syncWrites=true` 在所有声明支持的系统文件目标上代表真实 durable-sync；不把 Unix `fflush` 冒充 `fsync`。
- 高层世界写租约持有有效 `session.lock`，raw/fake filesystem API 不虚构跨进程锁能力。
- JVM、适用 desktop Native、官方互操作、发布元数据和外部消费者验证全部通过。
- README/AGENTS 准确说明只有 `world-io` 迁移文件系统 API；其余模块继续使用调用方拥有的 `kotlinx.io.Source`/`Sink`。

## 明确不做

- 不复刻 `Files.createTempFile` 的随机字符串、JVM 时间戳文件名格式或日志文案。
- 不保证损坏诊断副本的时间、权限或扩展属性与 Java `COPY_ATTRIBUTES` 完全一致；其文件内容、创建时机和 fallback 行为必须一致。
- 不在 `world-io` 实现 Mojang DataFixer、registry codec、terrain/entity/POI 的游戏语义或服务器自动保存调度。
- 不复制 `IOWorker` 的优先级线程池、old-chunk blender cache、JFR/profiling；没有性能证据前不增加后台 actor。
- 不做 MCA compact、旧扇区擦除、日志式事务、CRC、损坏 header 自动落盘修复或 header 失败回滚。
- 不把世界删除、ZIP 备份、世界选择 UI、`allowed_symlinks.txt` 发现策略纳入 raw storage；路径必须仍受根目录包含和调用方授权约束。
- 不迁移 buildSrc、Fixture Host、KSP 或其他模块的开发期文件访问，也不为早期公开 API 保留弃用适配层。

## Node target 能力更正与准入条件

此前仅检查 `com.squareup.okio:okio:3.18.1` 并据此断言 Okio 没有 Node 系统文件系统后端，这是错误的；Node 后端作为独立
artifact
`com.squareup.okio:okio-nodefilesystem:3.18.1` 发布，其 JS variant 是 `okio-nodefilesystem-js`。

该 artifact 提供由 Node 同步 `fs` API 实现的 `okio.NodeJsFileSystem`，已经覆盖 `world-io` 的通用主机文件能力：

- 真实磁盘的 canonicalize、metadata、目录列举、创建、删除和 symlink；
- Source/Sink 顺序读写；
- `openReadOnly`/`openReadWrite` 以及 `FileHandle` 的位置读写、size 和 resize；
- 通过 Node `renameSync` 实现的 `atomicMove`。

因此 Node target 不需要本项目自行实现完整 `FileSystem`/`FileHandle`，也不能以“Okio 无法在 Node 读取文件”为由排除。正确接入方式是
在 JS/Node source set 依赖 `okio-nodefilesystem`，并让系统文件系统平台入口返回 `NodeJsFileSystem`。Okio 的 Node 文件 API
是同步阻塞 API，会阻塞事件循环；这是公开使用限制，但不影响存档操作的正确性。

仍需补齐的只有两个窄平台能力：

1. **durable sync**：Okio 3.18.1 的 `NodeJsFileHandle.protectedFlush()` 是空实现，`flush()` 不会调用 `fsync`。当
   `syncWrites=true` 时，需要 Node 平台原语调用 `fsyncSync` 或经验证的等价操作，不能把 Okio flush 当作持久化提交。
2. **`session.lock`**：Okio 的 `FileSystem` 契约明确不提供文件锁，`okio-nodefilesystem` 也没有增加锁 API。Node 实现必须使用经验证的
   OS 级跨进程锁方案，能够与 JVM 官方服务端互斥，并保证正常关闭和进程意外退出后释放；不能用可能遗留陈旧文件的存在性标记代替。

据此，Node target 在普通文件 I/O、region 随机访问和独立文件策略方面是合理的。实施时应优先复用全部 `commonMain` raw store，只为
`systemFileSystem`、durable sync 和世界目录锁提供最小 Node 平台接线。只有在锁与 durable sync 均实现并通过以下验证后，才声明完整
Node 支持：

- 真实 Node 文件系统的位置读写、resize、同目录替换和独立文件策略测试；
- Node 与另一个 Node 进程、Node 与 JVM 官方服务端之间的 `session.lock` 互斥及释放测试；
- Node durable-sync 平台原语测试、公共 fake-filesystem 测试及完整 `allTests`。

如果最终找不到满足官方跨进程语义的 Node 锁后端，可以暂缓 Node target；此时阻塞原因应明确记录为 `session.lock`，而不是 Okio
缺少 Node 文件读写能力。
