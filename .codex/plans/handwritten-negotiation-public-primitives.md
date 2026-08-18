# 客户端与服务端可手写协商公开原语计划

- 状态：待实施
- 记录日期：2026-08-18
- 目标：用户可以只依赖公开 API 手写从 Handshake 到 Play 的完整客户端或服务端协商；库提供的
  `queryStatus`/`negotiate` 只是复用同一组公开原语的 preset
- 首个受支持基线：仓库所选版本的 vanilla Status、offline Login、Configuration、Play Login 与服务端初始世界投影
- 后续兼容：online authentication、transfer、cookie/custom query、Configuration tasks 和 Fabric/Forge/NeoForge profile
  继续由相同公开 channel 与 profile hook 组合，不引入另一条特权路径

## 1. 完成标准

只有同时满足以下条件，才可在 README 中宣称“支持手写协商”：

1. 一个外部调用者能从 `MinecraftClientConnection` 的公开属性构造并发送 Handshake，手写 Status 或 Login/Configuration， 从同步
   registry 数据建立正确 context，收到 Play Login 后切换到维度感知 context，并继续解码 Play 数据。
2. 一个外部调用者能从 `MinecraftServer.accept()` 返回的公开连接读取 Handshake，手写 Status 或 Login/Configuration，
   在正确时点安装 context、发送 Play Login，并调用初始世界投影 API。
3. 两条手写路径不调用 `queryStatus`、`negotiate`，不写 internal 属性，不复制 private helper，也不依赖测试友元可见性。
4. preset 与手写路径共用相同的 context 构造、Play Login 校验和状态提交原语；preset 不拥有调用者拿不到的状态写入口。
5. 完整手写场景是 `commonTest` 中可编译、可执行的双端测试，并至少有一个官方 peer 场景验证公开原语的时序。
6. README 示例只展示已经由测试覆盖的流程，不以“进入了 `ConnectionState.PLAY`”冒充完成了 Play 初始化。

## 2. 当前已有能力

以下基础设施已经公开，不应再包装一层新的 phase DSL：

- `incoming`、`outgoing`、`state`、`awaitState`；
- `installRegistryContext`、`activateExtensionRoutes`；
- `prepareOutboundEncryption`、`enableEncryption`，以及 Session 对 Compression/Handshake/ack 的 wire-commit 状态效果；
- 全部方向正确的 packet model；
- `MinecraftClientKeyExchange`、`MinecraftServerKeyPair`、`MinecraftSessionApi` 等认证原语；
- `ClientNegotiationProfile`/`ServerNegotiationProfile` 的公开 hook，它们本来就只接收公开
  `MinecraftPacketConnection`；
- `ProtocolDataSet`、`MinecraftDimensionLayout`、`ProtocolRegistryContext` 和静态 registry schema。

用户可以自行决定包序、分支、策略和错误响应；本计划不把 `negotiate` 拆成一串必须调用的高层 phase 方法。

## 3. 现有阻塞点

### 3.1 服务端有 preset 专用的隐藏状态写入

`MinecraftServerConnection.playLogin` 是 public read / internal set。preset 发送 Play Login 后写入它，
`synchronizeInitialWorld` 又隐式读取它。手写协商虽然能公开地发送同一 `PlayLoginPacket`，却无法建立这个内部标记，因此随后
调用初始世界投影必然失败。

这也是旧 README 手写示例不能成立的核心原因。

### 3.2 客户端正确构造 registry context 的语义仍是 private

客户端 preset 内部完成两次转换：

1. 把 Configuration 收到的 `RegistryDataPacket` 与静态 schema 合并，验证 biome registry，再交给 profile 修正；
2. 根据 Play Login 的 dimension type 选择同步数据或版本数据，验证 level/dimension 一致性，并写入 chunk section count。

虽然底层类型都公开，要求每个调用者复制 `resolveRegistryContext` 和 `configureActiveDimension` 的项目特定逻辑既不简洁， 也会让
preset 与手写实现逐渐漂移。

### 3.3 文档代码没有编译证明

旧示例遗漏 `HandshakeNextState.UNUSED`，且停在发送 Play Login 之前。以后不再用不可编译的长代码块作为功能完成证据。

