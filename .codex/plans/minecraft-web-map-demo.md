# Minecraft 网页地图 Demo 实施计划

- 状态：待实施；剩余工作均位于 `:demo:web-map`
- 记录日期：2026-08-30
- 适用版本：仓库所选择的 Minecraft 官方版本；本计划不改变版本选择
- 计划模块：`:demo:web-map`
- 当前范围：独立网页地图进程、视口批量查询、维度相关的 Chunk 表面投影、live world 读取和错误 Chunk 轮询
- 后端目标：`jvm`、`linuxX64`、`linuxArm64`、`mingwX64`、`macosArm64`
- 浏览器产物：Kotlin/JS browser bundle；它是由后端提供的网页资源，不是额外的后端运行平台

## 1. 目标与结论

新增一个独立于现有 Minecraft 客户端和服务端的 Demo。用户启动该程序并提供一个正在被 Minecraft
服务端使用的世界目录，随后通过浏览器访问网页地图。程序只观察磁盘上已经保存的世界数据，不接入服务端进程，
也不尝试读取仍只存在于服务端内存中的变化。

已经确定的总体设计如下：

1. 浏览器根据当前视窗计算一个包含两端的 Chunk 矩形范围，并用一个 HTTP 请求查询整个范围。
2. 浏览器拖动或缩放时不连续请求；交互停止后经过约 200 ms 防抖再查询当前视窗。
3. 后端按请求范围枚举 Chunk 并按 Region 分组；每个请求内通过
   `dimensions[dimensionId].openRegion(...).use { withReadScope(chunkNbtCodec) { readChunk(...) } }` 为每个 Region 创建一个
   `LiveRegionHandle` 并连续解码该组 Chunk。
4. 后端按 Demo 自己拥有的维度表面策略提取每个 X/Z 列的代表方块，返回 16 × 16 的二维表面数据，不渲染图片；下界从维度的
   `logicalBlockYRange` 顶端开始，跳过与逻辑顶端连通的连续非空气顶棚，越过首次空气区后选择第一个非空气方块；其他维度使用明确记录的
   fallback。
5. Region 文件不存在或 header 中没有目标 Chunk 时，响应省略该 Chunk；Region 打开或 header 读取失败时，该 Region 内本次请求涉及的全部
   Chunk 都返回错误标识；单个 Chunk payload 读取、解压或解码失败，或者成功解码后尚未完全生成时， 只标记该 Chunk。
6. 每个 HTTP 请求独立打开和关闭自己的 Region handles。后端不缓存结果，也不合并、共享或协调同时到达的请求。
7. 视窗请求期间继续显示旧内容；新响应到达后再整体替换当前视窗状态。错误 Chunk 使用单-Chunk请求按指数退避和最大重试次数
   进行有限补漏。
8. 一致性单位是一个成功解码的 Chunk。每个 Region 组通过 `withReadScope` 只读取一次 header，但该 header、后续 `.mca`
   payload 或 `.mcc` sidecar 仍可能来自不同保存时刻；不同 Chunk 来自不同保存时刻是可接受的。
9. Demo 通过公开库 API 组合世界与协议数据。它只接收世界目录，不要求调用方另外提供 data-pack references、
   registry snapshot、维度列表、dimension layout 或高度表；所需引用与数据全部由存档和仓库所选择版本的生成 vanilla 数据取得。

## 2. 明确不做的事情

第一版明确不处理以下内容：

- 不在 `LiveMinecraftWorldAccess` 或 Demo 中增加共享 Region registry、引用计数、锁或写入协调。
- 不跨请求复用 `LiveRegionHandle`/`FileHandle`；每个请求的每个 Region 组独立持有一个 handle，并在组处理完成后关闭。
- 不让 `RegionReadScope`、其 sequence 或借用的 Chunk stream 逃逸出回调。
- 不构造 Region 级稳定快照、revision、ETag 或跨 Chunk 事务。
- 不观察 Region 文件变化，不实现 SSE、服务端推送或成功 Chunk 的后台刷新。
- 不在后端生成地图图片、地图瓦片或纹理图集。
- 不从官方资源、资源包或模组资源加载方块贴图；第一版只使用方块标识的确定性颜色。
- 不在 `world-format`、`world-io` 或 `protocol-datapack` 提供“最高方块”或地图表面查询。表面含义属于游戏内容和 Demo
  展示策略；库只提供完整 Chunk、维度布局和 registry 事实。
- 不支持 Fabric 等 mod loader 注入的数据包来源、动态 registry 或其他运行时扩展；支持未使用 mod loader 的原版世界，以及原版机制
  能够启用的 core、built-in 和世界目录 file data packs。
- 不让用户或 Demo 启动参数另行提供 data-pack references、registry 内容、维度高度或其他 Minecraft 领域数据；启用列表只来自
  `level.dat`，file packs 只来自选定世界的 `datapacks` 目录。
