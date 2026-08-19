# Mosaic 多平台 Minecraft 启动器 Demo 实施计划

- 状态：已确认可行，等待实施
- 最近更新：2026-08-20
- 目标目录：`demo/launcher`
- OAuth 应用 Client ID：`eecdf7ef-6501-4ad6-a769-789b237ada00`
- 性质：仓库内、不发布的示例应用；运行时不读取或依赖仓库所选 Minecraft 协议版本
- 首版兼容性承诺：版本清单中的条目都可选择尝试；仓库所选官方版本只用作开发时的真实启动样本

## 1. 可行性结论

该方案在当前依赖和仓库边界下可行：

- Mosaic 0.18.0 已发布 JVM、`mingwX64`、`linuxX64`、`linuxArm64` 和 `macosArm64` 产物，能够在这些目标上提供 同一套 Compose
  风格终端 UI 和键盘事件。
- `account-auth` 已覆盖 Authorization Code + PKCE、Microsoft token 刷新、Xbox User token、XSTS、Minecraft Services access
  token、Java Edition entitlement 和 Java profile。
- Ktor 3.5.1 的 CIO client 和 CIO server 均支持 JVM 与上述桌面 Native 目标，可同时承担官方资源下载、认证 HTTP 调用和
  loopback OAuth 回调。
- Okio 3.18.1 已支持这些目标的系统文件系统和流式 I/O，可以完成下载及原子文件发布。
- Kommand 2.3.0 已发布相同目标的进程实现，可统一启动系统浏览器和从 `PATH` 调用字面量 `java`，无需让 demo 自己维护一套完整的
  Win32/POSIX 子进程封装。

首版 macOS Native 明确为 Apple Silicon `macosArm64`。Mosaic 当前没有发布 `macosX64`，因此 Intel macOS 不属于 本计划验收范围。JVM
产物仍可在装有匹配 Java 的 Intel macOS 上运行。

版本选择完全由 Mojang manifest 驱动，不读取 `MinecraftProtocol.MINECRAFT_VERSION`，也不在程序中维护允许列表。 manifest
中的任意条目都可以选择并进入安装流程；这只表示“允许尝试”，不构成跨版本兼容承诺。仓库所选官方版本 仅作为开发和人工验收样本，不进入
launcher 源码、UI、安装状态或发布产物。

## 2. 目标与非目标

### 2.1 首版必须完成

1. 启动器自身不暴露命令行参数或子命令；从目标工作目录直接启动后立即进入 Mosaic TUI，使用方向键移动、Enter 确认、Esc
   返回，并为文本输入提供最小编辑能力。
2. 从 Mojang `version_manifest_v2.json` 读取完整官方版本列表，不在代码中限制版本 ID；用户可以选择任意条目并尝试 安装和启动。
3. 每个 Minecraft 版本独占一个自包含子目录；客户端 JAR、libraries、平台 native 依赖、asset index、asset objects、 logging
   configuration 和游戏存档均位于其中，不跨版本共享附属资源；可联网恢复的 launcher metadata 不落盘。
4. 提供已安装版本列表、启动和带确认的删除操作。
5. 提供离线身份的新增、选择、显示和删除。
6. 使用给定公开 Client ID 完成系统浏览器 Authorization Code + PKCE 登录，验证正版资格，显示 Java profile 名称与
   UUID，并在需要时通过 refresh token 重新取得 Minecraft Services access token。
7. 根据版本元数据生成 Java 启动参数，并把 Minecraft Services access token 作为
   `--accessToken <token>` 传给官方客户端。
8. 启动游戏后把 TUI 切换为实时游戏输出阅读器：只在内存中保留最近 10,000 行，支持方向键和 PageUp/PageDown 滚动；进程退出后在最后一行提示按
   Esc 返回启动器。
9. 同一份 `commonMain` 业务实现构建为 JVM、Windows Native、Linux Native 和 macOS Native 可执行程序。

### 2.2 首版刻意不做

- 不下载、捆绑或调用 Mojang 官方启动器；只下载版本元数据所引用的官方游戏运行时资源。
- 不下载 Java runtime，不读取 `JAVA_HOME`、`java.home` 或 JDK 安装目录；始终调用 `PATH` 中的字面量 `java`。
- 不支持 Forge、NeoForge、Fabric、Quilt、第三方版本清单或 `inheritsFrom` 自定义版本。
- 不提供整合包、游戏参数编辑器、内存设置 UI、服务器列表、快速加入、Realms 或游戏内协议代理。
- 不提供 CLI 子命令、flags、参数化工作目录、headless/batch 模式或从命令行直接触发安装、登录和启动的接口；所有 应用操作都从
  TUI 完成。
- 不承诺 manifest 中任意版本都能完成安装、生成正确参数或启动；首版只对开发时实际验收的样本负责，其他结果均为 best
  effort，但运行时不会按版本 ID 主动拦截。
- 不处理单字符串 `minecraftArguments`、legacy native classifier 解压或 legacy virtual/resource-mapped assets。
  首版只实现开发样本需要的现代结构；不匹配时给出明确的“此 demo 未实现该元数据形态”错误。
- 不实现通用账户保险库。为保持 demo 可读性，refresh token 暂以明文 JSON 保存在工作目录，并在 README 与 UI 中明确警告；任何
  token 都不得显示、写入普通日志或进入测试快照。
- 不实现跨版本 libraries/assets 缓存、硬链接、引用计数或垃圾回收。这个 demo 明确用额外磁盘占用换取目录和删除
  语义的简单性；删除版本就是删除该版本唯一的完整子目录。

## 3. 模块和依赖设计

### 3.1 Gradle 变更

