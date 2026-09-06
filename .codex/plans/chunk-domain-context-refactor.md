# Chunk 数据模型、表示转换与模块重构计划

- 状态：已完成实现及本轮复核；完整 JVM、`allTests` 和配置缓存复用检查通过。实施结果和验收记录见第 12 节。
- 适用范围：仓库所选 Minecraft release；以 `MinecraftTarget.MINECRAFT_VERSION` 为唯一手动选择器，本次不更新 release。
- 兼容策略：直接迁移，不保留旧 Chunk/Entity 内容泛型、typealias、deprecated overload、旧模块转发壳或两套转换实现；property
  键和基础容器的必要泛型保留。
- 实施原则：先落实可用于用户计算的数据模型，再围绕模型实现磁盘、网络和应用组合。
- 内容边界：库不理解游戏逻辑。具体方块/实体的属性键、包装、强类型字段映射、配方、库存容量和交易等内容由用户定义；本次代表性实现仅放在测试代码。库提供通用数据结构、值映射机制及表示转换。

## 1. 目标与设计依据

`Chunk`、`EntityChunk`、`PoiChunk` 是可长期持有、可直接修改的内存数据。用户在自己的服务端、客户端或工具中读取它们，执行计算，再把结果写回这些对象。库负责数据表示、寻址和表示转换；用户负责
tick、AI、红石、光照传播、索引、事件、加载策略和保存时机。

本次重构同时完成原计划中的定向 codec、context 拆分、Play world adapter、Configuration/vanilla provider
拆分和命名迁移。这些后续设计均服从本文件第 2—3 节的数据契约。

依据如下；临时材料只提供可复查证据，不成为构建输入：

| 材料                                                                        | 用途                                                            |
|-----------------------------------------------------------------------------|-----------------------------------------------------------------|
| [数据模型调研 v2](../../temp/chunk-domain-study/research.md)                | 已确认的三个根类型、内部结构、普通引用与 property 契约          |
| [原版计算能力审查](../../temp/chunk-domain-study/computation-review.md)     | 对照 LevelChunk、实体、POI 及代表性计算；列出尚需细化的字段语义 |
| [Fabric 结构审查](../../temp/chunk-domain-study/fabric-structure-review.md) | 新增 NBT 路径、Section 计数行为和自定义网络同步边界             |
| [demo 能力审查](../../temp/chunk-domain-study/demo-capability-review.md)    | web-map 的实际读取、扫描、状态属性、时间戳与转换输入需求        |

官方 producer、consumer 和 codec 是 Minecraft 表示行为的依据。临时材料记录设计讨论阶段的审查；当前公开数据及方向映射见
[字段清单](../../world-format/CHUNK-DATA.md)、[网络投影清单](../../protocol-world/README.md) 和
[packet 形状说明](../../protocol-model/PACKET-MODELS.md)。具体原版实体、方块实体及结构 piece
的游戏内容由用户定义，库不实现完整内容目录；测试中的代表性定义用于验证开放能力。

## 2. 所有模型共同遵守的数据契约

### 2.1 普通对象、普通引用

1. 承载可变状态的根对象和嵌套数据对象使用普通可变字段、集合和引用。用户可以增删元素、替换整个集合、替换 properties 或替换根对象的
   domain context。
2. 完整构造器保存调用方传入的引用；getter 返回当前引用。没有接管、独占所有权、attach/detach、父引用、自动重新绑定或删除墓碑。
3. 删除对象只改变当前集合。外部保留的旧对象仍可修改；编码遍历当前根可达的数据，不保存所有曾出现过的对象。
4. 没有 dirty、revision、统一变更通知、自动缓存失效、隐藏派生索引或跨对象事务。修改方块后是否同步修改 BlockEntity、tick、光照或
   POI，由用户安排。
5. 不建立模型自带的深快照、锁或并发隔离。`data class.copy()` 是浅复制；需要独立数据或一致视图时由用户明确安排复制与执行顺序。
6. 不可变值保持不可变：坐标、标识、BlockState 等通过替换所在字段或格子修改。共享 BlockState 不提供可变后门。
7. 容器仍维护自身所需的形状和寻址约束。编码可报告循环、必需数据缺失、字段冲突或目标无法表达的值；这些操作检查不变成持续的对象生命周期管理。
8. 方法可以简化定位、遍历或集合操作，但不是唯一合法修改通道。不能公开裸容器后又要求用户必须经过某个 setter 才能使另一份权威数据正确。

以下 Kotlin 声明是目标 API 的字段与构造契约，省略方法体和基础值类型实现。普通承载类型优先使用 `data class`
；需要维护容器内部形状的类型按实际不变量选择实现，内部压缩方式不在本计划定案。

### 2.2 固定结构与动态内容

固定结构是代码直接表达的关系，例如 Section、palette、坐标、实体乘客列表和 POI
票数；它们同样可变。动态内容是名称、类型或具体字段集合可以扩展的内容，不按原版/mod、已知/未知、运行/保存来源拆成不同存储区。

```kotlin
class PropertyType<T : Any>(val name: String)

data class PropertyKey<T : Any>(
    val name: String,
    val propertyType: PropertyType<T>,
)

data class PropertyValue<T : Any>(
    val propertyType: PropertyType<T>,
    val value: T,
)

data class DataProperties(
    var entries: MutableMap<String, PropertyValue<*>> = linkedMapOf(),
) {
    operator fun get(name: String): PropertyValue<*>?
    operator fun set(name: String, propertyValue: PropertyValue<*>)
    operator fun <T : Any> get(propertyKey: PropertyKey<T>): T?
    operator fun <T : Any> set(propertyKey: PropertyKey<T>, value: T)
    fun <T : Any> require(propertyKey: PropertyKey<T>): T
    fun remove(name: String): PropertyValue<*>?
}

data class OptionalValue<T : Any>(val value: T?)
data class PropertyList(var values: MutableList<PropertyValue<*>>)
```

- `entries` 是唯一存储。按名称访问、带类型键访问和用户的包装类都操作同一条目。
- 类型令牌维持安全的类型关联，不写入 Minecraft 数据，也不靠相同 name 字符串证明两个令牌具有相同类型。带类型读取核对令牌；缺失与类型不匹配有明确结果。
- 动态入口允许删除或替换字段类型。之后需要原类型或必需字段的操作自行报告前提不满足，不在修改时阻止中间状态。
- 字段缺失表示没有该信息；`OptionalValue(null)` 等明确值可以表示已知为空。不能把缺失普遍解释成零、空集合或没有目标。
- 基础值、嵌套 DataProperties、PropertyList、NBT 值和库支持的语义值都可作为内容。可变值取出后是原引用，不在每个 getter
  中反序列化整个对象。
- 用户包装类通过组合 properties 提供方法，不要求继承 BlockEntity/Entity，不提供按泛型返回用户实际子类的工厂、绑定器或注册服务。库不预置具体游戏内容的包装。

`DataComponentMap`、`DataComponentPatch`、`StateProperties` 使用同一 property 思路，但保留各自的键、可变性和表示语义。它们不是通用
`extraNbt` 的别名；也不把不同存档层次的字段汇总到根 properties。

### 2.3 坐标与身份的唯一来源

| 数据                                            | 唯一来源                                      |
|-------------------------------------------------|-----------------------------------------------|
| 三种 Chunk 的位置                               | 根对象的 `chunkPosition`                      |
| ChunkSection / PoiSection 的绝对 Section Y      | 外层 `sections` 的键                          |
| BlockEntity 的绝对方块位置                      | `Chunk.blockEntities` 的键                    |
| PoiRecord 的绝对方块位置                        | `PoiSection.records` 的键                     |
| Entity 的 UUID、连续位置与旋转                  | Entity 自身字段                               |
| Attribute、modifier、effect、组件和结构类型标识 | 相应 Map 的键，除非该对象本来就是独立的标识值 |

修改根位置或替换 context 不会自动移动内部绝对坐标、重新分组实体或重算布局。需要位置的独立条目操作接受位置和对象，或使用
Map.Entry。坐标换算继续复用 `MinecraftCoordinates`，保留负坐标的 floor 语义。

## 3. 目标数据模型

### 3.1 Chunk 与完成态边界

```kotlin
data class Chunk(
    var chunkPosition: ChunkPosition,
    var chunkContext: ChunkContext,
    var sections: MutableMap<Int, ChunkSection>,
    var blockEntities: MutableMap<BlockPosition, BlockEntity>,
    var heightmaps: ChunkHeightmaps,
    var lighting: ChunkLighting,
    var blockTicks: MutableList<ScheduledTick<BlockId>>,
    var fluidTicks: MutableList<ScheduledTick<FluidId>>,
    var structures: ChunkStructures,
    var postProcessing: ChunkPostProcessing,
    var status: String,
    var inhabitedTime: Long,
    var upgradeData: UpgradeData?,
    var blendingData: BlendingData?,
    var properties: DataProperties,
) {
    constructor(chunkPosition: ChunkPosition, chunkContext: ChunkContext)

    val isFullyGenerated: Boolean
        get() = status == "minecraft:full"

    fun getBlockState(blockPosition: BlockPosition): BlockState
    fun setBlockState(blockPosition: BlockPosition, blockState: BlockState): BlockState
    fun getBiome(blockPosition: BlockPosition): BiomeId
    fun setBiome(blockPosition: BlockPosition, biomeId: BiomeId): BiomeId
    fun getBlockEntity(blockPosition: BlockPosition): BlockEntity?
}

data class ChunkContext(
    val dimensionId: DimensionId,
    val dimensionTypeLayout: DimensionTypeLayout,
    val defaultBlockState: BlockState,
    val defaultBiome: BiomeId,
)
```

