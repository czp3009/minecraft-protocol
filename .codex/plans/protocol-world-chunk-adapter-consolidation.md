# Protocol/World Chunk 共享适配器归位计划

- 状态：已完成；共享实现、portable tests、文档与 agent 指南均已归位，完整 `./gradlew allTests` 已通过
- 记录日期：2026-08-27
- 适用版本：仓库所选择的 Minecraft 官方版本；本计划不改变版本选择
- 影响模块：`protocol-datapack`、`protocol-client`、`protocol-server`，以及对应 README/AGENTS
- 最终目标：client/server 用户无需额外添加适配器依赖即可获得协议上下文与 `world-format` Chunk 类型之间的转换能力

## 1. 背景与已确认问题

当前 `MinecraftDimensionLayout` 属于 `protocol-datapack`，`ChunkLayout` 属于 `world-format`，但两者的规范转换被放在
`protocol-server`：

```kotlin
fun MinecraftDimensionLayout.toChunkLayout(): ChunkLayout
```

与此同时，`protocol-client` 没有复用该扩展，而是在 `MinecraftClientNegotiationResult.chunkLayout` 中再次手写相同的
`minY -> minSectionY` 和 `sectionCount` 转换。该函数的放置位置因此带有以下问题：

1. 一个不依赖 server 行为的纯转换被错误地归到 `protocol-server`。
2. client/server 的 API 形状不一致，并且同一规则有两份实现。
3. 如果仅因为 JVM 可以使用 `compileOnly` 就把跨模块扩展放回较低层模块，Kotlin/Native metadata 和链接阶段无法可靠解析
   其公开签名；而且该函数的返回类型本来就是调用方必须拥有的公开依赖，`compileOnly` 在语义上也不合适。
4. 为每一类小适配器新增独立 Gradle subproject 会造成模块碎片化，而当前依赖图已经存在可以承担该关系的模块。

审计还发现第二组完全相同的重复：

- `protocol-client` 公开 `ProtocolRegistryContext.toChunkDataRegistries()`，内部实现 block-state/biome 适配。
- `protocol-server` 在 `MinecraftChunkPacketEncoder` 所在文件中私有复制了同一套适配逻辑。

这两组转换都只依赖 Configuration/registry 事实和 `world-format` 的语义 Chunk 类型，不依赖 socket、session、client/server
生命周期或文件系统。

## 2. 已决定的架构

本次不新增 Gradle subproject，也不增加任何 `compileOnly` 依赖。

`protocol-datapack` 已在 `commonMain` 中以 `api` 依赖 `protocol-model` 和 `world-format`，并且它本来就负责：

- 从 Configuration/数据包事实构造 `MinecraftDimensionLayout`；
- 构造和解析 `ProtocolRegistryContext`；
- 在世界数据包资源与协议 registry 投影之间建立联系。

因此，它是当前依赖图中同时认识协议 registry/dimension 与语义 world Chunk 的最低且已有合法职责的公共模块。共享适配器统一 归入
`protocol-datapack`；client/server 继续通过现有 `api(project(":protocol-datapack"))` 自动向调用方暴露该能力。

依赖关系保持为：

```text
protocol-client ─┐
                 ├──api──> protocol-datapack ──api──> world-format
protocol-server ─┘                    └────api───────> protocol-model
```

不得为了本次整理：

- 新建 `protocol-world`、`protocol-client-world` 或 `protocol-server-world`；
- 让 `world-format` 反向依赖任何 protocol 模块；
- 给 `protocol-model` 增加 `world-format` 依赖；
- 使用 `expect`/`actual`、平台条件源码或 `compileOnly` 隐藏公开类型依赖；
- 保留旧 server 扩展作为 deprecated/转发别名；
- 移动 packet 编解码、连接、initial-world snapshot 或 Entity projection 的所有权。

## 3. 最终公开 API 形状

### 3.1 Dimension 到 Chunk 布局

保留当前转换函数的扩展形式和名称，但将唯一声明移动到 `protocol-datapack`：