1. 在 `settings.gradle.kts` 中加入 `:demo:launcher`。
2. 在 `gradle/libs.versions.toml` 中集中声明：
    - Mosaic `0.18.0` 与 `mosaic-runtime`；
    - Kommand `2.3.0`；
    - 已有 Ktor 版本下缺少的 `ktor-server-core`、`ktor-server-cio` 和 `ktor-server-test-host`；
    - Compose compiler plugin `org.jetbrains.kotlin.plugin.compose`，版本与 Kotlin 相同。
3. 在根 `build.gradle.kts` 的 plugins block 中声明 Compose compiler plugin，并使用 `apply false`。
4. 新建 `demo/launcher/build.gradle.kts`：
    - 应用 Kotlin Multiplatform、Kotlin Serialization 和 Kotlin Compose plugins；
    - 目标为 `jvm()`、`mingwX64()`、`linuxX64()`、`linuxArm64()`、`macosArm64()`；
    - JVM 与所有 Native 目标都声明 executable，入口位于 `commonMain`；
    - `commonMain` 只使用仓库模块 `project(":account-auth")`，另加 Mosaic、Ktor client/server CIO、
      kotlinx.coroutines、kotlinx.serialization JSON、Okio 和 Kommand；
    - `commonTest` 使用 Kotlin Test、coroutines-test、Ktor MockEngine、Ktor server test host 和 Okio FakeFileSystem；
    - 所有依赖使用 `implementation`，该 demo 不发布 API。

先建立一个仅显示菜单并能在全部目标解析依赖、链接 executable 的小检查点。它应尽早暴露 Kotlin 2.4.10 与 Mosaic/Kommand
已发布二进制之间的兼容问题；只有该检查点通过后才继续业务实现。

### 3.2 责任边界

`demo/launcher` 是完整的应用边界，依赖方向如下：

```text
Mosaic UI
  -> Launcher state/controller
    -> Microsoft account coordinator -> account-auth
    -> Offline account model -> demo-local persisted name/UUID
    -> Installation service -> Ktor + Okio
    -> Launch command builder -> Mojang metadata + selected identity
    -> Process/browser service -> Kommand
```

- 不依赖 `buildSrc`、`protocol-auth`、`protocol-model`、`protocol-client`、`protocol-session` 或
  `protocol-transport`，也不把 Gradle task 类型移动到运行时 classpath。
- `OfficialDownloadTasks.kt` 和 `MinecraftProtocolToolSupport.kt` 只是实现时阅读的参考代码。demo 在自己的源码中保留
  精简、独立的运行时下载实现；它不调用、加载、生成或复制 buildSrc class，也不从 buildSrc 获取版本常量。
- `account-auth` 取得 Minecraft Services access token、entitlement 和 profile 后，本仓库模块的职责即结束。后续账户
  参数映射、官方文件安装、Java 命令生成和官方客户端进程生命周期都属于 demo 应用本身。
- 离线账户不使用 `protocol-auth`：demo 只持久化用户输入的名称和一次生成的 UUID，并把它们作为官方客户端启动参数；
  不宣称实现在线会话认证。
- 不使用 `world-io` 管理启动器文件；普通启动器状态直接使用 Okio `FileSystem`、`Path` 和 `FileHandle`。

## 4. 工作目录布局

无参数 `main()` 启动时立即将 `.` 规范化为固定的 `launcherRoot`；不解析 application argv，运行期间也不再根据
命令行或环境变量改变根目录。所有 launcher 专属持久状态都直接放在根目录；能够从官方重新取得、且游戏运行本身 不需要的
metadata 只进内存。每个版本目录都是独立的 `gameRoot`：

```text
./auth.json                             # 账户、token、所选账户及安装级 UUID
./installed.json                        # 所有已完成安装的最小索引
./minecraft/
  <version-id>/                         # 该版本的 gameRoot，也是 Java 子进程工作目录
    client.jar
    libraries/<metadata artifact path>
    assets/
      indexes/<asset-index-id>.json
      objects/<first-two-hash-chars>/<hash>
    logging/<logging-file-id>
    natives/...
    ...                                 # 官方客户端随后创建的存档、配置、原生日志等，启动器不管理
```

约束：

- 所有需要持久化的下载、安装状态和游戏目录都位于 `launcherRoot` 内。
- 元数据提供的版本 ID、artifact path、logging ID 和 asset path 必须在拼接前校验，拒绝绝对路径、分隔符逃逸和
  `..`。
- 每个 artifact 的目标路径都必须落在所选 `minecraft/<version-id>/` 内；不同版本即使需要相同 hash，也各自下载一份，
  不建立跨目录链接或引用。
- 根目录 `installed.json` 用一个类型化对象保存 `schemaVersion` 和安装记录数组；每条记录只含 `versionId` 与
  `platformKey`，路径由版本 ID 确定，不保存绝对路径。记录只在该版本当前平台的所有资源都已就绪后加入；目录存在
  不等于安装完成，同一记录在不同平台打开时必须提示重新安装。
- `${game_directory}` 和 Kommand working directory 都严格等于该版本 `gameRoot`，不再增加嵌套 instance/game 目录。
- 除安装时按官方 metadata 写入所需运行资源、启动前只读校验和用户确认后的整目录删除外，启动器把 `gameRoot` 视为
  官方客户端私有内容：不解析、不迁移、不改写、不清理客户端自行创建的任何文件。launcher 专属文件绝不写进该目录。
- JSON 使用 `kotlinx.serialization` DTO 编解码，写入临时同级文件后原子替换；不得拼接 JSON 文本。
- Mojang 版本列表 manifest、单版本 metadata 和解析结果只放在本次启动器进程的内存中，退出即丢弃；每次启动都 重新下载
  manifest 和已安装版本的 metadata，不保存 metadata cache。
