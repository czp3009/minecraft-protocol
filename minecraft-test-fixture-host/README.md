# Minecraft test fixture host

This unpublished JVM module implements the remote fixtures exposed by `minecraft-test-support`. A shared Gradle Build
Service starts one loopback kotlinx.rpc JSON host lazily after the consuming standard test task's required artifact
providers have produced their files. The host consumes those exact paths and never downloads fixtures.

`MinecraftTestSupportServiceServer` implements the shared service contract. Its creation methods return serializable
official-server or headless-client resource values, and every later operation accepts those values directly.

The host owns official-server and HeadlessMC/Fabric/HMC-Specifics client processes, unique workspaces, bounded in-memory
logs, readiness probes, codec execution, the documented working-directory backdoor, and cleanup. Prepared resources are
complete before launch; the host never downloads or repairs them. Gradle starts the host with
`--enable-native-access=ALL-UNNAMED` for its in-process codec oracle, and the host places the same argument on every
official-server and HeadlessMC JVM command.

Gradle publishes an immutable runtime and normally stopped template for each process kind. Exact default optional
configuration clones the corresponding template automatically, while non-default optional configuration starts from the
assembled runtime without seeded mutable state. The required offline client name does not disable its template.
Templates preserve generated configuration, Fabric's processed-mod cache, the sole HMC-Specifics mod, server world and
access-control state, and all reusable empty directories. Only the fixed per-process files listed in each manifest are
removed. Runtime inputs and templates are never launched in place. Immutable runtime trees, the HeadlessMC launcher,
HMC-Specifics, and the processed-mod cache are shared only through read-only links: where supported, the complete client
Minecraft runtime and official-server library directory each use one directory symbolic link. The fallback and all other
immutable inputs use per-file hard links or copies. Generated options, server state, and all other mutable files are
copied into the workspace. Cleanup unlinks directory symbolic links without traversing their targets.

Official-server creation requires a complete Status response and pong. Headless-client creation requires HMC-Specifics
initialization plus a correlated `gui` observation of `TitleScreen`; connection is a separate operation, and only packet
evidence from the production server establishes Play. Normal client shutdown uses HMC-Specifics `quit`
and requires output EOF and exit code zero.

Codec verification returns no success report and propagates bounded failure diagnostics. A fair eight-slot pool bounds
live or retained fixture resources. Each slot covers startup, running, a stopped process with retained files,
termination, and workspace deletion. Manual process and directory operations wait for their postconditions; a close
request returns after the same combined cleanup is scheduled, and the slot becomes available only after successful
directory cleanup.

Subproject tests depend on `minecraft-test-support`, not this module. Task completion releases that task's resources,
and Build Service shutdown closes the host and any remaining resources.