```kotlin
package com.hiczp.minecraft.protocol.datapack

fun MinecraftDimensionLayout.toChunkLayout(): ChunkLayout
```

语义保持不变：

```kotlin
ChunkLayout(
    minSectionY = MinecraftCoordinates.sectionCoordinate(minY),
    sectionCount = sectionCount,
)
```

本次不同时增加 `MinecraftDimensionLayout.chunkLayout` 成员或扩展属性，避免为同一个无参数转换保留两种公开写法。

`MinecraftClientNegotiationResult.chunkLayout` 仍是 client 的高层便利属性，因为它表达“本次 Play Login 选中的 Chunk
布局”；其实现 改为委托唯一转换：

```kotlin
val chunkLayout: ChunkLayout = minecraftDimensionLayout.toChunkLayout()
```

server 代码和测试直接使用从 `protocol-datapack` 导入的同一扩展。

### 3.2 Protocol registry 到 world Chunk registry

将以下公开扩展移动到 `protocol-datapack`：

```kotlin
fun ProtocolRegistryContext.toChunkDataRegistries(
    defaultBlock: Identifier = Identifier("air"),
    defaultBiome: Identifier = Identifier("plains"),
): ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry>
```

建议放入新文件：

```text
protocol-datapack/src/commonMain/kotlin/com/hiczp/minecraft/protocol/datapack/MinecraftWorldChunkAdapters.kt
```

该文件统一拥有：

- `MinecraftDimensionLayout.toChunkLayout()`；
- `ProtocolRegistryContext.toChunkDataRegistries()`；
- 将 `BlockStateDescriptor` 与 `ProtocolBlockState` 互转所需的私有辅助函数；
- 将持久化 biome 名称与 `ProtocolRegistryEntry` 互转所需的私有辅助函数。

适配器必须继续保留当前行为：

- 默认 block/biome 通过调用方的 active `ProtocolRegistryContext` 解析；
- block descriptor 使用完整方块标识和 properties 查找状态；
- 只描述仍属于当前 block-state registry 的值；
- biome 名称必须是合法 `Identifier` 且存在于 active biome registry；
- 只为仍属于当前 biome registry 的 entry 返回持久化名称；
- 不缓存、复制或重建 `ProtocolRegistryContext` 中的大型不可变 registry。

`MinecraftClientConnection.chunkDataRegistries()` 继续留在 `protocol-client`，因为它是 connection-specific
convenience；它只委托：

```kotlin
protocolRegistryContext.toChunkDataRegistries(defaultBlock, defaultBiome)
```

`MinecraftChunkPacketEncoder.chunkDataRegistries` 继续属于 `protocol-server` 的 encoder，但改为调用相同的公共扩展。

## 4. 文件级实施步骤

### 阶段 A：在 `protocol-datapack` 建立唯一实现

1. 重新阅读根目录及 `protocol-datapack/AGENTS.md`，确认实施时依赖图未发生变化。
2. 新增 `MinecraftWorldChunkAdapters.kt`，迁入两项公开转换和共用的私有 registry 辅助逻辑。
3. 使用精确 imports；不要从 client/server 文件复制 packet encoder/decoder、palette packing、lighting 或 block-entity 逻辑。
4. 保持 `protocol-datapack/build.gradle.kts` 中现有 `api(project(":world-format"))` 和
   `api(project(":protocol-model"))`。除非仓库状态已经变化，否则本次不应修改依赖声明。
5. 在 KDoc 中说明转换使用 active dimension/registry context，不提供 release-global `ChunkLayout` 默认值，也不读取世界文件。

### 阶段 B：整理 `protocol-client`

1. 在 `MinecraftClientProtocol.kt` 中让 `MinecraftClientNegotiationResult.chunkLayout` 调用
   `minecraftDimensionLayout.toChunkLayout()`，删除手写 `MinecraftCoordinates.sectionCoordinate` 转换及不再使用的 import。