- 根目录 `installed.json` 只是最小安装索引，不复制官方 metadata 或启动参数；`InstallPlan`/`LaunchPlan` 每次从本次 联网取得的
  metadata 重新计算。它与 `auth.json` 都使用同级 `.tmp` 文件原子替换。
- Windows 所需的 JDK argument file 在启动游戏前以唯一名称临时创建于 `launcherRoot`，游戏进程结束后删除；它不
  属于持久状态或缓存，且即使异常退出也不得包含账户信息或 access token。启动时清理遗留的已知前缀临时文件。
- 启动器不为游戏 stdout/stderr 创建日志文件，输出缓冲只存在于内存并在离开阅读器后丢弃。官方客户端按自身 logging
  configuration 在 `gameRoot` 中创建的内容保持原样；demo 不额外复制、归档、解析或清理它们。

## 5. Mojang 元数据和安装管线

### 5.1 序列化模型

在 demo 内声明最小但类型化的 `@Serializable` wire DTO，只覆盖所需字段，并让 JSON 忽略未来新增字段：

- version manifest、version entry；
- version metadata、Java version、client download、logging configuration；
- library、artifact/classifier；
- ordered rule、OS predicate、feature predicate，以及字符串或字符串数组 argument value；
- asset index 和 asset object。

不要把 Mojang wire DTO 与安装状态模型混在一起。解析完成后生成内部的 `InstallPlan`：它包含当前平台需要的每个 下载、classpath
顺序、元数据要求的 native 目录、asset 计划、logging 文件和最终 launch metadata。

### 5.2 平台和规则求值

建立很窄的 `LauncherPlatform`：

- Mojang OS 名称映射为 `windows`、`linux`、`osx`；
- 架构至少规范化为 `x86_64`、`aarch64` 和 `x86`；
- 提供 classpath separator、平台 key 和可用于规则判断的 OS version；
- JVM 从标准系统属性读取当前运行平台；Native 从 Kotlin/Native 平台信息及最小系统调用取得同等信息。

规则求值必须是一个无 I/O 的纯函数，并同时复用于 library、JVM argument、game argument 和
`default-user-jvm`：

- 没有 rules 时允许；
- 有 rules 时从“不允许”开始，按声明顺序应用所有匹配项，最后一个匹配 action 决定结果；
- 支持现代 Mojang metadata 使用的 `os.name`、`os.arch` 和 `os.versionRange` min/max，且不按版本 ID 分支；
- demo、custom resolution、quick play 等 feature 默认均为 `false`；
- 遇到未知 action、无法安全解释的 predicate 或未识别的占位符时失败，不猜测。

### 5.3 下载和发布

下载服务使用一个调用方持有并最终关闭的 Ktor CIO `HttpClient`：

1. 安装请求超时、连接/读取超时、User-Agent 和最多三次的临时网络/5xx 重试。
2. 响应体直接从 Ktor channel 流向 Okio sink，不能把客户端 JAR、library 或 asset 整体读入内存。
3. 每个目标先写同目录唯一 `.download` 文件；HTTP 成功、长度及元数据 SHA-1 校验通过后再原子移动到目标。
4. 所选版本目录内已存在且长度/SHA-1 匹配的文件直接复用；不匹配的文件重新下载，不能把残缺文件视为已安装。
5. 该次安装的 libraries 和 assets 使用一个 `Semaphore(8)` 有界并发；单版本 metadata、client JAR、asset index 与 logging
   config 按依赖关系调度。
6. 一个子下载失败应取消同一安装操作，清理该操作的临时文件，并保留该版本目录内此前已经完整发布的对象以便重试；
   绝不读取或写入另一版本目录。
7. 捕获广义异常时先重新抛出 `CancellationException`；取消后的必要清理放在 `NonCancellable`，且清理失败只作为 suppressed
   context。
8. 下载进度通过只读状态/Flow 上报总文件数、已完成文件数、当前资源和已知字节数，下载层不直接操作 Mosaic。

安装顺序：

1. 获取 manifest 到内存，UI 展示完整 `versions` 列表，并允许按 release/snapshot/old_beta/old_alpha 类型过滤；不读取
   仓库常量、不维护版本白名单，也不把某个条目标为程序特有的“已验证版本”。
2. 把用户选择项引用的单版本 metadata 下载到内存，校验返回 `id` 与选择一致并验证其参数结构是否受 demo 支持； 该响应只保留在本次进程的
   session store，不写入磁盘。
3. 计算当前平台的 library 和 argument rules，形成确定性的 `InstallPlan`。
4. 下载 client JAR、适用 libraries/classifiers、asset index、全部 asset objects 以及可选 logging config。
5. 将通过 rules 选择的现代 native classifier artifact 保留在 classpath，并创建元数据 JVM 参数引用的 native 子目录；遇到
   legacy `library.natives`/`extract` 结构时停止并报告超出 demo 范围。
6. 遇到 asset index 的 legacy `virtual` 或 `map_to_resources` 要求时停止并报告超出 demo 范围，不生成兼容副本。
7. 所有实际游戏资源就绪后，最后把记录原子加入根目录 `installed.json`。删除操作经确认后删除目标完整目录，再从
   根索引移除记录；启动时清理“索引存在但目录缺失”的崩溃残留，并把“目录存在但没有索引”的内容视为可复用的未完成
   安装，而不是已安装版本。整个过程不修改 `auth.json` 或其他版本。

## 6. 账户和 OAuth

### 6.1 固定公开配置

在 demo 源码中直接声明：