- 不实现 DataFixer，也不根据 `DataVersion` 主动拒绝输入；按仓库所选择版本的 schema 直接解码，结构不兼容时按普通解码失败处理。
- 不观察服务端尚未保存到磁盘的内存状态。
- 不实现用户、权限、多世界管理、远程世界目录或面向公网的部署安全策略。

本计划使用现有 caller-owned live Region 生命周期：一个 `LiveRegionHandle` 在本请求的一个 Region 组内复用其 `.mca` 句柄，
一次 `withReadScope` 再让该组所有 Chunk 共用一遍 header 读取。后续请求重新打开 handle 和 scope，外部 `.mcc` sidecar 仍由
需要它的单个 Chunk 操作独立打开和关闭。

## 3. 模块和目标布局

使用一个新的 `:demo:web-map` Kotlin Multiplatform 子项目，保持 Demo 内部共享 DTO，同时隔离浏览器与主机文件系统能力：

```text
demo/web-map/
├── AGENTS.md
├── README.md
├── build.gradle.kts
└── src/
    ├── commonMain/       # 可序列化 API DTO、包含边界的 Chunk 范围和纯逻辑
    ├── commonTest/       # 坐标、范围、响应语义和表面投影测试
    ├── serverMain/       # JVM/desktop Native 共用的 Ktor 和 live world 代码
    ├── jvmMain/          # JVM executable 装配
    ├── nativeMain/       # desktop Native executable 装配
    ├── jsMain/           # 浏览器入口、Leaflet/Canvas、fetch 和请求代次控制
    └── jsTest/           # 不依赖真实浏览器 DOM、由 Gradle-provisioned Node 执行的前端状态测试
```

后端 target 集合固定为 JVM、Windows x64 (`mingwX64`)、Linux x64/ARM64 (`linuxX64`/`linuxArm64`) 和 macOS ARM64
(`macosArm64`)。不增加 `macosX64`。浏览器前端使用 Kotlin/JS browser target，但该 target 只生成由上述后端提供的 静态网页
bundle。Node 只可作为 Gradle 提供的 JS 测试运行器，不产生 Node executable 或 Node/JS server。不要增加 Android、
iOS、watchOS、tvOS、Wasm 或其他后端目标。

`serverMain` 是一个真实的 JVM + desktop Native 共享能力，因此允许创建一个自定义 source set；浏览器代码不得依赖
`world-io` 或 Ktor Server。`commonMain` 只放双方真正共享的序列化模型和纯逻辑。

类型归属固定如下：

- HTTP DTO、包含两端的视口转换和 `SurfaceProjectionPolicy` 放在 Demo `commonMain`；它们不进入 Minecraft 运行时库。
- Chunk/维度纯数据、NBT/Anvil schema、坐标、layout 和 filesystem-independent codec 只使用 `world-format` 类型，Demo 不复制对等模型。
- `Path`、`FileSystem`、世界目录映射、Region handle/read scope 和 live 文件生命周期只能通过 `world-io`
  出现在 Demo `serverMain`，不得进入 `world-format` 或 Demo `commonMain`。
- active registry 到 `ChunkDataRegistries`/`MinecraftChunkContext` 的组合只使用 `protocol-datapack`；不在
  `world-format` 或 `world-io` 增加协议类型依赖。

Gradle 接入包括：

1. 在 `settings.gradle.kts` 注册 `:demo:web-map`。
2. 在 version catalog 增加 Ktor Server content negotiation 等缺失 alias。
3. `commonMain` 直接依赖 `world-format` 和 DTO 所需的 kotlinx.serialization；`serverMain` 直接依赖
   `protocol-datapack`、`protocol-datapack-vanilla` 和 `world-io`。不要仅为取得 Chunk adapter 而依赖
   `protocol-client` 或 `protocol-server`，也不要让 `commonMain` 依赖 `serverMain` 的传递依赖。
4. 使用仓库根部已经声明的 Kotlin Multiplatform 和 Serialization 插件，不选择独立插件版本。
5. 配置 Kotlin/JS browser executable 和 Node test environment，并把 browser distribution、`index.html` 和 CSS 复制到每个
   server install 目录；不要发布 Node executable。
6. server 从明确的静态资源目录提供网页；开发运行可由参数传入 JS distribution 路径，安装产物使用相邻固定目录。
7. 按仓库规则为新子项目提供 README 和 AGENTS；README 只描述已经完成的启动方式和支持目标。

### 3.1 世界目录发现

后端启动时只选择一个世界目录，并使用以下严格优先级：

1. 首先读取 `MINECRAFT_WORLD_DIRECTORY`。环境变量存在且非空时，将其作为用户明确指定的世界目录；路径无效、不是目录或不包含
   `level.dat` 时启动失败，不再回退到自动发现。
