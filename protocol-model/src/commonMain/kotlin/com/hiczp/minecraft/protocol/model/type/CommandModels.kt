@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.VarIntElements
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * One node in the Brigadier command graph.
 *
 * The sealed variants express all fields selected by the flags byte without
 * putting binary reads or writes in the model.
 */
@Serializable(with = CommandNodeSerializer::class)
sealed interface CommandNode {
    val children: List<Int>
    val redirect: Int?
    val executable: Boolean
    val restricted: Boolean

    @Serializable
    data class Root(
        override val children: List<Int>,
        override val redirect: Int? = null,
        override val executable: Boolean = false,
        override val restricted: Boolean = false,
    ) : CommandNode

    @Serializable
    data class Literal(
        val name: String,
        override val children: List<Int>,
        override val redirect: Int? = null,
        override val executable: Boolean = false,
        override val restricted: Boolean = false,
    ) : CommandNode

    @Serializable
    data class Argument(
        val name: String,
        val parser: CommandParser,
        val suggestionsType: Identifier? = null,
        override val children: List<Int>,
        override val redirect: Int? = null,
        override val executable: Boolean = false,
        override val restricted: Boolean = false,
    ) : CommandNode
}

/** A command argument parser and the exact properties following its numeric ID. */
@Serializable(with = CommandParserSerializer::class)
sealed interface CommandParser {
    @Serializable
    data class Simple(val type: SimpleCommandParser) : CommandParser

    @Serializable
    data class FloatRange(
        val minimum: Float? = null,
        val maximum: Float? = null,
    ) : CommandParser

    @Serializable
    data class DoubleRange(
        val minimum: Double? = null,
        val maximum: Double? = null,
    ) : CommandParser

    @Serializable
    data class IntegerRange(
        val minimum: Int? = null,
        val maximum: Int? = null,
    ) : CommandParser

    @Serializable
    data class LongRange(
        val minimum: Long? = null,
        val maximum: Long? = null,
    ) : CommandParser

    @Serializable
    data class StringValue(
        val behavior: CommandStringBehavior,
    ) : CommandParser

    @Serializable
    data class Entity(
        val single: Boolean,
        val playersOnly: Boolean,
    ) : CommandParser

    @Serializable
    data class ScoreHolder(
        val allowsMultiple: Boolean,
    ) : CommandParser

    @Serializable
    data class Time(
        val minimumTicks: Int,
    ) : CommandParser

    @Serializable
    data class Registry(
        val type: RegistryCommandParser,
        val registry: Identifier,
    ) : CommandParser
}

@Serializable
enum class CommandStringBehavior {
    SINGLE_WORD,
    QUOTABLE_PHRASE,
    GREEDY_PHRASE,
}

/**
 * Parser IDs that have no following properties in the selected protocol.
 *
 * The explicit ID is intentionally not inferred from the enum ordinal because
 * property-bearing parsers occupy gaps in the vanilla registry.
 */
@Serializable
enum class SimpleCommandParser(val protocolId: Int) {
    BOOL(0),
    GAME_PROFILE(7),
    BLOCK_POSITION(8),
    COLUMN_POSITION(9),
    VECTOR_3(10),
    VECTOR_2(11),
    BLOCK_STATE(12),
    BLOCK_PREDICATE(13),
    ITEM_STACK(14),
    ITEM_PREDICATE(15),
    TEAM_COLOR(16),
    HEX_COLOR(17),
    COMPONENT(18),
    STYLE(19),
    MESSAGE(20),
    NBT_COMPOUND_TAG(21),
    NBT_TAG(22),
    NBT_PATH(23),
    OBJECTIVE(24),
    OBJECTIVE_CRITERIA(25),
    OPERATION(26),
    PARTICLE(27),
    ANGLE(28),
    ROTATION(29),
    SCOREBOARD_SLOT(30),
    SWIZZLE(32),
    TEAM(33),
    ITEM_SLOT(34),
    ITEM_SLOTS(35),
    RESOURCE_LOCATION(36),
    FUNCTION(37),
    ENTITY_ANCHOR(38),
    INTEGER_RANGE(39),
    FLOAT_RANGE(40),
    DIMENSION(41),
    GAME_MODE(42),
    TEMPLATE_MIRROR(49),
    TEMPLATE_ROTATION(50),
    HEIGHTMAP(51),
    LOOT_TABLE(52),
    LOOT_PREDICATE(53),
    LOOT_MODIFIER(54),
    DIALOG(55),
    UUID(56),
    ;

