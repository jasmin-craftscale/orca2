> **ARCHIVED — Phase 0 is built and verified.** This was the entry point for the
> Phase 0 build session, originally at the repository root. It is kept beside its
> brief and report for review; delete all three together when the review is done.
> The brief it references is now the sibling `phase-0-brief.md`.

# Kickoff — ORCA Phase 0

You are building the foundation of the ORCA platform rebuild. Everything you need is in this repository.

## Read first, in this order

1. **`phase-0-brief.md`** (beside this file) — what you build, in eight work packages. This is your instruction set.
2. **`docs/ORCA_ARCHITECTURE.md`** — the design. Read **§B10** first: it lists what the architecture guarantees and how each guarantee is verified. Those are your acceptance criteria.
3. **`docs/PLATFORM_PRIMITIVES.md`** — what each of the five primitives is for, the named pattern behind it, and the concrete case it serves.
4. **`docs/ORCA_OPEN_QUESTIONS_REGISTER.md`** — what is deliberately unsettled. Consult it before concluding something is missing by accident.

`docs/REPOSITORY_GUIDE.md` explains the folder layout if you want it.

## What you are building

A Gradle multi-project: **twelve modules, six bootable Spring Boot applications, and no business logic at all.** Five shared primitives, the build checks that enforce them, per-service migrations and contracts, a local stack, and CI.

**Each service owns its own schema, its own database login, its own migrations and its own OpenAPI contract.** Schema ownership is enforced by credentials, not by convention — proving that restriction is part of the work.

The repository currently holds a single Spring Initializr project — **Java 25, Spring Boot 4.0.7, `com.lynxis.orca`** — already committed. Your first package turns it into the multi-project and removes the generated application at the root.

## The outcome that matters

**When you are finished, the setup must actually run.** Not compile — run.

- `./gradlew build` from a clean tree.
- `docker compose up` gives SQL Server and Keycloak.
- The bootstrap creates seven schemas and seven logins; every service then migrates its own schema on startup, and re-running is a no-op.
- **All six services start at once**, each on its own port, each answering health.
- A token from Keycloak is accepted; a tampered one is rejected.
- **One service's login cannot read another's schema** — the attempt is refused.
- **Each of the five build checks fails the build when deliberately violated** — prove each one by breaking it, then revert.

§7 of the brief lists these as thirteen numbered checks. **Run them. Record what actually happened.** An unrun command is not a passing one, and writing a test is not evidence that it passes.

## How to work

- **Commit once per work package**, with a message saying what landed. Eight commits, not one.
- **Never invent a resolution to an unspecified question.** If the architecture is silent, that silence is information — leave it unbuilt and report it. A plausible guess written as working code is far harder to find later than a gap.
- **`platform/` holds primitives, never domain.** If a class there knows what a visit, a lane or a ticket is, it belongs in a service.
- **Tests must prove the property, not exercise the path.** "The outbox writes a row" is not a test. "Killing the process between the two writes leaves neither" is.
- **Build checks land with the code they govern**, never afterwards.
- **Copy dependency names from the committed `build.gradle.kts`, not from memory.** Spring Boot 4 renamed the starters — it is `spring-boot-starter-webmvc`, and each starter has its own paired `-test` artifact.

## When you are blocked

Do not improvise. §8 of the brief covers the cases. In particular:

- **A tool does not support Spring Boot 4** → stop, report it, name what it blocks. Do not hand-roll a replacement; that silently reverses a signed decision.
- **The architecture is silent on something you need** → check the register. If it is there, it is deliberately open. If it is not, report it as a gap.
- **A test is hard to write** → that is usually the design telling you something. Report it rather than weakening the test until it passes.

**Ask me when a decision is mine to make** — anything commercial, anything about scope, anything security-shaped. State the options and your recommendation, and wait.

## Report when done

Per §9 of the brief:

- What you built, package by package.
- **The §7 verification table with the real result of each item**, including anything you could not run.
- What you could not build, and why.
- Every decision you made that the architecture did not dictate, with your reasoning — these are what I need to review.
- Anything in the architecture that looks wrong or self-contradictory. Report it; do not silently correct it.

**A gap reported is worth more than a gap filled with a guess.**

Start with Package 1.
