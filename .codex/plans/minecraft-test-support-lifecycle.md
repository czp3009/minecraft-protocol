# Minecraft 测试进程资源与构建配置重构计划

## 状态

本文记录本轮讨论中已经确认的设计，作为后续实现与验收的唯一计划基线。

本轮设计已经全部确定，包括各子项目声明 `hostProcessTest` 所需官方资源的最终写法。本文更新完成后先交由用户审阅；在用户明确确认计划之前，不开始代码、Gradle
配置或文档重构。

## 目标

- 将 `minecraft-test-support` 做成一个普通的 Kotlin Multiplatform 测试工具库，使用体验接近 Testcontainers：调用库 API
  即可创建、操控和关闭一个外部 Minecraft 进程资源。
- 让进程、运行目录、端口、日志、准备步骤和兜底清理由库完整拥有，调用方不需要了解内部路径、Gradle 任务或启动器细节。
- 用标准 KMP 测试源集表达平台能力，用标准平台测试任务和 `allTests` 执行测试，不再创建特殊的“外部进程测试任务”。
- 让 Gradle 继续负责固定版本官方资源的下载、校验、缓存和任务依赖；测试运行期间只使用已准备好的本地资源，不访问互联网。
- 保留 Gradle 的增量构建、UP-TO-DATE 和构建缓存语义，并适配低内存开发环境。

## 已确定的公共 API

### `MinecraftTestSupport` 门面

- 对外提供单一门面 `object MinecraftTestSupport`。
- 提供 `suspend fun newOfficialServer(...)`，并以相同原则提供官方客户端资源的创建函数。
- `newOfficialServer` 不接受 Minecraft 版本：整个仓库只使用 `MinecraftTarget.MINECRAFT_VERSION`
  选定的一个版本，测试支持库读取与该版本对应的运行时事实并校验 Gradle 已准备的资源。
- 调用方不能传入官方 JAR 路径、缓存目录、运行目录、Java 路径、UUID 或 Gradle 任务信息。允许的参数只应是测试确实需要改变的语义配置。
- 创建函数在外部进程已经通过确定性的就绪检查后才返回；端口绑定失败时，在返回资源之前完成有限重试。
- 返回的官方服务端/客户端资源实现 common Kotlin 可用的 `AutoCloseable`，正常调用方式是 `.use {}`，不增加自定义生命周期接口。
- 返回值公开测试真正需要的信息和操作，例如端口/端点、存活状态、必要的日志诊断和服务端命令；内部缓存路径、启动器布局和清理实现保持封装。

示意 API 只表达调用体验，不冻结具体类型名或可选语义参数：

```kotlin
@Test
fun testFunc() = runTest {
        MinecraftTestSupport.newOfficialServer().use { officialServer ->
            val port = officialServer.port
            // 执行协议场景
        }
    }
```

### 共享策略

- 不实现隐式实例池、配置到实例的全局缓存、脏数据标记或类似 Spring Context 的自动复用规则。
- 是否共享一个服务端/客户端实例完全由普通测试代码的作用域决定：测试方法内的 `.use {}` 表示单测试独占；在 `@BeforeAll` 创建并在
  `@AfterAll` 关闭，表示一组测试显式共享。
- JVM、Native、Node 等平台测试分别运行在不同程序实例中，因此各自拥有独立的 `MinecraftTestSupport` 状态和外部进程，这符合预期。

## 资源生命周期

### 全局所有权

- `cleanupScope` 只属于 `MinecraftTestSupport` 单例，在每个测试程序实例中全局唯一。
- 该 scope 使用协程和已有平台调度能力，例如 `SupervisorJob() + Dispatchers.Default`；不为清理再创建专用线程、线程池或额外的清理进程。
- `MinecraftTestSupport` 维护全局资源注册表。注册表保存实际的受管资源/进程句柄、运行目录和生命周期状态，不能只保存
  PID，也不能只保存 UUID。
- 只保存 PID 会引入 PID 复用和资源归属不完整的问题，因此统一清理必须对库直接创建并持有的资源对象执行。

### 创建

1. 解析仓库根目录、所属模块的 `build` 目录、固定 Minecraft 版本、Java 可执行程序和 Gradle 已准备的不可变官方资源。
2. 在规定的版本目录中原子创建一个 UUID 运行目录。
3. 写入该实例需要的配置和日志目标，选择内部端口并直接启动进程。
4. 将资源登记到全局注册表。
5. 根据可观察的进程输出、端口或协议事件等待就绪；不得靠任意 `delay`、sleep 或超长测试超时猜测就绪。
6. 如果任一步失败，关闭已经创建的进程/日志资源并调度删除已经创建的目录，不能泄漏半初始化资源。

### `close()`

