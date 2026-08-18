# 客户端/服务端协商入口返回值去冗余计划

- 状态：已实施；2026-08-18 复核后修正 `PlayReady.login` 与手写协商文档
- 原始目标：简化 `protocol-server` 的握手入口返回类型，并清理客户端、服务端协商结果中与连接状态重复的字段
- 范围：`protocol-client`、`protocol-server` 的协商结果、KDoc、README 和受影响测试
- 不在本计划内：增加完整手写协商所缺的公开原语；后续见
  [handwritten-negotiation-public-primitives.md](handwritten-negotiation-public-primitives.md)

## 1. 问题

原服务端入口返回：

```kotlin
sealed interface MinecraftServerNegotiationResult {
    data object StatusCompleted : MinecraftServerNegotiationResult
    data class PlayReady(...) : MinecraftServerNegotiationResult
}
```

这里混合了两种概念：Handshake 把连接分成 Status 或 Login，而 `PlayReady` 描述 Login/Configuration 成功后得到的事实。
`StatusCompleted` 没有可供应用继续处理的会话；preset 已经发送 Pong、排空发送队列并彻底关闭连接。调用点为此写一个
`when` 分支只有样板价值。

客户端与服务端结果还各自复制了已经安装在连接上的 `ProtocolRegistryContext`。连接的 registry context 是编解码器当前
使用的动态状态，后续重配置还可能替换它；返回值中的快照会形成第二份、可能过期的真相。

## 2. 最终设计

服务端 preset 返回一个可空的成功值：

```kotlin
suspend fun MinecraftServerConnection.negotiate(
    profile: ServerNegotiationProfile = VanillaServer,
    options: MinecraftServerNegotiationOptions = MinecraftServerNegotiationOptions(),
    policy: MinecraftServerNegotiationPolicy = DefaultMinecraftServerNegotiationPolicy,
): MinecraftServerPlayReady?
```

```kotlin
data class MinecraftServerPlayReady(
    val profile: GameProfile,
    val clientInformation: ClientInformation,
    val acceptedKnownPacks: List<KnownPack>,
    val login: PlayLoginPacket,
    val negotiationProfile: NegotiationProfileResult,
    val transferred: Boolean = false,
)
```

- Status：完整应答并关闭后返回 `null`。
- Login：进入 Play 并安装 registry context 后返回非空 `MinecraftServerPlayReady`。
- `MinecraftServerNegotiationResult` sealed 层级删除。
- `registries` 从服务端和客户端协商结果删除；调用者读取各自连接的 `registries`。

### 为什么保留 `PlayReady.login`

`registries` 与 `login` 不是同一种重复：

- `connection.registries` 是非空、可替换且直接参与当前编解码的连接状态，结果中再保存一次只会产生快照歧义。
- `PlayLoginPacket` 是成功跨入 Play 的不可变阶段产物。非空 `MinecraftServerPlayReady` 应当直接证明这个事实；若只留下
  `connection.playLogin: PlayLoginPacket?`，调用者刚拿到成功结果仍需 `checkNotNull`，类型反而丢失了 preset 已经建立的保证。
- 当前 `connection.playLogin` 仍供初始世界投影内部校验使用。它与结果字段的最终归一化属于“完整手写协商”设计的一部分，
  不在本次返回类型改造里仓促扩大范围。

因此本次只删除 `PlayReady.registries`，保留 `PlayReady.login`。

## 3. 出口与资源所有权

| 出口                                | 连接状态                            | 后续责任                                      |
|-------------------------------------|-------------------------------------|-----------------------------------------------|
| 返回非空 `MinecraftServerPlayReady` | 打开、PLAY，registry context 已安装 | 调用者继续收发并关闭                          |
| 返回 `null`                         | Status 已应答且连接已完全关闭       | 无需额外操作，外层 `use` 仍可幂等收尾         |
| 抛出库协商异常                      | 连接通常仍打开                      | 调用者可发送异常提供的 failure packet，再关闭 |
| 抛出线路、泵或编解码失败            | 连接已终止                          | 只需关闭资源，不能再可靠发送                  |

