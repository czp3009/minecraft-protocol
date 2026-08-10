# Channel-first 连接 API、完全自定义协商与模组 Profile 计划

## 计划定位

- 状态：待实施。
- 目标版本：只面向仓库通过 `MinecraftTarget.MINECRAFT_VERSION` 选定的官方 Minecraft Java Edition
  发布版；模组加载器协议也必须锁定到与该发布版匹配的明确源码修订，不能用“latest”或跨版本猜测。
- 本计划是一次有意的破坏性 API 重构：先把 client/server 统一为由标准 Kotlin Channel 驱动的基础连接，再把原版协议协商、Configuration
  和主流模组协商做成建立在基础连接之上的普通扩展函数与可选 Profile。
- 本计划覆盖 `protocol-model`、`protocol-serialization`、`protocol-transport`、`protocol-session`、`protocol-client`、
  `protocol-server`、`protocol-vanilla-data`、新的模组协议集成模块、测试、发布元数据和全部相关 README/迁移文档。
- 本计划不实现 gameplay handler、事件总线、packet listener、actor/mailbox DSL 或另一套自定义流类型。
- 登录过程及最终凭证定义全部由 [cross-platform-online-authentication.md](cross-platform-online-authentication.md)
  负责。本计划只把该计划产出的最终登录凭证当作已完成输入，不重新设计或复述登录过程。
- 本计划中的 `Login` 只表示官方协议 state 或 loader custom-query 所在 phase；它不是账号登录流程的第二份设计。
- 浏览器 carrier 的独立限制继续由 [browser-websocket-transport.md](browser-websocket-transport.md) 负责；Channel API 不因
  TCP 或 WebSocket carrier 不同而改变。

## 最终架构原则

### 1. 基础 API 才是产品核心

`MinecraftClientConnection` 和 `MinecraftServerConnection` 首先是可独立使用的 packet connection。它们公开标准
`kotlinx.coroutines.channels` 类型，不要求用户学习本项目自创的 stream、intent、listener 或 handler 抽象：

```kotlin
interface MinecraftClientConnection : Closeable {
    val incoming: ReceiveChannel<ClientboundPacket>
    val outgoing: SendChannel<ServerboundPacket>
    val state: ConnectionState
    val registries: ProtocolRegistryContext
}

interface MinecraftServerConnection : Closeable {
    val incoming: ReceiveChannel<ServerboundPacket>
    val outgoing: SendChannel<ClientboundPacket>
    val state: ConnectionState
    val registries: ProtocolRegistryContext
}
```

这里的名字是计划级 API 草案，实施时可以按仓库命名规范微调；以下行为不能改变：

- `incoming` 是标准 `ReceiveChannel`，可以直接 `for (packet in connection.incoming)`。
- `outgoing` 是标准 `SendChannel`，可以直接 `connection.outgoing.send(packet)`。
- 不为它们再包一层项目专用 `IncomingPackets`、`PacketStream` 或 `receiveIntent()`。
- 使用方向受限的标准接口而不是把完整 `Channel` 两端都交给用户：用户不能从自己的 outgoing 抢走待发送 packet，也不能关闭由连接生产的
  incoming；这仍然是用户已经熟悉的 Kotlin Channel API。
- 一个连接内部只有一个 reader pump 和一个 writer pump。用户可以从任意 coroutine 调用 `send`，Channel
  决定排队顺序；如果业务并发产生了不合法协议顺序，责任属于用户代码，库不增加全局 action queue、actor 或隐式串行事务模型。
- transport、frame stream、raw socket 和可任意变更状态的 session 不再从高层 connection 公开。

### 2. 预制 `negotiate` 没有特权路径

这是本计划的硬性架构约束：

> 库提供的 `negotiate(profile)` 必须只是普通 Kotlin 扩展函数。它能完成的每一个动作，都必须能由用户代码通过同一组公开基础
> API 完成。

因此：

- `negotiate` 不成为 connection 成员，不在内部绕过 Channel 直接调用 `MinecraftSession.send/receive`。
- `negotiate` 只读取 `connection.incoming`、写入 `connection.outgoing`，直接消费关联计划产出的最终登录凭证，并调用公开的连接状态原语，例如协商后
  registry context 安装和已声明 extension codec 激活。
- 不存在 `internalNegotiate`、私有 packet lane、专供预制 Profile 使用的 session handle 或只有库代码能触发的状态迁移。
- 用户可以完全不调用任何 `negotiate`，自行写一个普通扩展函数：

```kotlin
suspend fun MinecraftClientConnection.negotiateWithMyServer(
    credential: MinecraftClientIdentity,
): MyNegotiationResult {
    // credential 由认证计划产出；这里只使用公开连接 API 编排 packet。
}
```

- 如果一个预制协商步骤无法用基础 API 表达，修复方法是补全一个窄型、安全、可公开的基础原语，而不是给预制函数开后门。
- 新增外部消费者编译测试：在只依赖已发布 API 的独立 source set 中重写一次原版协商的关键路径，防止实现无意依赖仓库内部对象。

### 3. “完全自定义协商”与“connection 管理 wire 状态”并不冲突

用户拥有 packet 顺序和流程控制权；connection 拥有只有 wire boundary 才能安全完成的物理状态。二者职责如下：

| 能力                                    | 用户或普通 `negotiate` 扩展负责                | connection/session 引擎负责                                                   |
|-----------------------------------------|------------------------------------------------|-------------------------------------------------------------------------------|
| 何时发送、等待和拒绝                    | 主动 `send`、`receive`、`for` 循环和普通控制流 | 不替用户选择流程                                                              |
| packet 顺序                             | 用户代码或 Profile 算法                        | 按 outgoing Channel 的实际接收顺序单 writer 编码                              |
| packet ID、方向与 phase                 | 选择 packet 类型                               | 依据当前 state 和 per-connection registry 编解码并校验                        |
| Handshake/Login/Configuration/Play 转换 | 发送或收到官方转换 packet                      | 在准确的 post-wire 边界原子更新 state                                         |
| compression                             | 发送/收到 `SetCompressionPacket`               | 只在该 packet 自身完成后启用后续 frame compression                            |
| 最终登录凭证                            | 把关联计划产出的 credential 作为协商输入       | 按关联计划定义的公共交接契约消费；本计划不展开内部步骤                        |
| 模组静态 registry                       | 提供本地 schema，处理或委托 loader sync        | 安装已解析的 per-connection context，并更新依赖 registry size 的 codec format |
| 自定义 packet                           | 启动连接前声明 codec，协商中决定激活哪些 route | 编码、解码、未知回退和 route snapshot 的原子切换                              |

