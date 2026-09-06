---
name: minecraft-project-docs
description: "Audit or restructure this repository's AGENTS.md and README.md hierarchy against source, build scripts and tests. Use for stale API examples, ambiguous module boundaries, parent/child duplication, concise root documentation, or placement of durable rules versus optional skill workflows."
---

# Minecraft project documentation

## Establish the evidence

1. Inventory documents with `rg --files --hidden -g AGENTS.md -g README.md`, excluding generated, dependency and
   temporary trees. Compare Gradle settings with the documents present in actual subprojects, including buildSrc.
2. Read each target with its ancestors. Inspect its build script, public declarations and behavior tests before editing
   claims about targets, defaults, construction, mutability, absence, recovery or resource lifetime.
3. Check referenced snippets as caller code: every input must have an origin, overloads must resolve, resources must
   remain open while used, and packet examples must keep direction/state and coroutine ordering coherent.
4. Distinguish an inaccurate description from an implementation defect. Documentation work does not authorize changing
   runtime behavior merely to make proposed wording true.

## Give each fact one owner

| Content                                                                           | Owner                  |
|-----------------------------------------------------------------------------------|------------------------|
| Project purpose, distinctive capabilities, module choice and short entry examples | Root README            |
| Detailed public workflow, defaults, lifetime, failures and extension contract     | Owning module README   |
| Mandatory shared design or development invariant                                  | Root AGENTS            |
| Local invariant or verification consequence                                       | Owning module AGENTS   |
| Conditional inspection, research or audit procedure                               | Focused optional skill |

README and AGENTS serve different audiences: a boundary may need a user consequence in one and an implementation
constraint in the other. Within either hierarchy, link to its owner and keep only the local consequence. Before moving
text, preserve unique facts, check affected descendants and repair inbound links. Do not require a skill for normal
builds or place mandatory rules only in one.

Keep the root README easy to scan: identity, a few distinctive capabilities, module navigation, short examples and
build/demo links. Move complete connection loops and representation details inward. Other files need only headings
that help their own readers; parallel modules need not have identical outlines.

## Verify the edit

- Recheck changed prose against declarations and tests, including required context inputs and actual generated wiring.
- Compare ancestors/descendants and remove duplicate rules, API catalogues and repeated explanations around examples.
- Check local links and changed anchors, stale module/symbol names and copied release/tool version literals.
- Run `git diff --check`. Compile or test when a changed example or source edit needs it; prose-only edits normally do
  not require new tests. Report factual corrections separately from editorial changes.
