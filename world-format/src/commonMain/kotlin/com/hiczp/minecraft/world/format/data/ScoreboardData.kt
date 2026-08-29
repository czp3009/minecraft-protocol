package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.nbt.NbtTag
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of root `minecraft:scoreboard` saved data. */
@Serializable
data class ScoreboardData(
    @SerialName("Objectives")
    val objectives: List<Objective> = emptyList(),
    @SerialName("PlayerScores")
    val playerScores: List<PlayerScore> = emptyList(),
    @SerialName("DisplaySlots")
    val displaySlots: Map<String, String> = emptyMap(),
    @SerialName("Teams")
    val teams: List<Team> = emptyList(),
) {
    @Serializable
    data class Objective(
        @SerialName("Name")
        val name: String,
        @SerialName("CriteriaName")
        val criteriaName: String = "dummy",
        @SerialName("DisplayName")
        val displayName: NbtTag,
        @SerialName("RenderType")
        val renderType: RenderType = RenderType.INTEGER,
        @SerialName("display_auto_update")
        val displayAutoUpdate: Boolean = false,
        val format: NbtTag? = null,
    )

    @Serializable
    data class PlayerScore(
        @SerialName("Name")
        val name: String,
        @SerialName("Objective")
        val objective: String,
        @SerialName("Score")
        val score: Int = 0,
        @SerialName("Locked")
        val locked: Boolean = false,
        val display: NbtTag? = null,
        val format: NbtTag? = null,
    )

    @Serializable
    data class Team(
        @SerialName("Name")
        val name: String,
        @SerialName("DisplayName")
        val displayName: NbtTag? = null,
        @SerialName("TeamColor")
        val color: TeamColor? = null,
        @SerialName("AllowFriendlyFire")
        val allowFriendlyFire: Boolean = true,
        @SerialName("SeeFriendlyInvisibles")
        val seeFriendlyInvisibles: Boolean = true,
        @SerialName("MemberNamePrefix")
        val memberNamePrefix: NbtTag = NbtString(""),
        @SerialName("MemberNameSuffix")
        val memberNameSuffix: NbtTag = NbtString(""),
        @SerialName("NameTagVisibility")
        val nameTagVisibility: Visibility = Visibility.ALWAYS,
        @SerialName("DeathMessageVisibility")
        val deathMessageVisibility: Visibility = Visibility.ALWAYS,
        @SerialName("CollisionRule")
        val collisionRule: CollisionRule = CollisionRule.ALWAYS,
        @SerialName("Players")
        val players: List<String> = emptyList(),
    )

    @Serializable
    enum class RenderType {
        @SerialName("integer")
        INTEGER,

        @SerialName("hearts")
        HEARTS,
    }

    @Serializable
    enum class TeamColor {
        @SerialName("black")
        BLACK,

        @SerialName("dark_blue")
        DARK_BLUE,

        @SerialName("dark_green")
        DARK_GREEN,

        @SerialName("dark_aqua")
        DARK_AQUA,

        @SerialName("dark_red")
        DARK_RED,

        @SerialName("dark_purple")
        DARK_PURPLE,

        @SerialName("gold")
        GOLD,

        @SerialName("gray")
        GRAY,

        @SerialName("dark_gray")
        DARK_GRAY,

        @SerialName("blue")
        BLUE,

        @SerialName("green")
        GREEN,

        @SerialName("aqua")
        AQUA,

        @SerialName("red")
        RED,

        @SerialName("light_purple")
        LIGHT_PURPLE,

        @SerialName("yellow")
        YELLOW,

        @SerialName("white")
        WHITE,
    }

    @Serializable
    enum class Visibility {
        @SerialName("always")
        ALWAYS,

        @SerialName("never")
        NEVER,

        @SerialName("hideForOtherTeams")
        HIDE_FOR_OTHER_TEAMS,

        @SerialName("hideForOwnTeam")
        HIDE_FOR_OWN_TEAM,
    }

    @Serializable
    enum class CollisionRule {
        @SerialName("always")
        ALWAYS,

        @SerialName("never")
        NEVER,

        @SerialName("pushOtherTeams")
        PUSH_OTHER_TEAMS,

        @SerialName("pushOwnTeam")
        PUSH_OWN_TEAM,
    }
}