`Chunk` 不保存 EntityChunk 或 PoiChunk。ChunkContext 只含共享数据事实，不含 codec、registry raw ID、Level、连接或回调。
`DimensionTypeLayout` 保留 minY、height、logicalHeight、hasSkyLight、hasCeiling 和派生 ChunkLayout。多个 Chunk 可以共享
context；根对象的 context 引用可以被用户替换。

**支持范围固定为已完成生成区块的内存表达。** 磁盘读取始终按完成态模型尽量读取，保留实际 status 供用户判断，不根据 status 选择
ProtoChunk 读取分支，不补全、不继续生成，也不专门保留生成期字段。没有承载位置的数据在转换后不再存在。

“尽量读取”不保证每个未完成记录都能成功构造 Chunk，也不要求为缺失的必需结构制造值。一般格式错误照常报告。成功取得结果后，是否因
`status != "minecraft:full"` 放弃使用由用户决定；库不自行拒绝加载或重试。把 status 改成 full 只改变字符串。

### 3.2 Section、palette 与 BlockState

| 类型              | 字段和数据形状                                                                                                                  |
|-------------------|---------------------------------------------------------------------------------------------------------------------------------|
| ChunkSection      | `var terrain: SectionTerrain?`、`var lighting: SectionLighting`、`var properties: DataProperties`                               |
| SectionTerrain    | `var blockStates: PalettedContainer<BlockState>`、`var biomes: PalettedContainer<BiomeId>`、`var statistics: SectionStatistics` |
| SectionStatistics | 四个独立的 `Int?`：nonEmptyBlockCount、fluidCount、tickingBlockCount、tickingFluidCount                                         |
| BlockState        | 不可变 `blockId: BlockId`、`properties: StateProperties`；提供带类型读取和返回新状态的 `with`                                   |
| StateProperties   | 不可变开放集合；支持全量枚举、名称访问和 StateProperty<T> 访问                                                                  |
| StateProperty<T>  | 属性名、值类型、合法值与规范值名之间的明确对应                                                                                  |

方块 palette 为 16×16×16 个格子，biome palette 为 4×4×4 个采样格。容器提供按局部坐标或索引读写和枚举；其内部 palette ID
不是方块身份，不进入 BlockState 的相等性。

在建造范围内，缺失 Section 或 null terrain 按 context 默认方块与 biome 读取，读取不物化数据；写入需要时创建默认
terrain。允许只有光层或 properties 的 Section，边界光照 Section 不必制造空气地形。建造范围外的地形不是本版 Chunk 的有效地形输入。

BlockState 的相等性取决于 blockId 与逻辑属性值，属性顺序和类型令牌对象身份不改变相等性。枚举时能取得 Minecraft
的规范属性名与字符串值，用于存档及资源匹配；未知字符串值可保留。类型访问和字符串表示通过同一属性规则对应，不增加第二份可变字符串
Map，不用任意对象的 toString 猜测值名。

统计中的 null 表示未提供；方块修改不会自动清空或更新统计。不能仅凭 isAir、不可见或零计数省略实际数据。需要省略 Section
时，转换必须保证实际方块、biome、光层和 properties 的语义不变，详见第 4.3 节。

普通方块没有原版通用的每格任意 NBT 槽。方块状态属于 palette，个体内容通常属于 BlockEntity；用户额外的位置到内容映射可以组织在相应
properties 内。

### 3.3 BlockEntity、组件与物品

| 类型                | 可变数据                                                                    |
|---------------------|-----------------------------------------------------------------------------|
| BlockEntity         | blockEntityTypeId、components: DataComponentMap、properties: DataProperties |
| DataComponentMap    | `entries: MutableMap<ComponentId, PropertyValue<*>>`                        |
| ItemStack           | itemId、count、components: DataComponentPatch、properties: DataProperties   |
| DataComponentPatch  | `entries: MutableMap<ComponentId, ComponentPatchEntry>`                     |
| ComponentPatchEntry | `SetValue(PropertyValue<*>)` 或 `Removed`                                   |
| ItemSlots           | `items: MutableList<ItemStack?>`；null 表示空槽                             |

BlockEntity 不重复保存位置或当前格子的 BlockState。components 与普通本体 properties 对应不同的原版字段位置；二者都可动态操作，均不区分原版和
mod。

ItemStack 的组件 patch 保留三态：缺少键表示沿用物品定义，Removed 表示明确删除默认组件，SetValue 表示覆盖。删除 patch
的键只移除覆盖。共享物品定义和默认组件由外部提供，不在实例中复制另一份。

库存、燃烧/烹饪计时、战利品、文本、锁、移动活塞状态、箱盖进度等放入 BlockEntity.properties 的对应条目。需要开放内容的
ItemStack 等通用语义值也保留自身的动态入口。文本、匹配条件及具体内容的包装由用户定义并操作这些引用；具体机器字段、映射和算法不进入库。

### 3.4 高度、光照、tick 与其他 Chunk 状态

| 类型                   | 可变数据和语义                                                                                       |
|------------------------|------------------------------------------------------------------------------------------------------|
| ColumnData<T>          | 16×16 列数据，支持逐列读写                                                                           |
| ChunkHeightmaps        | `maps: MutableMap<HeightmapType, Heightmap>`、properties                                             |
| Heightmap              | `firstAvailable: ColumnData<Int?>`；已知空列是 minY，null 为未知                                     |
| ChunkLighting          | isLightCorrect、`skyLightSources: ChunkSkyLightSources?`                                             |
| SectionLighting        | `blockLight: LightLayer?`、`skyLight: LightLayer?`                                                   |
| LightLayer             | 16×16×16 个 0..15 光值；整个光层缺失不同于全零                                                       |
| ChunkSkyLightSources   | `lowestSource: ColumnData<SkyLightSourceBoundary?>`                                                  |
| SkyLightSourceBoundary | AtY(y) 或 BelowWorld；外围 null 为未提供                                                             |
| ScheduledTick<T>       | type、blockPosition、`triggerTick: Long`、priority、`subTickOrder: Long`、properties                 |
| SavedTick<T>           | type、blockPosition、`delay: Int`、priority、properties                                              |
| ChunkPostProcessing    | `positions: MutableMap<Int, MutableList<LocalBlockPosition>>`，保留顺序与重复项                      |
| ChunkStructures        | starts: StructureId 到 StructureStart、references: StructureId 到可变 ChunkPosition 集合、properties |
| StructureStart         | Invalid；或含 chunkPosition、references、pieces、properties 的 Valid                                 |
| StructurePiece         | pieceTypeId、boundingBox、orientation: Direction?、genDepth、properties                              |
| UpgradeData            | sides、按 Section 的 indices、邻区 block/fluid SavedTick 列表、properties                            |
| BlendingData           | minSection、maxSection、heights、biomes、densities、properties；缺失采样和未提供的运行数据保持可空   |

高度图、统计、天空光来源和光值是可独立修改的数据，没有自动重算或依赖失效。结构起点包围盒、piece
的派生变换由用户按现有字段计算，不保存需要自动同步的第二份结果。

普通运行 tick 列表保存 ScheduledTick；它不保证排序或去重。SavedTick 用于持久化相对延迟和升级邻区请求，时间转换见第 4.2
节。模型不含优先队列、调度器或 onTickAdded 回调。

BlendingData 的精确嵌套形状沿用已确认草案：heights 为 `MutableList<Double?>`，biomes 为
`MutableList<MutableList<BiomeId>?>?`，densities 为 `MutableList<MutableList<Double>?>?`
。列索引和采样解释按匹配官方实现补齐，不手写未经审计的长度或索引公式。

### 3.5 EntityChunk 与 Entity

```kotlin
data class EntityChunk(
    var chunkPosition: ChunkPosition,
    var entityChunkContext: EntityChunkContext,
    var rootEntities: MutableList<Entity>,
    var properties: DataProperties,
) {
    constructor(chunkPosition: ChunkPosition, entityChunkContext: EntityChunkContext)
}

data class EntityChunkContext(val dimensionId: DimensionId)

data class Entity(
    var entityTypeId: EntityTypeId,
    var uuid: Uuid,
    var position: EntityVector3d,
    var deltaMovement: EntityVector3d,
    var entityRotation: EntityRotation,
    var passengers: MutableList<Entity>?,
    var properties: DataProperties,
)
```

Entity 和 EntityChunk 均移除内容泛型。rootEntities 和 passengers 只是当前嵌套关系，不登记树所有权，不维护 vehicle 反向引用、UUID
索引、Section 分桶或自动迁移。移动实体只改 position；更换所属根列表由用户完成。实体及乘客不受方块建造高度限制，乘客位置可以跨出根所在区块。

passengers 的 null 表示尚未获得关系信息，空列表表示已知没有乘客。持久化表示明确省略 Passengers
时采用其空列表缺省；网络没有发送该关系不自动等同于空列表。要求完整关系的输出由调用方补足未知值。

编码从当前根列表递归遍历，不全局去重，不按位置悄悄迁移文件。循环由编码操作报告，非循环共享引用按所在位置读取。官方保存乘客时对
Pos 的表示规则在遍历中处理，不反向改动内存 Entity.position。

除骨架字段外，实体公共状态和具体内容都进入同一份 Entity.properties，包括环境/碰撞、历史位置、姿态、展示
flags、名称、属性、效果、装备、目标、Brain、交易和任意 customData。LivingEntity 的 Float health 与 ItemEntity 的 Int health、持久化
glowing 与显示 flags 等不同事实保持不同语义，不按字段名相似而合并。

