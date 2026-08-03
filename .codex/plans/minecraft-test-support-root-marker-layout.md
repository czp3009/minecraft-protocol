# Minecraft Test Support 根标识与根级运行目录计划

## 状态

本文记录已经确认的 `minecraft-test-support` 根目录定位和运行文件布局方案，供后续实现与验收使用。

本文是对 `minecraft-test-support-lifecycle.md` 的专项补充，并取代其中“发现消费模块并将运行文件写入
`<module>/build`”的设计。其他已经确认的进程生命周期、官方资源准备、`hostProcessTest` 和标准 KMP 测试任务设计保持不变。

本文件完成后先交由用户审阅；在用户明确要求实施之前，不修改实现、Gradle 配置、测试或文档。

## 目标

- 保持 `minecraft-test-support` 为普通 Kotlin Multiplatform 测试库，测试代码直接调用
  `MinecraftTestSupport` API，不添加自定义测试任务、运行器或命令行入口。
- 不通过 Gradle system property、环境变量、生成源码或生成资源向测试运行时注入路径。
- 使用仓库根目录唯一的固定标识文件确定 repository root，删除根据工作目录名称猜测消费模块的逻辑。
- 将 `minecraft-test-support` 产生的全部可变运行文件集中到根项目 `build` 下的独立命名空间。
- 保持官方 Minecraft 下载、校验、准备、UP-TO-DATE 和 build-cache 语义不变。
- 保持 `hostProcessTest` 对语义 fixture producer 的现有 lazy Gradle 依赖链不变。

## 非目标

- 不让测试运行时代码调用 Gradle、Gradle Tooling API 或 `gradlew`。
- 不让测试运行时代码下载、更新或修复官方 Minecraft fixture。
- 不把官方 JAR、客户端资源或 codec oracle 复制到每个消费模块。
- 不把根目录绝对路径编译进 `minecraft-test-support` 二进制或生成代码。
- 不根据调用栈、类路径、可执行文件路径、模块名编码或 npm package 目录推断消费模块。
- 不改变 Minecraft 版本的单一来源，也不改变官方资源的版本化目录语义。

## 根标识契约

### 标识文件

- 在仓库根目录提交唯一文件 `.minecraft-protocol-root`。
- 标识文件是稳定的源树文件，不由 Gradle 生成，不放在 `build/`，也不会被 `clean` 删除。
- 文件包含固定的一行 schema/magic，例如 `minecraft-protocol-root-v1`；运行时同时校验文件类型和内容，避免只凭同名文件误判。
- 文件名和 magic 一旦发布到本仓库测试基础设施中就视为内部稳定契约；需要升级时显式提升版本并同步实现和测试。
- 标识文件不包含绝对路径、Minecraft 版本、机器信息或其他动态数据，避免影响仓库可迁移性。

### 查找算法

1. 使用 `SystemFileSystem.resolve(Path("."))` 获得测试进程当前目录。
2. 从当前目录开始逐级检查祖先目录。
3. 第一个包含合法 `.minecraft-protocol-root` 文件的目录就是 repository root。
4. 找不到时立即失败，错误信息包含起始目录、期望的标识文件名，并提示测试必须从本仓库目录树内运行。
5. 不再回退到 `settings.gradle.kts`、`.git`、`gradlew`、模块 `build.gradle.kts` 或模块名匹配。

该方案仍要求测试进程的工作目录位于仓库目录树内。这与当前从工作目录向上查找
`settings.gradle.kts` 的前提相同，但唯一标识消除了嵌套 Gradle build 和普通同名文件造成的歧义。

## 根级目录布局

Gradle 拥有的不可变 fixture 和测试库拥有的可变运行文件使用互不重叠的目录：

```text
<repository>/
├── .minecraft-protocol-root
└── build/
    ├── protocol-reference/
    │   └── <version>/
    │       ├── mojang-server/
    │       ├── mojang-client/
    │       ├── headlessmc/
    │       └── codec-oracle/
    ├── generated/official-minecraft/
    └── minecraft-test-support/
        ├── runtimes/
        │   ├── official-server/<version>/<UUID>/
        │   └── official-client/<version>/<UUID>/
        ├── reports/<UUID>/...
        └── tmp/<UUID>/...
```

### 所有权规则

- `build/protocol-reference` 和 `build/generated/official-minecraft` 只由现有 Gradle producer task 写入。
- `minecraft-test-support` 只读取并校验上述不可变 fixture，绝不在其中创建日志、配置、临时文件或运行目录。
- `minecraft-test-support` 的所有写入只能位于 `build/minecraft-test-support`。
- 每个服务端、客户端、报告和临时工作单元使用原子创建的 UUID 目录隔离。
- 每个测试进程和资源对象只能删除自己直接拥有的 UUID 目录；不得删除共享父目录或其他进程的目录。
- 正常资源关闭负责清理自己的目录；根项目 `clean` 负责清除崩溃或强制终止后遗留的根级运行文件。