2. 环境变量未指定时，从规范化后的当前工作目录开始逐级向父目录查找 `.minecraft-protocol-root`。包含第一个该标记文件的目录是
   工程根目录；只判断标记文件存在，不解释其内容。到达文件系统根仍未找到时启动失败。
3. 在工程根目录下使用
   `demo/launcher/minecraft/${MinecraftProtocol.MINECRAFT_VERSION}/saves` 作为候选 saves 目录；版本路径必须来自
   `MinecraftProtocol.MINECRAFT_VERSION`，不能复制仓库当前选择的版本字面量。
4. 只枚举 saves 目录的直接子目录，按目录名称升序排序并选择第一个。候选目录不存在、没有直接子目录，或选中目录不包含
   `level.dat` 时启动失败。
5. 启动日志记录世界目录来自环境变量还是工程根自动发现，并记录最终规范化路径。自动发现只服务仓库内开发运行；安装产物正常使用
   环境变量，不搜索其他约定目录。

环境变量读取是 Demo 的宿主进程配置边界；JVM 与 desktop Native 只提供各自最小的平台实现，不把通用进程环境 API 加入
Minecraft 运行时库。Demo README 把 `MINECRAFT_WORLD_DIRECTORY` 记录为安装产物的启动契约。

## 4. 运行时结构

```mermaid
flowchart LR
    Browser[Browser map] -->|Chunk range changed| Controller[Viewport generation controller]
    Controller -->|one viewport request| SurfaceRoute[Surface HTTP route]
    Controller -->|repair request group| SurfaceRoute
    SurfaceRoute --> Query[Viewport query service]
    Query -->|group by Region| LiveAccess[LiveMinecraftWorldAccess]
   LiveAccess -->|dimensions [dimensionId ] . openRegion|RegionHandle[LiveRegionHandle.use then withReadScope/readChunk]
    RegionHandle --> RegionFiles[.mca / .mcc]
Query --> Projection[Dimension-aware surface projection]
    Projection -->|Chunk results| Controller
    Controller -->|atomic view replacement or repair| Browser
```

每个视窗代次只拥有两组请求：一个完整视窗请求，以及该响应产生的一组错误 Chunk 补漏请求。Chunk 范围改变时先取消上一代的
视窗请求和全部补漏请求，再创建新代次。后端不知道前端代次，也不在不同请求之间共享 Region 资源或结果。

## 5. 视口查询契约

### 5.1 包含两端的坐标

请求使用两个 Chunk 端点，后端将四条边都视为包含：

```http
GET /api/map/{dimension}/surface?minChunkX=-10&minChunkZ=4&maxChunkX=6&maxChunkZ=15
```

语义为：

```kotlin
val chunkRange = ChunkRange.enclosing(
    ChunkPosition(minChunkX, minChunkZ),
    ChunkPosition(maxChunkX, maxChunkZ),
)
for (chunkPosition in chunkRange) {
    // query one Chunk
}
```

- 前端只要发现一个 Chunk 有任何像素与视窗相交，就把它放进范围。
- 精确边缘造成多包含一个 Chunk 是允许的，不为此增加开区间协议。
- 后端用 `ChunkRange.enclosing` 规范化两端，调用方传递顺序不影响结果。
- 范围宽、高和总 Chunk 数使用 `Long` 计算并在读取前验证，避免 Int 溢出和无界请求。
- 补漏请求复用同一 endpoint，并令最小、最大 Chunk 坐标相同来查询一个 Chunk；不增加第二套 HTTP 协议。
- 前端连续世界坐标转 Chunk 坐标使用 `floor`；后端使用 `ChunkRange`/`RegionRange` 转换，不手写对负数错误的 `/` 或 `%`。

响应回显规范化后的包含边界，使前端可以把它作为该矩形的一次完整查询结果。

### 5.2 HTTP 结果

普通视口查询返回 HTTP 200。单个 Chunk 缺失、单个 Chunk live read 失败，或者某个 Region 打开/header 读取失败，都不是整次 HTTP
请求失败。未处理的程序、Ktor 或基础设施异常可以终止整个请求并成为 500；客户端不会应用 500 的不完整响应。

后端必须先完成响应 DTO 的构造，再交给 Ktor 序列化，不能一边读取 Chunk 一边向 HTTP body 发布部分结果。

响应形状：

```json
{
  "minChunkX": -10,
  "minChunkZ": 4,
  "maxChunkX": 6,
  "maxChunkZ": 15,
  "chunks": [
    {
      "chunkX": -3,
      "chunkZ": 8,
      "status": "success",
      "surface": {}
    },
    {
      "chunkX": -2,
      "chunkZ": 8,
      "status": "read_failed"
    }
  ]
}
```