| 可嵌套类型        | 可变数据                                                                                |
|-------------------|-----------------------------------------------------------------------------------------|
| EntityAttributes  | AttributeId 到 AttributeInstance 的 entries                                             |
| AttributeInstance | baseValue: Double、ModifierId 到 AttributeModifier 的 modifiers、properties             |
| AttributeModifier | amount: Double、operation、permanent: Boolean、properties                               |
| EntityEffects     | MobEffectId 到 EffectState 的 entries                                                   |
| EffectState       | amplifier、duration、ambient、visible、showIcon、hiddenEffect: EffectState?、properties |
| EntityEquipment   | EquipmentSlotId 到 ItemStack? 的 slots                                                  |

Brain、MemorySlot、VillagerData、交易和 gossip 属于用户定义的游戏内容，本次仅在测试侧提供示例类型、键和映射。
用户可以表达 memory 缺少键、已有空值和已有值等区别；库不预设这些内容的字段语义。通用效果 duration 的表示 sentinel、属性
modifier
的 operation 值和 permanent 的持久化语义保留，但库不计算效果、属性、TTL、价格、补货或历史样本。

### 3.6 PoiChunk 与 POI

```kotlin
data class PoiChunk(
    var chunkPosition: ChunkPosition,
    var poiChunkContext: PoiChunkContext,
    var sections: MutableMap<Int, PoiSection>,
    var properties: DataProperties,
) {
    constructor(chunkPosition: ChunkPosition, poiChunkContext: PoiChunkContext)
}

data class PoiChunkContext(
    val dimensionId: DimensionId,
    val chunkLayout: ChunkLayout,
)

data class PoiSection(
    var isValid: Boolean,
    var records: MutableMap<BlockPosition, PoiRecord>,
    var properties: DataProperties,
) {
    constructor(isValid: Boolean)
}

data class PoiRecord(
    var poiTypeId: PoiTypeId,
    var freeTickets: Int,
    var properties: DataProperties,
) {
    constructor(poiTypeId: PoiTypeId, freeTickets: Int)
}

data class PoiType(
    val matchingStates: Set<BlockState>,
    val maxTickets: Int,
    val validRange: Int,
)
```

没有 PoiManager、世界索引、村民对象或保存回调。PoiType 是外部共享定义，不在每条记录重复保存。

缺失 Section、存在且无效、存在且有效为空、存在且有效非空是不同状态。增删 records 或修改票数不自动改变 isValid；删除最后一条记录不自动删除
Section 及其 properties。

freeTickets 是普通 Int，不静默 clamp。原版读取时 `free_tickets` 缺省为 0、`Valid` 缺省为 false；新建未占用记录由调用方显式给出
maxTickets。这些 schema 缺省不替代构造器的显式参数。

PoiRecord 不保存占用者 UUID。村民记忆与 POI 票数的共同修改、最近 POI 查询、匹配方块扫描和距离传播由用户实现。

### 3.7 构造、默认值与共享定义

完整构造器要求上述全部初始数据，直接保存引用；不限制构造器只为强迫使用 factory。便捷新建入口只采用以下明确默认值：

| 入口                                          | 默认行为                                                                                                                                                                                         |
|-----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Chunk(position, context)                      | 新建空 sections、blockEntities、ticks、结构集合、postProcessing 和 properties；空高度图；isLightCorrect=false、skyLightSources=null；status=full、inhabitedTime=0、upgradeData/blendingData=null |
| ChunkSection(defaultBlockState, defaultBiome) | 新建两个均匀 palette；四项统计为 null，光层均未提供，properties 为空                                                                                                                             |
| EntityChunk(position, context)                | 新建空 rootEntities 与 properties                                                                                                                                                                |
| PoiChunk(position, context)                   | 新建空 sections 与 properties                                                                                                                                                                    |
| PoiSection(isValid)                           | 新建空 records 与 properties                                                                                                                                                                     |
| PoiRecord(type, freeTickets)                  | 只新建空 properties                                                                                                                                                                              |
| BlockEntity / 其他便利入口                    | 只省略明确为空的 components/properties；身份、位置、状态等必需事实不暗中选择                                                                                                                     |

新建完成态 Chunk 不执行生成，也不保证默认方块已经具有所需 BlockEntity 或计算结果。解码不能调用便利构造器来掩盖来源缺失。

方块定义、属性合法值、物品默认组件、Attribute 默认供应、POI 定义、碰撞与流体规则等由外部共享定义提供；不通过名称猜测，不把这些算法或
registry raw ID 放进 ChunkContext。属性集合缺项与默认供应之间的具体访问规则在阶段 A 完成审计后写入对应类型契约。

### 3.8 相对官方形状的有意调整

| 官方对应物                              | 本库取舍与原因                                                                     |
|-----------------------------------------|------------------------------------------------------------------------------------|
| LevelChunk / ChunkAccess                | 保留数据类别；剥离 Level、ticker、listener、加载与保存所有者                       |
| LevelChunkSection                       | 保留两种 palette 形状；按绝对 Y 的 Map 组织普通数据，支持光层边界                  |
| 光照引擎的数据层                        | 聚合纯光值到 Chunk，使调用方可以从该数据对象取得和修改它们                         |
| BlockEntity / Entity 具体子类           | 以 properties 保存内容、以组合提供强类型方法，避免 Java runtime 继承与生命周期耦合 |
| BlockEntity / PoiRecord 内部位置        | 只使用外层 Map 键，避免可变子对象与索引的重复权威字段                              |
| LevelChunkTicks、实体管理器、PoiManager | 保留记录、关系和状态，索引、调度和事件由用户拥有                                   |

## 4. 表示差异与动态映射

### 4.1 唯一数据源与字段映射

每项映射记录：对象作用域、字段路径、表示类型、domain 值类型、缺省/缺失含义、读写方向、官方
producer/consumer，以及必要的调用方输入。固定结构字段由其 domain 字段或 Map 键产生；动态内容由该作用域的 properties
产生。结构名称与动态条目冲突时报告错误，不靠覆盖顺序选择权威来源。

动态 NBT 映射保留数值宽度、数组种类、列表及嵌套语义。未知 compound 可以物化成嵌套 DataProperties，NBT
值也可作为单个属性值保留。库提供匹配保存格式的结构转换和通用值 reader/writer；具体游戏字段的强类型绑定由用户提供。未绑定字段走基础动态映射，不要求安装
mod 类或知道它的
attachment type。

普通 Kotlin 自定义值需要调用方提供相应读写映射；没有映射时不以“任意对象”身份自动序列化，也不静默丢弃。只在运行时使用的值可以由明确的输出映射排除。映射规则属于转换配置，不是
properties 的另一份存储或变更追踪分类。

模型保留了动态入口，并不意味着任意固定 schema 改动都受支持。未完成生成专属数据、无法表达的结构形状、原始压缩字节和未建模的表示细节仍可通过
raw NbtDocument/record 路径处理；不为它们伪造完成态语义。

### 4.2 持久化 metadata 与时间

| 类型                   | 仅供表示转换的内容                                                              |
|------------------------|---------------------------------------------------------------------------------|
| ChunkNbtMetadata       | dataVersion: Int、lastUpdateTime: Long                                          |
| EntityChunkNbtMetadata | dataVersion: Int                                                                |
| PoiChunkNbtMetadata    | dataVersion: Int                                                                |
| 各自的 NbtDecodeResult | 对应 Chunk 值与对应 metadata                                                    |
| RegionChunkInfo        | Region/slot、compression、placement、压缩长度、timestampEpochSeconds 等存储事实 |

前三种 metadata 不进入域对象，不合并成万能 WorldNbtMetadata。完整文件 schema，例如
LevelDat、PlayerData、SavedDataFile，仍按其文件职责保存 DataVersion。codec 不把读取的 DataVersion 与 selected release 或
expected version 比较；兼容与迁移政策由用户决定。

`LastUpdate` 是存档的 world game time，Region timestamp 是有符号 32 位 Unix epoch seconds，运行 triggerTick 是调用方运行时钟中的执行
tick。这三者不能互相替代。

- NBT decoder 接收显式 tick 时间基准，按官方 SavedTick.unpack 语义把相对 delay 还原为绝对 triggerTick，并按官方列表顺序恢复
  subTickOrder。
- NBT encoder 接收本次时间基准，按官方 ScheduledTick.toSavedTick / LevelChunkTicks.pack 语义生成相对 Int delay
  与保存顺序。它可以建立临时编码顺序，但不重排或修改用户的运行列表，不执行 tick、不去重。
- 负 delay、优先级、数值窄化和同 tick 顺序按匹配实现验证，不 clamp 成方便的默认值。磁盘不携带原始 subTickOrder
  的全部绝对数值，不能承诺该数值无损往返。
- 本次 encoder metadata 与 tick 时间基准在完整 context 中显式给出。真实保存通常由调用方用同一次 game-time 采样准备；库不从
  LastUpdate 推测调用方的运行时钟，不读系统时钟，不缓存随保存变化的时间。

NBT 未携带的运行缓存按模型已经定义的 null/字段缺失语义表达；需要额外必需事实的具体映射从其构造配置取得。不能把“未持久化”当成用户不得在
properties 中保存运行状态的理由。

### 4.3 Fabric 与 mod 的具体边界

本轮核对的匹配 Fabric API 不要求改变 Section/palette、实体根列表/乘客或 POI 的固定形状，但增加了以下 NBT 字段：

| 位置                                                       | 当前模型入口                      |
|------------------------------------------------------------|-----------------------------------|
| 地形 Chunk 根的 fabric:attachments                         | Chunk.properties 中的同名嵌套条目 |
| block_entities 中每个对象的 fabric:attachments             | 对应 BlockEntity.properties       |
| Entities 及递归 Passengers 中每个对象的 fabric:attachments | 对应 Entity.properties            |

附件 ID 位于这个嵌套 compound 内，其值形状由附件格式决定；不将 ID 展平到根，也不把 Entity 附件移入 EntityChunk 根。标准
attachment API 本轮未发现 EntityChunk 根、Section 或 POI 目标；这些层次的 properties 是本库通用扩展能力。