“完全自定义”指用户能从 Handshake 开始自行编写全部协商控制流，并直接使用已经取得的最终登录凭证；不表示用户可以绕开
framing、compression 或 protocol state。凭证如何产生和消费完全服从关联认证计划。

## 目标用户代码

### 直接手写客户端协商

下面的示例故意不调用库提供的 `negotiate`，并从关联认证计划已经交付最终登录凭证的位置开始。凭证相关分支按关联计划最终提供的公开
API 编写，这里只展示本计划拥有的 Channel、registry 和 extension 部分：

```kotlin
val credential: MinecraftClientIdentity = finalLoginCredential
val connection = MinecraftClientConnection.connect(
    selectorManager = selectorManager,
    host = host,
    extensions = myExtensionCodecs,
    staticRegistries = myStaticRegistrySchema,
)

val configuration = MyConfigurationAccumulator()
while (connection.state != ConnectionState.PLAY) {
    when (val packet = connection.incoming.receive()) {
        // 使用 credential 的协议分支以认证计划最终 API 为准，此处不重复展开。
        is SetCompressionPacket -> Unit
        is RegistryDataPacket -> configuration.add(packet)
        is MyLoaderRegistryPacket -> configuration.add(packet)

        is FinishConfigurationPacket -> {
            connection.installRegistryContext(configuration.resolve())
            connection.activateExtensionRoutes(configuration.acceptedRoutes)
            connection.outgoing.send(AcknowledgeFinishConfigurationPacket)
        }

        is UnknownPacket.Clientbound -> handleUnknownDuringNegotiation(packet)
        else -> handleNegotiationPacket(packet)
    }
}

for (packet in connection.incoming) {
    handlePlayPacket(packet)
}
```

关键点：

- 本计划只要求 `credential` 是认证计划最终产出的公开类型，并能由 preset 与用户代码通过同一个公共交接入口消费；不规定其内部结构或处理过程。
- `SetCompressionPacket` 仍然作为普通 packet 交给用户；它的 post-read compression effect 在交付前已由 connection 完成，用户不调用
  `enableCompression`。
- registry 与 extension route 的安装也是公开、窄型、显式的基础 API；预制 loader Profile 调用同一方法。
- 用户可以把整个函数替换成自己的状态机、循环、parser 或 DSL，但不需要实现本项目的 callback interface。

### 直接手写服务端协商

`accept()` 只接受 raw connection，不自动运行预制协商，不接收 per-connection callback：

```kotlin
while (server.isOpen) {
    val connection = server.accept()
    launch {
        connection.use {
            myServerNegotiation(connection)
            for (packet in connection.incoming) {
                handlePlayPacket(connection, packet)
            }
        }
    }
}
```

服务端所需的最终认证配置或验证凭证同样直接来自关联认证计划。手写服务端协商与预制 server `negotiate`
使用其同一个公共交接契约；本计划不描述验证步骤。

### 使用库的预制协商

普通原版用户只需：

```kotlin
val result = connection.negotiate(credential)

for (packet in connection.incoming) {
    handlePlayPacket(packet)
}
```

模组用户按需增加一个独立 artifact 和 Profile：

```kotlin
val result = connection.negotiate(
    credential = credential,
    profile = FabricApi(
        staticRegistries = myModpackRegistries,
        extensions = myModPackets,
        onUnhandledQuery = ::answerMyModQuery,
    ),
)
```

建议的顶层签名为：

```kotlin
suspend fun MinecraftClientConnection.negotiate(
    credential: MinecraftClientIdentity,
    profile: ClientNegotiationProfile = Vanilla,
): MinecraftClientNegotiationResult

suspend fun MinecraftServerConnection.negotiate(
    profile: ServerNegotiationProfile = Vanilla,
): MinecraftServerNegotiationResult
```

- `MinecraftClientIdentity` 在这里代表认证计划最终交付的连接凭证契约；若关联计划冻结了不同名称，以关联计划为准，本计划只同步签名，不重新定义类型。
- `Vanilla` 提供 repository-selected official release 的默认流程。
- `FabricApi(...)`、`NeoForge(...)` 和 `Forge(...)` 来自可选集成模块，不让 `protocol-client` 或 `protocol-server` 默认依赖所有
  loader。
- Profile 是复用预制算法的途径，不是自定义协商的必经接口。只想手写流程的用户不需要实现 Profile。
- `negotiate` 返回前独占借用 incoming/outgoing。文档明确约定在它返回前用户不得从相同 incoming 读取或向 outgoing 写入；库不增加
  owner token、嵌套 intent 或 callback 层来强制这一点。违反约定造成的 packet 竞争由调用方负责。
- `negotiate` 返回后不遗留 packet listener、后台 gameplay callback 或 Profile task；连接重新完全由用户的 Channel 循环控制。

## 基础连接 API 的完整边界

### Channel 生命周期和失败语义

1. reader pump 成功解码 packet 后才发送到 incoming；incoming 的 backpressure 会停止继续读取新 frame，不建立无界队列。
2. writer pump 是唯一访问 frame sink 的 coroutine；它依次消费 outgoing，编码并写出 packet，再提交该 packet 的 post-wire
   effect。
3. outgoing 的 `send` 成功表示值已进入 Channel，不承诺已经 flush 到网络。需要观察 wire state 的代码使用公开的
   `awaitState(...)` 或明确的 state-commit completion，而不是访问 transport。
4. 远端 EOF、decode failure 或 transport failure 以 cause 关闭 incoming，并使后续 outgoing 操作失败；不通过 `onError` 回调报告。
5. 用户关闭 outgoing 时，writer 按约定 drain 已接受 packet 后关闭连接，或采用明确记录在 API 文档中的 immediate-close
   语义；实施前必须选择一种并对所有平台做一致测试，不能依赖 Ktor engine 偶然行为。
6. `connection.close()` 幂等，取消两个 pump，关闭 transport，并以结构化方式关闭两个 Channel；不会留下 detached coroutine。
7. 默认 capacity 使用一个有界、跨平台一致的值。高级构造配置可以接受 capacity，但不在普通连接对象上暴露可变 buffer 策略。

### 公开而窄型的状态原语

除 Channel、只读 state、只读 registry context、close，以及关联认证计划已经定义的最终凭证交接契约外，本计划只新增下列无法由普通
packet 值独立表达的基础 API：

1. `installRegistryContext(...)`
    - 只接受已经完整验证、不可变的 context。
    - 在进入 Play 前安装影响 block-state palette、biome palette 和其他 registry-indexed codec 的值。
    - 不允许用户直接改 `MinecraftProtocolFormat` 的任意字段。