2. 在 `MinecraftWorldChunkProjection.kt` 中删除 client 私有的 `protocolChunkDataRegistries`、descriptor/identifier 辅助实现。
3. 保留公开的 `MinecraftClientConnection.chunkDataRegistries()`，但让它调用 `protocol-datapack` 的公共扩展。
4. `MinecraftChunkPacketDecoder`、`ChunkDataAndUpdateLightPacket.toChunk()`、packet position、palette unpacking、light
   decoding 和 Entity projection 均留在 `protocol-client`。
5. 不改变 negotiation、reconfiguration、active registry context 更新或 packet 生命周期。

### 阶段 C：整理 `protocol-server`

1. 从 `MinecraftWorldChunkProjection.kt` 删除原有 `MinecraftDimensionLayout.toChunkLayout()`。
2. 删除 server 私有的 `protocolChunkDataRegistries` 及只被它使用的 descriptor/identifier 辅助函数。
3. 让 `MinecraftChunkPacketEncoder.chunkDataRegistries` 调用 `protocol-datapack` 的公共
   `ProtocolRegistryContext.toChunkDataRegistries()`。
4. 保留 `MinecraftChunkPacketEncoder`、semantic Chunk 到 clientbound packet 的投影、`MinecraftChunkSnapshot`、initial-world
   和 Entity snapshot APIs；这些仍然是 server 的有限初始世界投影能力。
5. 不改变 palette packing、heightmap、lighting、block entity update tag 或 packet 顺序。

### 阶段 D：迁移和收紧测试所有权

在 `protocol-datapack/commonTest` 新增 `MinecraftWorldChunkAdaptersTest`，作为共享规则的唯一细粒度测试所有者：

1. 使用 `minY = -64`、`height = 384` 的 `MinecraftDimensionLayout`，验证转换结果为 `minSectionY = -4`、
   `sectionCount = 24`、`blockYRange = -64..319`。
2. 使用最小的 `ProtocolRegistryContext` 验证默认 air/plains、stone descriptor 解析和反向描述。
3. 验证 block properties 参与精确状态解析，而不是只按 block ID 选择任意状态。
4. 验证未知或格式错误的 block/biome 名称返回 `null`，而不是抛出或回退到默认值。
5. 验证不属于 active registry 的 block state/biome entry 不会被描述为可持久化值。
6. 验证显式 `defaultBlock`/`defaultBiome` 参数选择调用方指定的 registry entry。

调整现有测试：

- 将 `protocol-server/MinecraftWorldChunkProjectionTest.convertsDimensionBlockBoundsToAChunkLayout` 移除；该规则由
  `protocol-datapack` 的新测试拥有。
- `protocol-client/MinecraftWorldChunkProjectionTest` 保留 packet 解码场景，但不再承担整套 registry adapter 的重复细粒度断言；保留
  一项通过共享适配器成功建立 decoder registry 的集成断言即可。
- server 的 wire round trip、direct palette、lighting 和 block entity 测试保持原位，并通过 encoder 间接覆盖共享 registry
  adapter。
- client negotiation tests 继续验证 `MinecraftClientNegotiationResult.chunkLayout` 与
  `minecraftDimensionLayout.toChunkLayout()` 一致，证明高层便利属性没有形成第二套规则。

所有新增适配器测试放在 `commonTest`，不得只写 JVM 测试；本次问题本身涉及 Kotlin/Native 的依赖和 metadata 行为。

### 阶段 E：同步公开文档与 agent 指南

1. `protocol-datapack/README.md` 新增简短的 world Chunk adapter 小节，给出以下两个值的来源和用法：

   ```kotlin
   val chunkLayout = minecraftDimensionLayout.toChunkLayout()
   val chunkDataRegistries = protocolRegistryContext.toChunkDataRegistries()
   ```

   示例必须在这些语句前将 `minecraftDimensionLayout` 和 `protocolRegistryContext` 声明为参数、局部值或由紧邻的既有示例产生，
   不得留下来源不明的变量。

2. `protocol-client/README.md` 保持通过 `MinecraftClientNegotiationResult.chunkLayout` 构造 decoder 的简单示例，并说明它委托
   `protocol-datapack` 的规范 dimension 转换。