`negotiate` 独占借用 `incoming`/`outgoing` 直到返回；调用者不得同时收发。

## 4. 客户端同构优化

`MinecraftClientNegotiationResult.registries` 删除，理由如下：

1. 全仓没有生产或测试消费者读取该字段。
2. `MinecraftClientConnection.registries` 是公开、非空且由连接编解码器直接使用的权威状态。
3. preset 在 Finish Configuration 时安装基础 context，在收到 Play Login 后安装带当前维度高度的 context，返回前已经完成。
4. 客户端测试已经直接断言 `client.registries` 的 chunk section count、biome registry 等事实。
5. 删除结果字段后，`resolvedContext` 不再需要保存第二次安装后的值；保留 Configuration 到 Play Login 之间必需的局部值，
   并以内部不变量检查取代不可达的业务失败分支。

客户端结果仍保留 `login`、`configuration`、`playLogin` 和 `profile`：这些是连接门面没有保存的协商产物，不是重复状态。

## 5. README 与手写协商边界

- 根 README 与 `protocol-server/README.md` 的服务端示例使用
  `connection.negotiate() ?: return@use`，明确 preset 同时处理 Status 和 Login。
- 模块 README 说明非空结果携带精确的 Play Login，registry context 从连接读取。
- 删除原“Handwritten negotiation”示例。它虽然能用公开 channel 推进到 `PLAY`，却无法建立
  `synchronizeInitialWorld` 所依赖的 `connection.playLogin` 不变量，因此不是完整、可用的手写流程；枚举分支也未覆盖
  `HandshakeNextState.UNUSED`。
- 在公开原语补齐且示例进入编译测试前，不在用户文档中宣称完整手写协商已经受支持。

## 6. 实施清单

### protocol-server

- 删除 `MinecraftServerNegotiationResult`，新增顶层 `MinecraftServerPlayReady`。
- `negotiate`/Status/Login 三个出口按 §2 和 §3 调整。
- `MinecraftServerPlayReady` 保留 `login`、删除 `registries`。
- KDoc 写明独占 channel、nullable 含义、状态与关闭所有权。
- 测试把 Status 结果改为 `assertNull`，Login 结果改为 `assertNotNull`，registry 断言改读连接；Play Login 断言继续读
  非空结果，并额外验证连接内部记录一致。

### protocol-client

- 删除 `MinecraftClientNegotiationResult.registries`。
- 删除仅为返回该快照而存在的死存储，保留两个 registry-context 安装点。
- 补全 `queryStatus`/`negotiate` 的状态、关闭与所有权 KDoc。
- 保持连接 registry context 的既有测试覆盖。

### 文档

- 修正根 README 和模块 README 的 preset 用法。
- 删除当前无法兑现的手写协商演示，另立实施计划。

## 7. 明确不做

- 不改变 `MinecraftServer.accept` 的传输层职责。
- 不增加 Flow、回调式 accept loop 或自动 gameplay。
- 不改变协商异常的断开策略。
- 不在本次改动中公开现有 private 阶段函数，也不让调用者直接写 `connection.playLogin`。
- 不用兼容别名保留旧 sealed 结果或已删除的 `registries` 字段；项目允许干净的早期 API 重构。

## 8. 验证

- `:protocol-client:jvmTest`
- `:protocol-server:jvmTest`
- 两个模块的 `allTests`，覆盖适用的 Android host、Node、WasmJS、Native 与官方 peer 场景
- `git diff --check` 与全仓引用搜索：不存在旧 sealed 类型、结果 `.registries` 或失效 README 示例

2026-08-18 复核结果：

- `:protocol-client:jvmTest :protocol-server:jvmTest` 通过；
- `:protocol-client:allTests` 通过；
- `:protocol-server:allTests` 通过；
- 适用目标覆盖 Android host、JS Node、WasmJS Node、Mingw 与 JVM 官方 peer 场景；当前主机不支持的 Apple/Linux 可执行测试按既有
  Gradle 配置跳过；
- `git diff --check`、cached diff check 与旧 API 引用搜索通过。
