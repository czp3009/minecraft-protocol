@file:JsModule("fs-native-extensions")
@file:JsNonModule

package com.hiczp.minecraft.world.io

internal external fun tryLock(fd: Number): Boolean

internal external fun unlock(fd: Number)
