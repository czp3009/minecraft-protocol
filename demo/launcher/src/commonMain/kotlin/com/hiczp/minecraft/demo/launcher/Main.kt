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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val platform = LauncherPlatform.current()
    val store = LauncherStore(fileSystem, launcherRoot)
    val mojangApi = createMojangApi(httpClient)
    val controller = LauncherController(
        scope = scope,
        store = store,
        installationService = InstallationService(mojangApi, fileSystem, store, platform),
        accountService = AccountService(httpClient, store),
        processService = GameProcessService(fileSystem, launcherRoot),
        platform = platform,
    )
    try {
        controller.start()
        runMosaicMain {
            LauncherApplication(controller, platform)
        }
    } finally {
        scope.cancel()
        httpClient.close()
    }
}