```text
MICROSOFT_CLIENT_ID = eecdf7ef-6501-4ad6-a769-789b237ada00
OAUTH_REDIRECT_HOST = 127.0.0.1
OAUTH_REDIRECT_PATH = /oauth/callback
```

Client ID 是公开的 public-client 标识，不是秘密。README 同时写明 Entra 应用必须：

- 允许个人 Microsoft 账户；
- 作为 public client 启用；
- 按 `account-auth` 的 public-client 指南注册匹配的 loopback redirect URI，例如
  `http://127.0.0.1/oauth/callback`。

OAuth callback server 在 JVM、Windows Native、Linux Native 和 macOS Native 上统一使用 Ktor CIO，不增加平台专属 server
engine。每次登录直接让 CIO 仅在 `127.0.0.1` 上以端口 `0` 启动，由操作系统原子分配空闲端口；engine 确认 已经监听并报告实际端口后，才构造
`http://127.0.0.1:<actual-port>/oauth/callback` redirect URI 并打开浏览器。不得先 扫描一个“空闲”端口再二次 bind，也不得绑定
wildcard interface。

### 6.2 在线登录状态机

1. 生成一次性 OAuth `state` 和 PKCE `codeVerifier`。
2. 创建 Ktor CIO callback server，只监听 loopback、只接受预期 path；以端口 `0` 启动并等待 engine 已就绪，从 resolved
   connector 取得实际端口。只有 server 已经可以接收回调后，登录状态才进入“等待浏览器”。
3. 使用这个实际 redirect URI 调用 `MicrosoftOAuthTools.authorizationUrl(...)`，再通过 browser service 打开系统浏览器：
    - Windows：Kommand 调用 `rundll32.exe url.dll,FileProtocolHandler <url>`；
    - Linux：调用 `xdg-open <url>`；
    - macOS：调用 `/usr/bin/open <url>`；
    - JVM 按其运行 OS 选择同一策略，所有值作为独立 argument 传递，不经过 shell 拼接。
4. callback route 必须校验 GET method、path、唯一 `state`、单个 `code` 或 OAuth `error`。无效 path/state 返回不含 输入回显的安全
   4xx 文本且继续等待真正回调；第一个合法 code/error 原子取得 terminal ownership，重复回调不能 再次交换 code。
5. 合法 code 在 CIO route 的登录协程内通过 `account-auth` 调用 `tokenWithAuthorizationCode`，完成 Microsoft code
   exchange；callback server 不负责后续 Xbox/Minecraft 请求。
6. route 随即用 `text/plain; charset=UTF-8` 返回人类友好且不含 code/token/内部异常的终态消息：交换成功时提示 “Microsoft
   授权完成，可以关闭此页面并返回启动器”，OAuth 拒绝或交换失败时提示安全的失败摘要和回到启动器 重试。响应完成后再通知外层
   owner 停止 CIO engine；不能在 response flush 前从 handler 内强行 stop server。
7. server 的唯一 owner 在 `finally` 中关闭 engine。浏览器打开失败、TUI 取消、OAuth error、code 交换失败和成功都
   必须汇合到同一关闭路径；取消仍须重新抛出 `CancellationException`，且不会遗留监听端口。
8. server 关闭后，账户 coordinator 接收已交换的 Microsoft token，在 TUI 显示后续登录进度，再依次调用
   `authenticateUser`、`authorizeXsts`、`loginWithXbox`、`getStoreEntitlements` 和 `getMinecraftProfile`。只有确认 Java
   Edition entitlement、取得 profile 后才原子保存账户并显示登录完成；此阶段失败只在 TUI 报告。

### 6.3 `auth.json` 与 token 刷新

根目录 `auth.json` 承担全部账户与凭据持久状态，并使用 `@Serializable` 模型读写：

- `schemaVersion`；
- `installationId`：首次运行生成的 UUID，专用于游戏参数 `${clientid}`；
- nullable `selectedAccountId`；
- `accounts`：以 `kind` 区分的在线/离线账户列表；
- 在线账户仅保存稳定 local ID、profile UUID/name、Microsoft refresh token、Minecraft Services access token 和
  `minecraftAccessTokenExpiresAtEpochSeconds`；
- 离线账户仅保存稳定 local ID、name 和 UUID。

不要保存 PKCE verifier、OAuth code、Microsoft access token、Xbox User token 或 XSTS token。所有更新先把完整类型化 模型写入根目录唯一的
`auth.json.tmp`，flush 后原子替换 `auth.json`；成功后删除临时文件。解析失败时显示可恢复错误， 不得用空模型覆盖原文件。README/UI
明示 refresh token 以明文保存是 demo 的安全限制。

每次用在线账户启动前，根据绝对过期时间执行：

1. 若 Minecraft access token 在当前时间加两分钟安全余量之后仍有效，直接使用，不进行刷新。
2. 否则用 Microsoft refresh token 调用 `tokenWithRefreshToken`。若响应轮换了 refresh token，先将新值原子写回
   `auth.json`，即使后续 Xbox/Minecraft 阶段失败也不能恢复旧值。
3. 用新的 Microsoft token 重新执行 Xbox、XSTS、Minecraft Services、entitlement 和 profile 链。
4. 成功后用响应 `expiresIn` 计算新的绝对 Minecraft token 到期时间，同时原子更新 access token、profile 和名称。
5. 任何失败都保留在线账户和最新 refresh token，在 TUI 要求重试或重新登录；不能继续传过期 token，也不能静默 降级为离线账户。

### 6.4 离线身份

- demo 自己定义最小 `OfflineAccount(name, uuid)`；用户输入非空名称时，用 Kotlin 的 multiplatform UUID API 生成 UUID
  并随账户持久化，后续启动保持稳定。这里不依赖 `protocol-auth`，也不需要网络请求。
