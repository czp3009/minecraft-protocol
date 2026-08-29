package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of root `minecraft:scheduled_events` saved data. */
@Serializable
data class ScheduledEventsData(
    val events: List<Event>,
) {
    @Serializable
    data class Event(
        @SerialName("trigger_time")
        val triggerTime: Long,
        val id: String,
        val callback: Callback,
    )

    @Serializable
    data class Callback(
        val type: Type,
        val id: String,
    )

    @Serializable
    enum class Type {
        @SerialName("minecraft:function")
        FUNCTION,

        @SerialName("minecraft:function_tag")
        FUNCTION_TAG,
    }
}
