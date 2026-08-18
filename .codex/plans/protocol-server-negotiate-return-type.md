# protocol-server `negotiate` 返回类型重构计划

- 状态：设计已讨论定稿，尚未实施
- 记录日期：2026-08-18；同日按源码核对修订：修正 §2/§3/§5 的事实与契约表述，补全 §6 细节，新增 §6.2 手写协商 README 演示任务
- 范围：`protocol-server` 公开 API、根与模块 README 示例（含新增"不使用 `negotiate`"的手写协商演示）、受影响测试；不改
  `MinecraftServer.accept` 语义，不加 Flow/回调式高层 API，客户端侧 API 不动

## 1. 现状

`MinecraftServerConnection.negotiate` 返回 sealed 接口：

```kotlin
sealed interface MinecraftServerNegotiationResult {
    data object StatusCompleted : MinecraftServerNegotiationResult
    data class PlayReady(...) : MinecraftServerNegotiationResult
}
```

README 的推荐写法因此是：

```kotlin
connection.use {
    when (val result = connection.negotiate()) {
        MinecraftServerNegotiationResult.StatusCompleted -> Unit
        is MinecraftServerNegotiationResult.PlayReady -> {
            for (packet in connection.incoming) handlePlayPacket(connection, packet)
        }
    }
}
```

## 2. 原 API 为什么别扭

1. **sealed 结果混合了两种正交概念。** Handshake 的 `nextState` 把一条连接分类为 status ping 或 login，这是"连接是什么"；而
   `PlayReady` 携带的是"协商产出了什么事实"。
   `StatusCompleted` 不是一种协商结果，而是"没有会话"。把它放进协商返回值的联合类型，是别扭感的根源。
2. **`StatusCompleted` 的连接生命周期在调用点不可见。** `handleStatus` 在内部完成
   `outgoing.send(pong)` → `outgoing.close()`（排空发送队列并终止连接）→ `awaitClosed()`， 返回时连接已彻底关闭。调用点的
   `use` 只是幂等空操作，但没有任何签名或文档表达这一点， 读者只能去猜"status 分支结束后连接去哪了"。
3. **关闭所有权不对称且未成文。**
    - 返回 `PlayReady`：连接打开，调用者收发并用 `use` 关闭；
    - 返回 `StatusCompleted`：连接已在内部关闭；
    - 抛库协商异常（`MinecraftLoginRejectedException`、版本不匹配、意外包等）：连接保持 打开，调用者可选择发送 `failurePacket`
      再关闭（库从不自动发 disconnect）；
    - 线路/泵失败以原始异常浮现时，连接已终止，只剩 `use` 一种收尾。
      `negotiate` 的 KDoc 只写了"独占借用 incoming/outgoing"，未覆盖以上契约。
4. **README 示例中的 `when` 是纯样板。** 绝大多数应用对 `StatusCompleted -> Unit`
   的处理就是丢弃，却要求每个使用者写一遍握手分类；且示例中 `result` 的 payload 从未被读取。
5. **双份真相。** `PlayReady.login` 与 `connection.playLogin`、`PlayReady.registries`
   与 `connection.registries` 重复暴露同一状态。

## 3. 新设计

保留 `negotiate` 名称（它确实涵盖"握手分类 + 登录/配置全程协商"），返回类型改为可空的单一成功类型：

```kotlin
/**
 * - 非登录连接（status ping）在内部被完整应答并关闭，返回 null，调用者无事可做；
 * - 返回 [MinecraftServerPlayReady] 表示连接已进入 Play：连接打开、registry context
 *   已安装，后续收发与关闭归调用者；
 * - 库抛出的协商异常（如 [MinecraftLoginRejectedException]）不关闭连接，调用者可选择
 *   发送 failurePacket 再关闭；
 * - 线路/编解码失败以原始异常浮现，连接已终止，只能关闭（failurePacket 不可用）。
 */
suspend fun MinecraftServerConnection.negotiate(
    profile: ServerNegotiationProfile = VanillaServer,
    options: MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(),
    policy: MinecraftServerNegotiationPolicy = DefaultMinecraftServerNegotiationPolicy,
): MinecraftServerPlayReady?
```

