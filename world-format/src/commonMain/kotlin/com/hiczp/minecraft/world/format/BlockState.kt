package com.hiczp.minecraft.world.format


/** An explicit correspondence between the legal typed values and Minecraft's canonical value names. */
class StateProperty<T : Any>(val name: String, values: Map<String, T>) {
    private val valuesByName = values.toMap()

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

    fun name(value: T): String = valuesByName.entries.firstOrNull { it.value == value }?.key
        ?: throw IllegalArgumentException("Value is outside state property $name")

    fun values(): Map<String, T> = valuesByName.toMap()
}

/** Immutable, open logical state properties. Canonical strings are the sole stored representation. */
class StateProperties(values: Map<String, String> = emptyMap()) : Iterable<Pair<String, String>> {
    private val valuesByName = values.toMap()

    init {
        require(valuesByName.all { (name, value) -> name.isNotBlank() && value.isNotBlank() }) {
            "State property names and values must not be blank"
        }
    }

    val size: Int get() = valuesByName.size

    operator fun get(name: String): String? = valuesByName[name]

    operator fun <T : Any> get(stateProperty: StateProperty<T>): T? =
        valuesByName[stateProperty.name]?.let(stateProperty::value)

    fun with(name: String, value: String): StateProperties = StateProperties(valuesByName + (name to value))

    fun <T : Any> with(stateProperty: StateProperty<T>, value: T): StateProperties =
        with(stateProperty.name, stateProperty.name(value))

    fun without(name: String): StateProperties = StateProperties(valuesByName - name)

    /** Returns an independent map, so an unsafe MutableMap cast cannot change a shared BlockState. */
    fun toMap(): Map<String, String> = valuesByName.toMap()

    override fun iterator(): Iterator<Pair<String, String>> =
        valuesByName.asSequence().map { (name, value) -> name to value }.iterator()

    override fun equals(other: Any?): Boolean = other is StateProperties && valuesByName == other.valuesByName

    override fun hashCode(): Int = valuesByName.hashCode()

    override fun toString(): String = valuesByName.toString()
}

data class BlockState(val blockId: BlockId, val properties: StateProperties = StateProperties()) {
    operator fun <T : Any> get(stateProperty: StateProperty<T>): T? = properties[stateProperty]

    fun <T : Any> with(stateProperty: StateProperty<T>, value: T): BlockState =
        copy(properties = properties.with(stateProperty, value))
}