- `close()` 定义在创建函数返回的资源类型上。
- `close()` 必须幂等，通过原子状态将资源从 `OPEN` 转换为 `CLOSING`；重复调用不重复关闭进程或删除目录。
- `close()` 只把关闭工作提交给 `MinecraftTestSupport.cleanupScope`，随后立即返回。
- 清理协程负责请求进程退出、必要时终止库直接启动的进程、关闭日志资源、删除该资源拥有的 UUID 目录、从注册表注销资源并转换为
  `CLOSED`。
- `close()` 返回时不保证进程已经退出，也不保证目录已经消失。每个实例由 UUID 目录隔离，因此旧实例的异步收尾不阻塞下一个测试实例的创建。
- 不引入额外进程或线程来换取同步关闭语义。

### 统一与兜底清理

- 提供幂等的统一关闭入口，对注册表内全部 `OPEN`/`CLOSING` 资源发起清理；它仍遵循异步清理模型。
- 各支持平台安装最小的 shutdown hook/进程退出回调作为测试代码忘记关闭资源时的 best-effort 兜底。
- `.use {}`、`@AfterAll` 等显式关闭仍是主路径；强制杀死测试进程、运行时崩溃或断电时不承诺完成目录清理。
- 测试库只关闭自己直接启动并持有的进程，不扫描或终止系统中的同名进程。

## 运行目录与不可变资源

每个消费者模块的运行目录统一为：

```text
<module>/build/test-runtimes/
├── official-server/<version>/<UUID>/
└── official-client/<version>/<UUID>/
```

- 服务端和客户端使用相同的 `<version>/<UUID>` 分层，不再生成 `run-*`、测试名或其他动态层级。
- 每个返回资源只拥有并清理自己的 UUID 目录。
- UUID 分配使用“生成 UUID + 原子创建目录”的循环；目录已存在就重新生成。文件系统本身同时覆盖同一程序、遗留目录和并发测试程序的碰撞，不额外维护“本次已使用
  UUID”集合。
- 官方服务端、客户端、资源文件、HeadlessMC 和其他已验证输入是共享的只读 Gradle 构建资源，保留在根项目版本化缓存下；运行资源不得复制所有权或在关闭时删除它们。
- HeadlessMC 的运行工作目录是该客户端实例自己的 UUID 目录，不是共享的 `versions` 目录。其已准备的版本布局仍按 Minecraft
  版本放在共享不可变资源中，不能把 `versions` 当作进程工作目录。

## `hostProcessTest` 的已确定语义

- `hostProcessTest` 是普通 KMP 中间测试源集，没有自定义运行器、任务类型或生命周期魔法。
- 它表示能够在宿主机创建进程的平台集合：JVM、桌面 Native，以及项目实际支持创建子进程的 Node JS/Wasm 运行时；浏览器和移动端不加入该源集。
- 需要真实外部 Minecraft peer 的 E2E 场景放在 `hostProcessTest`，普通可移植单元测试继续放在 `commonTest`。
- 现有消费者模块中的 `externalProcessTest` 统一重命名为 `hostProcessTest`，并同步更新源码目录、文档和最近的模块级
  `AGENTS.md`。
- 各平台仍由标准 `jvmTest`、Native test、Node test 和 `allTests` 执行，不增加 root `test`、interoperability test
  或其他自定义聚合测试任务。
- `hostProcessTest` 本身不负责下载、不注入系统属性、不创建进程；进程生命周期完全由其测试代码调用 `minecraft-test-support`
  管理。

### 子项目最终配置

`createHostProcessTestSourceSet` 固定创建名为 `hostProcessTest` 的源集。子项目只声明它需要的完整语义
fixture，并可在可选配置块中添加真正属于该模块的额外测试依赖：

```kotlin
createHostProcessTestSourceSet(
    requiresOfficialServer = true,
) {
    dependencies {
        implementation(libs.someTestOnlyLibrary)
    }
}
```

- 所有 `requires...` 参数默认为 `false`，拼写统一使用 `Official`。
- `requiresOfficialServer = true` 表示一个完整的官方服务端 fixture，包括服务端 JAR、校验元数据及启动该资源所需的全部已准备输入，而不是某一个底层下载
  task。
- `requiresOfficialClient = true` 表示一个完整的官方客户端 fixture，内部包含客户端 JAR、libraries、assets、HeadlessMC
  launcher 和已准备的 `versions/<version>` 布局；调用方不需要了解这些组成任务。