动态保留不使用 Fabric 的注册对象解码作为前提，避免未注册附件被跳过。Fabric 对自定义空气方块计数的修正提醒转换不能只凭空气分类或零计数丢弃非默认状态。

附件网络同步另用 `fabric:attachment_sync_v1` 等自定义 payload 和专用 codec；磁盘保留能力不能推出原版 Chunk packet
会携带它。库不内建 mod 数据类型或加载 Fabric 生命周期机制。改变固定格式或另用外部文件的 mod 按其实际表示另行适配。

### 4.4 网络视图与缺失信息

| 网络输入/输出范围                                                                 | domain 处理                                                             |
|-----------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| 完整 Chunk packet 中的方块、biome、计数、heightmap、光照与 BlockEntity update tag | 仅映射 packet 实际提供的内容                                            |
| 未发送的 status、inhabitedTime、运行 tick、structures、postProcessing 等根字段    | 由 decoder context 中的显式缺失数据提供方确定                           |
| 未发送的统计、缓存、BlockEntity 私有字段、Entity 关系等                           | 按具体映射显式提供值，或采用模型允许的未知/未提供表达；不把未知等同于空 |
| 服务端私有状态与自定义附件                                                        | 根据目标消息的实际字段另行映射，不能把完整存档 NBT 直接当作 update tag  |
| DataVersion、LastUpdate、Region metadata                                          | 从相应持久化/存储上下文提供，不从网络恢复                               |

磁盘和网络共用这些域对象，不建立 DiskChunk、ClientChunk、来源标签或全局“完整性”标志。模型自身有语义理由的可空字段保留，不再采用旧稿“一律禁止
availability”的表述。

packet-derived Chunk 可以在获得本次编码配置后写盘；输出体现其当前数据，不能宣传为服务端世界的无损备份。编码不能仅因来源是网络而拒绝，也不能仅因希望立即写盘就补出未知字段；目标必需的信息由调用方补齐或由显式映射提供。

本次迁移保持完整初始 Chunk packet 的转换和保存能力，不实现持续客户端世界镜像。后续方块、biome、光照、BlockEntity
增量包及卸载包的应用，由用户代码或单独任务实现。

## 5. 定向转换 API 与上下文

### 5.1 规范路径

```text
文件/Region 存储
  ↕ world-io、Anvil、compression
已解压 binary NBT Source / Sink
  ↕ 对应 NbtDecoder / NbtEncoder
Chunk / EntityChunk / PoiChunk
  ↕ 有真实网络对应的 PacketDecoder / PacketEncoder
packet model
  ↕ MinecraftPacketPayloadFormat
packet payload bytes
```

这里的三层是持久化层、内存层和网络层，对应磁盘记录、内存域值和网络数据包。MCA 封装、压缩、NBT 二进制以及 packet 字节编码属于各层内部处理。

encode 始终从 domain 值到表示，decode 始终从表示到 domain。跨层转换的两个方向提供对应的 encoder/decoder 类型与操作名。
各方向只在需要可配置输入时接收 context，且只携带所需事实；没有配置时不建立空 context。数据自己的 context 独立表达该层共享事实，
并非每层都有。需要配置的定向 codec 使用各自显式命名的完整 context；其中可以保留转换所需的 domain context 引用。
表示内部的双向物理 Format 不因此拆成两个公共 facade，内部编码与解码类型仍分别负责自己的方向。

只连接相邻层。不新增 packet↔NBT、Region↔packet codec；端到端便利函数可以组合相邻转换，但不能绕过域值。

domain context 是用户可读写根引用所指向的数据。decoder 可以把构造配置中的同一 domain-context 引用交给结果；encoder
的配置只来自自身构造 context，不能从输入值的 domain context 读取或交叉校验配置。编码读取当前值的 sections、properties
等是必要的数据访问，不受这项配置边界限制。

### 5.2 NBT codec

```kotlin
data class ChunkNbtDecoderContext(
    val chunkContext: ChunkContext,
    val nbtFormat: NbtFormat,
    val nbtPropertyReadMappings: NbtPropertyReadMappings,
    val tickBase: Long,
)

data class ChunkNbtEncoderContext(
    val chunkLayout: ChunkLayout,
    val nbtFormat: NbtFormat,
    val nbtPropertyWriteMappings: NbtPropertyWriteMappings,
    val tickBase: Long,
    val chunkNbtMetadata: ChunkNbtMetadata,
)

class ChunkNbtDecoder(val chunkNbtDecoderContext: ChunkNbtDecoderContext) {
    fun decode(source: Source): ChunkNbtDecodeResult
    fun decodeDocument(nbtDocument: NbtDocument): ChunkNbtDecodeResult
}

class ChunkNbtEncoder(val chunkNbtEncoderContext: ChunkNbtEncoderContext) {
    fun encode(chunk: Chunk, sink: Sink)
    fun encodeDocument(chunk: Chunk): NbtDocument
}
```

`NbtPropertyReadMappings` 与 `NbtPropertyWriteMappings` 是第 4.1 节所述的方向性内容映射配置，包含所安装语义值映射需要的全部定义/事实。它们不保存值对象、修改历史或另一个
properties。通用动态 NBT 路径不要求注册每个未知字段；通用值映射由拥有模块提供，原版或 mod 的具体内容绑定均由用户显式传入。具体映射声明
API 在阶段 A
固定，其输入和输出不得突破第 4.1 节的单一数据源规则。

其余 NBT 方向遵循相同公开形状，各自独立命名，不恢复双向 `*NbtCodec` facade 或公共 base context：

| 方向 context                 | 完整构造输入                                                                  |
|------------------------------|-------------------------------------------------------------------------------|
| EntityChunkNbtDecoderContext | entityChunkContext、nbtFormat、nbtPropertyReadMappings                        |
| EntityChunkNbtEncoderContext | nbtFormat、nbtPropertyWriteMappings、entityChunkNbtMetadata                   |
| PoiChunkNbtDecoderContext    | poiChunkContext、该条记录的 chunkPosition、nbtFormat、nbtPropertyReadMappings |
| PoiChunkNbtEncoderContext    | chunkLayout、nbtFormat、nbtPropertyWriteMappings、poiChunkNbtMetadata         |

各 decoder 返回相应域值与 metadata 的 decode result；各 encoder 接受域值与 Sink，并提供同源 document
支线。按具体内容需要的额外必需事实由映射配置显式携带，不建立空 context，也不把后续可变全局状态当作输入。

DataVersion 和 LastUpdate 由 decoder 从磁盘记录读取，不由 decoder context 提供；encoder context 则必须提供本次写入的
metadata。它们不进入内存域值，LastUpdate 也不代替 tickBase。

NBT 普通路径读写一份已解压、使用匹配官方根模式的完整值，并组合 NbtFormat。codec 不关闭调用方 Source/Sink，不 flush
Sink。compression、Region framing、文件路径和替换政策不进入 codec。

流与 document 入口复用同一语义实现。普通流路径不先把整个输入装成 NbtDocument、字节数组或字符串；为最终属性值物化所需的动态子树是正常模型构造。raw
document 入口仍可独立于域模型检查或保留原始内容。

Chunk 和 EntityChunk 的位置从 NBT 获取，Region slot 只负责选择记录，不增加 expectedPosition。POI 根 NBT
没有位置，因此解码前把选中记录的位置放入完整 PoiChunkNbtDecoderContext；decoder 不推测它，也不把它伪装成维度级常量。记录与
Section 的内在坐标关系由使用该关系的操作验证。

解码所需 layout 来自显式 domain context，编码则直接接收所需的 ChunkLayout；Entity NBT 编码不需要维度或 layout。
持久化 yPos 不成为第二份 layout 配置。只检查转换需要的结构与范围，不将跨来源配置一致性变成默认加载政策。

### 5.3 codec 的绑定范围与 world-io

| 配置变化范围                                          | 绑定位置                                                   |
|-------------------------------------------------------|------------------------------------------------------------|
| 维度身份、布局、默认值稳定                            | 共享 domain context；这不是 codec 绑定本身                 |
| NBT 配置、映射和 tickBase 在某次读取批次稳定          | 该批次或更窄 typed read scope 复用预构造 decoder           |
| 所有 decoder 输入确实在整个维度会话稳定               | typed dimension view 可以绑定 decoder                      |
| POI 的位置或其他解码输入逐记录变化                    | 为该次记录构造完整 context/decoder，使用显式 per-read 入口 |
| 保存时间、metadata 或其他 encoder 输入逐次变化        | 本次 write 接收 encoder；明确共享全部输入的 batch 才复用   |
| packet registry 在连接 epoch 稳定、布局在当前维度稳定 | 对应 epoch/维度的 packet codec 组合                        |

不再笼统规定“Chunk decoder 必然在维度生命周期稳定”：新增运行 tick 时间基准后必须按全部构造输入判断。使用预构造 codec 或完整
context 的入口分别清晰表达稳定与逐次场景，不通过 nullable codec 和隐式 override 混合。

world-io 的类型化便利入口只负责定位、资源与相邻格式组合：读时把解压流交给 decoder，写时把 encoder 接到压缩流。其 public
原始回调继续使用 Okio，跨到格式层使用既有官方 stream adapters。

POI 的定位参数既用于选记录，也在该操作明确构造其 decoder context；采用接收完整 context 的构造函数时使用同一位置值，不再要求第二个
expected 值做比较。已经定位的 raw callback 也允许调用方自行构造并调用朴素 decoder。

保留 readChunkInfo、raw compressed payload、record、NBT document 和文件访问；它们不要求 semantic codec。typed write 使用域值的
chunkPosition 选择 slot，不另收一份写入位置。mutable/live 的返回值、metadata 访问与 overload 对称性在迁移清单中逐项确定，不能让便利
read 静默丢掉调用方需要的 DataVersion/LastUpdate。