Kotlin 模型使用带 `status` discriminator 的 sealed variants，使 `success` 必须携带 surface、`read_failed`
不能意外携带半成品数据。不要使用一个 nullable surface 配合可构造出矛盾状态的普通 data class。

### 5.3 三态规则

一个请求范围内的 Chunk 有且只有以下三种可观察结果：

| 状态     | Wire 表示                | 后端含义                                                       | 前端行为                                       |
|----------|--------------------------|----------------------------------------------------------------|------------------------------------------------|
| 不存在   | 响应中没有该坐标         | Region 文件缺失，或本次 Region header 没有该 Chunk             | 不渲染；若来自补漏请求则停止轮询               |
| 成功     | `status = "success"`     | 完整读取、解压、NBT/Chunk 解码和表面投影成功                   | 使用新表面；若来自补漏请求则停止轮询           |
| 读取失败 | `status = "read_failed"` | Region 元信息不可读；Chunk 读取/解码失败；或解码后尚未完全生成 | 暂时保留同坐标旧表面，并加入当前代次补漏请求组 |

对 Demo 而言，Region 打开/header 读取失败会把该 Region 内本次请求涉及的全部坐标映射为 `read_failed`；payload 读取、解压、NBT
或语义 Chunk 解码的其他失败只映射当前 Chunk。成功解码后若
`chunk.chunkMetadata.chunkStorageMetadata?.isFullyGenerated != true`，也只把当前
Chunk 映射为
`read_failed`，不扫描或发布其部分表面，并进入相同的有限补漏流程。响应不包含内部异常文本，具体原因只通过服务端日志记录。

后端按 Chunk 捕获明确的 live-read/format/decoding 异常并继续构造同一次响应。`CancellationException` 必须立即重新抛出；
编程错误和启动配置错误不转换成 `read_failed`。

一个已存储、完全生成，但 16 × 16 列中都找不到非空气方块的 Chunk 仍然是 `success`，其 surface 表示 256 个空列。只有 Region
header 中没有该 Chunk 时才完全省略坐标。

### 5.4 世界 metadata

metadata endpoint 返回按维度 ID 排序的维度 ID 列表，前端由列表大小得到维度数量并用 ID 选择 surface route。这个列表只来自
`SavedDataFile<WorldGenSettingsData>.data.dimensions`；响应不提供或推断当前玩家维度、world preset ID、磁盘目录枚举结果或
generator 细节。

## 6. Chunk 表面模型与算法

### 6.1 解码上下文

启动时：

1. 调用 live world 的无参 `dataPacks.readEnabled()`。该入口通过自己的高层 `level.dat` 读取取得完整 persisted
   selection，再从世界
   `datapacks` 目录读取 enabled `file/...` directory/ZIP packs，并返回 detached `WorldDataPackLoadResult`。Demo 不另读
   `level.dat`，也不接受任何额外 pack 或 registry 输入。
2. 通过 live world 的 `data.read<SavedDataFile<WorldGenSettingsData>>(SavedDataId("world_gen_settings"))` 读取根 saved
   data， 并把强类型 `WorldGenSettingsData.dimensions` 作为权威维度集合。map key 已经是 `DimensionId`，level stem 已经区分
   referenced/inline dimension type；Demo 不检查裸 NBT tag。Region 目录由 `dimensions[dimensionId]` 按仓库所选择版本的
   namespaced layout 映射，不枚举磁盘目录。目录尚未创建不影响维度出现在 metadata 中。
3. 先调用 `worldDataPackLoadResult.toVanillaProtocolData()`，按 persisted 顺序补入匹配的 vanilla core/built-in packs、拒绝其余
   未提供 ID，并得到 `ResolvedProtocolData`；再调用
   `resolvedProtocolData.resolveMinecraftChunkContexts(worldGenSettingsData)` 返回按 `DimensionId` 索引的
   `MinecraftChunkContext`。这两步对应 data-pack 协议投影与语义 Chunk context 解析两个独立阶段；referenced type 从
   complete dimension-type registry 解析，inline type 直接从存档 NBT 解析。dimension-type NBT 字段解析、Section 边界换算和
   registry adapter 均不出现在 Demo。Demo 不调用只适合可协商服务端的 `resolveMinecraftWorld()`，因为观察磁盘不需要 Play
   Login raw ID。
4. 对每个维度直接使用 `minecraftChunkContexts.getValue(dimensionId).chunkNbtCodec`。该 codec 解码出的语义 Chunk 可直接进入
   使用同一 `MinecraftChunkContext` 的服务端 packet encoder；Demo 生成 surface DTO 时再通过
   `ChunkDataRegistries.blockStates.describe` 取得 `BlockStateDescriptor`，不读取或发送进程内 raw ID。