- codec oracle 等确实独立的语义 fixture 使用同样的 `requires...` 原则；参数表达测试能力，不暴露下载、解压或编译任务的层次。
- helper 自动给消费者模块的 `hostProcessTest` 添加 `implementation(project(":minecraft-test-support"))`。调用方不重复声明这个必需依赖。
- `:minecraft-test-support` 自己使用同一 helper 时不添加自依赖；其 test compilation 本来就能访问本模块 main 输出。
- 配置块只用于模块特有的其他测试依赖或普通 source-set 配置；没有额外配置时可以省略。
- 该写法虽然使用布尔参数，但不同于旧 `officialDownloads` 布尔 DSL：参数在创建 `hostProcessTest` 时立即、精确地映射语义
  fixture，不通过根项目 `afterEvaluate` 事后扫描任务。

## Gradle 与平台配置调整

### Android 与版本常量

- `buildSrc` 中的 `BuildVersions` 是构建平台版本的单一来源：Java 25、Android `minSdk = 34`、Android `compileSdk = 36`。
- Minecraft 版本仍只由 `MinecraftTarget.MINECRAFT_VERSION` 选择，不与 Java/Android 版本耦合。
- 各 KMP 子项目使用官方 `kotlin { android { ... } }` DSL，替换
  `targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java)` 及相应类型导入。
- 保留各模块 namespace、Android host test 配置以及 Java 25 JVM/Android 字节码目标。

### 测试网络环境

- 删除 Node/Wasm 测试任务中的 `environment("NODE_USE_ENV_PROXY", "1")`。
- 单元测试和 E2E 测试只读取本地已准备资源并访问 loopback，不应访问互联网。
- 删除该环境变量不影响 Gradle、Maven、npm/Yarn 自己的依赖获取；它只是不再把代理配置注入测试程序。

### 官方资源下载与 Gradle 语义

- 所有官方资源下载继续是根项目 Gradle task，运行于 JVM，并统一使用 Ktor Java engine，避免引入其他网络引擎的额外平台问题。
- 自定义下载与 Gradle 保持一致，使用 JVM system properties（如 `http.proxyHost`、`https.proxyHost` 及认证属性）和 JDK
  默认代理选择器；不在任务中另行解释 `HTTP_PROXY`/`HTTPS_PROXY` 环境变量。
- 所有外部 HTTP 获取统一具备有限重试、请求/连接超时、流式写入、临时文件、长度和摘要校验以及失败后的临时文件清理。
- EOF、连接中断、代理返回截断内容、HTTP 状态错误和校验失败要转换为包含资源 URL、重试结果、代理配置状态和排查方向的人类可读
  Gradle 错误；底层异常作为 cause 保留供 `--stacktrace` 使用。
- 日志遵循 Gradle task 执行语义：任务被判定为 `UP-TO-DATE` 或命中缓存时不进入 task
  action，也不打印“正在下载/已下载”日志；只有实际发起或完成下载时才记录对应消息。
- 下载任务准确声明固定版本、URL/元数据等输入以及文件/目录输出。成功输出存在且输入、实现未变化时，由 Gradle
  直接跳过任务；任务代码不再做一套独立的“是否新鲜”调度判断。
- task action 内仍必须校验本次网络响应和将要提交的产物。这属于输出正确性，而不是绕过 Gradle 的自制 up-to-date 检查。
- 所有共享资源按 Minecraft 版本位于根项目 `build` 下。HeadlessMC 的下载文件也纳入版本根目录；其客户端版本布局必须包含完整的
  `versions/<version>/...` 层次。
- 保留构建缓存。首次缺少资源时允许联网准备；之后应由声明的输入、输出和生产者 provenance 决定 UP-TO-DATE 或缓存恢复。

### 移除旧下载 DSL

- 删除 `officialDownloads { server()/client()/headlessMc()/codecOracle() }` 布尔 DSL、`OfficialDownloadsExtension`
  以及根项目依靠 `subprojects`/`afterEvaluate` 扫描这些布尔值的接线。
- 删除当前只对 `org.gradle.api.tasks.testing.Test` 做 `dependsOn` 的间接实现；它不能正确表达 Native/Node 标准测试任务的资源输入。
- `buildSrc` 可以暴露语义明确、lazy 的根任务输出/Provider，例如官方服务端、客户端、资源、HeadlessMC 或 codec oracle
  的已准备输出；消费者不使用字符串任务路径。
- 不把官方服务端 JAR伪装成 source set 的源码、普通 resources 或运行 classpath。那会造成复制/打包或错误的依赖语义。
- 普通 `KotlinSourceSet` 不是 Gradle task，不能直接对任意外部 fixture 文件建立执行依赖。真正消费资源的是各平台标准测试执行任务；应由这些任务把
  producer 的输出 Provider 声明为输入，让 Gradle 根据 Provider provenance 自动推导生产任务依赖，而不是手写 `dependsOn`。
- `createHostProcessTestSourceSet` 根据 `requires...` 参数选择相应的 lazy 输出 Provider，并将它们注册到所有实际执行该源集的
  JVM、host Native 和 Node 标准测试任务的 `inputs.files(...)`。fixture 不进入源码、resources 或运行 classpath。