### 5.4 Chunk 与 packet 的转换

```kotlin
data class ChunkPacketEncoderContext(
    val chunkLayout: ChunkLayout,
    val hasSkyLight: Boolean,
    val defaultBlockState: BlockState,
    val defaultBiome: BiomeId,
    val packetCodecContext: PacketCodecContext,
    val chunkPacketWriteMappings: ChunkPacketWriteMappings,
    val chunkPacketRequiredDataProvider: ChunkPacketRequiredDataProvider,
)

data class ChunkPacketDecoderContext(
    val chunkContext: ChunkContext,
    val packetCodecContext: PacketCodecContext,
    val chunkPacketReadMappings: ChunkPacketReadMappings,
    val chunkPacketMissingDataProvider: ChunkPacketMissingDataProvider,
)

class ChunkPacketEncoder(val chunkPacketEncoderContext: ChunkPacketEncoderContext) {
    fun encode(chunk: Chunk): ClientboundLevelChunkWithLightPacket
}

class ChunkPacketDecoder(val chunkPacketDecoderContext: ChunkPacketDecoderContext) {
    fun decode(clientboundLevelChunkWithLightPacket: ClientboundLevelChunkWithLightPacket): Chunk
}
```

- PacketCodecContext 只拥有连接 epoch 的 registry/raw-ID 映射，不含 Chunk layout 或 Section count。block、biome、BlockEntity
  等使用同一映射来源。
- 编码只需要 ChunkLayout、天空光开关和缺失地形的方块/生物群系默认值，不接收完整 ChunkContext。解码接收完整 ChunkContext，
  因为它也是返回的内存 Chunk 所需的附属数据。
- ChunkPacketReadMappings / WriteMappings 负责已审计字段、组件、heightmap/light 和 BlockEntity update tag 的语义投影，不复制
  registry 映射。BlockEntity 完整存档本体与 update tag 分别映射。
- ChunkPacketMissingDataProvider 按从 packet 解出的权威 chunkPosition 提供第 4.4 节的遗漏字段。每个完整 packet
  调用一次；必需字段全部显式给出，可空/缺项字段的选择也写入方向字段表。endpoint 和 vanilla Configuration 默认不能暗中代选这些值。
- 返回的可变集合和 properties 遵守普通引用规则，不自动深复制，也不禁止调用方有意共享。需要独立结果时由提供方创建独立数据；库自带便利构造不得意外复用一份可变默认集合。
- ChunkPacketRequiredDataProvider 只提供目标 packet
  必需而当前域值尚未给出的值，例如缺少的计数或光照输入。模型已有值从模型读取；编码器不暗中重算、失效或覆盖它们。调用方可以在这个显式提供方内执行自己的算法，结果不回写输入
  Chunk。
- provider 的输入、返回字段和调用条件在阶段 A 按匹配 packet 字段表定稿。它们是转换的显式输入机制，不是 Chunk 中的运行服务或持久化分类。

编码不能从接收值的 ChunkContext 选择配置，不能调用隐式 vanilla registry。物理格式约束由下层处理；游戏光照是否合理、统计是否最新和多对象是否一致属于用户计算。

### 5.5 Entity pairing 与其他 world 投影

EntityPacketEncoder / EntityPacketDecoder 及各自完整 context 归 protocol-world。稳定的 registry 与属性/组件映射在 context
中；连接级数值 Entity ID、已解析的关系 ID 和本次 pairing 所需的网络事实在单次 EntityPairingData 中。

Entity 的 UUID、类型、位置、速度、旋转和语义 properties 从当前 Entity 读取。headYaw、属性、装备等已经存于 properties 的语义状态不能再在
EntityPairingData 中保留另一份可修改副本；其 packet 投影由映射产生。EntityPairingData 不复制另一份域实体，也不重新引入
subtype 泛型。乘客对象关系来自域值；映射成连接 ID、反查载具、解决 leash 引用及跨实体发送顺序由 endpoint/调用方负责。

ClientboundAddEntityPacket.data 的含义随实体类型变化，可能包含连接中的 raw ID。Entity 不提供通用 SpawnData 属性；
EntityPacketReadMappings / WriteMappings 的 spawnData 回调显式完成语义转换，不能把收到的原始整数自动塞进内存 properties。
缺失 headYaw 和乘客列表仍由 required-data provider 提供；生成网络 data 是每次执行的映射，不是缺失内存状态的默认值。

迁移现有 spawn、metadata、attributes、equipment、passengers、vehicle relation、leash 与 bundle 能力。codec 产生单个实体的有限
packet 序列；endpoint 负责选择实体、分配 ID、注册查找、跨对象顺序和原子 enqueue。不能把删除模型事务的决定误用为删除网络传输所需的
bundle 边界。

反向转换创建普通 Entity 与 properties。已收到的实体字段写入对应语义位置，缺失事实由 decoder context
提供或按明确的未知语义表达；未解析的网络引用保留在调用方协议状态中。旧 createData<E>/用户子类工厂和在 decoder
中登记世界对象的机制迁出，不能静默忽略原 API 能处理的 pairing 尾随消息。

EntityChunk 没有对应完整 packet，PoiChunk 也没有普通完整同步，因此不创建整体 packet codec。其他 world value 以第 7.3
节的投影清单决定是否提供有限方向转换。

### 5.6 三种公开入口

| 入口                      | 契约                                                                                                            |
|---------------------------|-----------------------------------------------------------------------------------------------------------------|
| 朴素定向 codec            | 构造时传入所需配置，再调用 encode/decode；没有配置则不要求 context                                              |
| Fluent extension / facade | 接受预构造 codec、完整 context，或权威结果加上其中缺失的事实；构造完整 context 并委托相邻操作，可组合端到端流程 |
| 显式低层与检查入口        | raw NBT、compressed record、palette 容器、Region diagnostics 等供调用方直接使用                                 |

fluent 不实现第二次转换，不补隐藏输入，不吞错误或改变资源所有权。除定义它的 adapter 外，库内 production 组件使用朴素
codec/底层格式，不反向调用便利层。普通模型字段和 properties 是主要数据 API，不再被降格为只能检查的“逃生支线”。

客户端 `MinecraftClientNegotiationResult.chunkPacketDecoder(...)` 和服务端
`MinecraftServerNegotiationResult.chunkPacketEncoder(...)` 使用该结果保留的初始维度与 registry epoch，只接收方块/生物群系默认值、
对应方向的 mappings 和缺失数据 provider。它们不接收 connection，也不访问网络；返回原有朴素 codec。连接关闭或切换 epoch 不改变
旧结果的含义。子模块 README 并列展示完整构造与快捷调用，根 README 使用快捷链路。

不创建万能泛型 Encoder<I, O, C> 或公共 base context。命名保持 `[Domain][Representation]Encoder`，需要专用配置类型时命名为
EncoderContext；decoder 使用对应命名，并按自己的配置需要选择 context。物理 Format 使用自身 Configuration。context
属性按可见类型命名，不把三个不同
domain context 混称为一个只读 value.context。

## 6. Packet model 与物理编码边界

ClientboundLevelChunkWithLightPacket 只含官方网络字段。其 ClientboundLevelChunkPacketData 用一个不可变 ByteString 保存连续
Section payload，不包括外层 VarInt 长度；packet 字段不保存 typed world Sections，也不要求维度布局才能完成物理 packet 解码。

| 所有者                               | 负责内容                                                                           |
|--------------------------------------|------------------------------------------------------------------------------------|
| protocol-model                       | packet、packet-owned values、wire annotations；显式低层 packet Section value       |
| MinecraftPacketPayloadFormat         | packet model 与 payload bytes；Section 字段的 VarInt byte length 和原始 ByteString |
| MinecraftChunkSectionPayloadFormat   | packet-layer Section/palette value 与内部 payload bytes；不处理外层 byte length    |
| protocol-world 的 Chunk packet codec | 域值与 packet 值之间的语义映射，组合 Section 低层 format                           |

MinecraftChunkSectionPayloadFormat 位于 protocol-serialization，其 Configuration 显式含 PacketCodecContext 与 Section
count，不依赖 world-format。protocol-world 从自身构造 context 的布局取得 count，构造并复用该 format。跨模块低层 Section
value 留在 protocol-model，名称依据官方对应物记录必要例外，不能重新变成完整 Chunk packet 的字段。

MinecraftPacketPayloadFormat、MinecraftPacketPayloadFormatConfiguration 和可选 ConfiguredMinecraftPacketPayloadFormat
保持网络表示内部的双向格式职责。framing、压缩 envelope、加密与 socket 仍属于 transport。

## 7. 模块与生成数据归属

### 7.1 目标模块

不新增 world-model。world-format 同时拥有纯域数据、世界文件 schema 和文件系统无关格式；这是围绕世界数据能力的模块边界，不限制
Chunk 只能作为短期存档 DTO。

| 模块                                    | 目标职责                                                                                                             |
|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| world-format                            | 第 2—4 节的世界域类型、基础 property 与语义内容、NBT 定向转换、坐标、standalone schemas、DataPack、Anvil/compression |
| world-io                                | Okio、目录、lease、Region store、live reads、替换恢复与类型化 I/O 组合                                               |
| protocol-model / protocol-serialization | 第 6 节的网络值与物理格式                                                                                            |
| protocol-world（新增）                  | 无 I/O、无 endpoint 生命周期的 Play world-value 与 packet 转换                                                       |
| protocol-configuration（迁移）          | DataPack 到 Configuration 投影、capture 到 registry/layout lookup                                                    |
| datapack-vanilla（拆出）                | 官方 DataPack archive/parsed payload、内建包集合和 vanilla stack completion                                          |
| protocol-configuration-vanilla（拆出）  | 官方静态 registry、block-state protocol 数据、Configuration defaults/capture/projector                               |
| protocol-client / protocol-server       | 协商、连接 epoch/维度切换、调用方转换策略接线、initial view 和网络发送政策                                           |