5. `level.dat`、`SavedDataFile<WorldGenSettingsData>` 和 Chunk 中的 `DataVersion` 作为存档数据保留，但 Demo 与 codec 都不主动
   将其与 `MinecraftWorldFormat.WORLD_VERSION` 比较。解码直接使用仓库所选择版本的 schema；调用方若需要预检版本，可在调用前
   自行读取并比较。

缺失、不可读或结构不合法的 `world_gen_settings.dat`，以及引用不存在 dimension-type entry 或包含无效 inline layout 的维度，都是
无法建立完整解码上下文的启动错误；不要仿照官方服务端生成随机 seed 的 fallback。第一版暴露该文件中每个 dimension type 都能解析的
维度，包括由 enabled data pack 注册类型的自定义维度；不要从 world preset、玩家当前位置或已有 Region 目录猜测维度集合、高度或
路径。

### 6.2 维度相关的表面策略

每个成功 Chunk 生成 16 × 16、按 `z * 16 + x` 排列的列结果。表面选择由 `:demo:web-map` 内可独立测试的
`SurfaceProjectionPolicy` 负责，而不是 `Chunk`、`ChunkLayout` 或 registry 的库方法：

1. 只有 `DimensionId.Nether` 使用顶棚策略。物理布局中高于逻辑范围的方块不参与扫描；扫描起点必须是
   `minecraftChunkContext.dimensionTypeLayout.logicalBlockYRange.last`，绝不能是 `ChunkLayout.maxBlockY`。若逻辑范围为空，则该列为空。
2. 状态机以 `SKIP_CEILING` 开始：当前方块为非空气时继续向下，不检查它是基岩、地狱岩、矿石、结构方块还是玩家放置的方块；这会跳过
   与逻辑顶端在该列连续相连的整个实体顶棚。遇到第一个空气方块后永久切换到 `SEEK_INTERIOR_SURFACE`。
3. `SEEK_INTERIOR_SURFACE` 跳过空气并返回第一个非空气方块。切换后再次出现的地狱岩按普通表层处理，岩浆和其他流体也属于非空气候选。
   若逻辑顶端本身是空气，则立即进入 `SEEK_INTERIOR_SURFACE`；若整列始终为实体、或进入空气区后直到 底部仍没有非空气方块，则该列为空。
4. 其他内置维度和自定义维度在完整 `ChunkLayout.blockYRange` 中返回最高非空气方块；不根据 `hasCeiling`
   自动选择 Nether 策略。
5. `minecraft:air`、`minecraft:cave_air` 和 `minecraft:void_air` 视为空气；其他方块和流体均为候选表面。
6. policy 选中的 `ProtocolBlockState` 必须经 active block-state registry 的 `describe` 转成方块名称和 properties；wire
   数据不携带 进程内 raw ID。
7. 找不到候选方块时记录空列，而不是把整个 Chunk 判为不存在。

不在库中新增 `highestBlock`、`surfaceBlock` 或 Nether 特例 API。

wire 数据使用每 Chunk palette + 256 个 row-major nullable palette index，避免重复发送相同 descriptor；空列使用 `null`。
响应不返回 Y 高度、biome 或光照。这是响应表示，不是后端缓存。

## 7. 后端查询流程

一次视口请求按以下顺序执行：

1. 解析两个包含端点并用 `ChunkRange.enclosing` 建立规范化的 `chunkRange`。
2. 按 `chunkRange.regionPositions()` 的确定顺序逐个处理所需 Region；每组目标由
   `chunkRange intersect regionPosition.chunkRange` 给出，请求内部不再并发 fan-out。
3. 每个 Region 组调用一次
   `liveMinecraftWorldAccess.dimensions[dimensionId].openRegion(regionPosition).use { liveRegionHandle -> ... }`，并在
   handle 内进入一次 `withReadScope(chunkNbtCodec)`；该 decoded scope 为组内所有目标 Chunk 共用一遍 Region header 读取和同一个
   codec。Region 打开或 scope/header 读取抛出预期的
   live-read/I/O/format 异常时，将该组全部目标坐标加入 `read_failed`，随后继续处理其他 Region。
4. scope 中 header 没有某个 Chunk 时省略该坐标；创建 handle 时 Region 不存在会得到 empty scope，因此整组自然省略。
5. 对 header 中存在的 Chunk，调用 scope 的 `readChunk(chunkPosition)`；`world-io` 负责读取 payload、按
   Region 压缩标识解压、完整消费 NBT source，并用 `ChunkNbtCodec` 解码语义 Chunk。Demo 不复制 Anvil framing 或拼装解码链路。
6. 解码成功后取得非空的 `chunk.chunkMetadata.chunkStorageMetadata` 并检查其 `isFullyGenerated`；若为 false，则当前 Chunk
   加入
   `read_failed`，不执行表面投影，并继续处理
   同组其他 Chunk。完全生成的 Chunk 才执行表面投影并加入 `success`。单个 Chunk 的 payload 读取、解压或普通解码异常同样加入
   `read_failed`。`CancellationException` 必须立即传播，不能转换成错误标识。
