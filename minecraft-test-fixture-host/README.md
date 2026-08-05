# Minecraft test fixture host

This unpublished JVM module implements the remote fixtures exposed by `minecraft-test-support`. A shared Gradle Build
Service starts one loopback kotlinx.rpc JSON host lazily after the consuming standard test task's required artifact
providers have produced their files. The host consumes those exact paths and never downloads fixtures.

`MinecraftTestSupportServiceServer` implements the shared service contract. Its creation methods return serializable
server/client resource values, and every later operation accepts those values directly.

The host owns official server and client processes, unique workspaces, bounded in-memory logs, readiness probes, codec
execution, world snapshot transfer, and cleanup. Codec verification returns no success report and propagates bounded
failure diagnostics. A fair eight-slot pool bounds concurrent Minecraft processes. Each slot covers startup, running,
process termination, and workspace deletion; a close request returns after cleanup is scheduled, and the slot becomes
available only after cleanup completes.

Subproject tests depend on `minecraft-test-support`, not this module. Task completion releases that task's resources,
and Build Service shutdown closes the host and any remaining resources.
