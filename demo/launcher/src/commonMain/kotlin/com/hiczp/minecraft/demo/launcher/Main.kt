package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.demo.launcher.ui.LauncherApplication
import com.jakewharton.mosaic.runMosaicMain
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

fun main() {
    val fileSystem = FileSystem.SYSTEM
    val launcherRoot = fileSystem.canonicalize(".".toPath())
    val httpClient = HttpClient { configureLauncherHttpClient() }
    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val launcherPlatform = LauncherPlatform.current()
    val launcherStore = LauncherStore(fileSystem, launcherRoot)
    val mojangApi = createMojangApi(httpClient)
    val launcherController = LauncherController(
        coroutineScope = coroutineScope,
        launcherStore = launcherStore,
        installationService = InstallationService(mojangApi, fileSystem, launcherStore, launcherPlatform),
        accountService = AccountService(httpClient, launcherStore),
        processService = GameProcessService(fileSystem, launcherRoot),
        launcherPlatform = launcherPlatform,
    )
    try {
        launcherController.start()
        runMosaicMain {
            LauncherApplication(launcherController, launcherPlatform)
        }
    } finally {
        coroutineScope.cancel()
        httpClient.close()
    }
}