7. `withReadScope` 结束后其 sequence 和 stream 全部失效，`use` 随后关闭 handle；所有 Region 组完成后一次性返回响应。

这里使用的是普通 Chunk Region handle 所产生的 `RegionReadScope`；Entity Region handle 对应
`EntityRegionReadScope`，两者的 `readChunk` 只接受各自语义匹配的 codec。Demo 不需要再提供私有组合函数。

同步文件 I/O、解压和 NBT 工作不能运行在 Ktor selector loop 上。每个 HTTP 请求只把自己的完整查询流程交给后端工作上下文， 不创建
Region 并发任务、全局 semaphore、共享 handle cache 或请求合并器。多个请求即使同时查询同一 Region，也各自独立打开、 读取和关闭自己的
handle，彼此不等待或复用结果。

## 8. 前端请求控制与渲染状态

前端按 Chunk 范围维护单调递增的“视窗代次”。每一代最多只有两组受控请求：一个完整视窗请求，以及一个管理所有错误 Chunk 的
补漏请求组。页面初始化创建第一代；只有拖动或缩放使所需 Chunk 范围实际改变时才创建新代。

### 8.1 视窗改变

1. Leaflet/Canvas 只要判断一个 Chunk 有任何像素需要显示，就把它包含在当前范围中。
2. 所需 Chunk 范围一旦改变，立即取消上一代尚未完成的视窗请求及其整个补漏请求组，并增加代次、记录新范围。补漏组使用一个 父
   Job/AbortController 管理，使取消能够终止已发出的所有单-Chunk fetch 和后续轮询。
3. 每次范围变化都重置约 200 ms 防抖；防抖结束且范围未再次变化时，只发出一个覆盖完整新范围的视窗请求。旧的已渲染内容在
   取消和等待期间保持不变。
4. 只接受代次和范围仍匹配的完整响应；取消后仍迟到的旧响应直接丢弃。
5. 在内存中构造新视窗状态：`success` 使用新表面，省略坐标不渲染，`read_failed` 保留同坐标旧表面（若存在）并进入新代的
   补漏集合。构造完成后一次性替换显示状态，避免请求期间先清空画面。
6. 新视窗响应是该代补漏请求组的唯一来源；收到它之前不发补漏请求。

若视窗请求发生网络错误或返回 500，不应用部分状态，也不清除旧画面。第一版不为完整视窗请求增加自动重试；后续有效的范围改变或页面
重新加载会创建新代并重新请求。

### 8.2 视窗不变时补漏

1. 若当前代没有 `read_failed`，视窗不变期间不再向后端请求。
2. 若存在错误 Chunk，当前代只创建一个补漏请求组。该组使用可注入的重试策略，对补漏集合中的每个坐标使用同一 surface endpoint
   发出单-Chunk范围请求，并按确定顺序逐个等待结果；不重新请求整个视窗，也不增加并发调度或额外协议。
3. 单-Chunk响应为 `success` 时更新该 Chunk 并移出补漏集合；省略该坐标时删除可能保留的旧表面并移出集合；仍为
   `read_failed` 时保留现状、增加该坐标的尝试次数，并在指数增长且有上限的延迟后继续请求。每个坐标达到最大重试次数后停止自动
   请求并保留当前显示；新的视窗代次或页面重新加载会重新建立尝试状态。
4. 每次应用补漏结果前再次核对视窗代次和坐标仍在当前范围内。补漏集合清空后，该请求组结束。
5. 一旦 Chunk 范围改变，8.1 的取消步骤终止整组补漏请求；新代只从自己的完整视窗响应重新建立补漏集合。

因此除页面自身的静态资源加载外，一次视窗代次没有第三类 world-data 请求：只有一个视窗请求和一组补漏请求。第一版不增加动态 贴图
route；Canvas 根据方块标识生成确定性颜色。浏览器不创建每方块 DOM 节点。测试通过可注入的请求端和重试调度器推进状态，
不依赖真实计时器等待。

## 9. 实施阶段

### 阶段 A：Demo 模块与共享契约

1. 注册 `:demo:web-map`，配置 common/server/browser source sets 和目标。
2. 新增 README、AGENTS、安装/开发运行入口和静态资源装配；实现 `MINECRAFT_WORLD_DIRECTORY` 优先、向上查找
   `.minecraft-protocol-root`、使用 `MinecraftProtocol.MINECRAFT_VERSION` 定位 launcher saves 并确定性选择第一个世界目录的
   启动发现流程。
3. 实现包含两端的 `ChunkViewport` 请求 DTO 到 `ChunkRange` 的转换、surface DTO、sealed Chunk 结果和 JSON 配置。
4. 为请求范围限制、负坐标和序列化 round trip 添加 portable tests；范围规范化、交集和 Region 转换复用
   `world-format` 的既有测试契约。

