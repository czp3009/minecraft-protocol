# `world-io` Node 平台支持实现记录

## 范围

`world-io` 增加标准 Kotlin/JS Node target，使 Node 消费者能够使用现有世界路径、独立文件 store、Anvil region store、durable
write 和 `session.lock` 世界写租约。浏览器和 Wasm 不提供部分文件系统实现。旧格式修复、迁移和损坏文件 兼容不在项目范围内。

## 实现

- `jsMain` 使用 `okio-nodefilesystem` 3.18.1 的 `NodeJsFileSystem`，并以 implementation npm 依赖固定
  `fs-native-extensions` 1.5.0。
- common store 通过最小的内部 `systemFileSystem` expect/actual 入口取得平台默认文件系统。JVM、Android 和 Native 仍返回
  `FileSystem.SYSTEM`，JS 返回 `NodeJsFileSystem`。
- Okio 继续负责目录、顺序 I/O、位置 I/O、resize 和 `atomicMove`。Node 的替换移动因此沿用
  `NodeJsFileSystem.atomicMove` 的宿主 rename 实现。
- Node durable sync 在 Okio handle flush 后，以只写方式重新打开已有路径，依次调用 `fsyncSync` 和 `closeSync`；主异常与
  close 异常按模块既有 suppressed-exception 规则组合。自定义或 fake filesystem 不调用 Node 宿主原语。
- Node `session.lock` 以 `O_WRONLY | O_CREAT` 打开且不截断文件，从偏移 0 写入 UTF-8 `☃`，同步同一个 fd，再通过
  `fs-native-extensions.tryLock(fd)` 请求默认的从 0 到 EOF 非阻塞独占锁。实例关闭时先 unlock 再 close，重复关闭幂等。
- 进程内 canonical path registry 补足同一 JS 进程内重复获取的检测；跨进程互斥由操作系统锁负责。锁文件在正常关闭或
  进程退出后保留，锁随 descriptor/HANDLE 关闭而释放。
- JS external 声明只覆盖实际调用的 Node `fs` 常量与同步函数，以及 addon 的 `tryLock`、`unlock`。

## 官方行为依据

- 匹配 26.2 官方服务端的 `DirectoryLock` 顺序为：打开或创建 `session.lock`、从位置 0 写 marker、`force(true)`、
  `tryLock()`；查询使用只写打开和临时独占锁，关闭时释放锁并关闭 channel。Node 实现保持相同流程和判断结果。
- 官方 `RegionFile` 使用 DSYNC channel、位置写入、完整 header 提交以及同目录临时 sidecar 的替换移动。既有 common region
  流程直接复用，Node 只提供与其所需语义对应的系统文件原语。
- `fs-native-extensions` 1.5.0 在 Linux 使用与传统 POSIX record lock 冲突的 OFD lock，在 macOS 使用与 `fcntl` lock 兼容的
  `flock`，在 Windows 使用 `LockFileEx`；固定包包含 Linux、macOS、Windows 的 x64 与 arm64 prebuild。

## 测试与验证

- `commonTest` 的真实系统文件测试在 Node 上覆盖替换移动、位置 MCA 更新、sector 扩展、external `.mcc` 替换、独立文件
  策略、世界租约、锁 marker 与旧尾部保留、重复获取、查询、释放后重获，以及 player fallback。
- 标准 `jsTest` 薄入口运行官方世界互操作场景：官方服务端持锁时 Node 必须报告已锁且获取失败；官方同步退出后 Node 必须能够获取；随后
  Node 原地改写官方生成的 MCA、sidecar 和独立文件，官方服务端重启后必须成功读取并再次保存。
- JVM 测试继续固定原有 Java NIO 行为。Native 的既有实现未改变，仅为 common 默认文件系统入口增加必需 actual。
- 当前开发机真实执行 Node native addon 与官方 JVM 的 Linux x64 互操作。其他 OS/CPU 组合依据固定包中的 prebuild 和
  对应平台原语源码审查，不宣称在本机运行过。
- `:world-io:jvmTest`、`:world-io:jsNodeTest` 和 `allTests` 均已使用 `--max-workers=2` 通过；最终 `allTests`
  用时 9 分 6 秒，909 个任务中 721 个执行、188 个复用。

没有为测试引入生产 seam、Node 子进程 helper 或逐 OS 外部消费者工程。现有真实系统文件覆盖与匹配版本官方服务端
generate/rewrite/reload 场景直接验证本项目需要的流程，同时避免仅为测试增加平台抽象。
