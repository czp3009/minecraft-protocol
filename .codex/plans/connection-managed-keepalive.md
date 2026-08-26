# 连接核心重构与官方 KeepAlive 实施计划

- 状态：已完成
- 适用版本：仓库所选择的 Minecraft 官方版本
- 涉及模块：`protocol-session`、`protocol-server`、`protocol-client`

## 目标和范围

这次修改不在现有 `MinecraftConnectionEngine` 上继续增加前置、后置或状态 hook，而是先重构连接层的职责边界，再实现官方
KeepAlive：

1. 客户端和服务端复用同一个不含端点行为的连接核心。
2. 客户端和服务端行为通过组合放在各自的连接实现中，不通过共享行为父类强行复用。
3. Bundle 的组装和展开下移到 packet session 一侧，不再由连接读写循环特判。
4. 服务端连接提供显式启用、停用和重新配置 KeepAlive 的 API；协议状态由上层流程决定。
5. 客户端连接自动回复官方服务端在 Configuration 和 Play 中直接发送的 KeepAlive。

不增加资源包策略、Login 超时、通用空闲超时、延迟指标、游戏逻辑或第三方服务端兼容分支。

## 官方实现依据

仓库所选官方版本的客户端和服务端采用以下分层。

### 共享 `Connection`，不共享两侧行为父类

- 客户端和服务端使用同一个 `net.minecraft.network.Connection`；正常网络路径没有分别继承它的 client/server connection 实现。
- `Connection` 只拥有 Netty Channel、协议管线、收发调度、flush、关闭、当前 `PacketListener` 和连接统计。
- 收到数据包后，`Connection.channelRead0` 只检查 listener 是否接收消息，然后调用
  `packet.handle(currentListener)`；它自身不处理 KeepAlive、Configuration 或 Play 行为。
- 任意线程都可以调用 `Connection.send`，最终写入统一串行化到 Netty event loop。已经交给 event loop 的发送不会因 listener
  随后被替换而撤回。

官方没有让客户端和服务端的公共包行为互相继承：

- 客户端的 Configuration 和 Play listener 分别继承 `ClientCommonPacketListenerImpl`；客户端 KeepAlive 回复在这里。
- 服务端的 Configuration 和 Play listener 分别继承 `ServerCommonPacketListenerImpl`；服务端 KeepAlive 定时、pending
  challenge 和回复校验在这里。
- Handshake、Status 和 Login listener 是各自独立的实现，不继承上述 common listener。
- client common 与 server common 之间没有共享行为父类，因为一个负责回复，另一个负责定时和校验，语义并不相同。

### listener 生命周期拥有 KeepAlive 生命周期

- `Connection.tick()` 只在当前 listener 实现 `TickablePacketListener` 时调用其 `tick()`。
- `ServerConfigurationPacketListenerImpl.tick()` 和 `ServerGamePacketListenerImpl.tick()` 分别调用继承来的
  `keepConnectionAlive()`。
- `ServerCommonPacketListenerImpl` 构造时以单调毫秒时间初始化 `keepAliveTime`，pending 初始为空。
- Login → Configuration、Configuration → Play 和 Play → Configuration 都会创建并安装新的 listener，因此服务端 KeepAlive
  的计时基线和 pending 会随 listener 更换而重置，不会由同一份状态跨阶段延续。
- 客户端 Configuration 和 Play listener 都继承同一个客户端 common handler，因此两阶段都会自动原样回复 challenge。

### Bundle 属于 packet 管线

- inbound protocol 安装时，官方根据 `ProtocolInfo.bundlerInfo` 在 decoder 后安装 `PacketBundlePacker`。
- outbound protocol 安装时，在 encoder 后安装 `PacketBundleUnpacker`。
- `Connection` 的读写调度和 common listener 都不特判 delimiter。
- `PacketBundlePacker` 拒绝 terminal 子包；Play → Configuration 的 `StartConfigurationPacket` 因此不能位于 Bundle 内。
- 官方客户端收到逻辑 `ClientboundBundlePacket` 后会依次调用每个子包的 handler；但官方服务端 KeepAlive 直接调用
  `send(ClientboundKeepAlivePacket)`，不会把它放入 Bundle。