`MinecraftServerPlayReady` 提升为顶层 data class，只保留连接上拿不到的应用层事实：

```kotlin
data class MinecraftServerPlayReady(
    val profile: GameProfile,
    val clientInformation: ClientInformation,
    val acceptedKnownPacks: List<KnownPack>,
    val negotiationProfile: NegotiationProfileResult,
    val transferred: Boolean = false,
)
```

删除 `PlayReady.login` 与 `PlayReady.registries`：线协议状态留在连接上 （`connection.playLogin`、`connection.registries`
），返回值是"谁、带着什么配置进来了"。
`MinecraftServerNegotiationResult` sealed 接口整体删除。

已知代价与边界：`connection.playLogin` 类型保持 `PlayLoginPacket?`，需要 Play Login 细节 的调用者要自行非空断言；KDoc
同时写明"返回非空后 `connection.playLogin` 必非空"。这与 §4 否决"事实挂 connection"不矛盾：那里反对的是把连接上本来没有的事实（
`clientInformation`、
`acceptedKnownPacks`、`negotiationProfile` 等）也塞进门面并全部可空化；这里只是不再复制 连接本来就持有的线协议状态（
`connection.registries` 本就非空）。

返回后的完整契约（需写进 KDoc）：

| 出口                                     | 连接状态           | 谁负责                                            |
|------------------------------------------|--------------------|---------------------------------------------------|
| 返回非空 `MinecraftServerPlayReady`      | 打开，PLAY 态      | 调用者收发并关闭（`use`）                         |
| 返回 null                                | 已在内部应答并关闭 | 无需任何操作（`use` 幂等）                        |
| 抛库协商异常（拒绝、版本不匹配、意外包） | 保持打开           | 调用者决定发不发 `failurePacket`，然后 `use` 关闭 |
| 抛线路/泵失败                            | 已终止             | 只能 `use`；此时再发送会失败                      |

调用点写法：不需要协商事实时不绑定变量；需要时绑定并命名为 `ready`
（连接才是会话，返回值不是，不要命名为 `session`）：

```kotlin
connection.use {
    connection.negotiate() ?: return@use
    for (packet in connection.incoming) {
        handlePlayPacket(connection, packet)
    }
}

// 需要协商事实时：
val ready = connection.negotiate() ?: return@use
log("player ${ready.profile.name} joined")
```

`null` 按"会话性质"而非"具体协议分支"分类：未来若出现其他终端型非登录连接 （例如 legacy ping），仍映射为 null，返回类型不需要演进。

## 4. 被否决的备选

- **`negotiate(): PlayReady` 永不返回（处理完 ping 后 `awaitCancellation`）**： 每个 ping 泄漏一个永不结束的协程；连接关闭不会取消调用者的
  `launch`。
  "处理完成"本应返回，而不是挂死。
- **抛 `StatusHandledException`**：把预期中的正常路径塞进异常通道，每个 accept 循环被迫 try/catch，监控和日志会把 ping
  当错误。异常只留给"连接仍打开、由调用者善后"的失败路径。
- **在 `accept()` 内过滤 ping**：`accept` 从"传输层接受一条连接"变成暗中循环收握手、 应答 ping、直到等到登录连接；options/policy
  必须搬到 `bind`/`accept`，调用者无法观察 或定制 ping 处理。用隐藏状态换表面类型干净，与"库保持简单"相反。
- **两阶段 `receiveHandshake()` + `handleStatus()` / `negotiateLogin()`**：类型最诚实， 但把 `when` 原样推回每个调用者。
- **`negotiate(): Boolean` + 协商事实挂 connection**：每个事实变成可空属性或逼调用者 `!!`；
  连接变成"协商前后两种生命周期挤在一个可变门面上"的对象；`Boolean` 读起来像"协商是否成功"， 而成功与否已由异常表达。 （§3
  保留 `connection.playLogin`/`connection.registries` 与此不冲突：它们是连接本就持有的 线协议状态，这里反对的是把返回值独有的事实也塞进门面。）
