package com.hiczp.minecraft.protocol.buildScript

/**
 * DSL for declaring which official downloads a subproject's test tasks need.
 *
 * Usage in a subproject build script:
 * ```
 * officialDownloads {
 *     server()       // needs mojang-server download + runtime extraction
 *     client()       // needs mojang-client + assets downloads
 *     headlessMc()   // needs headlessmc launcher + versions/ layout
 *     codecOracle()  // needs server runtime + codec bridge compilation
 * }
 * ```
 *
 * Each method adds [dependsOn] from every [Test] task in the subproject
 * to the corresponding root-project download tasks.  The task graph
 * guarantees every download task runs at most once per build.
 */
open class OfficialDownloadsExtension {
    var needsServer: Boolean = false
        private set
    var needsClient: Boolean = false
        private set
    var needsHeadlessMc: Boolean = false
        private set
    var needsCodecOracle: Boolean = false
        private set

    fun server() {
        needsServer = true
    }

    /** Includes client JAR, libraries, asset index, and asset objects. */
    fun client() {
        needsClient = true
    }

    fun headlessMc() {
        needsHeadlessMc = true
    }

    /** Includes server runtime extraction and codec bridge compilation. */
    fun codecOracle() {
        needsCodecOracle = true
    }
}