- 模块作者不需要知道 KMP 派生出的测试任务名称，也不手写 `dependsOn`；Gradle 根据这些输入 Provider 的生产者 provenance
  自动建立准备任务依赖。

## 实现顺序

1. 开工前读取根级以及每个待改模块最近的 `AGENTS.md`，但此时不把本机验证限制写成仓库政策。
2. 在 `minecraft-test-support` 内建立 `MinecraftTestSupport` 门面、全局 cleanup scope、资源注册表和幂等状态机。
3. 将现有环境、官方服务端和 HeadlessMC 客户端实现收进门面内部，统一创建失败回滚、就绪检测、异步关闭、日志和目录所有权。
4. 将服务端和客户端目录改为 `official-*/<version>/<UUID>`，删除旧 `run-*`/名称前缀目录策略。
5. 重构使用方测试：在测试方法或 `@BeforeAll`/`@AfterAll` 中显式持有资源，不再自行准备路径、进程或 Gradle 属性。
6. 将所有 `externalProcessTest` 重命名为 `hostProcessTest`，实现 `requires...` 语义参数、自动测试支持依赖和 Provider
   到标准测试任务输入的接线。
7. 替换 Android target 配置 DSL，删除 Node/Wasm 测试代理环境变量。
8. 删除旧 `officialDownloads` DSL、根项目 `afterEvaluate` 和间接 task wiring，清理失效的辅助类型和兼容 API。
9. 完成所有代码和构建重构后，遍历并核对仓库内全部 `AGENTS.md`、全部 `README.md`，以及 `.agents/skills/**` 下的全部 skill
   文件；修正与最新 API、源集名称、目录结构、Gradle 任务和验证方式不一致的内容。
10. 按下述顺序执行 clean build、聚焦测试、跨平台测试和最终 `allTests`，再完成增量/日志检查与 diff 审核。

## 测试与验收

### 聚焦验证

- `MinecraftTestSupport` 固定使用项目 Minecraft 版本，公共创建 API 不提供版本参数。
- 服务端/客户端创建后已经就绪，并能提供有效端口/端点、进程状态、日志诊断和必要控制能力。
- 运行目录严格匹配 `official-server/<version>/<UUID>` 和 `official-client/<version>/<UUID>`。
- 原子建目录能处理 UUID 碰撞、遗留目录和两个测试程序同时运行。
- `close()` 可重复调用且快速返回；实际进程退出、日志关闭、目录删除和注册表注销由全局协程 scope 完成。
- 统一关闭能覆盖仍为 `OPEN` 或 `CLOSING` 的全部资源；创建中途失败不会泄漏已启动进程或目录。
- 显式在测试生命周期中持有一个资源时，多项测试能共享它；不显式共享时不会发生隐式复用。
- 各平台只编译和运行其能力允许的测试，浏览器/移动端不获得伪实现或假通过测试。
- 下载任务在真实下载失败时给出友好错误，在成功后的相同输入上为 `UP-TO-DATE`/缓存命中，且跳过时没有下载日志。
- 不通过任意 sleep、`delay` 或超长 timeout 让进程/协议测试通过；使用 readiness、process exit、`await`、`join`、channel 或
  `CompletableDeferred` 表达顺序。

### 低内存执行策略

- 这里的单 worker 限制仅用于当前这台低内存机器上的本次实现与验证，不是项目自身要求，也不得写入任何 `AGENTS.md` 作为长期开发规则。
- 因为本次会改变测试运行目录和共享资源布局，完成全部重构及文档同步后，先让 Gradle 清理旧 `build` 内容，不手动删除目录：

```shell
./gradlew clean --max-workers=1 --no-parallel
```

- `clean` 后再开始编译和测试，避免旧目录、旧产物或旧 task 输出状态干扰新布局；允许首次验证重新准备已被清除的官方资源。
- 迭代时先运行受影响模块的 `jvmTest`，再把 JVM、Native、Node 等平台分开编译/测试。
- 不使用 `gradle build` 或并行 `allTests` 作为首轮反馈。
- 在当前机器上的最终完整验证使用单 worker：

```shell
./gradlew allTests --max-workers=1 --no-parallel
```

- 保持构建缓存开启，不使用 `--no-build-cache`。
- 不给单元测试配置超长 timeout，尽可能保持测试框架和 Gradle 的默认值。
- 最后执行 `git diff --check`，并针对官方资源准备任务做一次复跑，确认增量跳过和日志行为。

## 开工门槛

本计划文件更新完成后立即停止。只有用户审阅本文件并明确确认可以执行后，才开始修改实现、Gradle 配置、测试、`AGENTS.md`、
`README.md` 或 skill 文件。