world-format 的语义 ItemStack、组件、文本等不能依赖 protocol-model 中的 wire values；必要的边界转换在 protocol-world
中明确实现。同名官方概念在不同表示阶段的差异要记录，避免机械复制或为了复用一个值反向依赖整个模块。

### 7.2 依赖与配置来源

```text
world-format -> nbt, nbt-serialization
world-io -> world-format

protocol-serialization -> protocol-model, nbt-serialization
protocol-world -> world-format, protocol-model, protocol-serialization
protocol-configuration -> world-format, protocol-model

datapack-vanilla -> world-format
protocol-configuration-vanilla -> protocol-configuration, world-format, protocol-serialization

protocol-client/server -> protocol-world, protocol-configuration
vanilla endpoint factories -> protocol-configuration-vanilla
```

world-format/world-io 不依赖 protocol；protocol-world 不依赖 world-io、Ktor、auth、session、endpoint 或 vanilla
singleton。protocol-configuration 不保留普通 Play codec，也不因重命名引入 protocol-serialization。

两个 vanilla provider 不互相依赖或共享生成输出。DataPackArchive、DataPack、DataPackStack、ResolvedDataPackStack、WorldDataPackLoadResult
保持原阶段和归属。读取世界选择、补齐 vanilla stack、投影 Configuration 是可独立组合的步骤。

WorldChunkContexts 是维度到共享 ChunkContext 的数据映射。持久化事实的 resolver 属于 world-format，Configuration evidence
adapter 属于 protocol-configuration。EntityChunkContext、PoiChunkContext 按实际域事实独立构造，不强迫借用一个带全部网络配置的总
context。原 ResolvedMinecraftWorld / MinecraftChunkContext 聚合拆成域 context、Configuration 结果和各方向
codec，不仅换一个总聚合名称。

Identifier 保持 protocol-model 的官方共享协议值。world-format 的 BlockId、BiomeId、ComponentId、DimensionId
等按其领域职责表达，边界显式转换；不为一个 ID 建立宽泛 core 模块。

公开签名出现的依赖类型使用 api；内部物理格式组合使用 implementation。例如 world-format 的 NBT、Source/Sink 契约和 world-io
的 world-format/Okio 契约需要对消费者可见；protocol-world 的内部 Section format 若不出现在公开签名中，protocol-serialization
使用 implementation。不增加仅用于隐藏合法下层依赖的 wrapper。

### 7.3 网络投影清单

| world 内容                                                     | 迁移结果                                                              |
|----------------------------------------------------------------|-----------------------------------------------------------------------|
| Chunk                                                          | 第 5.4 节两个方向；明确网络缺失与有损性                               |
| Entity                                                         | 第 5.5 节单个实体与有限 pairing 序列                                  |
| EntityChunk                                                    | 没有整体网络表示                                                      |
| PoiChunk                                                       | 没有普通整体同步；如确有现存 debug projector，保留明确的单向有限视图  |
| DataPack、registry、tags、feature flags                        | protocol-configuration                                                |
| LevelDat、world generation settings                            | 提供配置/bootstrap 事实，不建立整体 packet codec                      |
| PlayerData                                                     | 逐功能检查有限网络视图，不以完整文件代表连接状态                      |
| MapData、ScoreboardData、advancements、statistics              | 审计现存 snapshot/delta/ordered projection；纯转换与订阅/顺序分别归属 |
| raids、tickets、random sequences、Anvil、compression、metadata | 没有通用客户端等价转换                                                |

阶段 A 对现有 API 逐项标为 paired-directional-codecs、one-way-projector、endpoint-orchestration 或
no-network-representation。两个方向存在不表示无损往返。本次迁移现有能力，不因清单列出某个 world schema 就顺带实现尚不存在的功能。

### 7.4 生成数据与通用映射支持

root official producers 独占匹配 server JAR 分析；生成器只消费声明的 artifacts。拆分时移动 owning module、注册任务、generated
package、source-set、publication 和 source JAR wiring，不复制生成代码或让两个模块占有同一输出。

DataPack payload 生成进入 datapack-vanilla；Configuration packet payload 和协议 registry 数据进入
protocol-configuration-vanilla。世界类型如需新的确定性原版定义，先记录事实来源与唯一生成所有者，不因方便让 world-format 依赖
protocol provider，也不手抄完整状态表。

通用值 reader/writer 属于拥有数据表示的模块，不内置具体方块/实体的路径绑定。具体游戏内容的 property
键、包装、映射及算法全部在用户代码；本次测试中的箱子、漏斗、熔炉、村民和商人代码不发布。Fabric
数据仅作为开放路径的验证案例，不生成或内置 mod 专用模型。

## 8. 命名迁移

### 8.1 规则

Protocol 用于真实网络协议、协议侧边界、版本或正式上游名称。Packet 用于网络 packet model，并保留 Packet 后缀。对
module、package、文件、声明、参数、测试、Gradle wiring、生成路径、diagnostics 和可选 skills 做大小写不敏感清单；按所有者判断，不做无差别文本替换。

所有 @PacketInfo 声明、packet-owned 嵌套值、字段名称、顺序和类型以匹配官方 Java class/member 为默认。完整保留
Clientbound/Serverbound、Level 和状态限定词；Kotlin 表示限制、真实共享逻辑值或运行容器耦合等偏离逐项记录原因。

packets.json 的资源 identity 不足以推导 Java class 和字段。扩展官方 class/member evidence，并逐 packet 对照
producer、consumer 和 codec。KSP 负责 source coverage、identity、名称/例外和 dispatch 检查；不能靠生成测试宣称已经证明全部字段与条件分支正确。

### 8.2 迁移表

| 当前                                                                                               | 目标                                                                                                              |
|----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Chunk<B, M> / ChunkSection<B, M>                                                                   | 非泛型 Chunk / ChunkSection 与 BlockState、BiomeId                                                                |
| Entity<E> / EntityChunk<E>、通用 subtype data                                                      | 非泛型 Entity / EntityChunk 与同源 properties                                                                     |
| ChunkMetadata / ChunkStorageMetadata                                                               | 域字段按第 3 节归位，表示 metadata 按第 4.2 节分离                                                                |
| ChunkNbtCodec / EntityChunkNbtCodec / PoiChunkNbtCodec                                             | 各自的 Encoder、Decoder、完整方向 context 与 decode result                                                        |
| MinecraftChunkSnapshot                                                                             | Chunk + ChunkPacketEncoder，删除快照包装                                                                          |
| MinecraftEntitySnapshot / MinecraftEntityPassengersSnapshot                                        | Entity + EntityPairingData + EntityPacketEncoder，网络关系由调用方解析                                            |
| MinecraftEntityPacketAdapter<E> / MinecraftEntityPacketDecoder                                     | 非泛型实体映射与 EntityPacketDecoder；世界登记和消息应用编排归 endpoint/调用方                                    |
| MinecraftChunkContext / ResolvedMinecraftWorld                                                     | 域 context、WorldChunkContexts、Configuration 结果与方向 codec，按第 7.2 节拆分                                   |
| protocol-datapack                                                                                  | protocol-configuration                                                                                            |
| com.hiczp.minecraft.protocol.datapack                                                              | com.hiczp.minecraft.protocol.configuration                                                                        |
| protocol-datapack-vanilla                                                                          | datapack-vanilla + protocol-configuration-vanilla                                                                 |
| com.hiczp.minecraft.protocol.datapack.vanilla                                                      | com.hiczp.minecraft.world.format.datapack.vanilla + com.hiczp.minecraft.protocol.configuration.vanilla            |
| MinecraftProtocolFormat / MinecraftProtocolFormatConfiguration / ConfiguredMinecraftProtocolFormat | MinecraftPacketPayloadFormat / MinecraftPacketPayloadFormatConfiguration / ConfiguredMinecraftPacketPayloadFormat |
| ChunkDataAndUpdateLightPacket / WorldEventPacket                                                   | ClientboundLevelChunkWithLightPacket / ClientboundLevelEventPacket                                                |
| ProtocolRegistryContext / installProtocolRegistryContext / resolveProtocolRegistryContext          | PacketCodecContext / installPacketCodecContext / resolvePacketCodecContext                                        |
| ProtocolRegistry / ProtocolRegistryEntry / ProtocolBlockState                                      | RegistryIdMap / RegistryIdMapping / BlockStateIdMapping，仅留在协议边界                                           |
| ProtocolData / ResolvedProtocolData / VanillaProtocolData                                          | ConfigurationData / ResolvedConfigurationData / VanillaConfigurationData                                          |
| toProtocolData / toVanillaProtocolData                                                             | toConfigurationData / toVanillaConfigurationData                                                                  |
| DataPackProtocolProjector / vanillaDataPackProtocolProjector                                       | DataPackConfigurationProjector / vanillaDataPackConfigurationProjector                                            |
| ProtocolSurfaceChunkProjector                                                                      | WorldSurfaceChunkProjector，具体组合按第 9 节简化                                                                 |
| MinecraftClientProtocol / MinecraftServerProtocol                                                  | MinecraftClientNegotiation / MinecraftServerNegotiation                                                           |
| ProtocolSampleProfile / MinimalProtocolValueDecoder / protocolValue                                | PacketSampleProfile / MinimalPacketValueDecoder / packetSampleValue                                               |
| MinecraftProtocolTarget / minecraftProtocolTarget / readMinecraftProtocolTarget                    | OfficialMinecraftTarget / officialMinecraftTarget / readOfficialMinecraftTarget                                   |
| MinecraftProtocolToolSupport.kt / MinecraftProtocolToolSupportTest                                 | MinecraftToolSupport.kt / MinecraftToolSupportTest                                                                |
| protocolJson / ProtocolHttp                                                                        | buildLogicJson / DownloadHttp                                                                                     |
| protocolRef / protocol-reference                                                                   | minecraftArtifactsRoot / minecraft-artifacts                                                                      |
| .agents/skills/minecraft-protocol-vanilla-data                                                     | .agents/skills/minecraft-vanilla-data，随拆分校正内容与引用                                                       |

