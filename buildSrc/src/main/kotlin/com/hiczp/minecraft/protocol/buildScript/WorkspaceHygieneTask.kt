package com.hiczp.minecraft.protocol.buildScript

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Checks ambient workspace state.")
abstract class WorkspaceHygieneTask : DefaultTask() {
    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    @get:Input
    abstract val forbiddenPaths: ListProperty<String>

    @TaskAction
    fun checkWorkspace() {
        val workspace = workspaceDirectory.asFile.get()
        val leakedArtifacts = forbiddenPaths.get()
            .map { workspace.resolve(it) }
            .filter { it.exists() }

        check(leakedArtifacts.isEmpty()) {
            "Vanilla runtime artifacts escaped build/: " +
                    leakedArtifacts.joinToString { it.relativeTo(workspace).path }
        }
    }
}
