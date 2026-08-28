---
name: minecraft-project-docs
description: Write, restructure, or audit hierarchical AGENTS.md and README.md files in this repository. Use for project or subproject agent guidance, human-facing module documentation, parent/child documentation boundaries, duplicate removal, or alignment of these files with current source, build wiring, and tests. Do not use for API reference or general prose unrelated to AGENTS.md and README.md.
---

# Minecraft project docs

Maintain concise, source-derived documentation whose detail increases toward the owning subproject. `AGENTS.md` teaches
an agent how to develop correctly; `README.md` teaches a human what the project provides and how to use it. Do not blend
the two audiences.

This skill is optional guidance, not project evidence. Treat checked-in source, build wiring, tests, and the applicable
`AGENTS.md` files as authoritative. When they disagree with this skill, correct the skill instead of changing the
project to satisfy it.

## Load the relevant guide

- For any `AGENTS.md` task, read [references/agents-files.md](references/agents-files.md) completely.
- For any `README.md` task, read [references/readme-files.md](references/readme-files.md) completely.
- Read both when creating, comparing, or reorganizing both document types.

## Establish scope and evidence

1. Inspect `git status --short` and preserve unrelated work.
2. Read the root `AGENTS.md` and every nearer `AGENTS.md` governing each file to be changed. Read the relevant parent
   and target `README.md` files so the new text has the right level of detail.
3. Inspect the owning build script, public source, tests, generated-source wiring, and directly related sibling modules.
   Use `rg` and `rg --files` to locate declarations and existing explanations. A current README or skill may guide
   discovery but is not sufficient evidence for a claim.
4. Establish the current public behavior, ownership boundary, prerequisites, defaults, resource lifetime, and relevant
   verification commands before drafting. When release-specific evidence is necessary, resolve the selected release
   through `./gradlew -q minecraftVersion`; never copy its current literal into documentation.

For a documentation-only request, do not change implementation or build wiring merely to make proposed prose true.
Document the current contract or report the mismatch. Broaden the edited file set only when moving content is necessary
to complete an explicitly requested hierarchy refactor.

## Place each fact at one level

Treat `README.md` and `AGENTS.md` as separate audience hierarchies. Within each hierarchy, use the narrowest document
whose scope covers every place where the fact applies:

- Repository-level files explain the overall purpose, module map, shared conventions, and cross-project workflows.
- Subproject files explain only that subproject's public contract or local development rules.
- A nested file adds detail or an exception; it does not restate an ancestor in different words.
- A directory that merely groups subprojects does not duplicate its children's guides. Every actual Gradle subproject,
  as established by Gradle settings and build wiring, keeps its own `README.md` and `AGENTS.md`, as required by the root
  guide.

The same underlying boundary may belong in both document types when both audiences need it. Explain its user-visible
effect in the README and its ownership or maintenance consequence in AGENTS.md; do not copy the same paragraph between
them.

When local understanding needs parent context, link to the canonical owner and state only the local consequence. When
moving a rule outward, verify every affected descendant before deleting its old copies. When moving detail inward,
ensure no remaining sibling still depends on it. Resolve contradictions from source and tests instead of preserving both
formulations.

## Draft for decisions, not coverage

Prefer a short document that answers the audience's next real questions. Do not impose a fixed heading template or
repeat content merely to make parallel modules look symmetrical. Match the repository's existing documentation language
and terminology unless the user requests otherwise. Use the representation-stage and module names established by the
root guide.

Describe only current behavior. Refer to release and tool versions through their owning selector or role. Stable
protocol identifiers, format revisions, status codes, and example addresses may remain literal when they are part of the
contract.

Update a README when a public capability, entry point, prerequisite, default, or user-visible constraint changes. Update
AGENTS.md when ownership, development invariants, evidence, or verification workflow changes. An internal edit that
changes neither does not require documentation churn.

## Verify the result

- Re-read every changed file together with its ancestors and remove repetition, contradictions, promises, and details
  owned elsewhere.
- Check claims, symbol names, signatures, defaults, target availability, paths, and commands against authoritative
  project files. Confirm relative links and any anchors changed by the edit.
- Check that examples introduce every value before use and visibly preserve required lifecycle, coroutine, and failure
  semantics.
- Run `git diff --check`. Run a narrow compile or test task only when an example or behavioral claim cannot be validated
  confidently by inspection; never run Gradle wrapper invocations concurrently.
- Report the documents changed, important placement decisions, and verification performed.