### 阶段 B：live world 表面查询

1. 启动时只用无参 `dataPacks.readEnabled()` 读取 persisted selection 和 enabled file packs，再通过根 `data` 子入口读取强类型
   `SavedDataFile<WorldGenSettingsData>`。依次执行 `toVanillaProtocolData()` 和
   `resolveMinecraftChunkContexts(worldGenSettingsData)`，取得每个维度的 raw-ID-free protocol-backed Chunk codec。Demo
   的公开配置不接受额外 data-pack reference、registry、维度列表或 高度表。
2. 按 `chunkRange.regionPositions()` 处理 Region，并通过 `chunkRange intersect regionPosition.chunkRange` 在 caller-owned
   `dimensions[dimensionId].openRegion(...).use { withReadScope(chunkNbtCodec) { readChunk(...) } }` 中读取目标
   Chunk、映射三态，并按 Demo 的 dimension-aware `SurfaceProjectionPolicy` 投影表面；下界测试从逻辑顶端开始，并覆盖连续非空气
   顶棚、首次空气转换和转换后的第一个非空气方块。
3. 实现 Ktor metadata 与 surface routes；使用注入的 reader 测试 route，不让 HTTP 测试依赖真实 Minecraft 文件。
4. 证明单个 Chunk 失败不会阻止同响应中的其他成功 Chunk；Region 打开/header 失败只把该 Region 的目标 Chunk 标记为失败；
   未完全生成的 Chunk 返回 `read_failed` 且不发布表面；所有路径都不会吞掉协程取消。

### 阶段 C：浏览器地图与请求控制

1. 建立 Leaflet 简单平面地图和 Canvas Chunk layer。
2. 实现视窗到包含边界 Chunk 范围的转换、200 ms 防抖和单调递增的视窗代次。
3. 实现新代次先取消上一代视窗请求与整个补漏请求组，再只发一个新视窗请求。
4. 实现响应后原子替换显示状态，以及只轮询 `read_failed` 坐标、使用指数退避和最大重试次数的单一补漏请求组。
5. 用方块标识的确定性颜色完成可视地图；真实贴图来源保持独立。

### 阶段 D：打包与文档

1. 将浏览器 distribution 和 server executable 组成可运行安装目录。
2. README 记录世界目录、监听地址、端口和支持维度的参数来源与运行步骤。
3. 说明 live read 只能观察已保存数据、`read_failed` 的暂时性以及没有 Region/全图一致性保证。
4. 只在相应目标实际编译和运行后，把该目标写入 README 支持矩阵。

## 10. 验证计划

最窄验证顺序：

```shell
./gradlew :demo:web-map:jvmTest
./gradlew :demo:web-map:jsNodeTest
```

随后执行适用的 host Native test/compile 和安装任务。JS distribution 到 server 安装目录的 Gradle wiring 要验证
configuration-cache 首次存储与再次复用，最后运行 `./gradlew allTests`。所有 Gradle 调用按顺序执行。

必需测试覆盖：

- 包含两端的正坐标、负坐标、反向端点、单 Chunk 和跨 Region 范围。
- Region 文件缺失、Region 中 Chunk 缺失、全空气成功 Chunk 和正常表面 Chunk。
- 世界 metadata 中的维度集合只来自 `WorldGenSettingsData.dimensions`，不枚举目录；标准维度和使用已注册类型的 namespaced
  自定义 维度都转换为 `DimensionId` 并通过 `dimensions[dimensionId]` 访问，尚无 Region 目录的已声明维度仍会列出。
- 启动解码上下文只使用 `dataPacks.readEnabled().toVanillaProtocolData()`、强类型 world-gen settings 和共享 resolution。 未知
  unresolved pack 作为启动错误传播，Demo 除世界目录外不接受额外 data-pack reference、registry、维度或高度输入，也不读取
  资源包或方块贴图。
- `MINECRAFT_WORLD_DIRECTORY` 覆盖自动发现；未指定时从嵌套工作目录向上找到最近的 `.minecraft-protocol-root`，使用生成的
  Minecraft 版本常量定位 launcher saves，并按目录名称选择第一个存档；无标记、无存档和无效显式路径都明确失败。
- 同一请求的同一 Region 只打开一个 live handle、只读取一次 scope header，并在回调结束后关闭；新请求重新打开 handle，
  不共享文件或生命周期。
- Region 打开或 header 读取失败时，该 Region 在请求范围内的全部 Chunk 都是 `read_failed`，其他 Region 仍可成功返回。
- payload/sidecar 读取、解压、NBT 或 Chunk codec 的普通失败映射为 `read_failed`；成功解码但未完全生成的 Chunk 也映射为
  `read_failed`，不发布部分表面并进入有限补漏。
