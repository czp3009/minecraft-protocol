@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model

import kotlinx.serialization.SerialInfo

/**
 * Marks a nullable protocol property whose nullability could not be resolved
 * from the Wiki, official implementation, MCProtocolLib, or Minestom.
 *
 * This is audit metadata only and does not select an on-wire presence format.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class UnknownNullability