2. `activateExtensionRoutes(...)`
    - 只能激活连接创建前已经声明并冻结的 codec route。
    - 允许 Login/Configuration 协商决定 Play 中哪些 channel 可解析为自定义 packet。
    - 不允许在 reader/writer 正在使用 registry 时并发替换 codec 实现。
3. `awaitState(...)` 或等价的只读完成机制
    - 仅等待 writer/reader 已提交的 state，不替用户发送或接收 packet。
    - 预制 `negotiate` 也必须通过这一公开能力确认返回时已经处于最终 state。

不公开以下通用危险入口：`configureCompression(threshold)`、`setState(...)`、可变 `format`、raw frame source/sink 或可替换运行中
registry。认证计划对其内部 transport 状态另有约束，本计划不重复定义。其余合法状态切换由 packet 和上述窄型原语驱动。

### 最终登录凭证的交接边界

- 本计划的输入是关联认证计划已经产出的最终客户端登录凭证，以及服务端已经完成配置的验证能力。
- 凭证的具体类型、语义和处理过程全部以关联认证计划为准。
- 本计划不得复制任何认证模型、helper 或实现。
- Channel-first `negotiate` 只直接接收最终凭证并调用认证计划提供的公共交接契约；用户手写协商可以调用完全相同的契约。
- 两份计划的集成测试只验证“同一个最终凭证能分别驱动手写协商和 preset 协商到达相同后续状态”，认证流程本身的正确性和边界测试只属于认证计划。

## Packet 扩展与 UnknownPacket

### 方向类型保持封闭但允许显式扩展

当前 `ClientboundPacket` 和 `ServerboundPacket` 是 sealed root，外部不能实现。为了既保留 exhaustive vanilla 分支，又允许用户
packet 直接出现在 Channel 中，增加一个公开 extension branch：

```kotlin
sealed interface ClientboundPacket : Packet {
    interface Extension : ClientboundPacket
}

sealed interface ServerboundPacket : Packet {
    interface Extension : ServerboundPacket
}
```

- vanilla generated packet 仍直接实现 sealed root/state marker。
- 用户自定义 packet 实现相应 `Extension`，不需要继承 transport wrapper。
- codec registration 同时校验 Kotlin 类型方向、协议方向和 state，错误在连接启动前失败。

### 统一未知 packet 模型

新增方向化、lossless 的统一模型，取代“未知顶层 ID 直接抛错”和只在 CustomPayload 内部出现的局部 unknown：

```kotlin
sealed interface UnknownPacket : Packet {
    val route: PacketRoute
    val data: ByteString

    data class Clientbound(
        override val route: PacketRoute,
        override val data: ByteString,
    ) : UnknownPacket, ClientboundPacket.Extension

    data class Serverbound(
        override val route: PacketRoute,
        override val data: ByteString,
    ) : UnknownPacket, ServerboundPacket.Extension
}
```

`PacketRoute` 至少表达：

- `TopLevel(state, packetId)`：当前 state 中没有注册 codec 的顶层 ID。
- `LoginQuery(state, transactionId, channel)`：带 channel 的 Login custom query；response 依靠 connection 保存的 query-ID
  关联恢复 route。
- `CustomPayload(state, packetId, channel)`：Configuration 或 Play 的 custom-payload route。

原始数据契约：

- 数据是 frame 已解密、解压并移除 packet ID/已解析 route header 后的剩余原始 payload；上限仍受 transport/frame 配置约束。
- 使用仓库已有的 immutable `ByteString` 保存，避免把可变 `ByteArray` 作为公开内部存储；提供零歧义的 `toByteArray()`
  获取方式，满足用户处理原始字节的需求。
- 未知 packet 原样再次发送时，registry 根据 route 重建外层 packet，并逐字节保留 `data`。
- 已注册 codec 遇到 malformed body 必须抛 decode error，不能静默退回 Unknown；Unknown 只表示 route 不认识，不表示已知协议损坏。
- 非法 Identifier、越界长度或无法关联的 Login response 仍然失败，不能用 Unknown 绕开 wire validation。

### 自定义 codec 的两级注册

模组生态绝大多数扩展通过原版 Login query 或 Configuration/Play CustomPayload 承载，因此公共注册 API 分两层：

1. 常用层：按 `state + direction + channel Identifier` 注册 bounded payload codec。
2. 高级逃生口：按 `state + direction + numeric packet ID` 注册顶层 packet codec，服务少数真正添加顶层 packet ID 的对端。

codec API 归 `protocol-serialization`，遵守物理层边界：

- 读写使用 `kotlinx.io.Source` 和 `Sink`，decode 必须取得明确 `byteCount` 并完整消费自己的 body。
- codec definition 是不可变值，连接创建时把 generated vanilla registry 与用户 definition snapshot 合成。
- 同一个 route 或 packet class 重复注册立即失败；默认不允许覆盖官方 codec。若确有代理场景需要覆盖，提供明确命名的 advanced
  opt-in，并在 route 中保留原始 ID。
- 对 CustomPayload 注册的 codec 在 Channel 边界被“提升”为用户的 `ClientboundPacket.Extension` 或
  `ServerboundPacket.Extension`；普通用户不会再收到一层 outer PluginMessage 后自行二次解析。
- 发送 extension packet 时 writer 根据注册 route 自动包装成相应 Login query/Configuration/Play CustomPayload wire
  packet。
- 同一份 registration metadata 同时驱动编码、解码、loader channel advertisement、方向/version 检查和协商后的 active route
  set，避免四份配置漂移。

### Per-connection registry，而不是全局 mutable registry

- `MinecraftPacketRegistry` 保留为 repository-selected vanilla base snapshot，不再由 `MinecraftSession` 硬编码为唯一
  registry。
- 每个 connection 获得不可变的 composed registry：`vanilla base + declared extension codecs`。
- 协商只切换 active route set 和 format context，不修改全局对象。
- Play 中 `minecraft:register`/`minecraft:unregister` 只激活或停用预先声明的 codec；未声明 channel 始终产生
  UnknownPacket，不在运行时并发构造 serializer。
- 用户如果故意并发调用 route activation 与发送导致 loader 语义错误，属于用户流程错误；引擎仍保证每次 packet 编解码看到一个完整
  snapshot，不看到半更新 Map。

## 模组静态 Registry 与 block-state ID

### 必须拆开的三类数据

1. `StaticRegistrySchema`
    - 本地已知的 block/item/entity 等逻辑条目，以及每个 block 的完整 state schema/order。
    - vanilla 实现由 `protocol-vanilla-data` 提供；模组客户端/服务端由使用方或 loader integration 输入。
2. `RemoteRegistrySnapshot`
    - loader 协商从 wire 得到的 raw-ID mapping、alias、override、blocked entry 等信息。
    - 只描述远端映射，不凭空包含某个 mod 的 block property/state 定义。