- 单 Chunk 失败不阻止同 Region 的其他 Chunk，且 scope/stream/handle 没有资源泄漏。
- `CancellationException` 不转换为普通失败。
- 同一响应中 success、read_failed 和省略坐标共存。
- 表面扫描覆盖完整 Y 边界、三种空气、row-major 顺序和 block-state properties；下界覆盖物理高度顶部的未生成空气不会参与扫描、
  逻辑顶端开始的任意连续非空气顶棚、逻辑顶端本身为空气、首次空气后的第一个非空气、转换后再次出现的地狱岩和岩浆，以及全列没有
  空气或进入空气区后再无非空气方块时的空列。
- 启动解码上下文使用 `resolveMinecraftChunkContexts()` 已构造的 `MinecraftChunkContext` 和 `ChunkNbtCodec`，并覆盖
  referenced 与 inline dimension type；持久化 codec 使用
  `ProtocolBlockState`/`ProtocolRegistryEntry`，输出 DTO 前再转换为 `BlockStateDescriptor`，且 Demo 不引入 client/server
  依赖。
- 两个同时到达且查询相同 Region 的 HTTP 请求分别打开和关闭自己的 handle，不共享结果或 lifecycle。
- Chunk 范围改变时，上一代视窗请求和已发出的全部补漏请求都被取消，迟到结果不能改变新代状态。
- 每代只发一个完整视窗请求；补漏请求组只查询该代 `read_failed` 坐标，按可注入的指数退避策略调度，并在成功、省略或达到最大重试
  次数后停止对应坐标的轮询。
- Demo 不比较 `LevelDat`、`SavedDataFile<WorldGenSettingsData>` 或 Chunk 的 `DataVersion`，也不增加版本专用异常或 HTTP
  状态分支。
- 视窗请求完成前旧画面保持不变；完整响应在内存中合成后一次替换，错误坐标可保留同坐标旧表面。
- 视窗不变且没有错误 Chunk 时不发 world-data 请求；重试测试使用注入调度器，不依赖真实 delay。
- surface route 在完整 DTO 构造前不发布部分 HTTP 响应。

Demo 不新增 host-filesystem source set，也不接收 Fixture Host 路径。对运行中世界的 live-read smoke test 使用开发者显式设置的
`MINECRAFT_WORLD_DIRECTORY` 手工执行，不是仓库 gate，不取得 `session.lock` 或修改世界。浏览器自动化同样不是仓库 gate；Node
测试覆盖纯前端逻辑，并执行一次人工浏览器渲染检查。

## 11. 完成标准

计划完成时应满足：

1. 一个独立安装产物能够从环境变量取得世界目录；仓库内开发运行未指定环境变量时，能够通过 `.minecraft-protocol-root`、生成的
   Minecraft 版本常量和 launcher saves 自动选择世界，并启动 Ktor 服务提供网页。
2. 页面首次打开和视窗停止移动后只发送一个包含边界的 surface 请求。
3. 后端只从世界目录和仓库生成的 matching vanilla 数据建立解码上下文：`level.dat` 提供有序 enabled references，世界
   `datapacks` 目录提供 enabled file packs，`VanillaDataPacks` 提供匹配的 core/built-in packs，全局
   `SavedDataFile<WorldGenSettingsData>` 提供权威维度集合，最终 active dimension types 提供 referenced 或 inline
   layout；缺失 reference 和无效 inline layout 在启动时失败。它不接受额外 Minecraft 领域输入，也不依赖目录枚举、world preset
   或玩家当前维度。
4. 后端为范围内每个可读且完全生成的 Chunk 返回 `SurfaceProjectionPolicy` 选出的表面；下界从逻辑顶端跳过连续非空气顶棚，在遇到
   空气后选择第一个非空气内部表层；其他维度返回最高非空气表面。真实缺失 Chunk
   被省略，读取失败或未完全生成返回 `read_failed`。
5. 单个 Chunk 失败不会破坏同一响应内其他成功 Chunk；Region 元信息失败只影响该 Region 的目标 Chunk；前端只通过当前代补漏
   请求组有限重试错误 Chunk，并使用指数退避与最大重试次数。
6. Chunk 范围改变时先取消上一代视窗请求和全部补漏请求；新响应返回前保留旧画面，返回后一次替换。
7. 后端没有世界表面缓存、图片渲染、共享 Region coordinator 或跨请求文件生命周期；只使用请求内每 Region 一个 caller-owned
   handle 和 callback-bound read scope。
8. 现有 `protocol-datapack`/`world-format`/`world-io` 边界未被反转；Demo 不依赖 `protocol-client`/`protocol-server`，live
   observer 从不获取 `session.lock` 或修改世界。
9. JVM、Kotlin/JS Node test-runner 逻辑测试和已声明的 host target 验证通过，README 与实际产物一致。