- 启动参数使用离线名称、无连字符 UUID 和 access token 占位值 `0`。
- UI 明确标记“离线”，不能把离线身份显示为已经通过正版资格检查，也不宣称该随机 UUID 是服务器侧的标准离线 UUID。

### 6.5 两种 client ID 与 XUID

实现时必须区分：

- 硬编码的 Entra OAuth Client ID，只用于 Microsoft OAuth 请求；
- `auth.json` 中首次运行生成并持久化的 `installationId`，用于版本参数 `${clientid}`。

二者不能混用。当前 `account-auth` 的轻量 XSTS DTO 不保证提供 Xbox `xid`，所以 MVP 将 `${auth_xuid}` 解析为空 字符串；这不妨碍基本
Vanilla 启动和 Java 服务器认证，但 Realms/完整官方启动器账户体验不在本计划范围。不得把 Minecraft profile UUID 冒充 XUID。

## 7. Java 命令生成和游戏启动

### 7.1 启动前验证

1. 从根目录 `installed.json` 查到版本记录，使用启动 loading 阶段联网取得的 session metadata 重新计算当前平台计划， 并确认该
   `gameRoot` 的 client JAR 及全部计划文件存在且匹配。
2. 通过 Kommand 执行字面量 `java -version`，解析实际 major；不查找 Java 安装目录。
3. 若 version metadata 含 `javaVersion.majorVersion`，实际 major 低于要求时阻止启动；较高版本允许继续但在 UI 显示兼容性提示。
4. 若旧元数据没有 Java major，只提示无法验证并允许用户继续。
5. 在线账户先完成 token 刷新；离线账户不发起任何账户 HTTP 请求。

### 7.2 占位符

构建一个完整且显式的 placeholder map，至少包含：

- `${auth_player_name}`、`${auth_uuid}`、`${auth_access_token}`、`${auth_xuid}`；
- `${version_name}`、`${version_type}`；
- `${game_directory}`、`${assets_root}`、`${assets_index_name}`、`${natives_directory}`、`${library_directory}`；
- `${classpath}`、`${classpath_separator}`；
- `${launcher_name}`、`${launcher_version}`、`${clientid}`。

按元数据顺序展开适用的 JVM arguments 和 game arguments；argument value 为数组时保持元素边界，不能先拼成一个 shell
字符串。logging argument 在 JVM arguments 中放到 main class 之前。最终若仍出现 `${...}`，立即失败并指出 具体占位符。

### 7.3 避免 Windows 命令行长度和泄密

- 临时 JDK argument file 只保存 JVM options、logging option 和 classpath，不包含 main class 后的账户/game arguments，因此不落盘
  access token；进程退出后删除。
- 使用 JDK argument-file 语法正确编码空格、反斜线和引号，并测试包含空格的绝对工作目录。
- 实际进程参数形态为：

```text
java @<absolute-temporary-argument-file> <main-class> <expanded-game-arguments-containing-access-token>
```

- Java working directory 设为对应的绝对 `minecraft/<version-id>/`，且 `${game_directory}` 指向完全相同的路径。
- 任何错误摘要、命令预览和测试 failure 都通过参数名识别并把 access token 替换成 `<redacted>`。

### 7.4 游戏进程输出阅读器

- `runMosaic` 在游戏存活期间保持运行，启动成功后立刻切换到专用 `GameOutput` screen。通过 Kommand 启动字面量
  `java`，关闭子进程 stdin，并把 stdout/stderr 都接到 pipe；不得让游戏直接继承终端，也不得使用一次性收集全部 输出的 API。
- 两条 pipe 必须由独立 coroutine 并发、持续读取，按启动器观察到的到达顺序合并为同一个文本流，同时保留 stdout/stderr
  来源供样式区分。收到进程退出后仍要把两条 pipe 读到 EOF，最后才把阅读器状态改为已结束，避免丢失 尾部输出或因任一 pipe
  填满而阻塞游戏。
- 使用增量文本解码和行切分：换行提交逻辑行，未换行尾部就地更新，回车替换当前逻辑行；非法字节使用替代字符。 ANSI escape
  和其他终端控制字符必须移除或可视化，防止游戏输出控制 Mosaic 终端；已知 access token 在进入 UI state 前统一替换为
  `<redacted>`。
- 阅读器以带单调序号的有界 ring buffer 保存最多 10,000 条游戏输出逻辑行，超过上限时逐条淘汰最旧内容；单条超长
  输出拆成有界显示行，避免没有换行的输出绕过内存限制。它没有任何文件 sink，Esc 返回后立即丢弃整个 buffer。
- UI 由进程状态/exit code header、可滚动 viewport 和固定底部状态行组成。视图位于末尾时自动跟随新输出；用户向上
  滚动后保持当前序号锚点且不被新行拉回底部，并显示新增行数。旧锚点被淘汰或终端尺寸变化时，把 viewport 安全 clamp 到仍可显示的范围。
- `↑`/`↓` 每次滚动一行，PageUp/PageDown 每次滚动一个当前 viewport 高度；滚动回末尾后恢复自动跟随。高频输出先 完整摄取，再按显示帧批量发布
  UI state，避免为每一行单独触发重绘。
- 游戏运行期间 Esc 和 `q` 都不返回菜单也不终止游戏。进程退出且输出完全 drain 后，屏幕最末行固定显示
  `按 ESC 回到启动器`；此时只有 Esc 返回已安装版本菜单，buffer 随 screen 销毁。

## 8. Mosaic UI 和应用状态

用明确的 sealed screen/state 表达导航，不让 composable 直接持有 HTTP client、文件句柄或进程：