## 4. 目标 API 设计

### 4.1 registry context 使用纯函数构造

在拥有版本数据与维度布局语义的 `protocol-vanilla-data` 中提取并公开两类纯操作，供 client、server 与用户共同调用：

```kotlin
fun ProtocolDataSet.resolveSynchronizedRegistryContext(
    registries: List<RegistryDataPacket>,
    staticRegistries: StaticRegistrySchema = this.staticRegistries,
): ProtocolRegistryContext

fun ProtocolRegistryContext.withPlayLoginDimension(
    login: PlayLoginPacket,
    registries: List<RegistryDataPacket>,
    protocolData: ProtocolDataSet,
): ProtocolRegistryContext
```

实施时可按 Kotlin 调用可读性微调名称和参数顺序，但语义必须固定：

- 第一个函数保留调用者选择的静态 schema，覆盖同步 registry 的 raw-ID 映射，并拒绝缺失或空 biome registry；
- 第二个函数验证活动 dimension 位于 `login.levels`，按 raw ID 解析 dimension type，只有同步条目省略 NBT 时才回退到匹配版本的
  `ProtocolDataSet`，最后只替换 chunk section count；
- 两者不访问连接、不发送包、不调用 profile，方便测试与复用；
- profile hook 的相对顺序保持当前已验证语义：客户端先从 Configuration registry 构造基础 context，再调用
  `ClientNegotiationProfile.resolveRegistryContext` 并安装；收到 Play Login 后才补活动维度并再次安装。服务端先从将发送的
  Play Login 补活动维度，再调用 `ServerNegotiationProfile.resolveRegistryContext`，并在 Finish Configuration 前安装。

服务端现有 `validatePlayLogin` 中与 dimension/context 重合的检查改由共享函数承担；max players、view distance、simulation
distance 等服务器策略前置条件保留为一个公开、纯校验函数，或下沉到真正通用的 model 构造不变量。不得在 preset 保留另一份
private 校验。

### 4.2 消除 `connection.playLogin` 隐式提交

采用显式数据流，不开放可变 setter：

```kotlin
suspend fun MinecraftServerConnection.synchronizeInitialWorld(
    world: MinecraftInitialWorld,
    login: PlayLoginPacket,
): MinecraftInitialWorldSynchronization
```

- 删除 `MinecraftServerConnection.playLogin`。
- preset 调用者传 `ready.login`；手写调用者传自己实际发送的同一个 packet。
- respawn/reconfiguration 同样显式传当前使用的 Play Login，避免连接中残留旧快照。
- `MinecraftServerPlayReady.login` 继续作为 preset 的非空阶段产物。
- 不增加 `markPlayReady`、公开 setter 或只为骗过校验而存在的 token；状态仍由真实 packet 的 wire effect 推进。

如果实施审计发现初始世界只需要 `PlayLoginPacket` 的一小部分，则提取不可变的最小 `MinecraftPlayContext` 值，并让 preset 与
手写路径都显式构造；不得退回隐藏可变状态。

### 4.3 preset 只保留编排便利

重构 `protocol-client` 与 `protocol-server` 的 preset：

- 复用 §4.1 的公开 context 纯函数；
- 通过普通 `incoming`/`outgoing`、公开连接操作和 profile hook 完成时序；
- 删除与公开原语同语义的 private helper；
- private 代码可以保留 bounded receive loop、preset policy 分派与错误归一化，因为这些是便利算法而不是特权能力；
- 状态推进仍完全由 `protocol-session` 在 packet wire commit 后执行，不新增可由调用者伪造的状态 setter。

## 5. 实施步骤

### 5.1 建立公开能力矩阵

逐阶段列出客户端和服务端的 producer、consumer、状态变化事件、所需 context、公开调用和现有 private 依赖：

- Handshake → Status/Login/Transfer；
- Status request/response 与 ping/pong；
- offline/online Login、compression、cookie/custom query、Login Success/ack；
- Client Information、Feature Flags、Known Packs、registry/tag sync、Configuration tasks、Finish/ack；
- Play Login、活动维度 context、初始世界；
- reconfiguration 回到 Play。

