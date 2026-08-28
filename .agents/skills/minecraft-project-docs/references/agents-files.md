# Writing AGENTS.md files

An `AGENTS.md` is operational context for a future coding agent. It should reduce repeated exploration and prevent
plausible but incorrect changes within its directory tree.

## Select useful content

Include a fact only when it changes how an agent should inspect, implement, or verify work in this scope. Useful local
content commonly includes:

- the subproject's ownership boundary and the nearby layer that owns excluded behavior;
- stable domain vocabulary, representation stages, and business invariants;
- non-obvious dependency direction, public API constraints, and supported platform boundaries;
- a selective source/package map when it materially shortens discovery;
- handwritten versus generated ownership and the evidence used to update either side;
- local coding conventions that refine, rather than repeat, repository-wide rules;
- narrow verification commands and the conditions that require downstream or platform tests;
- traps where an apparently reasonable edit would violate lifecycle, compatibility, protocol, or persistence behavior.

Describe structure by responsibility, not as an exhaustive directory listing that will quickly become stale. Name exact
symbols or paths when they are stable navigation points or ownership markers.

Omit generic agent advice, system/tool policies, aspirational features, historical narration, and rules already supplied
by an ancestor. Do not copy build, dependency, Minecraft, Java, Gradle, Kotlin, or other selected version literals.
Avoid suppressing a useful local exception merely to make the nested file shorter.

## Make instructions actionable

Write direct, scoped instructions. State the condition and consequence when either is not obvious:

- Prefer `Keep X in module Y; callers in Z consume it through ...` over a vague request to preserve separation.
- Use `Run :module:jvmTest; also run :consumer:jvmTest when ...` instead of an undifferentiated test catalogue.
- Reserve `must`, `never`, and `only` for actual invariants. Express judgment calls as decision criteria.

Explain business rules at the level needed to implement them, including important state transitions, ordering,
nullability, ownership, or failure behavior. Do not turn the file into an API tutorial; link to a focused project skill
when a substantial conditional workflow already lives there.

Useful headings may cover purpose, local invariants, ownership, structure, evidence, and verification, but include only
sections with meaningful local content. A short module may need only its boundary, a few invariants, and one test
command.

## Refactor a hierarchy

Compare the target file with every ancestor and with affected descendants.

1. Classify each instruction as repository-wide, shared by a subtree, or local.
2. Move shared instructions to their narrowest common ancestor.
3. Remove descendant copies only after the parent wording fully preserves the rule.
4. Keep local exceptions beside the general rule's concrete consequence; make the exception explicit rather than
   silently contradicting the parent.
5. Resolve duplicate or conflicting instructions from source, build wiring, and tests. Do not select wording merely
   because it appears in more files.

After refactoring, an agent that receives the root file plus the nearest nested file should have the required shared and
local context without reading sibling guides.

## Final review

For every remaining paragraph, ask:

- Would this change a capable agent's decision?
- Is this the narrowest correct owner?
- Is it supported by current project evidence?
- Is the instruction specific enough to act on and stable enough to keep?

Delete or relocate text that fails one of these tests.