3. `ProtocolRegistryContext`
    - 用本地 schema 和远端 snapshot 解析出的 per-connection 最终视图。
    - 提供 codec 真正需要的 raw IDs、registry sizes、block-state global IDs、默认值和必要 identifier lookup。

不要把这三者与原版 Configuration 的 dynamic `RegistryDataPacket`/`ProtocolDataSet` 合并成一个含大量 nullable 字段的对象。

### 为什么不能只注入一个 block 数量

loader registry sync 通常发送 identifier 与 raw ID 的映射；global block-state ID 还需要按最终 block registry 顺序遍历每个本地
block 的 state definition 才能得到。仅从服务器映射无法推导未知 mod block 有多少 property、state 顺序和默认 state。因此：

- 完整语义模式要求用户提供与目标 modpack 匹配的 `StaticRegistrySchema`。
- `FabricApi` 使用远端 raw-ID map 重排本地 schema。
- `NeoForge` 处理 registry snapshot、aliases 及 loader 定义的额外同步信息。
- `Forge` 处理 registry mapping、aliases、overrides/blocked entries 和相应确认流程。
- 可以另设明确命名的 opaque/proxy 模式，只接受各 registry 和 block-state palette 的最终 size，让未知 chunk 数据保持
  raw；该模式不得声称能把未知 state ID 解析成逻辑 block。

### 清除当前 vanilla 写死点

实施必须搜索并修改所有从 `VanillaStaticData` 或固定常量无条件取得连接相关 registry size/ID 的位置，至少包括：

- `MinecraftProtocolFormatConfiguration` 中 block-state/biome 默认尺寸。
- `MinecraftClientProtocol` 当前为 Play format 写入 `VanillaStaticData.blockStates.size` 的路径。
- `MinecraftInitialWorld`、`MinecraftChunkSnapshot`、`MinecraftEntitySnapshot` 及 server Play Login/初始视图中的 vanilla
  ID 假设。
- chunk palette、biome palette、entity/container/data-component 等任何由静态 registry 索引的 serializer。

原版便捷 API 仍可默认使用 `VanillaStaticData` 提供的 schema；一旦 connection 带有协商后 context，高层 client/server 和
world projection 必须从该 context 取值，不得回退到全局 vanilla 数量。

## Negotiation Profile 模型

### Profile 是预制算法，不是基础能力

Profile 只用于组合库提供的 `negotiate`：

- 原版协议协商仍由普通扩展函数实现；涉及登录凭证的部分只调用关联认证计划的公共交接契约。
- Profile 提供 loader 在 Login、Configuration 和进入 Play 前增加的步骤、内建 payload codecs、channel metadata、registry
  resolver 和少量 policy。
- Profile 执行时得到的能力不超过公共 connection base API。
- Profile 可以是有状态对象，以跟踪 query transaction、configuration task 和 registry fragments；生命周期只覆盖一次
  `negotiate` 调用。
- Profile 不能注册 Play callback；返回后只留下 immutable registry context 与 active codec routes。

为避免 loader 模块反向依赖或把 client/server 两个 sibling 全部带入，side-neutral 的 Profile SPI 和 packet-exchange
capability 放在 `protocol-session` 或一个更窄的、无 I/O 的协商 SPI 模块。实施时优先使用 `protocol-session`
，因为它已经拥有方向、state、typed dispatch 与 route activation；只有在公共 ABI 审核证明职责过重时才拆出新模块。

### 可接受的唯一 callback 范围

预制 Profile 无法认识某个 mod 自己的 custom query 时，可以接受一个 negotiation-scoped suspend callback：

```kotlin
onUnhandledQuery: suspend (UnknownPacket) -> NegotiationQueryResult
```

约束：

- loader 自身的标准 query 先由 preset 处理，callback 只接收未处理项。
- result 明确表达 respond/pass/reject，并可带零个、一个或多个返回 packet；runner 仍通过 outgoing 顺序发送。
- callback 只在 `negotiate` 的同步控制流中调用，不被缓存，不在 Play 中触发。
- 需要任意多阶段行为的用户可以实现自定义 Profile，或完全绕过 Profile 手写 Channel 协商；不继续堆叠 `onLoginQuery`、
  `onConfigurationTask`、`onPacket` 等多层 callback。

### 原版 Profile

`Vanilla` 是默认值，覆盖：

- Status（服务端返回 `StatusCompleted`）与 Login/Transfer intent 分流。
- 直接消费最终登录凭证；凭证验证和 Login 内部步骤完全委托给关联认证计划，本计划不枚举 packet 或状态细节。
- compression 和进入 Configuration 后的标准协议协商。
- cookie、known packs、feature flags、dynamic registry data、tags、resource packs、code of conduct、Configuration finish。
- server admission/status/resource-pack 等确需应用决策的事项移入 negotiation-scoped policy；有简单固定值时优先用 data
  property，而不是 callback。
- 进入 Play 前安装 vanilla `ProtocolRegistryContext`，随后发送/读取 Play Login。

### Fabric 预制品的真实范围

源码审计显示 Fabric Loader 本体不定义一个所有 Fabric 连接都必须执行的统一网络握手；通用网络扩展由 Fabric API 和具体 mod
实现。因此公开名称使用 `FabricApi`，并明确能力边界：

- 支持 Fabric API Login custom-query 注册与 transaction 关联。
- 支持 Configuration/Play 的 `minecraft:register`、`minecraft:unregister`。
- 支持 common channel 协商 `c:version`、`c:register`。
- 支持 Fabric registry sync 的 payload、完成确认和 raw-ID remap。
- 支持 Fabric split/passthrough 等 repository-selected revision 确实需要的内建 payload。
- 具体 mod 自定义协议通过用户注册 codec 和单个 unhandled-query policy 接入。
- 只有本地 schema 足够时才生成完整 modded block-state context；否则明确返回缺失 schema 错误或进入用户显式选择的 opaque
  模式。

### NeoForge 预制品

按照匹配源码修订实现 Configuration task 顺序，而不是只发送一个“我是 NeoForge”标记：

- 初始 `minecraft:register/unregister` 与 common `c:version/c:register`。
- `neoforge:register` query、`neoforge:network` channel metadata、版本/方向/optional component 判定及 failure payload。
- feature flags、extensible enum、registry data map、static/frozen registry、configuration file 等当前 revision 实际注册的
  task/payload。
- registry snapshot、aliases 和 block-state context 解析。
- negotiation result 暴露最终选中的 channel versions、远端组件和 registry diagnostics；Play 只激活已协商 route。

### Forge 预制品

