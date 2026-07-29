# Protocol specification state

This directory contains the checked-in, version-dependent protocol target and review evidence produced or validated by
the protocol update workflow.

It is project state, not skill instructions. Do not hand-copy values from these files into the skill. Each update
invocation refreshes the target from the Minecraft Wiki (or an explicit command argument), binds official evidence to
the matching Mojang server artifact, and invalidates stale review records. Wiki-based target selection does not make the
Wiki the wire authority: the matching official JAR is reviewed first, followed by the Wiki and then exact-version
third-party implementations.

Gradle's transient downloads, generated reports, decompiled sources, and test reports remain under the root `build/`
directory.
