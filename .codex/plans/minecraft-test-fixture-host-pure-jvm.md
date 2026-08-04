# 将 `minecraft-test-fixture-host` 转换为纯 JVM 模块

状态：待实施

## 目标

将 `minecraft-test-fixture-host` 从只有一个 JVM target 的 Kotlin Multiplatform 模块转换为使用
`org.jetbrains.kotlin.jvm` 的纯 JVM 模块，同时保持 Fixture Host 的行为、RPC 协议、源码、测试、资源、构建服务生命周期和其他业务模块不变。

这次迁移还应验证能否绕过 IDEA 的 KMP Gradle 依赖导入问题：Gradle 已能解析完整的 kotlinx-rpc JVM 类路径，但 IDEA 当前没有把
`kotlinx-rpc-krpc-core-jvm` 和
`kotlinx-rpc-krpc-serialization-json-jvm` 正确挂到 Fixture Host，因而在
`MinecraftTestFixtureHost.kt` 中产生 `KrpcConfigBuilder.Server` 和 `json()` 的错误提示。

## 范围

计划修改：

- `minecraft-test-fixture-host/build.gradle.kts`
    - 改用 Kotlin/JVM 插件。
    - 把 KMP 的 `jvmMain`、`jvmTest` 依赖声明改成 JVM 的 `implementation`、`testImplementation`。
    - 使用 JVM 插件的标准 `main`、`test` source set 和 `test` 任务。
- `minecraft-test-fixture-host/src`
    - 将生产代码和资源从 `jvmMain` 移到标准的 `main` 目录。
    - 将测试代码和资源从 `jvmTest` 移到标准的 `test` 目录。
- `minecraft-test-fixture-host/AGENTS.md`
    - 将局部验证命令从 `jvmTest` 更新为纯 JVM 模块的标准 `test`。
- `buildSrc/src/main/kotlin/com/hiczp/minecraft/protocol/buildScript/MinecraftTestFixtureService.kt`
    - 将 Fixture Host 运行时消费者从 KMP 的 `jvmRuntimeElements` 改为 JVM 插件的 `runtimeElements`。
- `buildSrc/src/main/kotlin/com/hiczp/minecraft/protocol/buildScript/OfficialMinecraftAnalysis.kt`
    - 将 codec oracle 源文件的声明路径更新为标准 `src/main/resources` 路径。

明确不修改：

- `minecraft-test-support`；它继续是 KMP 模块，并继续拥有跨平台 RPC contract、client 和 remote handles。
- `protocol-*`、`world-*`、`compression`、`nbt` 等运行时和测试消费者模块。
- RPC 服务接口、Fixture Host Kotlin 源码和对外协议。
- kotlinx-rpc、Ktor、Kotlin 或其他依赖的版本。
- Fixture Host 的 main class、标准输入控制协议、标准输出 READY 握手、进程池和清理行为。
- `settings.gradle.kts`、根项目插件配置和发布配置。

## 设计决定

1. 使用版本目录中已有的 `libs.plugins.kotlinJvm`，继续应用 `kotlinSerialization` 和 `kotlinxRpc`。
2. 保持 Java 25 toolchain 和 JVM bytecode target，不改变 `BuildVersions.JAVA_VERSION`。
3. 完整采用纯 JVM 项目的标准目录结构，不保留旧 KMP source-set 目录映射：
    - `src/jvmMain/kotlin` 移到 `src/main/kotlin`。
    - `src/jvmMain/resources` 移到 `src/main/resources`。
    - `src/jvmTest/kotlin` 移到 `src/test/kotlin`。
    - `src/jvmTest/resources` 移到 `src/test/resources`。
4. 使用 JVM 插件标准的 `test` 任务执行测试，不创建 `jvmTest` 兼容任务或第二个测试 source set。
5. Build Service 直接消费标准的 `runtimeElements`。不在 Host 中伪造一个名为 `jvmRuntimeElements` 的兼容 outgoing
   configuration。

## 实施步骤

### 1. 记录迁移前基线