Forge 在 repository-selected release 仍是独立协议实现，不能把 NeoForge profile 改名复用：

- Handshake hostname 的 Forge 标记及 vanilla/Forge intent 判定。
- mod versions、channel versions、vanilla channel list。
- registry sync、aliases、overrides/blocked entries、configuration sync 和逐 task acknowledgement。
- Forge custom payload/splitter 与 Play channel 激活。
- 版本、mandatory channel 或 registry 不兼容时返回 typed negotiation failure，不在 decode 深处抛无上下文异常。

### Quilt 的处理

Quilt 官方已经停止为新 Minecraft 版本维护 QSL/QFAPI。repository-selected release 不提供一个可以独立验证的当前 Quilt
协商实现，因此本计划不承诺第一方 `Quilt` Profile：

- 若目标 Quilt 环境实际使用 Fabric API 兼容网络层，用户显式选择 `FabricApi`，文档不把它宣传成完整 Quilt 支持。
- 将来只有在存在与 repository-selected release 匹配、仍维护且可端到端验证的独立协议源码时再增加单独 artifact。

## 模块所有权与依赖图

### `protocol-model`

- 增加 `ClientboundPacket.Extension`、`ServerboundPacket.Extension`。
- 增加逻辑 `PacketRoute`、方向化 `UnknownPacket` 和不可变 registry schema/context value types。
- 模型保持 buffer/I/O-free；raw payload 使用 immutable byte value，不引入 Ktor socket 或 serializer implementation。
- 区分 static registry schema、remote snapshot、resolved context 与 dynamic Configuration registry packet。

### `protocol-serialization`

- 公开经过边界审计的 packet/payload codec registration API。
- 支持 generated vanilla base 与 immutable extension snapshot 合成。
- 支持 Login query、Configuration/Play CustomPayload 提升与 raw top-level ID escape hatch。
- 实现 UnknownPacket decode/encode round trip、known-malformed failure 和严格 byte-count consumption。
- 让 `MinecraftProtocolFormat` 从 per-connection `ProtocolRegistryContext` 取得 palette/registry 参数。

### `protocol-transport`

- 继续只负责 Ktor socket/carrier、frame 和 compression envelope；认证计划已有的 transport 行为不在本计划重复修改。
- 若当前 `sendPacketData` 返回点不能证明完整 frame 已写入，则增加内部的 frame-commit primitive；它不认识 packet、profile
  或身份。
- 保证 writer 能在一个 frame 完成与下一个 frame 开始之间原子提交本计划负责的 compression/state effect。
- 不把 compression control 重新暴露给高层用户。

### `protocol-session`

- 持有 per-connection codec registry、direction、state、format context 和 post-wire effect state machine。
- 建立唯一 reader/writer pump，并把 typed Channel facets 提供给高层 connection。
- 移除对全局 `MinecraftPacketRegistry` 的硬编码。
- 把未知 route 送入 Channel，而不是因缺 codec 关闭连接。
- 实现 route activation snapshot、registry context install 和普通协议 state commit。
- 定义 loader-neutral Profile SPI/phase context；SPI 不能引入 client/server sibling 依赖或重新定义登录凭证。
- `MinecraftSession` 若继续作为独立低层 API，必须收窄危险 mutation；高层 connection 不再公开其实例。

### 与 `protocol-auth` 的交接

- 本计划不修改 `protocol-auth` 的登录流程、最终凭证或测试矩阵。
- `protocol-client`/`protocol-server` 只依赖关联认证计划最终发布的 credential/verification contract。
- 如果关联计划调整最终类型名称，本计划只同步 connection 扩展签名和示例，不在本模块群中增加 adapter credential 或复制字段。
- 手写协商和 preset 协商必须能消费同一个最终凭证实例。

### `protocol-client`

- `connect()` 返回 raw Channel-first connection，不自动运行预制协商。
- `negotiate` 扩展直接接受关联认证计划的最终登录凭证；connection 基础 API 实现 registry/context 安装、route activation 和
  state observation。
- 原版 `queryStatus` 与 `negotiate` 成为只使用公开基础 API 的扩展函数。
- 移除 `MinecraftClientHandler.onPacket` 及长生命周期 handler 主入口；cookie/resource-pack/query policy 只存在于
  negotiation options/Profile。
- 不再从 connection 公开 `socket`、`transport`、`session`、`protocol`。

### `protocol-server`

- `bind()` 只建立 listener；`accept()` 每次主动返回 raw Channel-first connection。
- 关联认证计划产出的服务端验证能力作为已配置依赖交给 connection；profile admission/status/configuration policy 是
  `negotiate` 参数，不挂在 listener callback 上。
- 原版 server `negotiate` 只调用公开基础 API，并返回 `StatusCompleted` 或 `PlayReady` 等显式结果。
- 移除 `MinecraftServerHandler.onPacket` 风格入口和隐藏 gameplay dispatch。
- 初始 chunk/entity projection 接受 connection registry context；vanilla convenience 仍保留简单默认值。

### `protocol-vanilla-data`

- 实现通用 `StaticRegistrySchema` 的 vanilla provider。
- 继续拥有 generated、version-matched vanilla data，不反向依赖 client/server/session。
- 提供从 vanilla schema 与官方 Configuration data 产生默认 `ProtocolRegistryContext` 的明确入口。

### 可选 loader 模块

新增三个独立发布模块：

- `protocol-fabric`
- `protocol-neoforge`
- `protocol-forge`

依赖约束：

- 它们依赖 `protocol-model`、`protocol-serialization`、`protocol-session` 和确实需要的 vanilla-data 下层能力；loader
  Profile 不依赖或包装登录凭证。
- 它们不依赖 `protocol-client` 或 `protocol-server`，而是实现 loader-neutral 的 client/server Profile SPI；这样只消费
  client + Fabric 的应用不会被迫把 server orchestration 带入运行时。
- `protocol-client`、`protocol-server` 也不反向依赖这三个模块；应用在顶层组合 connection 与 Profile。
- public signature 出现的下层类型使用 `api`，其余用 `implementation`。每个模块增加 published-metadata 检查与外部消费者
  smoke test。
- loader 源码只作为人工证据放在 `temp/`，不成为 Gradle 输入，不复制进发布物，不添加运行时 loader 依赖。

预期组合关系：

```text
application
├── protocol-client 或 protocol-server
└── protocol-fabric / protocol-neoforge / protocol-forge（按需）
        └── protocol-session
                └── protocol-serialization
                        └── protocol-model
```

## 现有 API 迁移

### 删除或降级的旧主入口

目标最终状态不同时保留两套同等地位的 orchestration：