表中的构建分析目标不是手动 release 选择器；MinecraftTarget 与 BuildVersions 的职责和唯一选择入口保持不变。

保留有真实协议职责或上游依据的名称，包括生成的 MinecraftProtocol /
GenerateMinecraftProtocolSourceTask、ProtocolModelProcessor、ProtocolModelContractTest、FabricProtocol/ForgeProtocol/NeoForgeProtocol
及其网络类型、WebMapProtocolTest、官方 ProtocolInfo 等。Identifier 不改成旧称 ResourceLocation。

生成类型由 owning generator 修改。实施阶段重新核对当前源码与生成声明清单，不能沿用旧稿“已扫描且没有遗漏”的完成声明代替本次审计。

## 9. demo 与现有调用方迁移

web-map 是本次数据读写和外部计算的实际验收用例。保留以下数据流：

1. 从世界数据包与 world generation settings 得到维度事实，显式组合 vanilla stack completion 和所需 registry/定义；应用可以依赖多个
   provider，不为消除 demo 的 protocol 依赖倒置库所有权。
2. 以完整 NBT decoder context 读取地形 Chunk。地图不使用 scheduled tick，但完整解码仍需时间基准；demo
   明确选择其投影批次使用的基准，不由库猜测。若以后作为运行世界使用，应提供该运行世界的时钟事实。
3. 读取 Chunk.status / isFullyGenerated 后自行决定投影。若成功解码之前已发生格式错误，仍按应用失败路径处理；不要求模型为所有未完成记录提供兼容结果。
4. 用 dimensionTypeLayout.logicalBlockYRange、当前 Section/palette 和默认方块完成下界逻辑高度、空气、透明层和回退扫描。投影算法继续在
   demo。
5. 从 BlockState 的 ID 和全量规范属性构造 SurfaceBlockState，不再经 ProtocolBlockState raw ID 才能反查状态。
6. readChunkInfo 的 timestampEpochSeconds 继续作为缓存版本；维度/Region 分组、Chunk 锁、失败重试和取消保留在应用及存储层。
7. 浏览器继续接收 kRPC ChunkSurface 和资源 DTO，不直接序列化整个域 Chunk，不把这条网络路径当作原版 Chunk packet 测试。

迁移生产 projector 及测试中的空 Chunk 构造与 setBlock 调用；模型不为测试构造自动计算统计或光照。demo/launcher
当前不使用这三个模型，只随实际受影响的构建、命名或 provider 依赖迁移。

client/server 和 world-io 的现有便利路径也按第 5 节迁移。Configuration/respawn/reconfiguration 时可以在 endpoint
原子替换后续操作的 codec 组合，已经开始的操作保持其显式输入；这不使域模型获得冻结、epoch 或生命周期字段。

## 10. 实施顺序与契约细化

阶段是依赖顺序，不要求以兼容壳维持旧 API。实施前读取对应最近 AGENTS.md；已与本方案冲突的局部规则随拥有阶段一并修改，README
只在实现后描述已提供的公共契约。通用完整文件 schema 的未知字段政策与三种 Chunk 的动态保留契约分别说明，不能把前者机械套到
properties。可选 skills 随所有权更新，不作为构建或运行门槛。

### A. 固定映射清单和接口细节

- 以当前 source/build/test 建立模块依赖、公开转换、命名、@PacketInfo 和 world projection 清单，记录每项所有者与迁移目标。
- 对第 3 节每层建立第 4.1 节的字段表，包括持久化、网络和未持久化的运行状态。具体未知 nullability 按仓库规则标注，不能由便利构造默认填掉。
- 固定 property 映射声明 API、packet missing/required provider 的完整字段与调用条件、NBT decode-result 的 I/O 返回形式及
  overload 矩阵。
- 完成下表有限事项；退出条件是这些契约可供直接实现与测试，不是要求先实现所有原版玩法。

| 契约细化事项                                   | 交付物                                                                                                         |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| StateProperties 的类型访问、规范字符串与相等性 | 同一逻辑值的读写/枚举规则和有代表性的原版/未知属性案例                                                         |
| EntityAttributes 缺项与外部默认供应            | 区分未提供集合、未物化实例和定义不存在的访问规则                                                               |
| 具体 Entity/BlockEntity 的运行状态             | 测试侧定义代表性内容的键、值类型、缺失语义和方向映射；验证用户可通过公开能力表达和修改它们，不发布具体游戏内容 |
| BlendingData 等采样结构                        | 精确索引、边界、可空语义和官方证据                                                                             |
| tick 时间和保存顺序                            | 官方 unpack/pack、负 delay、窄化、subTickOrder 与批次时间输入规则                                              |
| Entity pairing 与 packet 字段                  | 每个现有分支的权威输入、方向映射、未解析关系归属和官方名称例外                                                 |

### B. 实现域数据与基础属性

在 world-format 落实第 2—3 节及确定的域内容类型，移除根内容泛型和表示 metadata，保持 raw 格式及 standalone schema
的职责。构造、引用修改、缺失语义、palette/组件/乘客/POI 形状先形成可验证契约。

### C. 实现 NBT 和存储组合

落实第 4 节映射、第 5.2—5.3 节定向 codec、metadata、时间输入、POI 位置和 raw 支线；迁移 mutable/live world-io API。保留 Region
timestamp/sidecar、资源所有权、错误类别和恢复行为。动态字段在各嵌套层次从当前可达值编码。

### D. 迁移网络模型与 protocol-world

按第 6、8 节完成 packet 名称、ByteString Section payload、低层格式、PacketCodecContext 和新增 protocol-world；实现 Chunk
两个方向及现有 Entity pairing 能力。用显式映射处理缺失值，移除旧 snapshot/subtype factory。

### E. 拆分 Configuration 与 vanilla providers

按第 7 节迁移 module/package、唯一生成输出与 resolver。保留 Known Packs 两分支、registry 顺序、inline/referenced dimension
的各自 resolver 契约和默认 provider 能力；不让 Configuration 工厂选择域 decoder 缺失字段。

### F. 迁移 endpoint、demo 和便利 API

endpoint 只保留编排并复用朴素 codec；按第 9 节迁移 web-map。朴素路径通过验证后实现接收 codec/context 的便利入口，删除旧实现与无用导出。保持
API 自然的数据引用语义，不加入迁移时的所有权补丁。

### G. 完成命名、文档与跨平台收尾

更新 settings、publication、source JAR、生成目录、README、AGENTS 和可选 skills；核对第 8
节的完整清单与例外。逐模块完成验证后执行受影响的跨平台、配置缓存和官方互操作检查。

## 11. 验证与完成标准

### 11.1 行为验证

| 范围                             | 必须证明的结果                                                                                                                                       |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| 普通引用与编码                   | 构造保留引用；动态和强类型访问同源；删除或替换后编码只反映当前根；旧引用不复活条目；浅复制不宣称独立快照                                             |
| Chunk/Section                    | 稀疏默认、逻辑高度、局部索引、光层边界、未知统计；公开修改不触发跨对象更新；状态属性值相等且可全量描述                                               |
| 组件与嵌套内容                   | patch 三态、已知空与缺失、深层属性和未知 NBT 类型保留；用户包装无需注册或继承                                                                        |
| EntityChunk                      | 根/乘客递归、null 与空关系、跨区块乘客、移动后无自动迁移、循环编码错误、属性/effect/Brain/交易代表性状态                                             |
| PoiChunk                         | 缺失/无效/有效空 Section 区别，票数与外部类型定义分开，裸修改不更新有效性，三层动态字段保留                                                          |
| NBT                              | 完成态 schema、status 原值、metadata 外置、时间基准和顺序、结构冲突、流/document 同源、没有 ProtoChunk 专门支持                                      |
| Fabric 形状案例                  | Chunk/BlockEntity/Entity 原位嵌套 attachments，未知 type 保留，自定义空气状态不被错误折叠；不要求安装 mod runtime                                    |
| world-io                         | 完整 context 在正确范围绑定、POI 所需位置、逐次写入时间、metadata 可取、readChunkInfo 缓存路径、live/mutable API 对称、stream 与 sidecar/恢复边界    |
| protocol-model/KSP/serialization | 官方 packet identity/name/例外、字段审计；Section raw payload 与外层长度分工；exact-byte、分支与官方 codec oracle                                    |
| protocol-world                   | 缺失值显式输入、已有域值不被重算覆盖、BE update tag 与存档分离、有限 Entity pairing、packet→Chunk→NBT 当前数据保存                                   |
| Configuration/vanilla            | 生成输出唯一归属、Known Packs 两分支、registry 顺序、默认 stack/projector、无反向依赖或隐藏域默认                                                    |
| endpoint/demo/fluent             | 连接/维度转换配置切换、官方 initial world；web-map 六类表面扫描和缓存语义；便利/朴素路径结果与错误等价                                               |
| 用户计算与内容边界               | 测试侧定义箱子、漏斗、熔炉、村民/POI 和商人内容；通过公开能力完成计算、实际 MCA 读写、packet bytes 和客户端内存/菜单更新，生产代码不持有这些游戏定义 |