- 保存 `minecraft-test-fixture-host` 当前的 Gradle 依赖声明、源码目录和测试资源清单。
- 记录 `jvmCompileClasspath` 中 kotlinx-rpc 与 Ktor 的解析结果。
- 通过 IDEA 检查记录当前错误：
    - `Cannot access class 'kotlinx.rpc.krpc.KrpcConfigBuilder.Server'`。
    - `kotlinx.rpc.krpc.serialization.json.json` 无法解析。
- 记录 IDEA 当前缺少的两个 JVM library roots，作为迁移后的对照。

### 2. 将 Host 构建模型改为 Kotlin/JVM

在 `minecraft-test-fixture-host/build.gradle.kts` 中：

- 将 `alias(libs.plugins.kotlinMultiplatform)` 替换为 `alias(libs.plugins.kotlinJvm)`。
- 保留 serialization 和 kotlinx-rpc 插件。
- 删除 `kotlin { jvm { ... } }` 这一层 KMP target 配置。
- 在 Kotlin/JVM extension 上保留 `jvmToolchain` 和 `compilerOptions.jvmTarget`。
- 将 `jvmMain.dependencies` 的内容迁移到顶层 `dependencies` 的 `implementation`。
- 将 `jvmTest.dependencies` 的内容迁移到 `testImplementation`。
- 不新增、删除或升级任何依赖；现有显式 Ktor WebSocket 对齐依赖继续保留。

### 3. 迁移到标准 JVM 目录结构

使用文件移动保留历史，将整个 Host source tree 改成标准 JVM 布局：

- `src/jvmMain/kotlin/**` → `src/main/kotlin/**`。
- `src/jvmMain/resources/**` → `src/main/resources/**`。
- `src/jvmTest/kotlin/**` → `src/test/kotlin/**`。
- `src/jvmTest/resources/**` → `src/test/resources/**`。

迁移后不在 build script 中添加旧目录映射。确认：

- `OfficialCodecOracle.java` 仍位于 resources 下，继续作为显式发布的 oracle 输入，而不会被 JVM Java 编译器当成 Host 源码编译。
- 测试用 Java 子进程 fixture 仍位于 `src/test/resources`，并进入标准 `test` runtime classpath。
- 包名、资源相对路径和类名不变。
- 旧的 `src/jvmMain`、`src/jvmTest` 空目录不保留。

### 4. 采用标准 JVM 测试入口并更新局部说明

- 使用 Kotlin/JVM 插件提供的标准 `test` 任务，不注册 `jvmTest` 别名。
- 将 `minecraft-test-fixture-host/AGENTS.md` 中的验证命令更新为
  `:minecraft-test-fixture-host:test`。
- 仓库级验证需要同时执行 Host 的 `test` 和其余 KMP 模块的 `jvmTest`；不在根项目创建替代测试任务。

### 5. 更新 Fixture Host 运行时变体消费

在 `MinecraftTestFixtureService.kt` 中，将项目依赖的显式 configuration 从
`jvmRuntimeElements` 改为 `runtimeElements`。

在 `OfficialMinecraftAnalysis.kt` 中，将 `publishCodecOracleSource()` 使用的路径从
`src/jvmMain/resources/com/hiczp/minecraft/test/oracle/OfficialCodecOracle.java` 更新为
`src/main/resources/com/hiczp/minecraft/test/oracle/OfficialCodecOracle.java`。

保持以下行为不变：

- `minecraftTestFixtureHostRuntime` 仍是可解析、不可消费且传递的 configuration。
- `prepareMinecraftTestFixtureHostRuntime` 仍由 Gradle `Sync` 准备完整运行时目录。
- Host JAR、`minecraft-test-support` 的 JVM variant 和全部第三方 runtime dependencies 仍由该配置传递解析。
- Build Service 仍然只从准备完成的运行时目录启动同一个 main class。

### 6. 检查变更边界

- 确认没有修改 `minecraft-test-support` 或任何 fixture consumer 的 build script。
- 确认没有改变 RPC contract、JSON payload、WebSocket 路径或环境变量。
- 确认没有生成或提交构建输出。
- 检查 `git diff`，确保实现改动仅限 Host 的构建脚本、Host 内部文件移动与局部说明，以及上述两处必要的 buildSrc 路径/变体接线。

