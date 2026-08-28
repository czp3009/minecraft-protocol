# Writing README.md files

A `README.md` is the human-facing contract for the project or subproject. It should quickly explain what the software
is, why someone would use it, and how to exercise its most important public capabilities.

## Organize around the reader

At repository level, provide a concise project identity and scope, a capability or module map, a few high-level quick
starts, shared prerequisites, and links to the modules that own details. At subproject level, explain that module's
responsibility, when to depend on it, its main public entry points, and user-visible constraints. A private build or
test module may address contributors, but it remains human documentation rather than agent instructions.

Lead with the maintained high-level path. Mention lower layers so readers can choose them deliberately, not as equal
boilerplate alternatives. Link to the owning module instead of copying another module's workflow.

Include implementation details only when they directly affect dependency selection, supported targets, configuration,
correct use, observable behavior, resource lifetime, concurrency, compatibility, or failure handling. Omit internal file
maps, generated-code mechanics, agent rules, exhaustive API catalogues, speculative features, and incidental
implementation history.

## Teach key features with code

Use small Kotlin examples for the public operations that define the module's value. Each example should:

- use current public entry points and signatures rather than pseudocode or internal helpers;
- introduce every value before first use through a parameter, local declaration, clearly continued example, or an
  immediately described producer;
- identify the receiver type for an otherwise unqualified DSL property;
- show required `suspend`, `use`, close, callback, or ownership structure when omitting it would teach unsafe usage;
- rely on documented defaults when that keeps the common path simple, then explain the important defaults nearby;
- isolate one coherent task instead of reproducing an application's entire setup.

An example need not include every import, but a reader must be able to discover where all values and types originate.
Explain in a short paragraph what the example accomplishes, what the caller still owns, and any surprising result or
failure. Prefer one strong end-to-end example over several near-duplicates.

## Keep the public contract precise

Verify descriptions against source and tests, especially:

- required caller inputs versus release-matched defaults;
- supported Kotlin targets and runtime prerequisites;
- mutability, snapshots, caching, threading, coroutine context, and resource lifetime;
- null, empty, exception, and recovery behavior that changes how callers use the API;
- boundaries between this module and its direct dependencies or higher-level consumers.

Describe the repository-selected Minecraft release and matching tool versions by role or owning selector, never by
copying their current literals. Do not promise planned behavior or infer target support from another module.

## Refactor without losing facts

Reorganize around the reader's likely path: identify the module, select it, complete a common task, then understand
constraints and advanced branches. Before deleting repetitive prose, determine whether it contains a unique contract
fact and move that fact to the canonical owner. Update inbound relative links when headings or locations change.

Keep prose compact, concrete, and free of marketing filler. Tables are useful for real comparisons such as module
selection or access modes; do not use them merely to decorate a short list. Avoid duplicating the same explanation in a
feature list, an example introduction, and a later reference section.

## Final review

Read the result as a new user and confirm that it answers, in order:

1. What is this and what is outside its scope?
2. When should I use this project or module?
3. What is the shortest correct example of its main value?
4. Which constraints directly affect my use?
5. Where should I go for details owned elsewhere?

Remove content that does not help answer one of these questions, except for minimal contributor setup required at the
repository or private-infrastructure level.
