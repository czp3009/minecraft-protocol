# Discovering implementation state

Do not record the current Minecraft version, protocol number, Wiki revision, packet/type counts, source tags,
nullable-field counts, hashes, exception lists, or last passing results in this skill. Those facts change independently
of the workflow.

At the start of every invocation, obtain current state from deterministic project tasks:

1. Run `refreshProtocolSpecification` with the optional command target.
2. Run `prepareProtocolUpdate` in a separate Gradle invocation.
3. Read the refreshed files in the project-level
   `protocol-specification/` directory.
4. Read `build/reports/protocol-update/work-queue.json`,
   `nullability-inventory.json`, the official codec report, and every other report named by the Gradle workflow.
5. Read the official-server production-client report, committed/fresh vanilla-data report, and official-client
   production-server report when present.
6. Inspect the current source tree and Gradle task graph rather than trusting a previous invocation's prose.
7. Run every completion gate before reporting the implementation current.

The project-level specification directory is version-dependent checked-in state. The skill's own Markdown references
describe only stable procedures and design rules. Gradle-owned transient artifacts remain under `build/`; LLM scratch
remains under `temp/`.

If a newly discovered fact is needed by a later deterministic check, add it to an appropriately source-bound project
report or specification schema and teach the refresh/check tasks to maintain it. Do not copy the value into skill
instructions.