3. `protocol-server/README.md` 说明 encoder 使用同一 active registry adapter；不要把底层适配实现复制进示例。
4. `world-format/README.md` 中“从 negotiated protocol data 提供 `ChunkLayout`”的描述应链接或指向
   `protocol-datapack` 的转换入口。
5. 更新 `protocol-datapack/AGENTS.md`：该模块拥有由 Configuration dimension/registry facts 到
   `world-format` Chunk layout/registry contract 的无状态转换，但不拥有 packet、连接或文件系统行为。
6. 更新 `protocol-client/AGENTS.md` 与 `protocol-server/AGENTS.md` 中相关 world projection 描述，明确共享
   dimension/registry 适配来自 `protocol-datapack`，方向特定的 packet/connection/snapshot 行为仍由 client/server 拥有。
7. README 和 KDoc 不讨论尚未实现的新 bridge module，也不承诺 adapters 是可选依赖。

## 5. 验证顺序

不得并发运行 Gradle wrapper。先按最窄任务验证：

```shell
./gradlew :protocol-datapack:jvmTest
./gradlew :protocol-client:jvmTest
./gradlew :protocol-server:jvmTest
```

然后验证公共源码在 Web 和 host Native 上实际编译并执行：

```shell
./gradlew \
  :protocol-datapack:jsNodeTest \
  :protocol-datapack:wasmJsNodeTest \
  :protocol-datapack:linuxX64Test \
  :protocol-client:jsNodeTest \
  :protocol-client:wasmJsNodeTest \
  :protocol-client:linuxX64Test \
  :protocol-server:jsNodeTest \
  :protocol-server:wasmJsNodeTest \
  :protocol-server:linuxX64Test
```

最后执行仓库门禁：

```shell
./gradlew allTests
```

若全量运行因测试进程争用出现已知的浏览器 ping timeout，可按仓库指南使用有限 worker 重跑完整门禁，例如：

```shell
./gradlew allTests --max-workers=4
```

必须获得一轮完整成功结果，不能只用单独重跑失败任务代替最终 `allTests`。完成后运行 `git diff --check`，并确认：

- 仓库只剩一个 `MinecraftDimensionLayout.toChunkLayout()` 声明；
- 仓库只剩一个 `ProtocolRegistryContext.toChunkDataRegistries()` 核心实现；
- client/server 不再各自实现 block-state/biome registry adapter；
- 没有新增 `compileOnly`、自定义平台 source set 或 Gradle subproject；
- 文档中的包名、imports、API 名称和实际代码一致。

## 6. 非目标与停止条件

本次只是所有权和去重重构，不改变 Minecraft wire、NBT、Anvil、registry ID、dimension height 或 Chunk 语义。无需重新检查官方
Minecraft 实现，也不应借机修改 selected-release 行为。

以下情况出现时，接手 agent 应停止实施并先与用户讨论：

1. `protocol-datapack` 已不再以正常 `api` 依赖 `world-format`，而恢复它会造成新的依赖环。
2. 迁移发现共享适配器必须依赖 `protocol-client`、`protocol-server`、`protocol-session`、`protocol-transport` 或 `world-io`。
3. 为保持现有行为需要新增平台特定实现或 `expect`/`actual`。
4. 发现 client/server 的两份 registry adapter 实际存在有意的语义差异，而不是命名差异。
5. 需要改变 packet 编解码、initial-world 生命周期或持久化格式才能完成去重。

若触发上述任一条件，不要用转发别名、反射、源码复制或 `compileOnly` 绕过模块边界。

## 7. 完成标准

- client/server 使用者继续只依赖原有高层模块即可获得 Chunk layout 和 registry adapter 能力。
- `protocol-datapack` 成为两项规范共享转换的唯一实现所有者。
- client/server 的高层便利 API 仍存在，但全部委托共享实现。
- 没有新增模块、依赖环、平台 API 差异或 compatibility shim。
- portable tests 覆盖负 Y、registry 正反向映射、未知值和自定义默认值。
- README、KDoc、AGENTS 与最终代码一致。
- 最终 `./gradlew allTests` 完整通过。
