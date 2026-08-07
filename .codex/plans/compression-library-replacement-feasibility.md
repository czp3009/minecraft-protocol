# 压缩库替换与模块简化实施方案

状态：方案已确定。Minecraft 行为基线为本仓库当前选定的 Java Edition 26.2。

## 最终目标

1. 删除项目内手写的 raw DEFLATE、raw LZ4、Adler-32、CRC-32 和 XXHash32 实现，改用已确定的第三方库。
2. 在所有保留平台完整读写现代 vanilla 使用的 GZIP、ZLIB、NONE 和 LZ4 Anvil chunk。
3. 在 `world-format` 的 `commonMain` 中实现 lz4-java `LZ4Block` 容器；各平台只提供 raw LZ4 和 XXHash32 原语。
4. 保持同步 `kotlinx.io.Source` / `Sink` 压缩边界，不把公共 API 改为 `suspend`。
5. 迁移所有使用方后删除 `compression` 模块，不新增跨领域的压缩聚合模块。
6. 删除 `wasmWasi` 发布目标和 D8 测试运行器。保留 JVM、Android、全部现有 Native、Kotlin/JS 和 Kotlin/WasmJS；Web 支持 Node
   与现代浏览器，自动化测试使用 Node。

本方案只面向现代 Minecraft。旧格式转换、损坏文件修复和不再由当前版本产生的历史压缩格式不在范围内。

## 官方 Anvil 压缩契约

Minecraft 26.2 的内建 Anvil compression ID 为：

|  ID | 名称                     | 本项目行为                                 |
|----:|--------------------------|--------------------------------------------|
|   1 | GZIP                     | 内建读写                                   |
|   2 | ZLIB，配置名为 `deflate` | 内建读写，也是默认写入类型                 |
|   3 | NONE                     | 内建读写，原样传递 payload                 |
|   4 | LZ4                      | 内建读写 lz4-java `LZ4Block` stream        |
| 127 | CUSTOM                   | 保留公共 registry 注入，不提供固定内建算法 |

compression ID 属于每一条 chunk record，而不是整个 `.mca` 文件。因此，同一个 region 文件内必须能够同时存在并独立读取
GZIP、ZLIB、NONE、LZ4 和已注册的 CUSTOM chunk。重写一个 chunk 不得迫使其他已保存 chunk 改变压缩类型或重新压缩。

## 后端选择优先级

每项能力分别按以下固定顺序选择，不要求一个库覆盖整个平台的所有算法：

1. 所有平台首先使用 Okio 已支持且语义匹配的能力。
2. Okio 不支持时，JVM 和 Android 使用 Minecraft 官方同款库。
3. Native 优先使用 NativeBuilds 系列公开发布的 artifact 和公共 API；NativeBuilds 没有对应能力时，再使用其他维护良好的
   Kotlin Multiplatform 库。
4. JS 和 WasmJS 优先使用 Kompress；Kompress 没有对应能力时，再使用维护良好的 JavaScript 依赖。项目不引入 WebAssembly codec
   或 native 构建流程，JavaScript 依赖内部如何实现不影响选择。

未在库的公共 artifact、头文件或 API 中承诺的内部符号不视为该库支持的能力。

## 固定的平台后端

| 能力                     | JVM / Android              | Kotlin/Native                | Kotlin/JS / Kotlin/WasmJS      |
|--------------------------|----------------------------|------------------------------|--------------------------------|
| raw RFC 1951 DEFLATE     | Okio 3.18.1                | Okio 3.18.1                  | Kompress 2.3.1 `kompress-core` |
| RFC 1950 ZLIB            | Okio 3.18.1                | Okio 3.18.1                  | Kompress 2.3.1 `kompress-zlib` |
| RFC 1952 GZIP            | Okio 3.18.1                | Okio 3.18.1                  | Kompress 2.3.1 `kompress-gzip` |
| raw LZ4 block            | lz4-java 1.10.1            | NativeBuilds liblz4 1.10.0_5 | npm `lz4-lite` 1.1.2           |
| XXHash32                 | lz4-java 1.10.1            | Appmattus cryptohash 1.0.2   | npm `js-xxhash` 5.0.1          |
| lz4-java `LZ4Block` 容器 | `world-format` common 实现 | 同一份 common 实现           | 同一份 common 实现             |
| NONE                     | common 原样传递            | common 原样传递              | common 原样传递                |
| CUSTOM                   | common registry 分派       | common registry 分派         | common registry 分派           |

