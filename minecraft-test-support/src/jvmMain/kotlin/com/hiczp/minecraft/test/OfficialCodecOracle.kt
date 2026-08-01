package com.hiczp.minecraft.test

import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.*
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/**
 * Compiles and loads the exact-version official codec bridge inside the test
 * JVM. The consuming test calls this as a normal library API; no Oracle CLI or
 * Gradle compilation task exists.
 */
object OfficialCodecOracle {
    private const val LOG4J_CONFIGURATION_PROPERTY =
        "log4j2.configurationFile"
    private const val JOML_NO_UNSAFE_PROPERTY = "joml.nounsafe"
    private val verificationLock = Mutex()

    suspend fun verify(
        environment: MinecraftTestEnvironment,
        fixtures: Path,
        report: Path,
    ) {
        verificationLock.lock()
        try {
            verifyLocked(environment, fixtures, report)
        } finally {
            verificationLock.unlock()
        }
    }

    private suspend fun verifyLocked(
        environment: MinecraftTestEnvironment,
        fixtures: Path,
        report: Path,
    ) {
        require(fixtures.isRegularFile()) {
            "Official codec fixtures are absent: $fixtures"
        }
        val runtime = environment.officialServerRuntime()
        val classes = compileBridge(environment, runtime)
        prepareReport(report)

        val urls = buildList {
            add(classes.toNioPath().toUri().toURL())
            add(runtime.implementationJar.toNioPath().toUri().toURL())
            addAll(
                runtime.libraries.map {
                    it.toNioPath().toUri().toURL()
                },
            )
        }.toTypedArray()
        val loggingConfiguration = environment.temporaryFile(
            "official-codec-log4j2.xml",
        )
        loggingConfiguration.atomicWriteText(log4jNullConfigurationXml())
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
                val method = oracle.getMethod("run", Array<String>::class.java)
                try {
                    method.invoke(
                        null,
                        arrayOf(
                            fixtures.toNioPath().toString(),
                            runtime.implementationJar.toNioPath().toString(),
                            report.toNioPath().toString(),
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
        check(report.isRegularFile()) {
            "Official codec Oracle did not write its report"
        }
        val result = report.readJsonObject()
        check(result.requiredInt("failed") == 0) {
            "Official codec Oracle reported failed fixtures: $report"
        }
        check(
            result.requiredString("official_server_inner_sha256") ==
                    runtime.implementationJar.sha256(),
        ) {
            "Official codec report describes a different runtime JAR"
        }
    }

    private fun prepareReport(report: Path) {
        requireNotNull(report.parent).ensureDirectory()
        SystemFileSystem.delete(report, mustExist = false)
    }

    private fun compileBridge(
        environment: MinecraftTestEnvironment,
        runtime: OfficialServerRuntime,
    ): Path {
        val sourceBytes = checkNotNull(
            OfficialCodecOracle::class.java.getResourceAsStream(
                "/com/hiczp/minecraft/test/oracle/OfficialCodecOracle.java",
            ),
        ) {
            "Official codec bridge source is absent from test-support"
        }.use { it.readBytes() }
        val key = sourceBytes.sha256() +
                "-" + runtime.implementationJar.sha256()
        val root = Path(
            environment.sharedCacheDirectory,
            "official-codec-oracle",
        ).safeResolve(key)
        val classes = Path(root, "classes")
        val classFile = classes.safeResolve(
            "com/hiczp/minecraft/test/oracle/OfficialCodecOracle.class",
        )
        Path(root, ".test-support.lock").withExclusiveJvmFileLock {
            if (!classFile.isRegularFile()) {
                compileBridgeLocked(
                    sourceBytes = sourceBytes,
                    root = root,
                    classes = classes,
                    classFile = classFile,
                    runtime = runtime,
                )
            }
        }
        return classes
    }

    private fun compileBridgeLocked(
        sourceBytes: ByteArray,
        root: Path,
        classes: Path,
        classFile: Path,
        runtime: OfficialServerRuntime,
    ) {
        classes.deleteTree()
        classes.ensureDirectory()
        val source = root.safeResolve(
            "src/com/hiczp/minecraft/test/oracle/OfficialCodecOracle.java",
        )
        source.atomicWrite(sourceBytes)

        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Official codec tests require a full JDK, not a JRE"
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(
            diagnostics,
            Locale.ROOT,
            Charsets.UTF_8,
        ).use { fileManager ->
            fileManager.setLocationFromPaths(
                StandardLocation.CLASS_OUTPUT,
                listOf(classes.toNioPath()),
            )
            val classpath = buildList {
                add(runtime.implementationJar)
                addAll(runtime.libraries)
            }.joinToString(File.pathSeparator)
            val units = fileManager.getJavaFileObjectsFromPaths(
                listOf(source.toNioPath()),
            )
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf(
                    "--release",
                    "25",
                    "-classpath",
                    classpath,
                ),
                null,
                units,
            ).call()
            check(success) {
                diagnostics.diagnostics.joinToString(
                    prefix = "Official codec bridge compilation failed:\n",
                    separator = "\n",
                )
            }
        }
        check(classFile.isRegularFile()) {
            "Official codec bridge compiler produced no class"
        }
    }
}