- **`server.playConnections(...)` Flow 高层 API**：维护者明确否决——控制反转，库保持简单， 应用拥有 accept 循环与并发。

## 5. Fabric / Forge / NeoForge 兼容性

已核对 `protocol-session` 中三个 loader 的 server profile，结论是新写法完全兼容，无需为 mod profile 增加任何返回值分支：

1. **loader 协商结果已有统一出口，且不经过握手 sealed 层级。** 三个 loader 的
   `ServerNegotiationProfile.complete` 都返回各自实现 `NegotiationProfileResult` 的结果：
    - `FabricNegotiationResult(commonVersion, remoteConfigurationChannels, remotePlayChannels, registrySynchronized)`
    -
    `ForgeNegotiationResult(forgePeer, networkVersion, remoteChannels, remoteMods, remoteChannelVersions, registrySynchronized, configFiles)`
    -
    `NeoForgeNegotiationResult(neoForgePeer, networkSetup, commonVersion, remoteConfigurationChannels, remotePlayChannels, registrySynchronized, configFiles, remoteKnownDataMaps)`

   它们经由 `MinecraftServerPlayReady.negotiationProfile` 传递，改为可空返回不改变这条通路。调用方写法：
   `val forge = ready.negotiationProfile as? ForgeNegotiationResult`。
2. **握手分类与 loader 分类正交。** Handshake 的 `nextState` 只区分 STATUS/LOGIN/TRANSFER； loader 判定全部发生在
   Login/Configuration 阶段——Forge 通过握手 hostname 的
   `\u0000FORGE` 标记在 `acceptHandshake` 中识别（vanilla 客户端也可连 Forge 服务器，
   `forgePeer = false`），Fabric/NeoForge 通过 Login query / Configuration 交换探测。
   `begin` 与 `acceptHandshake` 在握手分类之前对所有连接执行（Forge 的标记探测正依赖 这一点），但 STATUS 连接不进入任何
   Login/Configuration 协商钩子，`complete()` 只在 Login 路径末尾调用，loader 也不定义 status 阶段协商，因此"STATUS →
   null"不产出也 不隐藏任何 mod 协商信息。
3. **mod 解析的 registry context 无信息损失。** `profile.resolveRegistryContext(...)`
   的结果经 `installRegistryContext(...)` 安装在连接上，`connection.registries` 就是 mod 解析后的上下文；从返回值删除
   `registries` 不影响 modded 服务器。
4. **`acceptedKnownPacks` 保留。** modded registry/数据补发决策依据仍在返回值中。
5. **原 sealed 层级从未承载过 mod 变体。** mod 结果只在 `negotiationProfile` 里， 所以删除 sealed 层级对 mod profile
   是纯简化。Forge status JSON 的 `modinfo`
   若需要，属 `MinecraftServerNegotiationPolicy.statusJson` 的职责，与本计划无关。

## 6. 改动清单

### 6.1 API 与实现

- `protocol-server/src/commonMain/.../MinecraftServerProtocol.kt`
    - 删除 `MinecraftServerNegotiationResult` sealed 接口；
    - 新增顶层 `MinecraftServerPlayReady`（不含 `login`、`registries`）；
    - `negotiate` 返回 `MinecraftServerPlayReady?`，`handleStatus` 返回 null （保留内部 `outgoing.close()` +
      `awaitClosed()`，使 null 拥有"完全结束"的最强语义）；
    - `handleLogin` 构造收窄后的 `MinecraftServerPlayReady`；
    - KDoc 按 §3 写全契约，并写明"返回非空后 `connection.playLogin` 必非空"。
- `MinecraftServer.kt` 本身无需改动：`connection.playLogin` 已是 public read / internal set 的线协议状态，本计划只是不再在返回值中复制它。

### 6.2 README

