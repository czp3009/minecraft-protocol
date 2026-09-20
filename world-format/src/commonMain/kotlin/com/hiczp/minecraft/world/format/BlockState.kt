package com.hiczp.minecraft.world.format


/** Canonical names for legal typed values. Values must retain stable equality and hash codes for reverse lookup. */
class StateProperty<T : Any>(val name: String, values: Map<String, T>) {
    private val valuesByName = values.toMap()
    private val namesByValue = values.entries.associate { it.value to it.key }

    init {
        require(name.isNotBlank()) { "A state property name must not be blank" }
        require(valuesByName.isNotEmpty() && valuesByName.keys.none(String::isBlank)) {
            "A state property must have named values"
        }
        require(valuesByName.values.distinct().size == valuesByName.size) {
            "A state property value must have exactly one canonical name"
        }
    }

    fun value(name: String): T =
        valuesByName[name] ?: throw IllegalArgumentException("Unknown value $name for state property ${this.name}")

    fun name(value: T): String = namesByValue[value]
        ?: throw IllegalArgumentException("Value is outside state property $name")

    fun values(): Map<String, T> = valuesByName.toMap()
}

/** Immutable, open logical state properties. Canonical strings are the sole stored representation. */
class StateProperties private constructor(
    private val valuesByName: Map<String, String>,
    @Suppress("UNUSED_PARAMETER") owned: Boolean,
) : Iterable<Pair<String, String>> {
    constructor(values: Map<String, String> = emptyMap()) : this(values.toMap(), true)

    private val contentHash = valuesByName.hashCode()

    init {
        require(valuesByName.all { (name, value) -> name.isNotBlank() && value.isNotBlank() }) {
            "State property names and values must not be blank"
        }
    }

    val size: Int get() = valuesByName.size

    operator fun get(name: String): String? = valuesByName[name]

    operator fun <T : Any> get(stateProperty: StateProperty<T>): T? =
        valuesByName[stateProperty.name]?.let(stateProperty::value)

    fun with(name: String, value: String): StateProperties =
        if (valuesByName[name] == value) this else StateProperties(valuesByName + (name to value), true)

    fun <T : Any> with(stateProperty: StateProperty<T>, value: T): StateProperties =
        with(stateProperty.name, stateProperty.name(value))

    fun without(name: String): StateProperties =
        if (name !in valuesByName) this else StateProperties(valuesByName - name, true)

    /** Returns an independent map, so an unsafe MutableMap cast cannot change a shared BlockState. */
    fun toMap(): Map<String, String> = valuesByName.toMap()

    override fun iterator(): Iterator<Pair<String, String>> =
        valuesByName.asSequence().map { (name, value) -> name to value }.iterator()

    override fun equals(other: Any?): Boolean = other is StateProperties && valuesByName == other.valuesByName

    /** Borrowed immutable view for lookups. Mutating it through an unsafe cast invalidates shared state values. */
    fun asMap(): Map<String, String> = valuesByName

    override fun hashCode(): Int = contentHash

    override fun toString(): String = valuesByName.toString()
}

/**
 * Immutable state value. A definition shares states and caches property transitions using canonical strings.
 * Free-form construction is supported; its transition family is created lazily on first change.
 */
class BlockState(val blockId: BlockId, val properties: StateProperties = StateProperties()) {
    internal var definition: BlockStateDefinition? = null
    private var transitions: MutableMap<String, MutableMap<String, BlockState>>? = null
    private val contentHash = 31 * blockId.hashCode() + properties.hashCode()

    operator fun <T : Any> get(stateProperty: StateProperty<T>): T? = properties[stateProperty]

    fun <T : Any> with(stateProperty: StateProperty<T>, value: T): BlockState =
        with(stateProperty.name, stateProperty.name(value))

    /** Repeated transitions reuse immutable values; unchanged assignments return this instance. */
    fun with(name: String, value: String): BlockState {
        if (properties[name] == value) return this
        val cache = transitions ?: HashMap<String, MutableMap<String, BlockState>>().also { transitions = it }
        return cache.getOrPut(name) { hashMapOf() }.getOrPut(value) {
            val owner = definition ?: BlockStateDefinition(blockId).also { it.register(this) }
            owner.state(properties.with(name, value))
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
            other is BlockState && blockId == other.blockId && properties == other.properties

    override fun hashCode(): Int = contentHash
    override fun toString(): String = "BlockState($blockId, $properties)"
}

/**
 * Caller-scoped family of shared immutable states, analogous to the official StateDefinition.
 * It interns encountered combinations without imposing vanilla property names or legal-value tables.
 * Keep one definition per block at the desired world/batch lifetime. No global pool, raw IDs or synchronization.
 */
class BlockStateDefinition(val blockId: BlockId) {
    private val states = HashMap<StateProperties, BlockState>()

    fun state(properties: StateProperties = StateProperties()): BlockState = states.getOrPut(properties) {
        BlockState(blockId, properties).also { it.definition = this }
    }

    internal fun register(blockState: BlockState) {
        states[blockState.properties] = blockState
        blockState.definition = this
    }
}