## 从官方设计采纳与不采纳的部分

采纳官方的所有权边界：

- 一个共享、无端点行为的连接核心；
- client/server 行为通过组合委托，不通过连接核心的可覆盖 hook；
- Bundle 位于 packet session/协议管线，而非连接行为层；
- KeepAlive 的状态归属于服务端端点行为，客户端只拥有回复行为。

不机械复制以下实现细节：

- 不引入官方运行时 `PacketFlow` side flag；继续通过
  `MinecraftClientPacketConnection`/`MinecraftServerPacketConnection` 和泛型方向保证类型安全。
- 不把官方 `Packet.handle(listener)` visitor 加入所有 packet model。本库的公共契约仍是原始 typed packet Channel，少量
  连接内部行为由私有端点 handler 识别，其他包原样公开。
- 不把 Minecraft 游戏主线程 tick 模型复制进库。服务端 KeepAlive 使用连接生命周期内的协程计时。
- 不复制官方 `PacketBundlePacker` 的 terminal 成员预检；README 要求调用者不要将 `StartConfigurationPacket` 放入
  Bundle，本库刻意不为错误用法增加语义校验。
- 不删除公共 `incoming`/`outgoing` Channel API；这次只重构其内部实现和行为所有权。

## 目标连接层结构

| 组件                                                | 共享范围       | 职责                                                                                       |
|-----------------------------------------------------|----------------|--------------------------------------------------------------------------------------------|
| `MinecraftPacketConnection`                         | 公共接口       | typed Channel、状态/context、flush、关闭等稳定契约                                         |
| `MinecraftPacketSession<Incoming, Outgoing>`        | 两侧共享基类   | packet ID、方向/状态校验、wire commit、compression/encryption 边界、extension route        |
| `MinecraftPacketConnectionCore<Incoming, Outgoing>` | 两侧组合复用   | reader/writer、Channel、flush、连接 Job、失败和关闭；不识别任何具体 packet                 |
| client connection implementation                    | 仅客户端       | 客户端 inbound 顺序、自动回复、Play context 屏障、客户端 encryption API                    |
| server connection implementation                    | 仅服务端       | 服务端 inbound 顺序、KeepAlive controller、Encryption Response 屏障、服务端 encryption API |
| clientbound Bundle 组件                             | session 内复用 | 客户端 inbound 组装、服务端 outbound 展开、数量、嵌套和 delimiter 约束                     |

### 取消抽象 connection engine 和 hook 链

将当前抽象 `MinecraftConnectionEngine` 改为 final/internal 的连接核心。删除：

- `receiveIncomingPacket()`；
- `afterIncomingPacket()`；
- `writeOutgoingPacket()`；
- `protocolRegistryContextInstalled()`。

连接核心的 reader 只负责读取并委托给构造时安装的一份端点 handler，语义等价于：

```kotlin
while (isActive) {
    incomingHandler(session.receive())
}
```

handler 是内部单方法函数或 `fun interface`，不是可公开安装的通用拦截器链。连接核心本身不决定包应被消费、公开还是等待；
client/server connection implementation 各自只有一个顺序明确的 inbound handler，并在需要公开时调用连接核心的内部
`publishIncoming(packet)`。

连接核心只向同模块端点实现提供最小内部能力：

- 公开一个 incoming packet；
- 向唯一 writer 提交一个 connection-owned outgoing packet；
- 启动随连接关闭而取消的任务；
- 以原始原因终止连接。

client/server 私有实现通过 Kotlin delegation 复用连接核心的 `MinecraftPacketConnection` 实现，而不是继承连接核心。
连接核心只能启动一次，factory 在端点实现完整构造后安装 handler 并启动 pumps。

### 两侧各自拥有完整 inbound 顺序

客户端 handler 的顺序：

1. 识别官方 Configuration/Play clientbound KeepAlive，提交原 challenge 的自动回复并消费该包；
2. 其他包投递到公共 `incoming`；
3. `PlayLoginPacket` 投递成功后等待调用方安装首个 Play registry context，再读取后续包。