### `MinecraftTestLayout` 调整

- 保留 `repositoryRoot` 和固定 Minecraft 版本。
- 删除 `moduleBuildDirectory` 和 `discoverOwningModule`。
- 增加或派生 `repositoryBuildDirectory = <repositoryRoot>/build`。
- `versionCacheRoot` 继续指向 `<repositoryRoot>/build/protocol-reference/<version>`。
- runtime、report、scratch 和 temporary 路径全部改从
  `<repositoryRoot>/build/minecraft-test-support` 派生。
- `processFixtureSource` 等仅属于本仓库测试的源树访问继续从 `repositoryRoot` 派生，不恢复模块猜测。

## Gradle build directory 不变量

运行时库无法访问 Gradle `Project.layout.buildDirectory`，因此本方案明确把根项目构建目录固定为
`<repository>/build`。当前根项目使用的就是该默认布局。

- 后续不得单独把 root project 的 `layout.buildDirectory` 重定向到仓库外或其他名字，而不同时重新设计此契约。
- 子项目是否改变自己的 `buildDirectory` 不再影响 `minecraft-test-support`，因为测试库不再写入子项目目录。
- 标识文件不记录动态 build directory；这样它在不同 checkout 和机器之间保持完全相同。

## 与 Gradle 下载及缓存任务的边界

### 保持不变的 producer 输出

现有官方资源任务继续使用 root project `layout.buildDirectory` 下的精确输出：

- `build/protocol-reference/version_manifest_v2.json`
- `build/protocol-reference/<version>/version.json`
- `build/protocol-reference/<version>/mojang-server/...`
- `build/protocol-reference/<version>/mojang-client/...`
- `build/protocol-reference/<version>/headlessmc/...`
- `build/protocol-reference/<version>/codec-oracle/...`
- `build/generated/official-minecraft/<version>/...`

不修改这些 task 的输入、输出、task implementation 或 Provider provenance。新增根标识文件不是下载任务输入，
`build/minecraft-test-support` 也不与任何 producer 的 `@OutputFile`/`@OutputDirectory` 重叠。因此：

- 新增标识文件不会使下载任务失去 UP-TO-DATE 状态或 build-cache 命中；
- 测试运行时文件不会进入下载任务的缓存归档；
- 仓库移动后，fixture task inputs 继续通过 `PathSensitivity.RELATIVE` 保持可迁移；
- 根 `clean` 删除下载 fixture 和运行目录后，fixture 仍按原逻辑从 build cache 恢复，缺少缓存时才联网准备。

`build/minecraft-test-support` 中的文件都是临时运行副作用，不把它们作为下载任务的输入或输出。若某类报告未来要求在 测试任务命中
build cache 后也能恢复，应由其拥有者另行建模为标准测试报告或明确 task output，不能依赖当前临时目录。

## `hostProcessTest` 与 fixture producer 依赖

保留当前语义 fixture wiring：

```text
standard JVM/host Native/Node test task
└── inputs.files(selected fixture outputs)
    └── FileCollection carrying TaskProvider provenance
        └── official download/preparation producer tasks
```

- `createHostProcessTestSourceSet` 继续通过 `requiresOfficialServer`、`requiresOfficialClient` 和
  `requiresCodecOracle` 表达消费模块所需的完整 fixture。
- `OfficialMinecraftFixtureOutputs` 继续封装 root producer 的 lazy output providers。
- 标准测试执行任务继续把选中的 fixture outputs 声明为 `inputs.files`，并保持
  `PathSensitivity.RELATIVE`。
- 不手写字符串 task path 或 `dependsOn`；Gradle 继续从 Provider provenance 推导 producer 依赖。
- 不把 root marker、repository root 或运行目录注入测试任务。
- `minecraft-test-support` 本身不注册测试任务；测试仍由 Kotlin Gradle Plugin 的标准 JVM、Native 和 Node test task
  执行，进程资源仍由单元测试代码直接调用库 API 创建。

需要明确区分两件事：

- fixture input wiring 是构建图对不可变测试前置资源的声明；
- `MinecraftTestSupport.newOfficialServer()` 等 API 是测试运行时对外部进程资源的直接管理。

测试运行开始后 Gradle task graph 已经固定，因此运行时 API 调用不可能自动补加下载任务。如果删除
`requires...`/`inputs.files` wiring，库只能使用碰巧已经存在的 fixture 或明确失败；本计划不删除该 wiring。

## 并发、清理和缓存注意事项

