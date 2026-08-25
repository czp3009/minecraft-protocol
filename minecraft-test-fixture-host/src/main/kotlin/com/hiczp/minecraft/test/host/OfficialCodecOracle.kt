package com.hiczp.minecraft.test.host

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * Loads the codec bridge prepared by the
 * `prepareOfficialMinecraftCodecOracle` Gradle gate and runs verification
 * inside the fixture-host JVM. No compilation or file lock happens at test
 * time.
 */
internal object OfficialCodecOracle {
    suspend fun verify(
        fixtures: JsonElement,
        loggingConfiguration: Path,
        methodName: String,
    ) {
        val runtime = officialServerRuntime()

        // Pre-compiled bridge classes supplied by the codec-oracle gate.
        val classes = HostedMinecraftTestSupport.layout.codecClassesDirectory

        check(classes.isDirectory()) {
            "Official codec bridge is not compiled: $classes; run the Gradle prepareOfficialMinecraftCodecOracle task first"
        }
        val classFile = classes.safeResolve(
            "com/hiczp/minecraft/test/oracle/OfficialCodecOracle.class",
        )
        check(classFile.isRegularFile()) {
            "Official codec bridge class is missing: $classFile"
        }

        val urls = buildList {
            add(classes.toUri().toURL())
            add(runtime.implementationJar.toUri().toURL())
            addAll(
                runtime.libraries.map {
                    it.toUri().toURL()
                },
            )
        }.toTypedArray()
        loggingConfiguration.writeText(log4jNullConfigurationXml())
        withOfficialCodecEnvironment(loggingConfiguration) {
            URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
                val previous = Thread.currentThread().contextClassLoader
                Thread.currentThread().contextClassLoader = loader
                var failure: Throwable? = null
                try {
                    val oracle = Class.forName(
                        "com.hiczp.minecraft.test.oracle.OfficialCodecOracle",
                        true,
                        loader,
                    )
                    val method = oracle.getMethod(methodName, String::class.java)
                    try {
                        method.invoke(
                            null,
                            testJson.encodeToString(fixtures),
                        )
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                } catch (caught: Throwable) {
                    failure = caught
                    throw caught
                } finally {
                    try {
                        Thread.currentThread().contextClassLoader = previous
                    } catch (restorationFailure: Throwable) {
                        failure?.addSuppressed(restorationFailure)
                            ?: throw restorationFailure
                    }
                }
            }
        }
    }
}

private val officialCodecEnvironmentMutex = Mutex()

internal suspend fun <T> withOfficialCodecEnvironment(
    loggingConfiguration: Path,
    block: suspend () -> T,
): T = officialCodecEnvironmentMutex.withLock {
    val previousLoggingConfiguration = System.getProperty(LOG4J_CONFIGURATION_PROPERTY)
    val previousJomlNoUnsafe = System.getProperty(JOML_NO_UNSAFE_PROPERTY)
    var failure: Throwable? = null
    try {
        System.setProperty(
            LOG4J_CONFIGURATION_PROPERTY,
            loggingConfiguration.toUri().toString(),
        )
        System.setProperty(JOML_NO_UNSAFE_PROPERTY, "true")
        block()
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        var restorationFailure: Throwable? = null
        try {
            if (previousLoggingConfiguration == null) {
                System.clearProperty(LOG4J_CONFIGURATION_PROPERTY)
            } else {
                System.setProperty(
                    LOG4J_CONFIGURATION_PROPERTY,
                    previousLoggingConfiguration,
                )
            }
        } catch (caught: Throwable) {
            restorationFailure = caught
        }
        try {
            if (previousJomlNoUnsafe == null) {
                System.clearProperty(JOML_NO_UNSAFE_PROPERTY)
            } else {
                System.setProperty(JOML_NO_UNSAFE_PROPERTY, previousJomlNoUnsafe)
            }
        } catch (caught: Throwable) {
            restorationFailure?.addSuppressed(caught)
                ?: run { restorationFailure = caught }
        }
        restorationFailure?.let { caught ->
            failure?.addSuppressed(caught) ?: throw caught
        }
    }
}

private const val LOG4J_CONFIGURATION_PROPERTY = "log4j2.configurationFile"
private const val JOML_NO_UNSAFE_PROPERTY = "joml.nounsafe"
