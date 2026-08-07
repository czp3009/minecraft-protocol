# `world-io` Node 平台支持计划

## 目标

为 `world-io` 增加 Kotlin/JS Node target，使 Node 消费者能够使用与 JVM 和 desktop Native 相同的世界路径、独立文件
store、Anvil region store、durable write 和 `session.lock` 世界写租约。现有 common store 直接复用，现有 JVM、Android 和
Native 平台实现保持原样。

## 1. Target 与依赖

1. 在 `world-io/build.gradle.kts` 增加仓库标准 Kotlin/JS IR `nodejs()` target，并使用标准 `jsMain`、`jsTest` source set。
2. 在版本目录中增加 `com.squareup.okio:okio-nodefilesystem:3.18.1` 坐标，在 `jsMain` 以 implementation 依赖引入。
3. 在 `jsMain` 以 runtime implementation npm dependency 引入固定版本 `fs-native-extensions@1.5.0`。
4. Node variant 的系统文件访问使用 `okio.NodeJsFileSystem`；Source、Sink、FileHandle、随机位置读写、resize、目录操作和
   `atomicMove`
   继续由 Okio 提供。
5. `okio-nodefilesystem` 和 `fs-native-extensions` 保持为 Node 实现细节，公开 API 继续只暴露既有 Okio `Path`、`FileSystem`
   和
   `FileHandle` 类型。
6. 发布元数据携带完整 Node npm 运行时依赖，使外部消费者安装 `world-io` Node variant 时获得匹配平台的 native addon。

## 2. 系统文件系统平台入口

1. common 代码通过内部 `systemFileSystem` expect/actual 入口取得默认系统文件系统。
2. JS actual 返回 `NodeJsFileSystem`。
3. JVM、Android 和 Native actual 继续返回 `FileSystem.SYSTEM`。
4. common store 的默认 `FileSystem` 参数以及 durable-sync 的真实系统文件 identity 检查统一使用 `systemFileSystem`。
5. JS external declarations 仅覆盖 Node `fs` 中实际调用的常量和同步函数，以及 `fs-native-extensions` 的 `tryLock` 和
   `unlock`。

## 3. Node durable sync

保留 common `FileHandle.flushDurably(fileSystem, path)` 组合入口：先调用 Okio `FileHandle.flush()`，系统文件 identity
匹配时再调用平台
`syncSystemFilePath(path)`。

JS actual 按以下顺序实现 `syncSystemFilePath`：

1. 使用 Node `fs.openSync` 以只写方式打开已有路径，并保留现有文件内容。
2. 对获得的 fd 调用 `fs.fsyncSync(fd)`。
3. 在 finally 路径调用 `fs.closeSync(fd)`。
4. 按模块现有规则保留 fsync 主异常，并把 close 失败附加为 suppressed exception。
5. fake/custom filesystem 继续使用自身 `FileHandle.flush()`；Node 宿主 primitive 由系统文件 identity 选择。

真实 Node 文件测试覆盖 `syncWrites=true` 的独立 NBT 文件、region header/payload 和显式 flush/close 路径。

## 4. Node `session.lock`

JS actual 使用 Node `fs` 和 `fs-native-extensions` 实现 `WorldDirectoryLock`。

### 获取锁

1. 确保世界根目录存在。
2. 使用 `O_WRONLY | O_CREAT`、mode `0666` 打开 `session.lock`，保留现有文件长度和尾部。
3. 从偏移 0 循环写完 UTF-8 `☃` 三个字节。
4. 对同一个 fd 调用 `fs.fsyncSync(fd)`。
5. 调用 `tryLock(fd, 0, 0, { shared: false })`，申请从偏移 0 到 EOF 的非阻塞独占锁。
6. `tryLock` 返回 `false` 时构造现有 `worldAlreadyLockedException` 并关闭 fd；其他 I/O 和 addon 错误按实际原因传播。
7. 获取成功后由 Node `WorldDirectoryLock` 实例持有 fd，`isValid` 表示该实例处于开放持锁状态。

### 释放锁

1. `close()` 先调用 `fs-native-extensions.unlock(fd, 0, 0)`，再调用 `fs.closeSync(fd)`。
2. unlock 失败作为主异常，close 失败按现有 suppressed exception 规则附加。
3. 重复 `close()` 保持幂等。
4. Node 进程退出后由 OS 关闭 fd/HANDLE 并释放锁，`session.lock` 文件保留在世界目录中。

