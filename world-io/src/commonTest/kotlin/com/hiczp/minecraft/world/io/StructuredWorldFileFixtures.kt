package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtIntArray
import com.hiczp.minecraft.world.format.LevelDat

internal fun testLevelDat(levelName: String = "typed-world"): LevelDat = LevelDat(
    data = LevelDat.Data(
        dataVersion = 4_903,
        lastPlayed = 1_786_958_771_250,
        levelName = levelName,
        gameType = 0,
        time = 2,
        version = 19_133,
        versionInfo = LevelDat.Data.Version(4_903, "26.2", "main", false),
        serverBrands = listOf("vanilla"),
        wasModded = false,
        allowCommands = false,
        initialized = true,
        difficultySettings = LevelDat.Data.DifficultySettings("easy", false, false),
        spawn = LevelDat.Data.Spawn(
            dimension = "minecraft:overworld",
            pos = NbtIntArray(intArrayOf(0, -60, 0)),
            yaw = 0F,
            pitch = 0F,
        ),
    ),
)