固定依赖坐标为：

- `com.squareup.okio:okio:3.18.1`
- `dev.karmakrafts.kompress:kompress-core:2.3.1`
- `dev.karmakrafts.kompress:kompress-zlib:2.3.1`
- `dev.karmakrafts.kompress:kompress-gzip:2.3.1`
- `at.yawk.lz4:lz4-java:1.10.1`
- `com.ensody.nativebuilds:lz4-liblz4:1.10.0_5`
- `com.appmattus.crypto:cryptohash:1.0.2`
- npm `lz4-lite:1.1.2`
- npm `js-xxhash:5.0.1`

Kompress 只用于 JS 和 WasmJS，因此它缺少 `iosX64` 和 `watchosDeviceArm64` 产物不会限制本项目的 Native 目标。Web 后端是同步的纯
Kotlin/JavaScript 实现，不使用 Web Compression Streams，也不引入压缩 WebAssembly 模块。

NativeBuilds 当前没有公开的 XXHash artifact。`lz4-liblz4` 内部包含的 namespaced `LZ4_XXH32` 未由 liblz4
的公共头文件暴露，不作为生产依赖；Native XXHash32 因此按上述回退规则使用 Appmattus cryptohash。

Native raw LZ4 通过一个最小 cinterop 边界调用 NativeBuilds 随依赖发布的静态 liblz4，只暴露：

- `LZ4_compressBound`
- `LZ4_compress_default`
- `LZ4_decompress_safe`

消费者不需要在系统中安装 liblz4，项目也不加入 C 源码、CMake、vcpkg 或动态系统库探测。直接配置 Kotlin cinterop，不使用与当前
Gradle 版本不兼容的 NativeBuilds Gradle plugin。Apple 目标的 cinterop 和最终链接在 macOS 上验证。

## 共享 LZ4Block 实现

JVM、Android、Native、JS 和 WasmJS 全部使用 `world-format` 中同一份 `LZ4Block` 容器代码。lz4-java 在 JVM 测试中同时作为官方同款互操作
oracle，不另行拥有一套生产容器流程。

每个 block 的格式固定为：

```text
8 bytes  magic "LZ4Block"
1 byte   token = method | compressionLevel
4 bytes  compressedLength，little-endian
4 bytes  originalLength，little-endian
4 bytes  checksum，little-endian
N bytes  raw 或标准 LZ4 compressed payload
```

写入规则：

- 输入按最多 64 KiB 的独立 block 处理，compression level 为 `6`。
- 每个非空 block 调用平台 raw LZ4 compressor。
- 压缩结果严格小于原始内容时使用 method `0x20`；否则使用 method `0x10` 并写入原始内容。
- checksum 是未压缩内容以 seed `0x9747B28C` 计算的 XXHash32，wire value 为 `hash & 0x0FFF_FFFF`。
- 流末尾写入合法终止 block：magic、raw token、两个零长度和零 checksum。

读取规则：

- 接受 method `0x10` 和 `0x20`，后者交给平台 raw LZ4 decoder。
- 验证 magic、method、compression level、长度组合、解压后长度、XXHash32、终止 block 和尾随数据。
- 在分配、解压和复制前执行单 block 与累计输出上限。
- 截断、整数溢出、非法 token、非法长度、校验失败和输出超限均作为格式或 I/O 失败报告。

平台边界保持为 `world-format` 内部最小原语，不泄漏第三方库、JavaScript 或 C 类型。容器、限制、错误映射和 Anvil compression ID
分派不进入平台实现。

## 模块归属

- `protocol-transport` 私有持有 Minecraft 网络 ZLIB envelope、阈值、声明长度验证和各平台 ZLIB 后端。
- `world-format` 私有持有 Anvil GZIP、ZLIB、LZ4Block、NONE、CUSTOM 分派以及解压限制。
- `world-format` 保留共享 LZ4Block 容器代码，但不实现 raw LZ4 或 XXHash32 算法。
- `world-io` 只组合文件系统操作、独立 NBT 文件策略和下层压缩能力，不拥有压缩算法。
- 所有第三方压缩依赖使用 `implementation`，不得进入公共或受保护 ABI。
- 删除 `compression` 项目、项目依赖、发布配置和文档入口；不建立替代性的公共 compression 模块。