- 根 `README.md` 与 `protocol-server/README.md` 的 preset 示例改为
  `connection.negotiate() ?: return@use`；需要事实处绑定 `val ready = ...`。
- `protocol-server/README.md` 中 "The preset `negotiate` extension ... returns a Play-ready connection" 一段按 nullable
  语义改写（null = status ping 已在内部应答并关闭）。
- `protocol-server/README.md` 新增"不使用 `negotiate`、手写协商"的演示小节：
    - 将现有 "Applications can instead write their own complete negotiation with
      `incoming`, `outgoing`, `awaitState`, `installRegistryContext`, and
      `activateExtensionRoutes`" 一句落成可跟随的示例代码；
    - 示例不调用 `negotiate`：接收 `HandshakePacket` 后按 `nextState` 分支——STATUS 手写 Request/Response/Ping/Pong
      收尾并关闭；LOGIN 手写原版流程（offline Login Start → Login Success → Login Acknowledged →
      `awaitState(CONFIGURATION)` → Client Information → Feature Flags → Known Packs → registry/tag 同步 → Finish
      Configuration → `installRegistryContext` → `awaitState(PLAY)`）；
    - 只演示原版（vanilla）流程即可：offline 身份、不启用压缩、默认 `VanillaProtocolData`； Fabric/Forge/NeoForge 仍指向
      `negotiate(profile = ...)` 的既有用法，不在此展开；
    - 根 `README.md` 不放手写示例，quickstart 保持只出现 preset 入口。

### 6.3 测试（当前引用点，行号为 2026-08-18 现状）

- `MinecraftServerProtocolTest.kt`
    - `StatusCompleted` 断言（现 L119-122）改为 `assertNull`；
    - `assertIs<MinecraftServerNegotiationResult.PlayReady>`（现 L142）改为
      `assertNotNull` 后读字段；
    - `result.login` 断言（现 L148-152）改读 `pair.server.playLogin`：先 `assertNotNull`
      （属性类型是 `PlayLoginPacket?`）再读字段；
    - registry 断言（现 L153-161）改读 `pair.server.registries`。
- `ClientToServerEndToEndTest.kt`
    - `StatusCompleted` 断言（现 L65-68）改为 `assertNull`；
    - `assertIs<MinecraftServerNegotiationResult.PlayReady>`（现 L75-76）改为
      `assertNotNull` 后读字段；
    - `ServerWorldOutcome.negotiation`（现 L264）类型改为 `MinecraftServerPlayReady`。
- `HeadlessClientEndToEndRunner.kt`
    - 现 L118-127 的 `when` 位于 `while`/`try` 内而非 `use` 内，改为
      `val ready = connection.negotiate(options = OPTIONS) ?: continue`；
    - 原 `StatusCompleted` 分支的 `connection.close()`（现 L124）是幂等空操作 （`handleStatus` 已在内部关闭连接），随分支一并删除。
- `OfficialHeadlessClientInteropTest.kt`：全仓搜索当前无直接引用，实施时复核一次即可。

### 6.4 验证

- Windows 下 `.\gradlew.bat :protocol-server:jvmTest`，必要时加 `--max-workers`；
- README 手写协商示例是文档、不参与编译：其中引用的每个符号都必须是 `commonMain` 现有 公开 API，写完后对照 `negotiate`
  的内部实现逐行核对顺序与包类型。

## 7. 明确不做的事

- 不新增 `playConnections` Flow 或回调式高层入口；
- 不改 `MinecraftServer.accept` 的传输层职责；
- 不改异常路径契约（库协商异常不关闭连接、`failurePacket` 由调用者发送；线路/泵失败本就 以异常浮现且连接已终止）;
- 不改 `protocol-client` 的 `MinecraftClientNegotiationResult`（客户端意图先行， 无握手分类问题）。
- 不为手写协商演示新增任何辅助 API：README 示例只使用既有公开原语（`incoming`、
  `outgoing`、`awaitState`、`installRegistryContext`、`activateExtensionRoutes`）。
