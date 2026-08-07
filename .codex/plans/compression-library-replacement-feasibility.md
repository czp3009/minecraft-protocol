# 以成熟库替代手写压缩算法的可行性与架构决策

状态：已确定的可行性结论与架构决策。Minecraft 基线为本仓库当前选定的 Java Edition 26.2。

## 结论

本项目采用以下固定方案：

1. 删除手写 DEFLATE、LZ4、Adler-32、CRC-32 和 XXHash32 算法，并在迁移完成后删除 `compression` 模块。
2. 完整支持现代 vanilla Anvil 的 LZ4 读取和写入。LZ4 不再是可排除的兼容分支。
3. Minecraft 使用的是 **标准 LZ4 block compression algorithm**。非标准的是 lz4-java 专用的 `LZ4Block` 流封装，不是 LZ4 算法。
4. JVM 和 Android 使用 lz4-java 直接读写 `LZ4Block`；Native、JavaScript 和 WasmJS 使用成熟的标准 raw LZ4 与 XXHash 实现，并由
   `world-format` 解析和生成 `LZ4Block` 封装。
5. JVM、Android 和 Native 的 ZLIB/GZIP 使用 Okio；JavaScript 和 WasmJS 使用宿主的 `CompressionStream` /
   `DecompressionStream`。
6. 公共压缩边界改为 `suspend` 操作，以容纳 Web Streams 的异步语义。
7. 删除 `wasmWasi` 发布目标和 D8 测试运行器；保留 JVM、Android、Native、JavaScript 和 WasmJS，其中 Web 运行时为 Node 与现代浏览器。

上述方案实现完整的现代 Minecraft 压缩兼容，同时不在项目中手写通用压缩或校验算法。项目仍保留 Minecraft 格式分派、资源限制、错误映射和
`LZ4Block` 容器适配代码。

## 24w04a 所说的 LZ4 是什么

