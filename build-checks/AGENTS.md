# `build-checks/` — the enforcement

**Tests only. No production code. The entire output of this module is build
failures.** It is also the reason this is a monorepo: these rules have to see all
twelve modules at once, and a service reaching into another service's internals is
invisible from inside either one.

Ten classes, run by `./gradlew check`: `PlatformPurityRule`, `ModuleWallRule`,
`ScopeSeamRule`, `ErrorEnvelopeRule`, `RetentionClassRule`, `SystemContextRule`,
`EngineConfinementRule`, `ScopeIndexRule`, `ContractInterfaceRule`,
`InternalSurfaceRule`, and `ImportedSetGuard` — the check on the checks.

**Two of them do not read bytecode**, and that is deliberate rather than a
shortcut. `ScopeIndexRule` reads the Flyway migrations, because an index definition
leaves no trace in a `.class` file and the trap it guards (a scoped table with no
index leading with the scope column → a scan → an `UPDLOCK` over every row at the
site) is invisible from Java. `InternalSurfaceRule` reads the authored OpenAPI
documents, because contract-first means the document is the surface and a route's
path is decided there. Both go through `RepositoryFiles`, and **both assert a floor
on what they read** — a rule that read no files passes exactly like a rule that saw
no classes.

## A new rule does not count until it has been watched to fail

Write it, then **introduce a deliberate violation, watch the build stop, and
revert.** This is not ceremony. This repository's own history is the argument:
`PlatformPurityRule`'s word matcher required a non-letter after the match, so
`VisitResponse` — the single most likely violation there is — did not match. The
rule passed, permanently and silently, until somebody tried to break it. Fixed in
`ac0eb0d`, and the matcher now has its own test with must-fire and must-not-fire
cases.

Every rule here is of the form *"no class should…"*, and **a rule that sees no
classes passes**. That is indistinguishable from a rule that is not wired in.

## Two obligations that follow from that

**Register what a new rule governs with `ImportedSetGuard`.** It asserts the
importer saw a floor of classes, names every module that must be visible, and
counts the six `HealthController`s. A module missing from
`build-checks/build.gradle.kts` is a module whose violations are simply not seen —
this is what catches it.

**An empty governed set is declared and recorded, never left implicit.** ArchUnit
fails a rule that checked nothing, which is the right default and the wrong answer
when the rule must land before the code it governs. Where that applies, the rule
carries `allowEmptyShould(true)` **and** the emptiness is recorded — today in
`ImportedSetGuard.whatIsStillEmptyIsStated`, which fails the day a runtime module
gets its first class so the exemption cannot outlive its reason.

Currently empty in Phase 0: both module-wall tests over `orca-runtime`'s five
modules, `RetentionClassRule`'s `@Entity` test (no JPA entities yet), and
`SystemContextRule` (no `@Scheduled` methods yet). Phase 1 populated
`execution` and `integration`; `ImportedSetGuard.whatIsStillEmptyIsStated` records
exactly which three remain.

**The three rules added in H1 were each watched to fail before they were
committed**, and what each violation was is written in
`docs/phase-1-hardening-report.md` §H2 rather than left as a claim: a scoped table
whose only index led with the wrong column; a `@RestController` implementing
nothing; and an operation tagged internal but authored one level above
`/internal/`, plus its mirror image.

## What the rules see

`OrcaClasses.production()` imports `com.lynxis.orca` with tests excluded — a test
may legitimately construct a query outside the seam to prove the seam withheld
something. **Jars are deliberately *not* excluded:** in a Gradle multi-project the
other eleven modules arrive on this classpath as jars, and excluding them would
leave the importer with almost nothing and every rule passing vacuously.

Write the rule against what it must enforce, not what it is named. `ModuleWallRule`
carries three tests — `persistence`, `domain`, and service-to-service imports —
and its name suggests one.