服务端 handler 的顺序：

1. 让当前启用的 KeepAlive run 尝试识别 serverbound 回复；识别后完成校验并消费；
2. 其他包投递到公共 `incoming`；
3. `EncryptionResponsePacket` 投递成功后等待调用方启用 inbound encryption，再读取后续帧。

这样不再需要同时理解 `processIncomingPacket` 和 `afterIncomingPacketPublished`；每侧的一个函数直接表达实际顺序。

`installProtocolRegistryContext` 的客户端附加行为由 client connection implementation 显式覆写：先委托核心/session 安装，
再释放 Play context 屏障。服务端不需要对应 hook。

## Bundle 和 clientbound 输出重构

将当前 client connection 中的 delimiter 聚合，以及 server connection 中的 Bundle 展开移出连接核心：

- `MinecraftClientPacketSession.receive()` 对 clientbound Play delimiter 流进行组装，向连接核心返回一个逻辑
  `ClientboundBundlePacket`；
- `MinecraftServerPacketSession.send()` 接受一个逻辑 `ClientboundBundlePacket`，在同一次 writer action 中依次发送开始
  delimiter、所有子包和结束 delimiter；
- 两侧通过一个内部 clientbound Bundle 规则组件共享 4,096 上限、嵌套与 delimiter 结构校验；terminal 状态切换子包 由 README
  约束调用者正确使用，代码刻意不检查；
- skippable clientbound packet 的编码失败策略也移到 server packet session/clientbound 输出层，不再要求 connection writer
  可覆盖；
- Bundle 仍是公共 Channel 边界上的一个原子逻辑 packet，不能在其子包之间插入自动 outgoing packet。

这对应官方 bundler/unbundler 位于 packet codec 与 listener 之间的层次，并使连接核心的收发路径对两侧完全相同。

## 唯一 writer 与 connection-owned outgoing

保留唯一 writer coroutine。连接核心新增一个私有的
`Channel<Outgoing>(Channel.RENDEZVOUS)`，专门承接连接自身产生的数据包；当前使用者只有客户端自动回复和服务端 KeepAlive
controller。

writer 的约束：

- 公共 `outgoing` 与 connection-owned outgoing 最终都只调用同一个 `session.send(packet)`；
- connection-owned packet 在下一个逻辑 packet 边界优先处理并立即 flush；
- `processFlushRequest` 排空公共 outgoing 时也必须在每个 packet 后重新检查 connection-owned lane，避免持续公共流量使其饥饿；
- 已开始写出的 packet、逻辑 Bundle 或 frame 不可抢占；
- framing、compression、encryption 和 TCP 顺序仍全部由现有下层实现保证。

连接核心不把这条 lane 命名为 KeepAlive lane，也不保存 KeepAlive 状态，以便其语义保持为“连接端点自身产生、需要及时发送并立即
flush 的 packet”。

## 服务端 KeepAlive

### 状态无关的 API

服务端连接提供一个不读取协议状态的底层映射 API：

```kotlin
fun enableKeepAlive(
    extractChallenge: (ServerboundPacket) -> Long?,
    createRequest: (Long) -> ClientboundPacket,
    interval: Duration = DEFAULT_KEEP_ALIVE_INTERVAL,
)

fun disableKeepAlive()

fun MinecraftServerPacketConnection.enableConfigurationKeepAlive(
    interval: Duration = DEFAULT_KEEP_ALIVE_INTERVAL,
)

fun MinecraftServerPacketConnection.enablePlayKeepAlive(
    interval: Duration = DEFAULT_KEEP_ALIVE_INTERVAL,
)
```

`extractChallenge` 从 incoming packet 提取 KeepAlive challenge，非当前映射的 packet 返回 `null`；`createRequest` 根据
challenge 构造对应 outgoing packet。连接不读取 `ConnectionState` 来选择映射。`protocol-session` 用两个具名扩展函数封装
仓库所选官方版本的 Configuration 和 Play packet 映射，上层只在正确生命周期调用对应函数。这里不公开无状态的
`PlayKeepAliveExchange`/`ConfigurationKeepAliveExchange` 对象，也不为两个固定映射增加公共策略类型。