公共压缩入口继续以同步 `kotlinx.io.Source` 和 `Sink` 为规范路径，数组方法仅作为适配器。各库所需的缓冲或流桥接留在对应模块内部，并继续执行调用方提供的输出上限。

## 源集与目标整理

- LZ4Block、compression ID 分派、NONE 和 CUSTOM registry 放在 `commonMain`。
- Okio 的 JVM、Android、Native 接入分别使用默认源集；只有无法由默认层级表达且确实共享实现的最小平台适配才创建一个能力命名的中间源集。
- Kompress 依赖和可共享实现放在默认 `webMain`；JS 与 WasmJS 各自只保留无法共享的 npm interop 声明和适配。
- NativeBuilds 与 Appmattus 依赖和适配放在默认 `nativeMain`。
- Gradle `sourceSets` 中 Main 配置位于 Test 配置之前；默认源集使用 `commonMain`、`jsMain`、`jvmTest` 等生成 DSL，不通过字符串
  `getByName` 或 `named` 获取。
- 仓库范围删除 `wasmWasi` target、source set、测试任务和发布 variant；删除所有 D8 测试执行器。
- 迁移后删除不再使用的自定义源集、空源集和空目录。

## 实施顺序

1. 在 version catalog 中加入上述固定依赖及 npm 版本。
2. 为 `protocol-transport` 接入 JVM、Android、Native 的 Okio ZLIB，以及 Web 的 Kompress ZLIB。
3. 为 `world-format` 接入各平台 GZIP、ZLIB、raw LZ4 和 XXHash32 原语。
4. 在 `world-format` common 代码中实现完整 LZ4Block 读写，并让所有平台走同一容器路径。
5. 按每条 chunk record 的 compression ID 分派，补齐同一 `.mca` 混用多种算法的读写和保留行为。
6. 删除项目内手写压缩和 checksum 算法，再删除 `compression` 模块及其所有引用。
7. 删除 `wasmWasi` 和 D8 配置，整理默认源集层级并清理空目录。
8. 更新所有受影响的 `AGENTS.md`、README、skill、发布说明和模块架构文档，使其与最终代码一致。

## 验收标准

1. 官方 26.2 服务端分别以 GZIP、默认 ZLIB、NONE 和 LZ4 配置生成区域文件，本项目能够读取其 chunk。
2. JVM、Android、各 Native、JS Node 和 WasmJS Node 都通过 GZIP、ZLIB、raw LZ4、XXHash32 和 LZ4Block 的有效、边界、畸形、限制及
   round-trip 测试。
3. 测试至少覆盖 LZ4Block 的 raw block、compressed block、多 block、终止 block、64 KiB 边界、不可压缩回退、错误 magic、非法
   token、非法长度、损坏 checksum、截断和输出超限。
4. 各平台写出的 LZ4Block 均可由 lz4-java `LZ4BlockInputStream` 解码；lz4-java 写出的流也可由各平台读取。标准 LZ4
   允许不同的等价压缩字节，不要求逐字节相同。
5. 同一 `.mca` 内混合 GZIP、ZLIB、NONE、LZ4 和测试注册的 CUSTOM chunk 时，可逐条正确读取；局部改写不会重压缩或改变其他保留
   chunk。
6. 官方 26.2 服务端能够加载、保存并重新加载本项目写出的 GZIP、ZLIB、NONE、LZ4 及混合压缩 region 文件。
7. 网络 ZLIB 覆盖压缩阈值、声明长度、上限、截断和跨平台交叉向量。
8. `protocol-transport` 与 `world-format` 的外部消费者 smoke test 证明第三方实现依赖未泄漏到公共 ABI，删除 `compression`
   后发布依赖图仍保持独立且无环。
9. JVM 反馈链路稳定后运行适用的平台标准测试，最终以受限 worker 执行 `./gradlew allTests --max-workers=2` 并通过。
10. macOS 验证全部 Apple target 的 NativeBuilds cinterop、编译和静态链接；其他 Native target 完成对应编译、链接和可运行平台测试。