- 多模块、多 target 和多个 Gradle 测试进程会共享 `build/minecraft-test-support` 父目录；UUID 原子建目录是隔离依据。
- 不增加跨进程全局注册表、PID 扫描或父目录级清理锁。
- 同一 checkout 中并发执行根 `clean` 和测试不受支持；它可能同时删除不可变 fixture 和活跃运行目录。
- `:<module>:clean` 不再清除该模块曾产生的测试运行残留；正常 close/shutdown cleanup 是主路径，根 `clean` 是遗留清理路径。
- 测试任务命中 build cache 时不会重新产生未声明的临时 report/tmp 文件；调用方不得把这些文件当成缓存可恢复构建产物。
- 标识文件是仓库结构不变量，而不是下载内容输入。可在 `settings.gradle.kts` 中可选地验证其存在和 magic，
  但不能把它接入每个测试任务或下载任务的输入。

## 实现步骤

1. 在仓库根目录新增并提交 `.minecraft-protocol-root`，写入固定 schema/magic。
2. 提取可测试的 `discoverRepositoryRoot(start: Path)`，改为只识别标识文件及其内容。
3. 删除 `discoverOwningModule`、`Path.isBelow` 模块推断辅助逻辑和 `moduleBuildDirectory`。
4. 将 `MinecraftTestLayout` 的 runtime、report、scratch 和 temporary 根切换到
   `build/minecraft-test-support`。
5. 保持 `build/protocol-reference/<version>` 的读取路径以及全部官方 artifact 完整性校验不变。
6. 更新 `minecraft-test-support` 的 common tests：覆盖根标识查找、缺失/错误 magic、最近祖先选择、根级输出布局、 UUID
   隔离和路径逃逸拒绝。
7. 更新 host-process fixture 测试，确认 JVM、host Native、Node/Wasm 从各自实际工作目录都能定位同一 root。
8. 不修改 `OfficialMinecraftFixtureOutputs` 和 `registerFixtureInputs` 的依赖语义；只在必要时补充防回归测试或说明。
9. 更新根和相关模块的 `AGENTS.md`、`README.md`，将 `<module>/build/test-runtimes` 改为新的根级布局， 并明确根 build
   directory 不变量。
10. 完成聚焦测试、跨平台 dry-run/实际测试、增量缓存检查和最终 diff 审核。

## 验证计划

### 根发现与目录行为

- 从仓库根、直接子模块目录和多层 `build`/npm 工作目录开始都能找到同一标识文件。
- 同名但 magic 错误的文件不能被接受。
- 找不到标识时给出确定、可操作的错误，不回退到其他启发式规则。
- 所有可变路径都严格位于 `build/minecraft-test-support` 下。
- 所有不可变 fixture 路径仍严格位于 `build/protocol-reference/<version>` 下。
- 并发分配得到不同 UUID 目录，关闭一个资源不会删除其他资源目录。

### 聚焦 Gradle 验证

先运行受影响模块的 JVM 测试：

```shell
./gradlew :minecraft-test-support:jvmTest
```

用 dry-run 检查标准测试任务仍携带对应 producer：

```shell
./gradlew :protocol-client:jvmTest --dry-run
./gradlew :protocol-client:linuxX64Test --dry-run
./gradlew :protocol-client:wasmJsNodeTest --dry-run
```

任务图必须在相应测试任务之前包含所需的 manifest、version metadata 和官方 server/client preparation 任务，且不出现新的自定义测试
runner task。

随后运行至少一个真实官方 server 消费场景，并复跑相同任务，确认：

- 第一次缺少输出时由 producer 准备或从 build cache 恢复；
- 第二次 producer 为 `UP-TO-DATE` 或适当的 cache 命中；
- 跳过 producer 时不输出伪下载成功日志；
- 测试运行文件只出现在 `build/minecraft-test-support`；
- `build/protocol-reference` 中没有测试运行时写入。

最后按仓库标准执行：

```shell
./gradlew jvmTest
./gradlew allTests
git diff --check
```

保持 build cache 开启，不使用 `--no-build-cache`，不增加自定义 root `test` 或 interoperability task。

## 验收标准

- repository root 只由唯一标识文件确定。
- `discoverOwningModule` 及其全部启发式模块匹配逻辑被删除。
- `minecraft-test-support` 的可变写入只发生在根 `build/minecraft-test-support`。
- Gradle producer 的不可变输出路径、声明和缓存语义未改变，也未与运行目录重叠。
- `hostProcessTest` 的 `requires...` 语义仍能通过 output Provider 自动拉起正确 producer。
- JVM、当前 host Native 和 Node/Wasm 标准测试均能直接调用 `MinecraftTestSupport` API。
- 没有 system property、环境变量、生成路径常量、Gradle Tooling API 或运行时 Gradle 调用。
- 没有新增自定义测试任务、测试运行器或下载入口。

## 开工门槛

本计划文件写入后立即停止。只有用户审阅并明确要求实施后，才开始修改标识文件、实现、Gradle 构建逻辑、测试或文档。