    val wireName: String
        get() = when (this) {
            BOOL -> "brigadier:bool"
            BLOCK_POSITION -> "minecraft:block_pos"
            COLUMN_POSITION -> "minecraft:column_pos"
            VECTOR_3 -> "minecraft:vec3"
            VECTOR_2 -> "minecraft:vec2"
            INTEGER_RANGE -> "minecraft:int_range"
            GAME_MODE -> "minecraft:gamemode"
            else -> "minecraft:${name.lowercase()}"
        }
}

@Serializable
enum class RegistryCommandParser(val protocolId: Int) {
    RESOURCE_OR_TAG(44),
    RESOURCE_OR_TAG_KEY(45),
    RESOURCE(46),
    RESOURCE_KEY(47),
    RESOURCE_SELECTOR(48),
    ;

    val wireName: String
        get() = "minecraft:${name.lowercase()}"
}

internal object CommandNodeSerializer : KSerializer<CommandNode> {
    private val childrenSerializer: KSerializer<List<Int>> = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.CommandNode",
    ) {
        element<Byte>("flags")
        element<List<Int>>(
            "children",
            annotations = listOf(VarIntElements()),
        )
        element<Int>(
            "redirect",
            annotations = listOf(VarInt()),
            isOptional = true,
        )
        element<String>(
            "name",
            annotations = listOf(MaxLength(32_767)),
            isOptional = true,
        )
        element<CommandParser>("parser", isOptional = true)
        element<Identifier>("suggestionsType", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: CommandNode) {
        val flags = flags(value)
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, FLAGS, flags.toByte())
        output.encodeSerializableElement(
            descriptor,
            CHILDREN,
            childrenSerializer,
            value.children,
        )
        value.redirect?.let {
            output.encodeIntElement(descriptor, REDIRECT, it)
        }
        when (value) {
            is CommandNode.Root -> Unit
            is CommandNode.Literal -> output.encodeStringElement(
                descriptor,
                NAME,
                value.name,
            )

            is CommandNode.Argument -> {
                output.encodeStringElement(descriptor, NAME, value.name)
                output.encodeSerializableElement(
                    descriptor,
                    PARSER,
                    CommandParserSerializer,
                    value.parser,
                )
                value.suggestionsType?.let {
                    output.encodeSerializableElement(
                        descriptor,
                        SUGGESTIONS_TYPE,
                        Identifier.serializer(),
                        it,
                    )
                }
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): CommandNode {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val flags = input.decodeByteElement(descriptor, FLAGS).toInt()
            val children = input.decodeSerializableElement(
                descriptor,
                CHILDREN,
                childrenSerializer,
            )
            val redirect = if (flags and FLAG_REDIRECT != 0) {
                input.decodeIntElement(descriptor, REDIRECT)
            } else {
                null
            }
            val result = when (flags and TYPE_MASK) {
                TYPE_ROOT -> CommandNode.Root(
                    children,
                    redirect,
                    executable = flags and FLAG_EXECUTABLE != 0,
                    restricted = flags and FLAG_RESTRICTED != 0,
                )

                TYPE_LITERAL -> CommandNode.Literal(
                    name = input.decodeStringElement(descriptor, NAME),
                    children = children,
                    redirect = redirect,
                    executable = flags and FLAG_EXECUTABLE != 0,
                    restricted = flags and FLAG_RESTRICTED != 0,
                )

                TYPE_ARGUMENT -> CommandNode.Argument(
                    name = input.decodeStringElement(descriptor, NAME),
                    parser = input.decodeSerializableElement(
                        descriptor,
                        PARSER,
                        CommandParserSerializer,
                    ),
                    suggestionsType = if (flags and FLAG_SUGGESTIONS != 0) {
                        input.decodeSerializableElement(
                            descriptor,
                            SUGGESTIONS_TYPE,
                            Identifier.serializer(),
                        )
                    } else {
                        null
                    },
                    children = children,
                    redirect = redirect,
                    executable = flags and FLAG_EXECUTABLE != 0,
                    restricted = flags and FLAG_RESTRICTED != 0,
                )

                else -> throw SerializationException(
                    "Unsupported command node type ${flags and TYPE_MASK}",
                )
            }
            input.endStructure(descriptor)
            return result
        }

        var flags: Int? = null
        var children: List<Int>? = null
        var redirect: Int? = null
        var name: String? = null
        var parser: CommandParser? = null
        var suggestionsType: Identifier? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                FLAGS -> flags = input.decodeByteElement(descriptor, FLAGS).toInt()
                CHILDREN -> children = input.decodeSerializableElement(
                    descriptor,
                    CHILDREN,
                    childrenSerializer,
                )

                REDIRECT -> redirect = input.decodeIntElement(descriptor, REDIRECT)
                NAME -> name = input.decodeStringElement(descriptor, NAME)
                PARSER -> parser = input.decodeSerializableElement(
                    descriptor,
                    PARSER,
                    CommandParserSerializer,
                )

                SUGGESTIONS_TYPE -> suggestionsType =
                    input.decodeSerializableElement(
                        descriptor,
                        SUGGESTIONS_TYPE,
                        Identifier.serializer(),
                    )

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected CommandNode field $index",
                )
            }
        }
        input.endStructure(descriptor)
        val actualFlags = required(flags, "flags")
        val actualChildren = required(children, "children")
        return when (actualFlags and TYPE_MASK) {
            TYPE_ROOT -> CommandNode.Root(
                actualChildren,
                redirect,
                executable = actualFlags and FLAG_EXECUTABLE != 0,
                restricted = actualFlags and FLAG_RESTRICTED != 0,
            )

            TYPE_LITERAL -> CommandNode.Literal(
                required(name, "name"),
                actualChildren,
                redirect,
                executable = actualFlags and FLAG_EXECUTABLE != 0,
                restricted = actualFlags and FLAG_RESTRICTED != 0,
            )

            TYPE_ARGUMENT -> CommandNode.Argument(
                required(name, "name"),
                required(parser, "parser"),
                if (actualFlags and FLAG_SUGGESTIONS != 0) {
                    required(suggestionsType, "suggestionsType")
                } else {
                    null
                },
                actualChildren,
                redirect,
                executable = actualFlags and FLAG_EXECUTABLE != 0,
                restricted = actualFlags and FLAG_RESTRICTED != 0,
            )

            else -> throw SerializationException(
                "Unsupported command node type ${actualFlags and TYPE_MASK}",
            )
        }
    }

    private fun flags(value: CommandNode): Int {
        var result = when (value) {
            is CommandNode.Root -> TYPE_ROOT
            is CommandNode.Literal -> TYPE_LITERAL
            is CommandNode.Argument -> TYPE_ARGUMENT
        }
        if (value.executable) result = result or FLAG_EXECUTABLE
        if (value.redirect != null) result = result or FLAG_REDIRECT
        if (value is CommandNode.Argument && value.suggestionsType != null) {
            result = result or FLAG_SUGGESTIONS
        }
        if (value.restricted) result = result or FLAG_RESTRICTED
        return result
    }

    private fun <T : Any> required(value: T?, name: String): T =
        value ?: throw SerializationException("Missing CommandNode $name")

    private const val FLAGS: Int = 0
    private const val CHILDREN: Int = 1
    private const val REDIRECT: Int = 2
    private const val NAME: Int = 3
    private const val PARSER: Int = 4
    private const val SUGGESTIONS_TYPE: Int = 5
    private const val TYPE_MASK: Int = 0x03
    private const val TYPE_ROOT: Int = 0
    private const val TYPE_LITERAL: Int = 1
    private const val TYPE_ARGUMENT: Int = 2
    private const val FLAG_EXECUTABLE: Int = 0x04
    private const val FLAG_REDIRECT: Int = 0x08
    private const val FLAG_SUGGESTIONS: Int = 0x10
    private const val FLAG_RESTRICTED: Int = 0x20
}