- `MinecraftClientProtocol.login(...)` → `MinecraftClientConnection.negotiate(...)` 扩展。
- `MinecraftClientProtocol.queryStatus(...)` → connection 扩展，内部只用 Channel。
- `MinecraftServerProtocol.negotiate()` → `MinecraftServerConnection.negotiate(profile)` 扩展。
- `MinecraftClientHandler`、`MinecraftServerHandler` → negotiation-scoped data/policy/Profile；删除 Play `onPacket`。
- connection 上的 `protocol/session/socket/transport` → internal implementation detail。
- 全局固定 `MinecraftPacketRegistry` 使用点 → connection-specific composed registry。

如果仓库发布策略要求一个过渡版本，可提供标记为 `@Deprecated` 的薄适配器，但必须满足：

- 适配器本身调用新 Channel API，不保留旧 protocol engine。
- 新 README、示例、测试和 public contract 不再把 handler API 当首选。
- 明确给出移除版本，不无限期维护两套生命周期。
- 若当前发布阶段允许直接 breaking change，优先一次性删除，避免用户在两套模型间选择。

### 配置对象重新分层

- Transport 配置：frame limits、compression bounds、Channel capacity、carrier。
- Connection 声明配置：extension codec definitions、static registry schema；认证能力只引用关联计划的最终 contract。
- Negotiation 配置/Profile：final credential、status/admission policy、known packs、resource packs、loader tasks、unhandled
  query。
- Play 数据：只通过 Channel 和最终 `ProtocolRegistryContext`，不继续引用 negotiation handler。

## 分阶段实施顺序

### 阶段 0：冻结协议事实和基础 API 契约

1. 用 `./gradlew -q minecraftVersion` 记录当前选择，仅用于本地审计，不把字面版本复制进公共文档。
2. 重新分析 matching official server 的 state transition、packet codec 和 compression；登录相关事实直接采用认证计划的结果。
3. 锁定 Fabric API、NeoForge、Forge 与目标发布匹配的具体 commit/coordinate；每个 loader 建立 payload
   ID、方向、phase、version、optional/mandatory 和 configuration task 顺序表。
4. 对 Fabric Loader 本体做负向审计，确认哪些行为确实属于 Loader、哪些属于 Fabric API/具体 mod，避免虚构统一 Fabric
   handshake。
5. 与认证计划冻结最终 credential/verification contract 的唯一交接点，证明 preset 与外部手写代码都能调用，且本计划没有复制
   credential 类型。
6. 完成 Login query transaction correlation、unknown custom payload raw-byte boundary和 custom top-level ID collision
   policy spike。
7. 完成 static schema + remote snapshot → block-state global ID 的三套 loader 算法说明，再冻结公共 registry types。

退出条件：API review 能用一段完全位于外部 consumer source set 的代码消费关联计划的最终登录凭证，并继续只靠公开
Channel/连接原语完成自定义协商；任何需要 internal 方法的步骤都视为设计未完成。

### 阶段 1：逻辑模型与 vanilla registry provider

1. 在 `protocol-model` 增加 extension branches、routes、UnknownPacket 和 registry value algebra。
2. 为所有 byte-containing value 定义 defensive/immutable ownership、equals/hashCode 和安全 `toString`。
3. 在 `protocol-vanilla-data` 实现 vanilla `StaticRegistrySchema` 与默认 resolved context。
4. 为 block raw-ID reorder、state iteration、default state 和缺失 schema 写 portable common tests。
5. 保持生成逻辑只生成 deterministic vanilla data，不提交 generated Kotlin。

### 阶段 2：可组合 serialization registry

1. 把 `PacketCodec`/payload codec 的可公开部分重新设计为 bounded Source/Sink API。
2. 让 generated vanilla registry 成为不可变 base，增加 connection builder 的 collision/方向/state 校验。
3. 实现 CustomPayload lifting、Login query correlation、raw top-level codec 和 Unknown fallback。
4. 让 format configuration 接收 immutable `ProtocolRegistryContext`，清除硬编码 registry sizes。
5. 增加 encode/decode round trip、unknown round trip、malformed known packet、duplicate registration 和 payload size tests。

### 阶段 3：session engine 与 Channel pumps

1. 将 `MinecraftSession` 改为使用注入的 composed registry 和 format context。
2. 建立结构化 reader/writer jobs，向 connection 暴露标准 `ReceiveChannel`/`SendChannel` facets。
3. 把现有 post-wire state effects 移到明确的 packet commit pipeline。
4. 实现 compression/state commit、错误传播、registry context/active route snapshot 的公开安装方法和只读 state wait。
5. 为 EOF、cancel、close、backpressure、buffered outgoing 与 transition packet 后紧跟 packet 的情况写 `runTest` 测试，不使用
   delay/sleep。

### 阶段 4：raw client/server connection

1. 重写 client `connect` 和 server `accept`，构造 pump 后立即把 raw connection 交给用户。
2. 收起 raw socket、transport、protocol/session handles。
3. 直接接入认证计划最终提供的 credential/verification contract，不在 client/server 新增中间凭证模型。
4. 写两个完全不调用 `negotiate` 的 scripted-peer 测试：一个客户端、一个服务端；它们从最终登录凭证交接点开始，后续全部由测试代码主动收发。
5. 增加交接 smoke：同一最终凭证可供手写流程与 preset 使用；登录过程仍只由认证计划测试。

### 阶段 5：只靠基础 API 重写 Vanilla negotiate

1. 把 status、最终登录凭证交接后的标准协商、compression、Configuration 和 Play 入口写成 connection 扩展函数；不在这里重写登录实现。
2. 扩展函数源码只能引用公开 connection contract、认证计划最终 credential contract、public packet/model types 和 standard
   Channel API；禁止导入 internal engine。
3. 将现有 handler 决策收敛为简单 options、negotiation policy 和一个 unknown-query fallback。
4. 对同一 scripted peer 分别运行“完全手写”和“Vanilla preset”，断言 wire transcript、state、result、registry context 完全一致。
5. 使用 official server 和 headless official client 验证 status、transfer、Configuration 及最终凭证交接后的连接路径；认证模式自身的
   interoperability 仍由认证计划验收。

### 阶段 6：Fabric API Profile

1. 新增 `protocol-fabric` 及其模块 `AGENTS.md`、README、KMP/publication 配置。
2. 实现 Fabric API standard networking codecs 和 phase driver。
3. 实现 common channel advertisement、Login query、registry sync、split payload 及 Play route activation。
4. 用 source-derived golden byte fixtures 和 scripted Fabric peers 覆盖 client/server 两侧。
5. 用至少一个明确版本、固定 mod set 的真实 Fabric client/server fixture 验证；若当前 Fixture Host 尚不能表达该资源，先按仓库标准扩展
   exact-version producer/RPC scenario，不添加独立 CLI 或 latest discovery。

