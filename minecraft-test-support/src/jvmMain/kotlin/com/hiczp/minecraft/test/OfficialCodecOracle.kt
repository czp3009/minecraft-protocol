package com.hiczp.minecraft.test

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/**
 * Compiles and loads the exact-version official codec bridge inside the test
 * JVM. The consuming test calls this as a normal library API; no Oracle CLI or
 * Gradle compilation task exists.
 */
object OfficialCodecOracle {
    private const val LOG4J_CONFIGURATION_PROPERTY =
        "log4j2.configurationFile"

    fun verify(
        environment: MinecraftTestEnvironment,
        fixtures: Path,
        report: Path,
    ) = synchronized(this) {
        require(fixtures.isRegularFile()) {
            "Official codec fixtures are absent: $fixtures"
        }
        val runtime = environment.officialServerRuntime()
        val classes = compileBridge(environment, runtime)
        report.parent.createDirectories()
        Files.deleteIfExists(report)

        val urls = buildList {
            add(classes.toUri().toURL())
            add(runtime.implementationJar.toUri().toURL())
            addAll(runtime.libraries.map { it.toUri().toURL() })
        }.toTypedArray()
        val loggingConfiguration = environment.temporaryFile(
            "official-codec-log4j2.xml",
        )
        loggingConfiguration.atomicWrite(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <Configuration status="OFF">
                <Appenders>
                    <Null name="Null"/>
                </Appenders>
                <Loggers>
                    <Root level="off">
                        <AppenderRef ref="Null"/>
                    </Root>
                </Loggers>
            </Configuration>
            """.trimIndent().encodeToByteArray(),
        )
        val previousLoggingConfiguration =
            System.getProperty(LOG4J_CONFIGURATION_PROPERTY)
        System.setProperty(
            LOG4J_CONFIGURATION_PROPERTY,
            loggingConfiguration.toUri().toString(),
        )
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
                            fixtures.toAbsolutePath().normalize().toString(),
                            runtime.implementationJar.toString(),
                            report.toAbsolutePath().normalize().toString(),
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
        val root = environment.sharedCacheDirectory
            .resolve("official-codec-oracle")
            .safeResolve(key)
        val classes = root.resolve("classes")
        val classFile = classes.resolve(
            "com/hiczp/minecraft/test/oracle/OfficialCodecOracle.class",
        )
        root.resolve(".test-support.lock").withExclusiveLock {
            if (classFile.isRegularFile()) return classes

            classes.deleteTree()
            classes.createDirectories()
            val source = root.resolve(
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
                    listOf(classes),
                )
                val classpath = buildList {
                    add(runtime.implementationJar)
                    addAll(runtime.libraries)
                }.joinToString(File.pathSeparator)
                val units =
                    fileManager.getJavaFileObjectsFromPaths(listOf(source))
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
        return classes
    }
}
