# Minecraft test fixture host

`minecraft-test-fixture-host` is the private JVM process behind
[`minecraft-test-support`](../minecraft-test-support/README.md). It lets multiplatform tests use matching official
Minecraft processes and codec implementations without exposing process or filesystem APIs to those tests.

Application and library consumers do not depend on this module directly. A shared Gradle Build Service starts it lazily
for supported test tasks and supplies the portable client with a loopback RPC endpoint.

## What the host provides

- official server processes with isolated workspaces and loopback endpoints;
- prepared HeadlessMC/Fabric/HMC-Specifics official clients;
- bounded process logs and event waits;
- official packet, NBT, and SNBT codec verification;
- lifecycle operations for stopping a process, retaining files, deleting a workspace, and final cleanup;
- the explicitly limited same-host working-directory path used by `world-io` tests.

All official artifacts and immutable templates are prepared by Gradle before launch. The host consumes the supplied
paths and does not download or repair fixtures.

## Implementation boundary

The portable test-facing guide owns the public readiness, command, and cleanup semantics. This module implements those
guarantees with process monitoring, correlated output sequences, bounded logs, Status probes, and process-tree cleanup;
none of those host mechanisms crosses the RPC model boundary.

## Workspaces and cleanup

Every resource gets a unique mutable workspace. Default configurations may clone prepared stopped templates; customized
resources start from immutable prepared runtimes. No template or immutable runtime is launched in place.

A four-slot pool bounds resources that are starting, running, stopped with retained files, or awaiting workspace
deletion. Task completion releases resources owned by that test task, and Build Service shutdown is the final fallback.

For the public test-facing operations and examples, use [`minecraft-test-support`](../minecraft-test-support/README.md).
Contributors changing host lifecycle or workspace behavior should also read [AGENTS.md](AGENTS.md).
