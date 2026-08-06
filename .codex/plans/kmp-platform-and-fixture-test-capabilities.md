# KMP 平台扩展与夹具测试能力路由

## 状态与范围

本文记录已经确认、尚需实施的平台与测试架构改动。实施时按本文执行；只有实际编译或运行证据证明 既定方案不可实现时，才重新评估对应改动。

本文只覆盖需要变化的部分：

- 为 `protocol-serialization` 增加 WasmJS Browser 与 D8 测试运行环境；
- 为四个依赖原始 TCP 的协议模块增加 Kotlin/JS Node target；
- 用稳定的 `fixturetest` 包节替代基于 `Official` 类名的全局夹具过滤；
- 在 `world-io` 中增加唯一的 `hostFilesystemTest` 中间测试源集；
- 修正 Apple Simulator 的 Fixture Host 环境变量转发。

本文关于 `fixturetest` 包过滤的决策取代
[`template-backed-minecraft-fixtures.md`](template-backed-minecraft-fixtures.md) 中按不同夹具能力维护多个测试名模式的方案；
关于 Host 文件系统测试布局的决策取代其他计划中将该 runner 留在 `commonTest` 的安排。

## 需要形成的 target 变化

| 模块                     | Gradle 改动                                         | 必须随之完成的测试接线                                                      |
|--------------------------|-----------------------------------------------------|-----------------------------------------------------------------------------|
| `protocol-serialization` | 在现有 `wasmJs` target 中增加 `browser()` 与 `d8()` | Browser、D8 只运行普通测试；所有夹具测试由全局包过滤排除                    |
| `protocol-transport`     | 增加 `js { nodejs() }`                              | 在 JS/Node 运行现有公共 framing、compression、encryption 与真实 socket 测试 |
| `protocol-session`       | 增加 `js { nodejs() }`                              | 在 JS/Node 运行现有 typed dispatch 与状态迁移测试                           |
| `protocol-client`        | 增加 `js { nodejs() }`                              | 在 JS/Node 运行普通测试和生产 client 对官方服务端的夹具测试                 |
| `protocol-server`        | 增加 `js { nodejs() }`                              | 在 JS/Node 运行普通测试和生产 server 对 headless client 的夹具测试          |

四个网络模块直接复用仓库所选 Ktor 已维护的 Node `node:net` 动态加载实现，不增加项目自有 Node socket 适配层。
`protocol-session`、`protocol-client` 和 `protocol-server` 继续通过现有向下依赖获得 transport 能力，不复制网络实现。

为使 `protocol-serialization` 的 JS/Node 官方服务端场景真正运行：

1. 给 `jsTest` 增加对 `:protocol-transport` 的测试依赖；
2. 将 `JsOfficialServerTransport` 从固定抛错实现改为与 JVM、Native、WasmJS/Node 相同语义的 Ktor TCP transport；
3. 删除 `protocol-serialization/build.gradle.kts` 中针对 `jsNodeTest` 的
   `OfficialServerInteropTest` 名称过滤；
4. 保证同一份 JS test compilation 仍能被 Browser 打包，Browser 端依靠全局 `fixturetest` 过滤而不会 调用 Node socket。

## 夹具测试包约定与全局过滤

### 包约定

所有会调用 `minecraft-test-support`、因而需要 Fixture Host 的带 `@Test` 入口都必须放在限定名中的独立
`fixturetest` 包节下：

```text
com.hiczp.minecraft.<module>.fixturetest
```

直接解引用 `hostWorkingDirectory` 并读取 Host 路径的入口使用唯一的细分类：

```text
com.hiczp.minecraft.world.io.fixturetest.hostfilesystem
```

本次至少移动并修改以下测试入口的 package：

| 模块                     | 测试入口                                                                |
|--------------------------|-------------------------------------------------------------------------|
| `nbt-serialization`      | `OfficialNbtInteropTest`                                                |
| `protocol-serialization` | `OfficialCodecInteropTest`、`OfficialServerInteropTest`                 |
| `protocol-client`        | `OfficialServerClientInteropTest`                                       |
| `protocol-server`        | `OfficialHeadlessClientInteropTest`，以及后续计划重命名后的同一测试入口 |
| `world-io`               | 各 JVM/desktop Native `OfficialWorldStorageInteropTest` 入口            |

测试 runner、fixture generator 或数据 helper 只有在其源码能力边界也需要隔离时才移动；Gradle 过滤的 稳定依据始终是带
`@Test` 入口的完整限定名，而不是类名中的 `Official`、`Server` 或 `Client`。

### `useMinecraftTestFixtures` 行为

保留当前 `useMinecraftTestFixtures` 的公开参数和总体 `if/else` 结构，不增加新的 DSL capability：