测试以这些可观察语义和真实表示边界为准，不为每个普通字段 setter 编写镜像测试，不以 JVM 反射代替 KMP 调用矩阵。官方 oracle
或代表性样本只证明其覆盖范围；字段清单、未覆盖分支和有损边界要保持可审查。

### 11.2 执行顺序

Windows 使用 `gradlew.bat`，其他平台使用其原生 wrapper；所有 Gradle
invocation 顺序执行。

先运行已完成阶段对应的最窄 JVM 任务。新增/重命名模块就绪后才使用其新 task path：

```powershell
.\gradlew.bat :world-format:jvmTest
.\gradlew.bat :world-io:jvmTest
.\gradlew.bat :protocol-model:jvmTest
.\gradlew.bat :protocol-serialization:jvmTest
.\gradlew.bat :protocol-world:jvmTest
.\gradlew.bat :protocol-configuration:jvmTest
.\gradlew.bat :datapack-vanilla:jvmTest
.\gradlew.bat :protocol-configuration-vanilla:jvmTest
.\gradlew.bat :protocol-client:jvmTest
.\gradlew.bat :protocol-server:jvmTest
.\gradlew.bat :demo:web-map:jvmTest
.\gradlew.bat :demo:web-map:jsNodeTest
```

构建/生成/KSP 变更执行其 owning layer 测试；buildSrc 使用 `.\gradlew.bat -p buildSrc test`。按受影响模块的实际 targets 继续
Node、Wasm、Native 检查；browser packaging 改动执行 web-map 的 jsBrowserDistribution 和受影响 server 编译。不能从其他模块推断不存在的
target。

最终集成使用 `.\gradlew.bat :minecraft-test-fixture-host:test jvmTest`，且必须执行并通过 `.\gradlew.bat allTests`。官方世界
generate/rewrite/reload 与官方 client/server 互操作走既有 Fixture Host 场景。module/source-set/task wiring 改动还要验证
configuration-cache store/reuse，保持 build cache 开启，不增加生成输出复制或重复检查流程。

夹具超时先定位阶段和负载，区分官方进程启动耗时与代码竞态；必要时降低 Gradle worker
数量。测试执行顺序通过协程作用域、显式信号和已接收消息建立，发送大量世界数据时同时消费入站消息，不能依靠睡眠、扩大缓冲或概率重试绕过背压。

### 11.3 完成条件

- 第 2—3 节的通用数据契约进入公开 API，具体游戏内容示例留在测试侧；强类型/动态访问同源，三个根类型没有内容泛型、所有权追踪或自动计算机制。
- 第 4 节的字段表覆盖实际支持内容；完成态范围、未知信息、metadata、时间和磁盘/网络差异有明确实现与测试，不能仅因有 properties
  就宣称任意内容天然可序列化。
- 所有定向转换使用自身完整 context 和相邻层输入；不从值的 domain context 取编码配置，不隐藏 POI 位置或时间基准，不靠便利层实现第二次转换。
- 模块依赖、生成输出和命名符合第 7—8 节；现有 endpoint、world-io 与 demo 能力完成迁移，无兼容壳或遗留转换实现。
- 相应 JVM、KMP、官方互操作和配置缓存检查通过；README/AGENTS/skills 与最终源码及实际验证结果一致。
- 复核各类型的包和模块归属、公开依赖及唯一生成所有者；清理本次重构造成的空目录和仅剩忽略文件的废弃目录，保留仍在使用的构建输出与无关文件。

## 12. 实施结果与验收记录

### 12.1 已完成的实现

- 三种 Chunk 及嵌套数据使用可修改的普通引用；properties 是动态值的唯一存储，构造、替换和删除不引入生命周期追踪或游戏计算。
- NBT、磁盘存储、Play 转换、Configuration、原版数据提供者、client/server 和 demo 均已迁移到明确的表示边界与完整定向 context。
- 世界数据与 NBT 映射归 `world-format`，文件访问归 `world-io`，Play 转换归 `protocol-world`，Configuration 归
  `protocol-configuration`；`datapack-vanilla` 与 `protocol-configuration-vanilla` 分别拥有各自生成数据。公开依赖、包路径与生成输出归属已复核。
- packet 使用官方名称，并将其专属 record/enum 放回所属 packet；有实际共享逻辑或 Kotlin
  表示原因的差异记录在 [packet 形状说明](../../protocol-model/PACKET-MODELS.md)。KSP 按声明名称跨轮解析，避免延迟解析时丢失先前有效的组件声明。
- 生产库提供通用 `NbtPropertyReaders`、`NbtPropertyWriters` 和嵌套映射能力；箱子、漏斗、熔炉、脑状态和交易等具体内容的类型、键及规则均在测试中定义。初始世界只接收已准备的
  Chunk、位置和能力数据；平坦地形生成及按游戏模式选择能力也仅存在于测试侧。
- 官方客户端夹具在初始世界、respawn 和重新配置阶段通过协程同时发送世界数据并接收确认；KeepAlive
  观察期间持续消费入站消息，修复有限通道背压引起的执行停滞。没有扩大超时或加入概率重试。

用户视角的主要验收入口：

| 测试                                                                                                                          | 验证内容                                                                                                    |
|-------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| [UserWorldSimulationTest](../../world-io/src/commonTest/kotlin/com/hiczp/minecraft/world/io/UserWorldSimulationTest.kt)       | 实际 MCA 文件读写；箱子存取、漏斗转移；packet bytes 到客户端 Chunk 和菜单；属性替换与删除后旧引用不复活数据 |
| [ServerComputationTest](../../world-format/src/commonTest/kotlin/com/hiczp/minecraft/world/format/ServerComputationTest.kt)   | 用户定义的熔炉状态推进、村民记忆与 POI 票数、实体跨 Chunk 移动及保存重读                                    |
| [UserGamePropertiesTest](../../world-format/src/commonTest/kotlin/com/hiczp/minecraft/world/format/UserGamePropertiesTest.kt) | 自定义语义类型与 NBT 映射、交易及嵌套状态、共享引用和循环边界                                               |

磁盘与网络各自只承载其表示中的字段。测试明确验证普通 Chunk packet 不携带箱子私有库存，菜单通过独立的物品 packet
转换更新；收到的客户端 Chunk 可按调用方提供的保存上下文写盘，但不会重建服务端未发送的信息。

再次逐项复核计划与实现后，已修正以下遗漏和重复：

- 组件 NBT 读写集中在 `world-format` 的 `DataComponentNbt.kt`，BlockEntity packet update tag 复用通用组件和属性读取。映射查找统一使用规范化的组件
  ID，拒绝两个拼写指向同一组件时的覆盖；回归测试覆盖组件 map、patch 和 packet 路径。
- Configuration 的服务端值、客户端视图和原版 provider 共用 `PacketCodecContext.withSynchronizedRegistries`，统一按 packet
  entry 顺序生成 raw ID。无变化时由 `PacketCodecContext.withRegistries` 直接复用实例，同时仍校验重复输入。
- 删除生产 `FlatChunk`、初始世界的地形工厂和游戏模式能力预设；测试应用自行构造内容，生产 bootstrap 显式接收
  `PlayerAbilities`。README 示例同步改为接收用户准备的数据，并在发送期间持续消费入站消息。
- 复核包、模块、公开依赖和生成源码归属，并校正可选技能中旧的数据包归属及 KSP 验证范围说明。保留有实际流式读写、类型和绑定范围职责的
  I/O 接口，不引入统一基类或可空 codec 来合并它们。

### 12.2 实际验证

所有 Gradle invocation 顺序执行，保留 build cache：

| 检查                                                  | 结果与证据                                                                                                                                                                                                                        |
|-------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| buildSrc 测试                                         | 通过；[日志](../../temp/chunk-domain-study/build-logic-tests.log)                                                                                                                                                                 |
| 完整 JVM：`:minecraft-test-fixture-host:test jvmTest` | 通过；[完整日志](../../temp/chunk-domain-study/review-jvm-and-sources.log)；最终共用 registry 转换后的 Configuration、原版 provider、client/server [补充检查](../../temp/chunk-domain-study/review-final-context-tests.log)也通过 |
| `allTests --max-workers=1 --continue --console=plain` | `BUILD SUCCESSFUL in 19m 40s`，1046 个任务中 359 个执行、687 个无需重跑；[日志](../../temp/chunk-domain-study/review-all-tests.log)                                                                                               |
| 配置缓存                                              | JVM 检查成功存储配置缓存；本轮完整 `allTests` 成功复用既有配置缓存，日志记录 `Configuration cache entry reused`                                                                                                                   |
| 六个本轮受影响库的 source JAR                         | 重新生成并[检查内容](../../temp/chunk-domain-study/review-source-jars.json)；无测试游戏内容或旧包路径，共用 registry 转换存在于最终 Configuration 源码包                                                                          |
| 包路径、模块依赖与文档链接                            | [检查结果](../../temp/chunk-domain-study/review-structure.json)：652 个源文件无包路径不匹配，无运行时项目依赖环、缺失模块文档或失效本地链接；公开依赖和生成所有者也已复核                                                         |

平台结果以 Windows 主机上实际执行的任务为准；Gradle 标记为 `SKIPPED` 的其他主机目标不计作已运行测试。官方客户端/服务端及世界生成、改写、重载场景通过现有
Fixture Host 执行。

### 12.3 清理

验收通过后已删除仅剩忽略文件的 `protocol-datapack/`、`protocol-datapack-vanilla/` 和 `build/protocol-reference/`
。删除前再次核实了绝对路径、工作区范围、非忽略文件和目录链接；当前模块的构建输出、测试报告与本计划引用的调研材料保留。

本轮复核未留下新的空源目录或废弃目录；上述旧目录和旧技能目录均未重新出现。