## 验证顺序

### Gradle 模型与类路径

1. 重新加载 Gradle，确认 Fixture Host 在 IDEA 中显示为只有 `main`、`test` 的 Kotlin/JVM 模块，而不是 KMP JVM source-set 模块。
2. 检查 `compileClasspath` 和 `runtimeClasspath`：
    - `kotlinx-rpc-krpc-core-jvm:0.10.3` 存在。
    - `kotlinx-rpc-krpc-serialization-json-jvm:0.10.3` 存在。
    - Ktor JVM artifacts 保持为 `3.5.1`。
3. 检查 `runtimeElements` 能被根项目的 `minecraftTestFixtureHostRuntime` 成功解析，并包含 Host JAR 和所有运行时依赖。

### IDEA

1. 重新检查 `MinecraftTestFixtureHost.kt`。
2. 验证 `KrpcConfigBuilder.Server`、`serialization` 和 `json()` 不再显示错误。
3. 通过 IDEA 项目依赖模型确认上述两个 kotlinx-rpc JVM JAR 已挂到 Host。
4. IDEA 日志中其他 KMP 模块仍可能产生 project-level duplicate library 警告；验收重点是 Host 的 module classpath
   和源文件解析，不要求全项目日志完全没有该类警告。

### 测试与 Fixture Host 生命周期

按以下顺序执行，并保持 build cache 开启：

1. `.\gradlew.bat :minecraft-test-fixture-host:test`
2. `.\gradlew.bat prepareMinecraftTestFixtureHostRuntime --configuration-cache`
3. 不改文件重复第 2 条，确认 configuration cache 复用且运行时准备任务可复用。
4. 运行一个现有 Fixture Host 消费者的 JVM 测试，例如 `.\gradlew.bat :world-io:jvmTest`，验证 Host 能被 Build Service
   懒启动、完成 READY 握手、提供 RPC 并正常清理。
5. 最后运行 `.\gradlew.bat :minecraft-test-fixture-host:test jvmTest`，同时覆盖纯 JVM Host 和其余 KMP 模块的 JVM 测试；不要假定
   KMP 的 `jvmTest` task selection 会自动包含纯 JVM 模块的 `test`。

只有在需要排除缓存损坏且普通重复验证不足时才使用 `clean` 或 `--rerun-tasks`。

## 验收标准

- `minecraft-test-fixture-host` 仅应用 Kotlin/JVM、serialization 和 kotlinx-rpc 插件，不再应用 Kotlin Multiplatform 插件。
- Host 的生产代码、测试和资源分别位于标准的 `src/main`、`src/test` 下，并由 JVM `main`/`test` 模型拥有。
- `minecraft-test-support` 及所有其他业务模块保持原样。
- Build Service 能通过 `runtimeElements` 获得完整 Host runtime classpath。
- Host 使用标准 `test` 任务且测试发现数量与迁移前一致。
- Fixture Host 能由现有消费者懒启动和关闭。
- Gradle 类路径完整，IDEA 能解析 `KrpcConfigBuilder.Server` 与 kRPC JSON serialization DSL。
- 配置缓存能够存储并在不变重跑时复用。

## 风险与后续处理

- 纯 JVM 化会绕过 Host 自身的 KMP resolver，但项目中仍有其他 KMP 模块。如果 IDEA 的全局同名 library 冲突仍影响纯 JVM
  Host，则保留正确的 JVM 构建结构并把最小复现与日志补充到 KTIJ-37550；不要因此把
  `minecraft-test-support` 改为 JVM-only，也不要继续通过无关的直接依赖掩盖 IDE 模型错误。
- 如果 `runtimeElements` 没有提供预期的传递运行时闭包，应修正根 Build Service 的 variant attributes 或消费方式， 而不是在
  Host 中复制运行时文件或增加手工 classpath。
- 因为纯 JVM Host 不提供 `jvmTest`，仓库完整 JVM 验证应显式组合
  `:minecraft-test-fixture-host:test` 与现有 KMP `jvmTest`，不要创建自定义测试 source set 或根级替代测试任务。