每次 `enableKeepAlive` 都创建新的 run、计时基线和空 pending；若已有 run，则原子替换并取消旧 run。官方流程仍显式
`disableKeepAlive()` 后再调用另一状态的具名扩展，使 listener 生命周期在调用点可见。`interval` 必须为正值，默认 15 秒。

### 官方计时和校验

每个 run 使用单调毫秒时间，并按官方语义执行：

```text
启用后等待一个 interval
  pending 存在  -> 连接按现有失败路径终止
  pending 不存在 -> challenge = 当前单调毫秒值
                  -> 先记录 pending(challenge)
                  -> 提交对应 request packet
                  -> 从本次检查时刻重新等待一个 interval
```

- 匹配的回复清除 pending 并被消费；回复不会推迟下一次检查。
- 当前映射识别到回复，但 challenge 不匹配或没有 pending 时，连接失败。
- KeepAlive 未启用或当前映射不识别该 packet 时，packet 原样投递到公共 `incoming`。
- 连接关闭时取消当前 run。
- 不增加官方 integrated-server owner 例外；本库服务端路径对应普通网络服务端。
- 超时和错误使用连接现有失败终止路径；是否先发送 disconnect packet 仍由 `protocol-server` 的调用方策略决定。
- 不在这次修改中公开官方 latency 平滑值。

### rendezvous 与取消边界

timer 到达发送时刻后，在 timer coroutine 的结构化子协程中向 capacity-zero connection-owned lane 发送具体 packet；子协程
必须以 timer Job 为父 Job，不能直接作为 connection scope 的兄弟任务。timer 自身继续等待下一周期，因此 writer 堵塞不会阻止
下一次超时检查。

`disableKeepAlive()` 先原子移除当前 run，再取消 timer Job：

- 尚未与 writer rendezvous 的发送没有缓冲残留，并随 timer 子协程取消；
- 已被 writer 接收的 packet 视为已经进入发送流程，允许完成；取消不能撤回它；
- rendezvous 与取消同时发生时也以 writer 是否已经接收为线性化边界。

这与官方 `Connection.send()` 把 packet 交给 Netty event loop 后不可撤回的语义一致。API 不承诺 “`disableKeepAlive()`
返回后，先前已经被 writer 接收的 packet 绝不会完成”，因此不增加 writer barrier 或可撤回队列。

启用自动 KeepAlive 后，调用方约定不再手动发送同方向 KeepAlive；只更新文档和测试，不增加运行时拒绝。

## 客户端 KeepAlive

客户端不提供开关、不维护 pending，也不主动生成 challenge。

- client endpoint handler 识别官方 Configuration 和 Play 的直接 clientbound KeepAlive；
- 使用对应 packet 类型和相同 challenge 向 connection-owned lane 提交回复；
- 原请求不进入公共 `incoming`；
- 删除 `protocol-client` Configuration 协商中的手动回复分支；
- 官方服务端不会把 KeepAlive 放入 Bundle，因此不为 Bundle 内 KeepAlive 增加分支或完成条件。

## `protocol-server` 生命周期集成

由 `protocol-server` 而非 connection core 决定启用哪个状态的官方映射：

1. Login acknowledgement 完成并进入 Configuration 后，调用 `enableConfigurationKeepAlive()`；
2. Configuration 完成、收到 acknowledgement 时，停用 Configuration run；
3. 在发送首个 Play packet 前，调用 `enablePlayKeepAlive()` 启用一个全新 run；
4. Play → Configuration reconfiguration 在相应 acknowledgement 边界执行反向的停用和重新启用；
5. connection close 自动清理，无需上层 finally 重复停用。

`negotiate()` 在返回 Play 连接前已经启用 Play KeepAlive。调用方在 `negotiate()` 返回后自行执行 reconfiguration 时，按文档
使用同一显式切换顺序。

## 测试迁移和新增覆盖

所有协程测试继续使用 `runTest`、虚拟时间和显式信号，不使用真实 delay、sleep 或调度器运气。

### 连接核心