### 阶段 7：NeoForge Profile

1. 新增 `protocol-neoforge` 及其模块文档/发布配置。
2. 按源码 task graph 实现 register/network metadata、feature/enum 检查、registry/data-map/config sync 和 failure reply。
3. 解析 remote registry snapshot，与用户 schema 合成完整 context。
4. 覆盖 optional/mandatory channel、version mismatch、alias、未知 mod payload 和 Play activation。
5. 与固定 revision 的真实 NeoForge client/server 做双向 interoperability。

### 阶段 8：Forge Profile

1. 新增 `protocol-forge` 及其模块文档/发布配置。
2. 实现 Forge hostname marker、mod/channel versions、registry/config task 和 splitter。
3. 覆盖 mappings、aliases、overrides、blocked entries 与拒绝诊断。
4. 与固定 revision 的真实 Forge client/server 做双向 interoperability。
5. 明确测试 Forge 与 NeoForge profile 不能误互通，避免共享名称掩盖协议差异。

### 阶段 9：迁移所有高层使用点和文档

1. 更新 root README、client/server/session/serialization/vanilla-data README 和示例。
2. 只按认证计划最终产物更新跨计划链接和 connection 调用示例；不修改或复制认证计划的实现与验收范围。
3. 更新 server initial world/chunk/entity API，显式接受或继承 connection registry context。
4. 删除或按发布策略 deprecate handler/protocol primary API，增加迁移表。
5. 搜索所有 `VanillaStaticData.blockStates.size`、固定 biome/block 数量和全局 packet registry 使用点，逐一证明保留或迁移。
6. 检查所有发布 POM/module metadata、runtime classpath 和 source JAR，保证新模块可独立消费。

## 验证矩阵

### 单元与 portable 行为

- `UnknownPacket.Clientbound` 与 `UnknownPacket.Serverbound` 的顶层 ID、Login query、Configuration/Play payload round
  trip。
- 已知 route 的 trailing bytes、truncated body、超限 collection、非法 Identifier 必须 typed failure。
- extension codec 的 state/direction/class collision 和 active/inactive route。
- connection registry snapshot 不受构造后原始 mutable collection 修改。
- vanilla、Fabric、NeoForge、Forge remote mapping 与 local schema 合并后的 block-state global ID。
- 缺失 mod block schema 不得悄悄使用 vanilla size。
- incoming backpressure、outgoing FIFO、多个 sender 的合法 Channel 行为和用户造成的协议顺序错误。
- close/cancel/EOF/failure cause 在所有 Channel 与 transport job 间传播一致。

### 手写协商与 preset 等价性

每个支持的 Profile 都至少有两条测试入口：

1. 测试代码只调用基础 API 手写流程。
2. 测试代码调用 `connection.negotiate(profile)`。

两者对同一 peer 必须产生相同的：

- 本计划范围内的 wire packet transcript 与 post-wire compression/state 边界；
- 最终 connection state；
- active extension routes；
- resolved registry context；
- credential handoff 之后的 result/failure 类型。

这项等价性是“预制协商没有特权”的可执行验收条件，不只是文档约定。

### 官方与 loader interoperability

- official server：status、最终凭证交接后的 Configuration、进入 Play、未知 Play payload。
- official headless client：server status、最终验证能力交接后的 Configuration、Play Login 与初始 view。
- 登录 interoperability 不在本矩阵重复列出，直接采用认证计划的完成证据。
- Fabric API、NeoForge、Forge：各自固定 revision 的 client 和 server 两侧；至少覆盖无额外 mod 与一个含自定义 registry/custom
  payload 的固定 mod set。
- mod fixture 的版本是独立 build input，不从 Minecraft 版本字符串推导，不在正常构建中查询 latest。
- 外部 fixture 仍通过标准 KMP test entry 和 Fixture Host RPC，禁止测试直接持有 process/path；只有既有 world-io backdoor
  例外，不扩散到本计划。

### 推荐 Gradle 顺序

不并发运行 Gradle wrapper。每一阶段先执行受影响 JVM suite：

```shell
./gradlew :protocol-model:jvmTest
./gradlew :protocol-serialization:jvmTest
./gradlew :protocol-transport:jvmTest
./gradlew :protocol-session:jvmTest
./gradlew :protocol-client:jvmTest
./gradlew :protocol-server:jvmTest
./gradlew :protocol-vanilla-data:jvmTest
./gradlew :protocol-fabric:jvmTest
./gradlew :protocol-neoforge:jvmTest
./gradlew :protocol-forge:jvmTest
```

稳定后运行：

```shell
./gradlew :minecraft-test-fixture-host:test jvmTest
./gradlew allTests
```

- 机器内存不足时每次命令显式加合适的 `--max-workers=<count>`。
- socket/process coroutine tests 使用 `runTest`、Channel、`CompletableDeferred`、`join` 或 observed readiness，不使用
  delay、sleep 或概率性 timeout 排序。
- 新模块增加外部消费者编译/运行 smoke，验证只带声明的 published dependencies 就能构造 Profile 和注册 custom packet。

## 完成条件

以下全部成立才算完成：

1. client/server 用户可以直接对标准 typed Channel 做 `for` 和 `send`，没有 handler/listener/intent 前置要求。
2. 一个外部消费者可以完全不调用本库 `negotiate`，直接消费认证计划产出的最终登录凭证，并从 Handshake 开始手写协商直至进入
   Play。
3. 本计划没有重新定义最终登录凭证或登录过程；这些内容只有关联认证计划一个 owner。
4. 库的 Vanilla/FabricApi/NeoForge/Forge `negotiate` 只调用相同公开基础 API，并通过手写/preset transcript 等价测试。
5. `negotiate` 返回前的独占借用规则和用户并发责任写入 KDoc/README；库没有为此引入 actor、callback 或 intent 层。
6. 未注册顶层 packet、Login query 和 custom payload 都以方向正确、raw bytes 完整的 UnknownPacket 到达 incoming，并可原样发回。
7. 用户注册的自定义 packet codec 能让 incoming 直接产出用户 packet subtype，outgoing 直接接受该 subtype。
8. known-malformed packet 不会被 Unknown fallback 掩盖。
9. registry context 是 per-connection；modded block-state/biome palette 不再读取全局 vanilla size。
10. Fabric API、NeoForge、Forge preset 各自按锁定源码 revision 通过 client/server interoperability；Quilt 的不支持范围明确。
11. Play 阶段没有由库或 Profile 注册的用户 callback，所有 packet 继续由用户主动读取。
12. public signatures、Gradle dependency scopes、source JAR、POM/module metadata 和外部消费者 smoke 证明各发布模块独立可用。
13. repository JVM pass 和适用的多平台 tests 全部通过，没有提交 generated Kotlin、`temp/` 研究副本或 fixture scratch。

