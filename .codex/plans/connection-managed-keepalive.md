# 连接级 KeepAlive 实施计划

- 状态：待实施
- 适用版本：仓库所选择的 Minecraft 官方版本
- 涉及模块：`protocol-session`、`protocol-server`、`protocol-client`

## 目标和范围

只增加以下能力：

1. 服务端连接可以启用或停用自动 KeepAlive，并可指定发送周期；默认周期与官方一致。
2. 服务端校验客户端回复的 challenge；回复不匹配或超过一个周期仍未回复时关闭连接。
3. 服务端 `negotiate()` 进入 Configuration 后默认启用该能力，进入 Play 后继续运行。
4. 客户端连接在 Configuration 和 Play 中无条件自动回复 KeepAlive。

不增加资源包策略、Login 超时、通用空闲超时、延迟指标或游戏逻辑。

## 行为约定

服务端连接提供语义等价于以下 API，最终命名遵循现有 Kotlin 风格：

```kotlin
fun enableKeepAlive(interval: Duration = DEFAULT_KEEP_ALIVE_INTERVAL)
fun disableKeepAlive()
```

- `interval` 必须为正值，默认值为官方的 15 秒。
- 重复启用是幂等操作，不覆盖已经启用的自定义周期；如需改变周期，先停用再启用。
- KeepAlive 只在 Configuration 和 Play 中发送，不在 Handshake、Status 或 Login 中发送。
- 停用后取消定时任务，清除 pending challenge 和尚未发送的内部 KeepAlive 请求。
- 连接关闭时自动停止 KeepAlive。
- 启用自动 KeepAlive 后，调用方不应再自行发送 KeepAlive；库只在文档中说明，不增加运行时检查。

计时与官方保持一致：

```text
t=0    开始计时
t=15   发送 A，记录 Pending(A)
t=20   收到匹配的 A，清除 pending
t=30   发送 B；如果此时 A 仍 pending，则关闭连接
```

回复时间不会推迟下一次发送时间，因此上例中下一次发送是 `t=30`，不是 `t=35`。

## `protocol-session` 改动

### 服务端定时与回复校验

服务端连接持有一个定时协程、一个内部 KeepAlive Channel，以及原子一致的 `disabled/idle/pending(challenge)` 状态。

启用后的每个周期执行：

```text
pending 存在
  → 上一个请求超时，关闭连接

pending 不存在
  → 生成 Long challenge
  → 先记录 Pending(challenge)
  → 向内部 KeepAlive Channel 提交发送请求
```

内部 Channel 容量为 1，并采用非阻塞提交，避免 writer 堵塞时定时协程无法执行下一次超时检查。提交失败按连接失败处理。

server reader 在投递公开 `incoming` Channel 之前处理 Configuration/Play KeepAlive 回复：

- 当前为 `Pending(expected)` 且 challenge 相同：清除 pending 并消费该包；
- challenge 不同，或当前没有 pending：关闭连接；
- 自动 KeepAlive 未启用：不拦截，仍投递给公开 `incoming` Channel。

定时检查、回复校验和停用操作必须同步，保证同一时刻只有一个 pending challenge。关闭使用连接现有的失败终止路径，同时结束
reader、writer 和定时协程。

### writer

保持唯一 writer，不从定时协程直接写 socket。writer 处理：

```text
flushRequests
public outgoingChannel
internal keepAliveChannel
```

- 内部请求根据当前状态编码成 Configuration 或 Play KeepAlive packet，并立即 flush。
- 每个 packet 写完后以及 flush 排空公开队列时，都必须重新检查内部 Channel。
- 内部请求已就绪时，在下一个 packet 边界优先处理；不能抢占正在写出的 packet，也不绕过 framing、compression、encryption 或 TCP
  顺序。

### 客户端自动回复

client reader 在公开投递之前识别 Configuration/Play clientbound KeepAlive，向内部 Channel 提交携带同一 challenge
的回复并消费原包。 回复仍由唯一 writer 编码、写出并 flush。

客户端不提供开关、不维护 pending，也不主动生成 challenge。删除 `protocol-client` 高层 Configuration 协商中现有的重复回复分支，使
Configuration 和 Play 都只由连接底层回复。

## `protocol-server` 集成

服务端 `negotiate()` 在确认连接进入 Configuration 后调用：

```kotlin
enableKeepAlive()
```

若调用方此前已使用自定义周期启用，幂等语义保证默认调用不会覆盖它。`negotiate()` 返回 Play 后，连接级定时协程继续运行，直到停用或
连接关闭。

## 测试

使用 `runTest` 和虚拟时间覆盖：

- 首次发送、正确回复，以及下一次发送仍以发送时刻为基准；
- 一个周期后仍 pending 时关闭连接；
- challenge 不匹配或无 pending 回复时关闭连接；
- 启用、重复启用、停用和连接关闭；
- Configuration/Play 使用正确方向的数据包，其他状态不发送；
- 内部 KeepAlive 在公开 outgoing 持续有数据或 flush 排空时不会饥饿；
- 客户端在 Configuration/Play 原样回复 challenge，且 KeepAlive 不进入公开 `incoming` Channel；
- 服务端 `negotiate()` 默认启用，并在进入 Play 后保持运行。

依次验证：

```shell
./gradlew :protocol-session:jvmTest
./gradlew :protocol-client:jvmTest
./gradlew :protocol-server:jvmTest
```

实现时同步更新相关 KDoc、README，以及当前禁止底层自动回复的本地 `AGENTS.md` 规则，只为 KeepAlive 增加明确例外。

## 完成条件

- 服务端启停、周期发送、精确 challenge 校验和超时关闭均按上述语义工作；
- `negotiate()` 默认启用服务端 KeepAlive；
- 客户端在 Configuration 和 Play 中完全由连接底层回复；
- KeepAlive 通过唯一 writer 在 packet 边界优先发送；
- 上述测试通过，文档明确调用方启用自动功能后不应手动发送 KeepAlive。