- client/server factory 通过组合复用同一个 final core，不存在 endpoint subclass hook；
- endpoint handler 可以消费 packet，也可以在公开后等待屏障，reader 顺序保持正确；
- 公共 outgoing 的 drain-on-close、flush 合并、原始失败传播和 transport cleanup 语义保持不变；
- connection-owned outgoing 与公共 outgoing 共用唯一 writer，在持续公共流量和 flush drain 中不饥饿；
- connection-owned packet 立即 flush，不能插入逻辑 Bundle 内部。

### Packet session 与 Bundle

- 将现有 Bundle 原子性测试下移到 client/server packet session；
- 覆盖空 Bundle、4,096 上限、未闭合 Bundle、嵌套/delimiter 拒绝和每个子包的 wire 顺序；
- 覆盖 skippable clientbound 子包失败不会终止后续 writer；
- 保持 packet-driven state 和 encryption/compression wire commit 测试。

### KeepAlive

- 启用后首次发送、正确回复、下一次检查仍以发送时刻为基准；
- 一个周期后仍 pending 时失败；
- challenge 不匹配或 active run 没有 pending 时失败；
- disabled 时回复原样公开；
- Configuration → disable → Play 会清除 pending 并重置计时基线；
- 重复 enable 原子替换旧 run；连接关闭取消 run；
- 显式阻塞 writer，证明未 rendezvous 的旧发送在 disable 后被取消；另行证明已经 rendezvous 的发送可以完成；
- 客户端对官方 Configuration/Play 直接 KeepAlive 原样回复并消费请求；不增加 Bundle 内 KeepAlive 测试。

### 高层和官方 peer

- 更新当前依靠手动 KeepAlive 的 client/server 单元测试和 fixture 场景，删除重复回复或探测逻辑；
- `protocol-client` 对匹配官方服务端完成 Configuration 和 Play，并由 connection 自动回复；
- `protocol-server` 对匹配官方客户端在 Configuration、Play 和 reconfiguration 后使用正确 packet 映射；
- 用显式观测的 challenge/response 证明官方 peer 行为，而不是仅以连接未断开作为证据。

## 实施顺序

1. 在 session 层引入 clientbound Bundle 组装/展开组件，并迁移 Bundle 与 skippable packet 测试。
2. 引入 final `MinecraftPacketConnectionCore`，以组合和单一 endpoint handler 替换抽象 engine 及四个 hooks。
3. 将客户端 Play context、客户端 encryption、服务端 Encryption Response 屏障迁入各自 connection implementation。
4. 加入 connection-owned rendezvous lane，并完成唯一 writer、flush 和取消边界测试。
5. 实现服务端 KeepAlive controller、官方状态扩展函数和客户端自动回复。
6. 在 `protocol-client` 删除手动回复，在 `protocol-server` 按 listener 等价生命周期显式切换状态扩展函数。
7. 更新 KDoc、README 和 `protocol-session/AGENTS.md` 中“不得自动回复”的规则，仅为官方 KeepAlive 明确例外。

依次验证：

```shell
./gradlew :protocol-session:jvmTest
./gradlew :protocol-client:jvmTest
./gradlew :protocol-server:jvmTest
```

JVM 路径稳定后再运行受影响的标准平台测试；Gradle invocation 不并发执行。

## 完成条件

- 抽象 `MinecraftConnectionEngine` 及其 endpoint hooks 已删除，client/server 通过组合复用无 packet 行为的 final core；
- Bundle 和 skippable clientbound 输出位于 session/packet 管线层，连接 core 不识别具体 packet；
- 每侧只有一个私有 inbound handler 表达消费、公开和必要的公开后屏障顺序；
- 服务端 KeepAlive 的启停、重新配置、计时、challenge 校验和超时行为与官方普通网络服务端一致；
- 客户端完全由 endpoint handler 回复官方 Configuration/Play 直接 KeepAlive；
- 所有 outgoing 来源通过唯一 writer，rendezvous 取消边界有确定性测试；
- 高层生命周期显式选择状态扩展函数，connection core 不观察状态来决定 KeepAlive；
- 相关 JVM、平台和官方 peer 测试通过，文档只声明当前代码已经提供的行为。