```text
Startup
  Loading official version list and installed-version metadata -> Home
                                                       failure -> Retry / Exit
Home
  Install version
    Version list -> Confirm -> Download progress -> Installed
  Installed versions
    Version actions -> Launch -> Game output reader -> Esc after process exit -> Installed versions
                    -> Delete / Back
  Accounts
    Account list -> Microsoft login / Add offline / Select / Delete
  Exit
```

交互约定：

- 普通菜单中 `↑`/`↓` 移动，Enter 确认，Esc 返回，`q` 仅在没有活动操作时退出；游戏输出阅读器使用上一节的专用 按键语义；
- `runMosaic` 建立后立即显示 metadata loading screen，再异步下载 Mojang manifest，并根据根 `installed.json` 为已安装
  版本下载单版本 metadata；成功后只留在 session memory。失败时显示 Retry/Exit，不得从磁盘回退到旧 metadata。
- 列表始终保证 selection 在过滤后的边界内；终端高度不足时只渲染围绕 selection 的窗口；
- 版本列表展示 manifest 的所有类型并允许过滤；不读取仓库版本常量、不特殊标记或隐藏任何版本。列表和确认页统一
  提示“允许尝试不代表兼容性保证”，因为资源下载完成也不等于版本已经通过运行验证。
- 安装、认证和刷新运行在 controller scope 中，通过不可变 UI state 更新进度；
- 活动下载/登录期间 Esc 发出取消，请求完成资源清理后才返回菜单；
- 错误作为可关闭 panel 显示可行动信息，不直接 `println` 或输出 stack trace；
- 首页持续显示当前账户名称、在线/离线状态和当前平台，不显示任何 credential。

## 9. 实施阶段

### 阶段 A：工程骨架和平台探针

1. 完成 settings、version catalog、根 plugin 声明和 demo KMP build。
2. 创建不接收 application arguments 的共同入口及最小 Mosaic 菜单；所有产物都以零参数直接启动。
3. 为所有 Native 目标链接 release executable，并在当前 Windows 主机实际运行 `mingwX64` 产物。
4. 用 Kommand 做三个探针：`java -version`、打开一个无敏感信息的本地 URL、带空格 argument 的子进程。
5. client 和 OAuth callback server 全部固定使用 Ktor CIO；在 JVM 和当前 Windows Native 上实际运行请求/回调探针，
   Linux/macOS 目标在各自 host 执行同一探针，不引入 expect/actual engine 选择。

验收门：依赖可解析，键盘输入正常，终端退出后状态恢复；所有目标完成编译链接，并在各自 OS host 通过同一套 CIO client/server 探针。

### 阶段 B：类型化元数据和纯规划器

1. 实现 Mojang DTO、平台模型、ordered rule evaluator 和 argument value serializer。
2. 实现 `InstallPlan` 与 `LaunchPlan`，先不访问网络/磁盘。
3. 使用小型固定 JSON fixtures 覆盖 Windows、Linux x64/arm64、macOS arm64 和 JVM 动态平台映射。
4. 覆盖拒绝 legacy arguments/natives/assets、未知规则、路径逃逸、未解析 placeholder 和 classpath 顺序。
5. 用一个现代官方 metadata 样本验证规划器，但不把样本 ID 写入 production source、UI 或条件分支；真实开发验收版本由
   人工测试步骤在仓库外层选择。

验收门：规划器 common tests 全部通过，输入相同元数据和平台时输出顺序完全确定。

### 阶段 C：工作目录存储和安装器

1. 实现根目录 `auth.json`/`installed.json` 原子存储、临时下载发布、SHA-1/长度校验、有界并发和进度事件。
2. 实现每次启动重新获取且不落盘的 manifest/单版本 metadata，以及版本私有 client、libraries、assets、logging 下载。
3. 实现当前现代 metadata 所需的 native artifact/classpath 处理，并显式拒绝 legacy natives/assets。
4. 实现 `minecraft/<version>/` 自包含游戏目录、根级最小安装索引、目录内重试复用、当前平台记录校验和整目录确认删除。

验收门：使用 Ktor MockEngine + FakeFileSystem 完成一次微型安装；中途失败/取消不向根索引加入完成记录，也不留下
`.download` 文件； 第二次安装只请求同一版本目录中缺失/失配对象；两个模拟版本的 libraries/assets 没有共享路径，删除其一不修改另一
版本或根目录 `auth.json`；连续两次启动都会请求 manifest 且不会生成 manifest cache 文件。

### 阶段 D：账户

1. 实现单一 `auth.json` schema、原子更新、离线身份和账户选择 UI。
2. 用全平台 Ktor CIO 实现端口 `0` loopback callback server、PKCE/state 校验、浏览器 service、handler 内 Microsoft code
   exchange、友好的 `text/plain; charset=UTF-8` 响应，以及 response 完成后的确定性关闭；后续账户链回到 TUI。
3. 串联 `account-auth` API，验证 entitlement/profile，并实现绝对过期时间、两分钟余量、Minecraft token 刷新和 Microsoft
   refresh token 轮换的安全落盘顺序。
4. 为所有 HTTP 阶段使用 MockEngine 测试正常、拒绝授权、state mismatch、无 entitlement、过期 token、取消和刷新失败。

验收门：自动测试无实时凭据；一次人工正版登录能在 JVM 和 Windows Native TUI 中显示 profile name/UUID，账户文件中 不包含短期
Xbox/XSTS token。

### 阶段 E：启动与完整 UI