```text
wasmWasi*、*BrowserTest、*D8Test
    -> excludeTestsMatching("*.fixturetest.*")
    -> 不注册 Fixture Host、fixture inputs 或 Build Service

其他标准测试任务
    -> 注册调用方已声明的 fixture inputs 与共享 Build Service
    -> 不做全局夹具测试过滤
```

具体改动为：

1. 将 `MinecraftTestFixtures.kt` 中的 `excludeTestsMatching("*Official*")` 替换为
   `excludeTestsMatching("*.fixturetest.*")`；
2. 保留现有 `wasmWasi` 前缀及 `BrowserTest`、`D8Test` 后缀判断，因为仓库使用固定的标准 target 名称；
3. 删除因测试类改名而维护额外全局模式的需要；`fixturetest.hostfilesystem` 自动被基础模式覆盖；
4. 确认排除任务仍会执行同一 compilation 中的普通测试，且不会触发 Fixture Host 的准备或启动。

## `world-io` 的 `hostFilesystemTest`

在 `world-io/build.gradle.kts` 中创建一个 `hostFilesystemTest` 中间测试源集：

```text
commonTest
    <- hostFilesystemTest
        <- jvmTest
        <- mingwX64Test
        <- linuxX64Test
        <- linuxArm64Test
        <- macosArm64Test
```

实施要求：

1. `hostFilesystemTest` 显式 `dependsOn(commonTest)`；上述五个实际与 Fixture Host 共享文件系统命名空间的 标准平台测试源集依赖它；
2. 将 `OfficialWorldStorageInteropRunner` 从 `commonTest` 移到 `hostFilesystemTest`，并把 package 调整为
   `com.hiczp.minecraft.world.io.fixturetest.hostfilesystem`，使取得并打开 Host 绝对路径的代码不进入 device 或 Simulator
   compilation；
3. 保留现有平台薄入口，以继续允许 JVM 入口传入 `BasicFileAttributes.fileKey()` oracle，而 desktop Native 入口使用 runner
   的默认 identity 行为；所有这些入口都移动到
   `fixturetest.hostfilesystem` package；
4. 不创建新的 test target、独立测试任务、fixture capability 或 lifecycle task；该源集只共享源码并 表达 Host 文件系统能力；
5. 确认一次平台 test task 仍只执行一次官方 world generate/rewrite/reload 场景，不能因源集继承产生重复 annotated entry。

## Apple Simulator Fixture Host 环境变量

KGP 2.4.10 的 `KotlinNativeSimulatorTest` 通过 `/usr/bin/xcrun simctl spawn` 启动测试进程。修改
`MinecraftTestFixtures.kt` 的环境注入边界：

1. 当执行任务是 `KotlinNativeSimulatorTest` 时，将 RPC URL 和 owner ID 作为
   `SIMCTL_CHILD_MINECRAFT_TEST_FIXTURE_RPC_URL` 与
   `SIMCTL_CHILD_MINECRAFT_TEST_FIXTURE_OWNER_ID` 注入 `xcrun` 环境；
2. 其他任务继续注入无前缀的两个变量；
3. Simulator 子进程内继续通过原有无前缀名称调用 `getenv`，依赖 `simctl` 在转发时剥离
   `SIMCTL_CHILD_`；不修改 `minecraft-test-support` 的公共契约；
4. URL 与 owner ID 仍在 test task 的执行期、Fixture Host 启动并返回动态连接信息后注入，不提前解析 Build Service provider；
5. 保持环境值不参与可复用 task 输入，并验证 configuration cache store/reuse 不因该分支回退。

## 文档与约束同步

实现代码后同步修改会被本次决策改变的规范文字：

- 根 `AGENTS.md`：记录 `fixturetest` 包标记、Browser/D8/WASI 的全局过滤，以及 `world-io` 唯一允许的
  `hostFilesystemTest` 中间源集；
- `buildSrc/AGENTS.md`：记录按包过滤和 Simulator 的 `SIMCTL_CHILD_` 转发；
- `minecraft-test-support/AGENTS.md`：将 world-io Host 路径 runner 的位置更新为
  `hostFilesystemTest`；
- `protocol-serialization/AGENTS.md`：删除 JS/Node 排除 TCP 场景的旧说明，改为 JS/Node 可运行该场景， Browser/D8/WASI 排除全部
  `fixturetest`；
- `protocol-transport/AGENTS.md`：把真实 socket 测试平台补充为 JS/Node；
- `world-io/AGENTS.md`：记录 Host 文件系统 runner 与薄入口的中间源集边界。

仅在模块 README 存在与新增 target 或测试入口直接冲突的平台说明时才更新对应段落，不借本次工作重写 无关文档。

## 实施顺序