只对矩阵中真实缺口新增 API。认证、compression 和 extension route 若已能完全公开组合，不新增包装器。

### 5.2 提取 registry/context 公共逻辑

- 在 `protocol-vanilla-data` 添加 §4.1 的纯函数与 portable unit tests。
- 覆盖完整 registry、Known Packs 省略 NBT、定制静态 schema、缺失/空 biome、错误 dimension raw ID、dimension 不在 levels、
  非默认高度。
- client/server preset 改用公共函数，并保持各自面向 peer 的异常语义；需要模块异常时只在边界包装一次并保留 cause。

### 5.3 改为显式服务端 Play context

- 修改 `synchronizeInitialWorld` 签名并删除连接的 `playLogin` 字段与 preset 内部赋值。
- 更新初次进入 Play、respawn、reconfiguration、in-process 与 HeadlessMC 场景。
- 验证传入的 login 与 world dimension、view distance、entity ID 等现有约束，不依赖调用历史猜测。

### 5.4 添加真正的手写协商测试

在 `commonTest` 写一个共享场景，客户端和服务端均不调用 preset：

1. 先完成一次手写 Status，验证 Pong 后双方有序关闭。
2. 新连接完成 vanilla offline Login 与完整 Configuration。
3. 客户端从实际收到的 registry packets 构造并安装基础 context；服务端从即将发送的 Play Login 构造并提前安装 context。
4. Finish Configuration ack 的真实 wire effect 把双方推进到 Play。
5. 服务端发送 Play Login；客户端安装活动维度 context并继续读取下一批 Play packet。
6. 服务端用显式 login 调用 `synchronizeInitialWorld`；客户端确认 teleport/chunk batch，双方再交换 keepalive。

测试代码只导入 public 声明。因为 Kotlin test compilation 对 main 的 `internal` 有 friend 可见性，评审时额外以 API
dump/签名搜索确认 场景没有误用 internal；若可见性仍无法由元数据证明，再增加最小 external-consumer compile
smoke，而不是新建常驻聚合模块。

另加 fake profile 场景，证明手写编排可以按公开 hook 完成 extension route/context 修正；官方 peer 继续验证 preset，至少补一个
官方客户端或服务端使用公开手写端的场景，防止双方共享同一个错误假设。

### 5.5 恢复用户文档

- `protocol-client/README.md` 与 `protocol-server/README.md` 都明确：preset 是快捷方式，raw typed connection 是基础 API。
- 给出短小、完整、可复制的 vanilla offline 手写示例或分阶段链接；示例必须包括 context 安装、Play Login 和资源关闭。
- online、transfer、loader profile 只说明如何插入公开 auth/profile hook，不复制一个无法维护的超长分支树。
- 根 README 保持 preset quickstart，并链接模块级手写说明。

## 6. 不采用的方案

- **公开 `connection.playLogin` setter**：允许应用伪造“已发送”事实，仍保留隐藏时间耦合。
- **`markNegotiated()` / `markPlayReady()`**：把真实 wire state 变成人工标志，失败发送时容易提前提交。
- **把所有阶段包装成 builder/DSL**：这只是另一个高层 negotiator，不能证明原始 packet API 可组合。
- **复制 preset 私有 helper 到 README**：实现会漂移，且调用者无法复用修复。
- **只测试双方都手写的 happy path**：可能让同源错误互相抵消，必须保留官方 peer 证据。
- **把动态 registry context 固化进协商结果**：重配置后会过期，连接仍是唯一权威状态。

## 7. 验证顺序

Gradle wrapper 不并发执行：

1. `:protocol-vanilla-data:jvmTest`
2. `:protocol-client:jvmTest`
3. `:protocol-server:jvmTest`
4. `:protocol-client:allTests`
5. `:protocol-server:allTests`
6. 若改动 public dependency metadata，检查发布 POM/module metadata；只有元数据不能证明独立消费时才运行 external-consumer
   smoke
7. `git diff --check`、API 引用搜索，并人工核对 README 中每个符号均为 `commonMain` public API

实施完成后记录各目标实际运行结果、跳过目标及原因，不以“代码能编译”代替官方 peer 与错误路径验证。