1. 实现 Java major 探测、placeholder map、规则化 arguments、argument file 和 token redaction。
2. 串联安装版本、所选账户和 Java 进程启动，并并发流式捕获 stdout/stderr。
3. 实现 10,000 行 ring buffer、增量行切分、控制字符清理、滚动锚点、自动跟随和批量 UI 更新。
4. 完成版本安装、安装管理、账户管理、进度、错误和游戏输出阅读器界面。
5. 添加 `demo/launcher/README.md`，记录零参数直接启动、无版本白名单、兼容性范围、构建/运行方式、极简工作目录、 Entra 配置、明文
   refresh token 风险、输出不持久化边界和 `java` PATH 要求；仓库的开发样本版本只出现在人工 验收说明中，不成为 launcher 输入。

验收门：离线身份和正版身份都能启动开发所选官方样本；官方客户端收到正确名称、UUID 和 access token，并以对应
`minecraft/<version>/` 为 working directory；删除安装只删除目标版本目录。启动后 TUI 能持续显示有界游戏输出， 退出后最后一行提示按
Esc 返回，且启动器没有创建游戏输出日志。其他版本的成功或失败不影响该阶段验收。

## 10. 测试与最终验证

### 10.1 自动测试重点

- manifest/version/asset/`auth.json`/`installed.json` 的类型化解析、严格必需字段与向前兼容；
- rules 的顺序语义、OS/arch/version 和 feature 分支；
- 现代 metadata 样本的 library/classifier 选择和 classpath 顺序，以及 legacy metadata 的明确拒绝；production 路径
  不读取仓库版本常量，也不按版本 ID 分支；
- 每次进程启动都重新请求 manifest 和已安装版本 metadata、loading/error/retry 状态，以及根目录不存在 metadata cache；
- `auth.json`/`installed.json` 原子写、下载失败、重试、取消、重复安装、根索引最后提交和崩溃残留恢复；两个版本的
  client/libraries/assets 路径完全隔离，删除互不影响；
- CIO server 端口 `0` 先监听后开浏览器、实际 redirect port、state/code/error、无效与重复 callback、单次 code
  exchange、成功/失败友好文本、response flush 后关闭、取消关闭和端口释放；
- 未过期 Minecraft token 不刷新，进入两分钟余量时刷新；Microsoft refresh token 轮换先落盘，entitlement/profile 复查和新
  Minecraft token 绝对到期时间正确，失败不回退旧 token；离线 UUID 持久稳定；
- 所有 placeholder 完整替换、`game_directory`/进程 working directory 等于版本根、路径含空格、临时 argument file
  escaping/清理和 Windows classpath 长度；
- stdout/stderr 并发 drain、跨流到达顺序、未结束行、回车覆盖、非法字节、控制字符清理和 token redaction；
- 10,000 行边界与淘汰、超长行限制、自动跟随/手动滚动、PageUp/PageDown、resize clamp、新增行计数，以及结束前 Esc 无效、结束后固定
  footer 和 buffer 释放；
- 输出阅读器不调用文件存储，access token 不出现在 UI state、序列化快照、异常文本、临时 argument file 或测试报告中。

### 10.2 Gradle 顺序

Gradle wrapper 不并发运行。实现时按下列顺序逐步扩大：

1. `gradlew.bat :demo:launcher:jvmTest`
2. `gradlew.bat :demo:launcher:mingwX64Test`
3. `gradlew.bat :demo:launcher:linkReleaseExecutableMingwX64`
4. `gradlew.bat :demo:launcher:allTests`
5. 在 Linux x64/arm64 和 macOS arm64 host 上分别运行对应 test、release link 和真实终端 smoke test。
6. 最后运行仓库级 `gradlew.bat :minecraft-test-fixture-host:test jvmTest`，确认新增 plugin/dependency wiring 没有影响现有
   JVM 图。

机器内存不足时，每次命令附加适当的 `--max-workers=<count>`，但保持 build cache 开启。

### 10.3 人工端到端验收

每个平台都必须从期望的工作目录直接、零参数运行构建后的 distribution/native executable，不能以 Gradle `run`、 IDE console 或
CLI 参数作为 Mosaic 交互验收。开发者在测试外层选择仓库所选官方版本作为样本，launcher 本身不得 获知该选择：

1. 从空工作目录直接启动，确认 Mosaic 先显示 loading、在线取得完整版本列表，根目录没有生成 manifest/metadata cache。
2. 创建离线账户，安装开发样本并启动到标题界面；确认根目录持久状态只有 `auth.json`/`installed.json`，所有游戏运行 资源都在
   `minecraft/<version>/`，且 Java working directory 正是该目录。
3. 游戏运行期间确认 TUI 已变为实时输出阅读器；用 `↑`/`↓` 和 PageUp/PageDown 检查手动滚动、暂停自动跟随与回到底部。
4. 关闭游戏，确认尾部输出已完整出现、最末行是 `按 ESC 回到启动器`、其他返回键无效；按 Esc 返回已安装版本菜单，
   并确认启动器没有创建进程输出日志文件。
5. 再次零参数启动，确认版本列表和已安装版本 metadata 被重新联网加载，而实际游戏 artifacts 不重复下载，账户和根 安装索引可恢复。
6. 完成系统浏览器正版登录：确认 CIO callback server 先监听随机 loopback 端口再打开浏览器，成功页面给出友好文本， 随后端口关闭且
   TUI 显示 profile name/UUID；启动游戏并验证正版多人服务器登录所需 token 有效。
7. 令 Minecraft token 进入刷新余量，确认使用 Microsoft refresh token 完整刷新并保存轮换结果后再次启动。
8. 删除该版本，确认只移除目标 `minecraft/<version>/` 和根 `installed.json` 中对应记录；`auth.json` 不变。

## 11. 主要风险及控制