1. **原子迁移测试入口与过滤规则**
    - 移动所有列出的 annotated fixture entry 并修改 package/import；
    - 同一提交批次内把全局过滤改为 `*.fixturetest.*`，避免中间状态误跑或漏跑夹具；
    - 用静态搜索确认没有仍依赖 Fixture Host、但入口不含 `fixturetest` 包节的测试。
2. **扩展协议 target**
    - 先给 `protocol-transport` 增加 JS/Node 并使其真实 socket 测试通过；
    - 再按依赖顺序增加 `protocol-session`、`protocol-client`、`protocol-server` 的 JS/Node；
    - 最后增加 `protocol-serialization` 的 WasmJS Browser/D8 运行环境，并接通 JS/Node 官方 TCP 测试。
3. **隔离 world-io Host 文件系统场景**
    - 创建并接线 `hostFilesystemTest`；
    - 移动 runner 和各平台入口，保留 JVM file-identity oracle；
    - 检查 device/Simulator test compilations 不再包含该 runner 或 annotated entry。
4. **修正 Simulator 环境转发**
    - 在 buildSrc 的单一环境注入边界增加 Simulator 前缀映射；
    - 不改变 Fixture Host 服务生命周期和 test-support 读取名称。
5. **同步规范并执行分层验证**
    - 更新上述 AGENTS/必要 README；
    - 先 JVM，再 JS/Node、WasmJS D8、host Native，最后在 macOS 验证 Simulator。

## 验证计划

### 静态与 Gradle 图检查

- `rg` 检查所有 Fixture Host 测试入口都位于 `*.fixturetest.*`；
- `rg` 确认仓库不再存在 `excludeTestsMatching("*Official*")`，且
  `protocol-serialization` 不再局部排除 `jsNodeTest` 的 server interop；
- 检查新增 JS/Node 与 WasmJS Browser/D8 标准 task 均已注册；
- 对 Browser、D8、WASI test task 检查 task inputs/service wiring，确认没有附加 Fixture Host；
- 检查 `world-io` source-set graph，确认 Host runner 只进入 JVM 与列出的 desktop Native compilations。

### JVM 基线

```shell
./gradlew :nbt-serialization:jvmTest \
  :protocol-transport:jvmTest \
  :protocol-serialization:jvmTest \
  :protocol-session:jvmTest \
  :protocol-client:jvmTest \
  :protocol-server:jvmTest \
  :world-io:jvmTest
```

### JS/Node 与 WasmJS

```shell
./gradlew :protocol-transport:jsNodeTest \
  :protocol-session:jsNodeTest \
  :protocol-serialization:jsNodeTest \
  :protocol-client:jsNodeTest \
  :protocol-server:jsNodeTest

./gradlew :protocol-serialization:wasmJsD8Test
```

`jsNodeTest` 必须实际执行对应的 `fixturetest`，包括 production client/server 互操作和
`OfficialServerInteropTest`；`wasmJsD8Test` 必须运行普通测试并排除所有 `fixturetest`。

对新增 WasmJS Browser 环境至少完成 test compilation 与 bundle；在具备仓库外部 Browser 可执行环境时运行
`:protocol-serialization:wasmJsBrowserTest`，但 Browser 执行不升级为仓库常规 gate。

### Host Native 与 Apple Simulator

在对应 Host 上运行 `world-io` 的标准 desktop Native test task，至少覆盖当前实施环境；例如 Windows：

```shell
./gradlew :world-io:mingwX64Test
```

在 macOS 上用一个纯 RPC fixture 和一个真实 TCP fixture 验证 Simulator 转发：

```shell
./gradlew :nbt-serialization:iosSimulatorArm64Test \
  :protocol-serialization:iosSimulatorArm64Test \
  --configuration-cache
```

随后不改输入重复同一命令，要求 configuration cache reuse。Simulator 内的测试必须读取到无前缀环境变量、 连接 Fixture
Host，并且不包含 `world-io` Host 文件系统入口。

## 完成标准

1. 五项 target 配置变化均产生可编译的标准 KMP variant/test task；JS/Node 四个网络模块通过真实 socket 测试。
2. Browser、D8、WASI 会运行普通测试，但不会准备、启动或调用 Fixture Host。
3. 支持夹具的任务不再依赖测试类名；所有夹具入口由 `fixturetest` 包节稳定识别。
4. `world-io` 的 Host 路径代码只编入 JVM 与 desktop Native Host 测试，官方世界场景每个 task 只运行 一次并保留 JVM
   file-identity 断言。
5. Apple Simulator 能通过普通 `getenv` 名称取得连接信息并完成 RPC/TCP 夹具测试，configuration cache 可复用。
6. 没有新增公开 Gradle DSL、Browser 夹具桥接、独立 fixture task 或项目自有 Node socket 实现。
