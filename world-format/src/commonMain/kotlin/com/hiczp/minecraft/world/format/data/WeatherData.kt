package com.hiczp.minecraft.world.format.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of root `minecraft:weather` saved data. */
@Serializable
data class WeatherData(
    @SerialName("clear_weather_time")
    val clearWeatherTime: Int,
    @SerialName("rain_time")
    val rainTime: Int,
    @SerialName("thunder_time")
    val thunderTime: Int,
    val raining: Boolean,
    val thundering: Boolean,
)