| 风险                                                | 控制方式                                                                                            |
|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| Mosaic 仍标记为 experimental                        | 固定 0.18.0；先做五目标链接和真实 TTY 探针；把 UI 与业务服务彻底分离                                |
| Intel macOS 无 Mosaic 产物                          | 首版只承诺 `macosArm64`；Intel 用户使用 JVM 产物                                                    |
| 外部依赖与 Kotlin 2.4.10 二进制不兼容               | 阶段 A 在业务代码前完成全部目标 dependency resolution/link；不在失败后堆兼容 shim                   |
| Native TLS/loopback 在不同 OS 行为有差异            | client/server 全部固定 Ktor CIO；端口 `0` 绑定后取 resolved port，并在三类 OS 上各做真实 smoke test |
| 用户把完整版本列表理解为兼容性承诺                  | UI/README 对所有版本统一显示 best-effort 提示；production 不含“仓库版本”概念或白名单                |
| 版本 metadata 差异过大                              | 只实现开发样本需要的现代结构；不按 ID 拦截，但不兼容结构在安装规划阶段明确拒绝                      |
| metadata 不落盘导致启动依赖网络                     | 启动即显示 loading；失败提供 Retry/Exit，不伪装成已有缓存可用                                       |
| Windows command line 太长                           | classpath/JVM 参数放入无秘密的 JDK argument file，game/token 参数保持独立 argv                      |
| stdout/stderr 高速输出导致死锁、内存膨胀或 TUI 抖动 | 两条 pipe 并发 drain；10,000 行 ring buffer 与单行上限；按帧批量重绘                                |
| 游戏输出携带终端控制序列或秘密                      | 在进入 UI state 前清理控制字符并按已知秘密做 redaction；子进程不继承终端输出                        |
| 工作目录包含空格或非 ASCII                          | 全程使用 Okio Path 和 argv 边界；用包含空格及中文的临时根目录做测试                                 |
| 每版本独占 resources 增加磁盘占用                   | 这是 demo 为换取最简单隔离/删除语义而接受的权衡；安装确认页显示估算下载量                           |
| refresh token 明文持久化                            | README/UI 明示 demo 限制；最小化保存范围；绝不记录 token；未来再替换为 OS credential store          |
| 安装/删除中断造成根索引与目录不一致                 | 资源校验后最后提交根索引；启动时清理缺目录记录，未索引目录只作为可续传半成品                        |
| `auth_xuid` 当前不可得                              | MVP 传空字符串且不宣称支持 Realms；不伪造 XUID                                                      |

## 12. 完成定义

只有同时满足以下条件，计划才算实施完成：

- `demo/launcher` 是可独立构建的 KMP demo；仓库模块依赖只有 `account-auth`，不依赖 `buildSrc`、`protocol-auth`、
  `protocol-model` 或协议客户端栈；
- 启动器没有 application command-line 参数、子命令或 headless 入口，从当前工作目录零参数启动即可完成全部操作；
- JVM、`mingwX64`、`linuxX64`、`linuxArm64`、`macosArm64` 都能解析并链接；
- Windows、Linux、Apple Silicon macOS 的 Native executable 都以外部选择的开发样本经过真实 TTY、下载、离线启动 smoke test，但
  launcher 源码、UI 和构建图不知道样本版本；
- 所有目标的 OAuth callback server 都使用 Ktor CIO、先成功监听系统分配的 loopback 端口再开浏览器，并在响应友好 终态文本后关闭；至少
  JVM 和 Windows Native 完成一次正版 OAuth 与官方客户端在线启动，Linux/macOS 在相应 host 补齐同样的人工验收；
- manifest 的所有条目均可选择，production 不读取 `MinecraftProtocol.MINECRAFT_VERSION`、不维护白名单或特殊标记；
- launcher 持久状态只在根 `auth.json`/`installed.json`；可联网恢复的 metadata 不落盘。每个版本在
  `minecraft/<version>/` 独占全部实际运行资源和游戏数据，Java working directory 就是该目录；
- 安装、管理、离线账户、正版账户、刷新和传给游戏进程的命令行 token 均有对应测试或明确人工验收记录；
- 游戏 stdout/stderr 只进入最多 10,000 行的内存阅读器，滚动和自动跟随行为通过测试；进程结束后最后一行提示
  `按 ESC 回到启动器`，按 Esc 返回时丢弃内容且没有启动器持久化的进程输出日志；
- 没有 token 泄漏到 TUI、普通日志、异常、测试输出或临时 Java argument file；
- `demo/launcher/README.md` 能让不了解实现的人从构建产物直接运行并理解支持范围。

## 13. 参考依据

- Mosaic：<https://github.com/JakeWharton/mosaic>
- Mosaic 当前目标声明：<https://github.com/JakeWharton/mosaic/blob/trunk/addAllTargets.gradle>
- Ktor Native server：<https://ktor.io/docs/server-native.html>
- Ktor client engines：<https://ktor.io/docs/client-engines.html>
- Kommand：<https://github.com/kgit2/kommand>
- Microsoft desktop public-client 配置：
  <https://learn.microsoft.com/en-us/entra/identity-platform/scenario-desktop-app-configuration>
- Microsoft loopback redirect URI 规则：
  <https://learn.microsoft.com/en-us/entra/identity-platform/reply-url#localhost-exceptions>
- 仓库唯一运行时认证入口：`account-auth/README.md`；loopback 状态机参考：
  `account-auth/third-party-client-java-authentication.md`
- 可参考但不得依赖的构建逻辑：
  `buildSrc/src/main/kotlin/com/hiczp/minecraft/buildlogic/OfficialDownloadTasks.kt`、
  `buildSrc/src/main/kotlin/com/hiczp/minecraft/buildlogic/MinecraftProtocolToolSupport.kt`