## 风险与实施时禁止的捷径

- 不要在本计划新增 credential wrapper 或登录 helper；直接使用关联认证计划的最终公共 contract。
- 不要为了“手写协商”公开通用 `setState`；协议 state 仍由合法 packet transition 驱动。
- 不要让预制 `negotiate` 继续直接操作 `MinecraftSession`，再声称用户可以“近似”复刻。
- 不要把 CustomPayload 的 outer packet 继续暴露给普通用户并要求二次 parse；注册 codec 后必须在 Channel 边界提升成自定义
  packet。
- 不要把所有未知 decode exception 吞成 UnknownPacket。
- 不要让 loader Profile 在 Play 安装 callback 或全局 mutable codec registry。
- 不要假设 Fabric Loader 等于 Fabric API，也不要把 NeoForge 和 Forge 当作同一握手的两个名字。
- 不要根据 registry wire mapping 猜 mod block-state schema。
- 不要让 `protocol-client`/`protocol-server` 聚合所有 loader artifact。
- 不要创建一套与 `ReceiveChannel`/`SendChannel` 平行的 project-specific stream API。
- 不要通过 delay 解决 compression/state race；这些都必须由明确 frame commit 和 Channel ordering 证明。

## 查证依据

协议事实的优先级仍遵守仓库 `AGENTS.md`。原版以 matching official server JAR 为准；loader 扩展以对应 loader/API 的匹配源码
revision 为准。以下链接是本计划形成时的源码证据，实施时要重新确认它们仍与 repository-selected release 匹配：

### Fabric

- [Fabric API networking common version/register tasks](https://github.com/FabricMC/fabric/blob/3e8afef37972285085fd0f7e414bcfcca487ed3c/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/impl/networking/CommonPacketsImpl.java)
- [Fabric API register/unregister channel setup](https://github.com/FabricMC/fabric/blob/3e8afef37972285085fd0f7e414bcfcca487ed3c/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/impl/networking/NetworkingImpl.java)
- [Fabric Login networking API](https://github.com/FabricMC/fabric/blob/3e8afef37972285085fd0f7e414bcfcca487ed3c/fabric-networking-api-v1/src/main/java/net/fabricmc/fabric/api/networking/v1/ServerLoginNetworking.java)
- [Fabric registry sync manager](https://github.com/FabricMC/fabric/blob/3e8afef37972285085fd0f7e414bcfcca487ed3c/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/impl/registry/sync/RegistrySyncManager.java)
- [Fabric registry state-ID tracker](https://github.com/FabricMC/fabric/blob/3e8afef37972285085fd0f7e414bcfcca487ed3c/fabric-registry-sync-v0/src/main/java/net/fabricmc/fabric/impl/registry/sync/trackers/StateIdTracker.java)
- [Fabric Loader source tree audited separately](https://github.com/FabricMC/fabric-loader/tree/b907c5b292fc062d75b6d8bf8255ac200109b992)

### NeoForge

- [NeoForge configuration task ordering](https://github.com/neoforged/NeoForge/blob/f025ddcf40a546ebdaa5e809323753f6e5f460af/src/main/java/net/neoforged/neoforge/network/ConfigurationInitialization.java)
- [NeoForge channel registry and register/unregister behavior](https://github.com/neoforged/NeoForge/blob/f025ddcf40a546ebdaa5e809323753f6e5f460af/src/main/java/net/neoforged/neoforge/network/registration/NetworkRegistry.java)
- [NeoForge network component negotiation payload](https://github.com/neoforged/NeoForge/blob/f025ddcf40a546ebdaa5e809323753f6e5f460af/src/main/java/net/neoforged/neoforge/network/payload/ModdedNetworkPayload.java)
- [NeoForge static registry sync task](https://github.com/neoforged/NeoForge/blob/f025ddcf40a546ebdaa5e809323753f6e5f460af/src/main/java/net/neoforged/neoforge/network/configuration/SyncRegistries.java)
- [NeoForge registry snapshot model](https://github.com/neoforged/NeoForge/blob/f025ddcf40a546ebdaa5e809323753f6e5f460af/src/main/java/net/neoforged/neoforge/registries/RegistrySnapshot.java)

### Forge

- [Forge configuration task assembly](https://github.com/MinecraftForge/MinecraftForge/blob/d17cfd0b4bbfd192a9007f02240032f19b9b340d/src/main/java/net/minecraftforge/network/tasks/ForgeNetworkConfigurationHandler.java)
- [Forge mod version task](https://github.com/MinecraftForge/MinecraftForge/blob/d17cfd0b4bbfd192a9007f02240032f19b9b340d/src/main/java/net/minecraftforge/network/tasks/ModVersionsTask.java)
- [Forge channel version task](https://github.com/MinecraftForge/MinecraftForge/blob/d17cfd0b4bbfd192a9007f02240032f19b9b340d/src/main/java/net/minecraftforge/network/tasks/ChannelVersionsTask.java)
- [Forge registry sync task](https://github.com/MinecraftForge/MinecraftForge/blob/d17cfd0b4bbfd192a9007f02240032f19b9b340d/src/main/java/net/minecraftforge/network/tasks/SyncRegistriesTask.java)
- [Forge handshake hostname patch](https://github.com/MinecraftForge/MinecraftForge/blob/d17cfd0b4bbfd192a9007f02240032f19b9b340d/patches/minecraft/net/minecraft/server/network/ServerHandshakePacketListenerImpl.java.patch)

### Quilt 与 API 设计参考

- [Quilt 官方关于停止 QSL/QFAPI 更新的说明](https://quiltmc.org/en/blog/2026-02-03-non-obfuscated-updates/)
- [Ktor ClientWebSocketSession 的标准 incoming/outgoing Channel API](https://api.ktor.io/ktor-client-core/io.ktor.client.plugins.websocket/-client-web-socket-session/index.html)
- [Minestom](https://github.com/Minestom/Minestom) 用于比较 transport/connection 与上层协商接线；不作为本库 callback API
  模板。
- [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib) 用于比较 packet protocol/registry 与 session listener 边界；其
  listener 风格不被本计划采用。
- [KorGE](https://github.com/korlibs/korge) 及 Korlibs 网络实现只用于比较 Kotlin Multiplatform 资源所有权和 coroutine
  接线，不作为 Minecraft 协议事实来源。

本地源码研究副本只能保留在被忽略的 `temp/` 下；Gradle、generator、测试和发布物不得读取它们。