Mojang
在 [Java Edition Snapshot 24w04a 官方说明](https://feedback.minecraft.net/hc/en-us/articles/23489515647117-Minecraft-Java-Edition-Snapshot-24w04a)
中新增专用服务器属性：

```properties
region-file-compression=lz4
```

官方说明把 `deflate` 称为默认的旧算法，把 LZ4 定位为新的高性能选择：压缩和解压消耗更少 CPU 时间，代价是使用更多磁盘空间。LZ4
是现代 Minecraft 当前提供的性能能力，不是为旧地图保留的过时算法。

Mojang 没有在该文章中宣布未来必然把默认值切换为 LZ4。Minecraft 26.2 的默认值仍是 `deflate`。本项目不依赖这项未来预测来决定兼容范围：26.2
已经会合法写出 LZ4 区域文件，且项目明确把 LZ4 作为必须完整支持的现代方向。

## 文章配置与实际磁盘格式的确定映射

文章本身只命名算法和配置项，没有描述字节级流封装。版本锁定的 26.2 官方服务端 JAR 给出了完整映射：

```text
server.properties: region-file-compression=lz4
  -> DedicatedServerProperties
  -> Main 调用 RegionFileVersion.configure("lz4")
  -> RegionFileVersion 中名称 "lz4" 对应压缩 ID 4
  -> 读取使用 LZ4BlockInputStream
  -> 写入使用 LZ4BlockOutputStream
```

因此，文章中的 `region-file-compression=lz4` 与压缩 ID 4、`LZ4BlockInputStream` 和 `LZ4BlockOutputStream` 是同一项官方功能。

26.2 证据基线为：

- `MinecraftTarget.MINECRAFT_VERSION`：`26.2`；
- 官方服务端 SHA-256：`cdacdfb25898de5e4b4b0e5ddcc2722f77067e46605709c2d886c000ebb63ec5`；
- 协议号：`776`；
- 官方运行时依赖：`at.yawk.lz4:lz4-java:1.10.1`；
- 内建区域压缩类型：ID 1 GZIP、ID 2 `deflate`、ID 3 `none`、ID 4 `lz4` 和 ID 127 `custom`；
- `RegionFileVersion.DEFAULT`：ID 2 `deflate`。

## 标准 LZ4 算法与专用 LZ4Block 封装

lz4-java 1.10.1 的 `LZ4BlockOutputStream(OutputStream)` 依次选择 64 KiB block 和
`LZ4Factory.fastestInstance().fastCompressor()`。该构造器的源码明确将其描述为标准 LZ4 压缩算法。写块时，
`compressor.compress(...)` 产生标准 raw LZ4 block。

lz4-java 的 [`LZ4BlockOutputStream` 文档](https://lz4-java.yawk.at/v1.10.4/javadoc/net/jpountz/lz4/LZ4BlockOutputStream)
同时说明该类使用自己的流格式，并且不兼容标准 LZ4 Frame。两项事实不存在冲突：

| 层次              | 标准性                | 含义                                          |
|-------------------|-----------------------|-----------------------------------------------|
| LZ4 压缩负载      | 标准 LZ4 block format | 可由官方 liblz4 和兼容 raw-block codec 编解码 |
| `LZ4Block` 流封装 | lz4-java 专用格式     | 具有自己的 magic、token、长度、校验和及结束块 |
| 标准 `.lz4` 文件  | LZ4 Frame format      | 不是 Minecraft ID 4 使用的外层格式            |

所以“文章里的 LZ4 是否是非标准 LZ4”的准确答案为：

- 算法不是非标准算法；它是标准 LZ4 block compression；
- 完整磁盘 payload 不是标准 LZ4 Frame；它是 lz4-java 专用 `LZ4Block` stream；
- 只接受标准 LZ4 Frame 的 `.lz4` 文件 API 不能直接处理 Minecraft ID 4；
- raw LZ4 block 编解码库可以处理其中的压缩负载，外围 `LZ4Block` 字段由 `world-format` 负责。

## 26.2 实际写入证据

使用官方 26.2 服务端、全新世界和 `region-file-compression=lz4` 完成出生区生成并正常停止后，新生成的
`dimensions/minecraft/overworld/region/r.0.0.mca` 在第一个区块记录处包含：

```text
00002000: 00 00 02 03 04 4c 5a 34 42 6c 6f 63 6b 26 d8 01
00002010: 00 00 4f 0b 00 00 c8 46 2d 00 f1 34 ...
```

字段含义为：

- `00 00 02 03`：Anvil 区块记录长度 `0x203`；
- `04`：Anvil 压缩 ID 4；
- `4c 5a 34 42 6c 6f 63 6b`：ASCII magic `LZ4Block`；
- token `26` 的高半字节 `0x20`：`COMPRESSION_METHOD_LZ4`；
- token `26` 的低半字节 `6`：最大 block 为 `1 << (10 + 6)`，即 64 KiB；
- 压缩长度：小端 `0x1d8`，即 472；
- 原始长度：小端 `0xb4f`，即 2895；
- 后续字段：lz4-java 所需的 XXHash32 校验值和真正的标准 LZ4 compressed block。

该记录不是只带 `LZ4Block` 外壳的 raw/uncompressed block。现代 vanilla 确实调用 LZ4 compressor 并把压缩结果写入区域文件。

## 必须实现的 LZ4Block 读写契约

每个 `LZ4Block` block 由 21 字节 header 和 payload 组成：

```text
8 bytes  magic "LZ4Block"
1 byte   token = method | compressionLevel
4 bytes  compressedLength，little-endian
4 bytes  originalLength，little-endian
4 bytes  checksum，little-endian
N bytes  raw 或标准 LZ4 compressed payload
```

固定语义为：

- method `0x10`：raw/uncompressed block；
- method `0x20`：标准 LZ4 compressed block；
- 默认 compression level 低半字节为 `6`，对应 64 KiB block；
- checksum 是未压缩内容的标准 XXHash32，seed 为 `0x9747B28C`；
- lz4-java 通过 `StreamingXXHash32.asChecksum()` 取得 wire value，该适配器将结果限制为低 28 位，因此写入和验证都必须使用
  `hash & 0x0FFF_FFFF`；
- 结束块继续携带 magic 和 raw token，两个长度及 checksum 均为零；
- 同一条流包含多个独立 block；每个 block 单独压缩和校验。

读取端必须：

- 同时接受 raw method 和 LZ4 method；
- 使用第三方标准 raw LZ4 decoder 解码 method `0x20`；
- 验证 magic、method、block size、长度组合、XXHash32、结束块和尾随数据；
- 在分配或复制前执行单 block 与累计输出上限；
- 拒绝截断、溢出、校验失败和解压长度不一致。

写入端必须：

- 以最多 64 KiB 的独立 block 处理输入；
- 对每个非空 block 调用第三方标准 raw LZ4 compressor；
- 在压缩结果严格小于原始内容时写 method `0x20`；
- 在压缩结果不小于原始内容时写 method `0x10` 和原始字节；
- 写入低 28 位 XXHash32 及合法结束块；
- 生成可由 vanilla `LZ4BlockInputStream` 读取的完整流。

当前项目的编码器总是写 raw/uncompressed block，因而只满足容器兼容，不满足实际 LZ4 压缩写入要求。迁移后的实现必须产生 method
`0x20` 的压缩块。

## 平台后端

| 能力              | JVM / Android          | Native         | JavaScript / WasmJS                                               |
|-------------------|------------------------|----------------|-------------------------------------------------------------------|
| 网络与 Anvil ZLIB | Okio                   | Okio           | `CompressionStream("deflate")` / `DecompressionStream("deflate")` |
| Anvil GZIP        | Okio                   | Okio           | `CompressionStream("gzip")` / `DecompressionStream("gzip")`       |
| LZ4 block 编解码  | `at.yawk.lz4:lz4-java` | 官方 liblz4    | 官方 liblz4 构建的 WebAssembly 后端                               |
| XXHash32          | lz4-java 的 XXHash     | 官方 xxHash    | 官方 xxHash 构建的 WebAssembly 后端                               |
| LZ4Block 封装     | lz4-java stream        | `world-format` | `world-format`                                                    |

Native 通过 cinterop 调用官方 [LZ4](https://github.com/lz4/lz4) 与 xxHash 实现。发布产物携带受版本锁定的 native
依赖，不要求消费者预装系统库。

JavaScript 和 WasmJS 使用由官方 LZ4/xxHash 源码构建并随发布产物提供的 WebAssembly codec。该 codec 同时暴露 raw-block
encode/decode 和 XXHash32，运行于 Node 与现代浏览器。Kotlin 代码只负责内存传递、边界检查和 LZ4Block 封装，不包含算法实现。

这一后端选择避免依赖仅支持解压、私自添加长度前缀、只支持 LZ4 Frame 或维护状态不确定的 npm 包。WebAssembly codec
必须固定上游版本、许可证、构建输入与产物校验值。

WHATWG [Compression Streams](https://compression.spec.whatwg.org/) 只负责 ZLIB/GZIP，不支持 LZ4。`deflate` 表示带 RFC 1950
包装的 ZLIB，正好覆盖 Minecraft 网络压缩和 Anvil ID 2。LZ4 始终走独立的标准 raw-block 后端。

## 异步 API

Web `CompressionStream`、`DecompressionStream` 以及 WebAssembly 初始化都具有异步边界。共同压缩 API 使用操作级 `suspend`：

- JVM、Android 和 Native 内部保持流式读写；
- JavaScript 和 WasmJS 异步拉取 Web Stream 输出；
- Web 在每个输出 chunk 到达时累计检查上限；
- NBT 解码在压缩数据进入受限 `Buffer` 后继续复用同步 `kotlinx.io.Source` 解析器；
- 网络压缩位于已有的挂起式 transport stream 边界内；
- 不使用无上限的 `Response.arrayBuffer()`。

## 删除 `compression` 模块后的职责

当前实现包含：

- [`compression`](../../compression) 的手写 raw DEFLATE；
- [`protocol-transport`](../../protocol-transport) 的手写 ZLIB 包装和 Adler-32；
- [`world-format`](../../world-format) 的手写 ZLIB/GZIP、Adler-32、CRC-32、LZ4 decoder、XXHash32 和 LZ4Block 封装。

迁移后：

- `protocol-transport` 私有持有网络 ZLIB 后端和 Web 适配；
- `world-format` 私有持有区域 GZIP/ZLIB/LZ4Block 后端、压缩 ID 分派和解压限制；
- `world-format` 保留 LZ4Block 容器解析和生成，不保留 LZ4/XXHash 算法；
- 两个模块不互相依赖，也不新建跨领域 compression 聚合模块；
- 公共 API 不暴露 Okio、JavaScript 包、WebAssembly 或 native C 类型；
- 第三方依赖保持为 `implementation`；
- `settings.gradle.kts`、发布配置、README 和模块说明移除 `compression`。

`compression` 当前是已发布模块并具有公共 API。删除它属于源代码、二进制与发布元数据层面的破坏性变更，必须进入发行说明和外部消费者验证。

## 放弃 wasmWasi 与 D8

`wasmWasi` 不是 JavaScript 目标。Kotlin 官方说明面向 `wasmWasi`
时[不能使用 JavaScript interop](https://kotlinlang.org/docs/whatsnew1920.html#support-for-the-wasi-api-in-the-standard-library)
，它不能直接使用 Web Compression Streams 或 JavaScript/WebAssembly 包装层。仓库范围删除 `wasmWasi` target、source
set、测试任务和发布 variant。

D8 是裸 JavaScript 引擎和测试执行器，不是发布目标。仓库当前配置的 D8 14.2.82 不提供 `CompressionStream`、
`DecompressionStream`、`ReadableStream` 或 `TransformStream`。仓库范围删除所有 `d8()` 测试执行器，Web 标准测试门禁使用
Gradle 管理的 Node。

最终 Web 支持矩阵为：

- Kotlin/JS：Node 与现代浏览器；
- Kotlin/WasmJS：Node 与现代浏览器；
- 自动测试运行时：Node；
- 不支持：Wasm/WASI 与 D8 运行环境。

## 验收标准

1. 官方 26.2 服务端以 `region-file-compression=lz4` 动态生成包含 method `0x20` 的 ID 4 区域文件。
2. JVM、Android、Native、JavaScript Node、WasmJS Node 的实现均能读取官方生成的 raw block、compressed block、多 block 和结束块。
3. 各平台写出的 LZ4Block 均能由 lz4-java `LZ4BlockInputStream` 解码。
4. 官方 26.2 服务端能加载、保存并重新加载本项目写出的 ID 4 区域文件。
5. 可压缩输入产生 method `0x20`；不可压缩输入按 lz4-java 规则回退为 method `0x10`。
6. 压缩字节不要求与 lz4-java 逐字节相同。标准 LZ4 允许多个等价编码；验收依据是交叉解码、长度、校验和及 vanilla 重载成功。
7. 畸形测试覆盖错误 magic、非法 token、负数或越界长度、损坏 XXHash32、截断 payload、错误结束块、尾随数据和累计输出超限。
8. ZLIB/GZIP 在 Okio 与 Web Compression Streams 之间执行交叉向量测试，并覆盖网络压缩阈值和声明长度。
9. `world-format` 与 `protocol-transport` 分别通过外部消费者 smoke test，确认实现依赖未泄漏到公共 ABI。
10. 删除 `compression`、WASI variants 和 D8 任务后，发布元数据与所有保留平台的标准测试通过。

## 完成状态定义

只有在所有保留平台均通过 LZ4 读写及 vanilla 互操作验证后，现有手写 LZ4/XXHash 实现和 `compression` 模块才能删除。WebAssembly
与 Native 后端是迁移的必要组成部分，不是可选增强。

最终支持对象明确为： **标准 LZ4 block algorithm，封装在 Minecraft 26.2 使用的 lz4-java `LZ4Block` stream
中，并同时支持读取和写入。**