internal object CommandParserSerializer : KSerializer<CommandParser> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.CommandParser",
    ) {
        element<Int>("parserId", annotations = listOf(VarInt()))
        element<Byte>("numberFlags", isOptional = true)
        element<Float>("floatMinimum", isOptional = true)
        element<Float>("floatMaximum", isOptional = true)
        element<Double>("doubleMinimum", isOptional = true)
        element<Double>("doubleMaximum", isOptional = true)
        element<Int>("integerMinimum", isOptional = true)
        element<Int>("integerMaximum", isOptional = true)
        element<Long>("longMinimum", isOptional = true)
        element<Long>("longMaximum", isOptional = true)
        element<CommandStringBehavior>("stringBehavior", isOptional = true)
        element<Byte>("entityFlags", isOptional = true)
        element<Byte>("scoreHolderFlags", isOptional = true)
        element<Int>("timeMinimum", isOptional = true)
        element<Identifier>("registry", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: CommandParser) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, PARSER_ID, parserId(value))
        when (value) {
            is CommandParser.Simple -> Unit
            is CommandParser.FloatRange -> {
                val minimum = canonical(value.minimum, -Float.MAX_VALUE)
                val maximum = canonical(value.maximum, Float.MAX_VALUE)
                val flags = numberFlags(minimum, maximum)
                output.encodeByteElement(descriptor, NUMBER_FLAGS, flags.toByte())
                minimum?.let {
                    output.encodeFloatElement(descriptor, FLOAT_MINIMUM, it)
                }
                maximum?.let {
                    output.encodeFloatElement(descriptor, FLOAT_MAXIMUM, it)
                }
            }

            is CommandParser.DoubleRange -> {
                val minimum = canonical(value.minimum, -Double.MAX_VALUE)
                val maximum = canonical(value.maximum, Double.MAX_VALUE)
                val flags = numberFlags(minimum, maximum)
                output.encodeByteElement(descriptor, NUMBER_FLAGS, flags.toByte())
                minimum?.let {
                    output.encodeDoubleElement(descriptor, DOUBLE_MINIMUM, it)
                }
                maximum?.let {
                    output.encodeDoubleElement(descriptor, DOUBLE_MAXIMUM, it)
                }
            }

            is CommandParser.IntegerRange -> {
                val minimum = canonical(value.minimum, Int.MIN_VALUE)
                val maximum = canonical(value.maximum, Int.MAX_VALUE)
                val flags = numberFlags(minimum, maximum)
                output.encodeByteElement(descriptor, NUMBER_FLAGS, flags.toByte())
                minimum?.let {
                    output.encodeIntElement(descriptor, INTEGER_MINIMUM, it)
                }
                maximum?.let {
                    output.encodeIntElement(descriptor, INTEGER_MAXIMUM, it)
                }
            }

            is CommandParser.LongRange -> {
                val minimum = canonical(value.minimum, Long.MIN_VALUE)
                val maximum = canonical(value.maximum, Long.MAX_VALUE)
                val flags = numberFlags(minimum, maximum)
                output.encodeByteElement(descriptor, NUMBER_FLAGS, flags.toByte())
                minimum?.let {
                    output.encodeLongElement(descriptor, LONG_MINIMUM, it)
                }
                maximum?.let {
                    output.encodeLongElement(descriptor, LONG_MAXIMUM, it)
                }
            }

            is CommandParser.StringValue -> output.encodeSerializableElement(
                descriptor,
                STRING_BEHAVIOR,
                CommandStringBehavior.serializer(),
                value.behavior,
            )

            is CommandParser.Entity -> {
                var flags = 0
                if (value.single) flags = flags or 0x01
                if (value.playersOnly) flags = flags or 0x02
                output.encodeByteElement(descriptor, ENTITY_FLAGS, flags.toByte())
            }

            is CommandParser.ScoreHolder -> output.encodeByteElement(
                descriptor,
                SCORE_HOLDER_FLAGS,
                if (value.allowsMultiple) 1 else 0,
            )

            is CommandParser.Time -> output.encodeIntElement(
                descriptor,
                TIME_MINIMUM,
                value.minimumTicks,
            )

            is CommandParser.Registry -> output.encodeSerializableElement(
                descriptor,
                REGISTRY,
                Identifier.serializer(),
                value.registry,
            )
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): CommandParser {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val result = when (val parserId = input.decodeIntElement(descriptor, PARSER_ID)) {
                FLOAT_ID -> {
                    val flags = input.decodeByteElement(descriptor, NUMBER_FLAGS).toInt()
                    CommandParser.FloatRange(
                        minimum = if (flags and 0x01 != 0) {
                            canonical(
                                input.decodeFloatElement(descriptor, FLOAT_MINIMUM),
                                -Float.MAX_VALUE,
                            )
                        } else {
                            null
                        },
                        maximum = if (flags and 0x02 != 0) {
                            canonical(
                                input.decodeFloatElement(descriptor, FLOAT_MAXIMUM),
                                Float.MAX_VALUE,
                            )
                        } else {
                            null
                        },
                    )
                }

                DOUBLE_ID -> {
                    val flags = input.decodeByteElement(descriptor, NUMBER_FLAGS).toInt()
                    CommandParser.DoubleRange(
                        minimum = if (flags and 0x01 != 0) {
                            canonical(
                                input.decodeDoubleElement(descriptor, DOUBLE_MINIMUM),
                                -Double.MAX_VALUE,
                            )
                        } else {
                            null
                        },
                        maximum = if (flags and 0x02 != 0) {
                            canonical(
                                input.decodeDoubleElement(descriptor, DOUBLE_MAXIMUM),
                                Double.MAX_VALUE,
                            )
                        } else {
                            null
                        },
                    )
                }

                INTEGER_ID -> {
                    val flags = input.decodeByteElement(descriptor, NUMBER_FLAGS).toInt()
                    CommandParser.IntegerRange(
                        minimum = if (flags and 0x01 != 0) {
                            canonical(
                                input.decodeIntElement(descriptor, INTEGER_MINIMUM),
                                Int.MIN_VALUE,
                            )
                        } else {
                            null
                        },
                        maximum = if (flags and 0x02 != 0) {
                            canonical(
                                input.decodeIntElement(descriptor, INTEGER_MAXIMUM),
                                Int.MAX_VALUE,
                            )
                        } else {
                            null
                        },
                    )
                }

                LONG_ID -> {
                    val flags = input.decodeByteElement(descriptor, NUMBER_FLAGS).toInt()
                    CommandParser.LongRange(
                        minimum = if (flags and 0x01 != 0) {
                            canonical(
                                input.decodeLongElement(descriptor, LONG_MINIMUM),
                                Long.MIN_VALUE,
                            )
                        } else {
                            null
                        },
                        maximum = if (flags and 0x02 != 0) {
                            canonical(
                                input.decodeLongElement(descriptor, LONG_MAXIMUM),
                                Long.MAX_VALUE,
                            )
                        } else {
                            null
                        },
                    )
                }

                STRING_ID -> CommandParser.StringValue(
                    input.decodeSerializableElement(
                        descriptor,
                        STRING_BEHAVIOR,
                        CommandStringBehavior.serializer(),
                    ),
                )

                ENTITY_ID -> {
                    val flags = input.decodeByteElement(descriptor, ENTITY_FLAGS).toInt()
                    CommandParser.Entity(
                        single = flags and 0x01 != 0,
                        playersOnly = flags and 0x02 != 0,
                    )
                }

                SCORE_HOLDER_ID -> {
                    val flags = input.decodeByteElement(
                        descriptor,
                        SCORE_HOLDER_FLAGS,
                    ).toInt()
                    CommandParser.ScoreHolder(flags and 0x01 != 0)
                }

                TIME_ID -> CommandParser.Time(
                    input.decodeIntElement(descriptor, TIME_MINIMUM),
                )

                in REGISTRY_FIRST_ID..REGISTRY_LAST_ID -> CommandParser.Registry(
                    RegistryCommandParser.entries.first {
                        it.protocolId == parserId
                    },
                    input.decodeSerializableElement(
                        descriptor,
                        REGISTRY,
                        Identifier.serializer(),
                    ),
                )

                else -> simpleParser(parserId)
            }
            input.endStructure(descriptor)
            return result
        }

        var parserId: Int? = null
        var numberFlags: Int? = null
        var floatMinimum: Float? = null
        var floatMaximum: Float? = null
        var doubleMinimum: Double? = null
        var doubleMaximum: Double? = null
        var integerMinimum: Int? = null
        var integerMaximum: Int? = null
        var longMinimum: Long? = null
        var longMaximum: Long? = null
        var stringBehavior: CommandStringBehavior? = null
        var entityFlags: Int? = null
        var scoreHolderFlags: Int? = null
        var timeMinimum: Int? = null
        var registry: Identifier? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                PARSER_ID -> parserId = input.decodeIntElement(descriptor, PARSER_ID)
                NUMBER_FLAGS -> numberFlags =
                    input.decodeByteElement(descriptor, NUMBER_FLAGS).toInt()

                FLOAT_MINIMUM -> floatMinimum =
                    input.decodeFloatElement(descriptor, FLOAT_MINIMUM)

                FLOAT_MAXIMUM -> floatMaximum =
                    input.decodeFloatElement(descriptor, FLOAT_MAXIMUM)

                DOUBLE_MINIMUM -> doubleMinimum =
                    input.decodeDoubleElement(descriptor, DOUBLE_MINIMUM)

                DOUBLE_MAXIMUM -> doubleMaximum =
                    input.decodeDoubleElement(descriptor, DOUBLE_MAXIMUM)

                INTEGER_MINIMUM -> integerMinimum =
                    input.decodeIntElement(descriptor, INTEGER_MINIMUM)

                INTEGER_MAXIMUM -> integerMaximum =
                    input.decodeIntElement(descriptor, INTEGER_MAXIMUM)

                LONG_MINIMUM -> longMinimum =
                    input.decodeLongElement(descriptor, LONG_MINIMUM)

                LONG_MAXIMUM -> longMaximum =
                    input.decodeLongElement(descriptor, LONG_MAXIMUM)

                STRING_BEHAVIOR -> stringBehavior =
                    input.decodeSerializableElement(
                        descriptor,
                        STRING_BEHAVIOR,
                        CommandStringBehavior.serializer(),
                    )

                ENTITY_FLAGS -> entityFlags =
                    input.decodeByteElement(descriptor, ENTITY_FLAGS).toInt()

                SCORE_HOLDER_FLAGS -> scoreHolderFlags =
                    input.decodeByteElement(descriptor, SCORE_HOLDER_FLAGS).toInt()

                TIME_MINIMUM -> timeMinimum =
                    input.decodeIntElement(descriptor, TIME_MINIMUM)

                REGISTRY -> registry = input.decodeSerializableElement(
                    descriptor,
                    REGISTRY,
                    Identifier.serializer(),
                )

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected CommandParser field $index",
                )
            }
        }
        input.endStructure(descriptor)
        return when (val id = required(parserId, "parserId")) {
            FLOAT_ID -> CommandParser.FloatRange(
                if (required(numberFlags, "numberFlags") and 0x01 != 0) {
                    canonical(
                        required(floatMinimum, "floatMinimum"),
                        -Float.MAX_VALUE,
                    )
                } else {
                    null
                },
                if (required(numberFlags, "numberFlags") and 0x02 != 0) {
                    canonical(
                        required(floatMaximum, "floatMaximum"),
                        Float.MAX_VALUE,
                    )
                } else {
                    null
                },
            )

            DOUBLE_ID -> CommandParser.DoubleRange(
                if (required(numberFlags, "numberFlags") and 0x01 != 0) {
                    canonical(
                        required(doubleMinimum, "doubleMinimum"),
                        -Double.MAX_VALUE,
                    )
                } else {
                    null
                },
                if (required(numberFlags, "numberFlags") and 0x02 != 0) {
                    canonical(
                        required(doubleMaximum, "doubleMaximum"),
                        Double.MAX_VALUE,
                    )
                } else {
                    null
                },
            )

            INTEGER_ID -> CommandParser.IntegerRange(
                if (required(numberFlags, "numberFlags") and 0x01 != 0) {
                    canonical(
                        required(integerMinimum, "integerMinimum"),
                        Int.MIN_VALUE,
                    )
                } else {
                    null
                },
                if (required(numberFlags, "numberFlags") and 0x02 != 0) {
                    canonical(
                        required(integerMaximum, "integerMaximum"),
                        Int.MAX_VALUE,
                    )
                } else {
                    null
                },
            )

            LONG_ID -> CommandParser.LongRange(
                if (required(numberFlags, "numberFlags") and 0x01 != 0) {
                    canonical(
                        required(longMinimum, "longMinimum"),
                        Long.MIN_VALUE,
                    )
                } else {
                    null
                },
                if (required(numberFlags, "numberFlags") and 0x02 != 0) {
                    canonical(
                        required(longMaximum, "longMaximum"),
                        Long.MAX_VALUE,
                    )
                } else {
                    null
                },
            )

            STRING_ID -> CommandParser.StringValue(
                required(stringBehavior, "stringBehavior"),
            )

            ENTITY_ID -> {
                val flags = required(entityFlags, "entityFlags")
                CommandParser.Entity(
                    single = flags and 0x01 != 0,
                    playersOnly = flags and 0x02 != 0,
                )
            }

            SCORE_HOLDER_ID -> CommandParser.ScoreHolder(
                required(scoreHolderFlags, "scoreHolderFlags") and 0x01 != 0,
            )

            TIME_ID -> CommandParser.Time(required(timeMinimum, "timeMinimum"))
            in REGISTRY_FIRST_ID..REGISTRY_LAST_ID -> CommandParser.Registry(
                RegistryCommandParser.entries.first { it.protocolId == id },
                required(registry, "registry"),
            )

            else -> simpleParser(id)
        }
    }

    private fun parserId(value: CommandParser): Int = when (value) {
        is CommandParser.Simple -> value.type.protocolId
        is CommandParser.FloatRange -> FLOAT_ID
        is CommandParser.DoubleRange -> DOUBLE_ID
        is CommandParser.IntegerRange -> INTEGER_ID
        is CommandParser.LongRange -> LONG_ID
        is CommandParser.StringValue -> STRING_ID
        is CommandParser.Entity -> ENTITY_ID
        is CommandParser.ScoreHolder -> SCORE_HOLDER_ID
        is CommandParser.Time -> TIME_ID
        is CommandParser.Registry -> value.type.protocolId
    }

    private fun simpleParser(id: Int): CommandParser.Simple {
        val parser = SimpleCommandParser.entries.firstOrNull {
            it.protocolId == id
        } ?: throw SerializationException(
            "Unknown command argument parser ID $id; its property length is unknowable",
        )
        return CommandParser.Simple(parser)
    }

    private fun numberFlags(minimum: Any?, maximum: Any?): Int =
        (if (minimum != null) 0x01 else 0) or
                (if (maximum != null) 0x02 else 0)

    private fun <T> canonical(value: T?, absentValue: T): T? =
        value?.takeUnless { it == absentValue }

    private fun <T : Any> required(value: T?, name: String): T =
        value ?: throw SerializationException("Missing CommandParser $name")

    private const val PARSER_ID: Int = 0
    private const val NUMBER_FLAGS: Int = 1
    private const val FLOAT_MINIMUM: Int = 2
    private const val FLOAT_MAXIMUM: Int = 3
    private const val DOUBLE_MINIMUM: Int = 4
    private const val DOUBLE_MAXIMUM: Int = 5
    private const val INTEGER_MINIMUM: Int = 6
    private const val INTEGER_MAXIMUM: Int = 7
    private const val LONG_MINIMUM: Int = 8
    private const val LONG_MAXIMUM: Int = 9
    private const val STRING_BEHAVIOR: Int = 10
    private const val ENTITY_FLAGS: Int = 11
    private const val SCORE_HOLDER_FLAGS: Int = 12
    private const val TIME_MINIMUM: Int = 13
    private const val REGISTRY: Int = 14
    private const val FLOAT_ID: Int = 1
    private const val DOUBLE_ID: Int = 2
    private const val INTEGER_ID: Int = 3
    private const val LONG_ID: Int = 4
    private const val STRING_ID: Int = 5
    private const val ENTITY_ID: Int = 6
    private const val SCORE_HOLDER_ID: Int = 31
    private const val TIME_ID: Int = 43
    private const val REGISTRY_FIRST_ID: Int = 44
    private const val REGISTRY_LAST_ID: Int = 48
}
