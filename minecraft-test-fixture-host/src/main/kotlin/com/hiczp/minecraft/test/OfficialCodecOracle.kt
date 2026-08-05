package com.hiczp.minecraft.test

import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * Loads the codec bridge prepared by the
 * `prepareOfficialMinecraftCodecOracle` Gradle gate and runs verification
 * inside the fixture-host JVM. No compilation or file lock happens at test
 * time.
 */
internal object OfficialCodecOracle {
    private const val LOG4J_CONFIGURATION_PROPERTY =
        "log4j2.configurationFile"
    private const val JOML_NO_UNSAFE_PROPERTY = "joml.nounsafe"

    suspend fun verify(
        fixtures: JsonElement,
        loggingConfiguration: Path,
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
            add(classes.toNioPath().toUri().toURL())
            add(runtime.implementationJar.toNioPath().toUri().toURL())
            addAll(
                runtime.libraries.map {
                    it.toNioPath().toUri().toURL()
                },
            )
        }.toTypedArray()
        loggingConfiguration.writeText(log4jNullConfigurationXml())
        val previousLoggingConfiguration =
            System.getProperty(LOG4J_CONFIGURATION_PROPERTY)
        val previousJomlNoUnsafe =
            System.getProperty(JOML_NO_UNSAFE_PROPERTY)
        System.setProperty(
            LOG4J_CONFIGURATION_PROPERTY,
            loggingConfiguration.toNioPath().toUri().toString(),
        )
        System.setProperty(JOML_NO_UNSAFE_PROPERTY, "true")
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val previous = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            try {
                val oracle = Class.forName(
                    "com.hiczp.minecraft.test.oracle.OfficialCodecOracle",
                    true,
                    loader,
                )
                val method = oracle.getMethod("run", String::class.java)
                try {
                    method.invoke(
                        null,
                        testJson.encodeToString(
                            JsonElement.serializer(),
                            fixtures,
                        ),
                    )
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            } finally {
                Thread.currentThread().contextClassLoader = previous
                if (previousLoggingConfiguration == null) {
                    System.clearProperty(LOG4J_CONFIGURATION_PROPERTY)
                } else {
                    System.setProperty(
                        LOG4J_CONFIGURATION_PROPERTY,
                        previousLoggingConfiguration,
                    )
                }
                if (previousJomlNoUnsafe == null) {
                    System.clearProperty(JOML_NO_UNSAFE_PROPERTY)
                } else {
                    System.setProperty(
                        JOML_NO_UNSAFE_PROPERTY,
                        previousJomlNoUnsafe,
                    )
                }
            }
        }
    }
}