### 查询锁状态

1. Node 进程内 canonical path registry 记录当前实例持有的世界锁，使重复获取抛出 `WorldLockException`，`isLocked` 返回
   `true`。
2. `isWorldDirectoryLocked` 以只写模式打开已有 `session.lock`。
3. 文件不存在时返回 `false`，权限拒绝时返回 `true`。
4. 调用 `tryLock(fd, 0, 0, { shared: false })`；返回 `false` 时报告已锁定。
5. 临时获取成功时立即调用 `unlock` 并报告未锁定。
6. 查询路径在 finally 中关闭 fd，并保留查询、unlock 和 close 的异常顺序。

## 5. Node 文件系统测试

在标准 Node test task 上复用 commonTest 场景，并增加真实 `NodeJsFileSystem` 覆盖：

1. canonicalize、metadata、目录创建/列举/删除和 symlink。
2. Source/Sink 顺序读写。
3. FileHandle 位置读写、size、resize、flush 和 close。
4. 同目录 `atomicMove`、临时文件创建、backup/fallback 和直接最终路径写入。
5. MCA header 读取、region 原地更新、sector 扩展、chunk clear 和 external `.mcc` sidecar。
6. terrain、entities 和 POI region 目录路径。
7. `level.dat`、playerdata、saved data、stats 和 advancements 的既有文件策略。
8. fake/custom filesystem 与 Node 系统文件 primitive 的分流。

## 6. durable-sync 测试

1. 可观测 Node `fs` seam 验证 `openSync`、`fsyncSync`、`closeSync` 的调用顺序。
2. fsync 成功、open 失败、fsync 失败、close 失败以及 fsync 与 close 同时失败的异常保持。
3. `syncWrites=true` 触发系统文件 durable-sync。
4. `syncWrites=false` 保持既有写入策略。
5. fake/custom filesystem 使用自身 flush 行为。

## 7. 锁互操作测试

1. 单进程测试覆盖 `☃` marker、旧尾部保留、第二次获取、`isLocked`、重复 close、失败清理和释放后重获。
2. Node 子进程测试双向覆盖 Node↔Node 互斥、正常释放、进程强制终止后的 OS 自动释放和重新获取。
3. Java 25 对端通过 `java` from `PATH` 使用 `FileChannel.tryLock()`，双向覆盖 Node↔Java 互斥与释放后重获。
4. Linux、macOS 和 Windows runner 分别执行 Node↔Java 测试，固定 `fs-native-extensions` 在三个 OS 上与 OpenJDK 锁的互操作行为。
5. Fixture Host 场景覆盖 Node↔匹配版本官方服务端：官方进程持锁时 Node 查询为已锁定且获取失败，官方同步退出后 Node
   成功获取；Node 持锁时官方服务端拒绝打开同一世界。

## 8. 发布与回归验证

1. 检查 Gradle variant 和发布元数据，确认 Node 依赖限定在 JS variant，JVM、Android 和 Native 生产 classpath 保持原样。
2. 使用全新外部消费者工程安装发布出的 Node variant，完成真实文件位置读写、durable write 和 lock acquire/release。
3. 在 Linux x64、Linux arm64、macOS x64、macOS arm64、Windows x64 和 Windows arm64 中验证 npm addon 的安装与加载。
4. 运行 `:world-io:jvmTest` 和适用 desktop Native tests，固定现有平台行为。
5. 运行标准 Node test task 和 `allTests`。
6. 更新 `world-io` README/API 文档，记录 Node runtime、同步阻塞文件 API、支持的 OS/CPU 组合和 `session.lock` OS 自动释放语义。

## 完成标准

- Node variant 使用 `NodeJsFileSystem` 完成全部真实世界文件操作。
- `syncWrites=true` 在 Node 系统文件上调用 `fs.fsyncSync`。
- Node `session.lock` 通过 `fs-native-extensions` 与 Node、Java 25 和匹配版本官方服务端双向互斥。
- 正常关闭和进程终止后锁均可重新获取。
- 外部消费者能够从发布元数据安装并独立运行 Node variant。
- JVM、Android 和 Native 的实现与行为保持稳定。
