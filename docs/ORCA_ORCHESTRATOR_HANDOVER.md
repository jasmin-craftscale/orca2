# ORCA — Orchestrator Handover

**Read this first. It is the entry point for a new session working on the ORCA rewrite programme.**

You are picking up an engagement that has been running for some weeks. The architecture is written and signed, **four build phases are complete and independently verified** (§9), and there is a register of things deliberately left open. **Your job is to help the product owner run the programme — not to redesign it.**

**Concretely, you will be asked to:** prepare the next phase (extract the 1.x reference → write the plan → hand the product owner a kickoff prompt) · review what each build session produces and find what its own report missed · verify by executing, never by trusting a report · work the open questions toward decisions · keep the document set honest as things change · and fact-check claims against the existing codebase when they matter, including for client-facing documents.

**You work in both codebases.** `~/Documents/Projects/orca` is yours to work in — review, extend, debug, and write code. `~/Documents/Projects/lynxis/Lynxis-Gate` is **read-only for analysis**: it is the production system being replaced, and it is the evidence base for everything about 1.x.

⚠️ **One timing boundary.** While a build session is running in `~/Documents/Projects/orca`, stay out of that repository — concurrent edits conflict on nearly every commit. When it stops, the repository is yours again; that is when you verify.

**When you write code there:** the ground rules are in `AGENTS.md` and each phase plan's §2 — `platform/` holds no domain types, tests prove properties rather than exercise paths, and build checks land with the code they govern. **Ten build checks enforce them; they fail the build.**

**Onboard yourself properly before acting.** §7 tells you what to read and in what order; **§10 tells you how to go deep into both codebases** — the method, the load-bearing code, and what a delegable implementation plan looks like. Do not act on this handover alone; it is a map, not the territory.

---

## 1 · What ORCA is

A gate-automation platform for logistics facilities — container terminals and distribution centres. Cameras read truck plates, customer-designed processes orchestrate devices and external systems, work that automation cannot finish is routed to a clerk, and carriers pre-announce visits through a driver portal.

Two properties shape every decision:

**The gate must keep working when other things do not.** A truck at a barrier is a physical queue. If the platform stops, the queue grows into the public road.

**The site's processes belong to the site.** Terminals do not run the same process. Processes and screens are designed by the customer's own administrators, visually, without code.

---

## 2 · Two repositories

| Path | What it is |
|---|---|
| `~/Documents/Projects/lynxis/Lynxis-Gate` | **The existing system**, in production today — 25 Go microservices, 4 React applications, SQL Server. Also holds the full document corpus for the rewrite. **Read-only for analysis. Never modify production code here** |
| `~/Documents/Projects/orca` | **The new build.** Spring Boot 4.0.7 / Java 25, trunk branch `phase-0-foundations` (kept by PO ruling — there is no `main`). **Four phases built and verified**: foundations, the gate path, the configuration world, the clerk workflow. See §9 |

---

## 3 · The existing system, and where it falls short

You will be asked to verify claims against this codebase. It is the evidence base, and it is frequently the only reliable source — several documents in the corpus have been wrong about it.

**What it is:** 25 Go microservices (Gorilla Mux, GORM), four React applications, SQL Server, a dedicated Kafka virtual machine per customer site, Keycloak for identity.

**How to navigate it:** `Lynxis-Gate/CLAUDE.md` is the operating manual for that repository — the service layout, the multi-module Go structure, the conventions that cause real bugs if missed (soft-delete filters, singular table names, dual int/UUID keys, per-engine migrations), and its security rules. **Read it before searching that codebase**, or you will misread what you find.

**Where it falls short** — each of these is what a corresponding decision in the target exists to fix:

| Limitation | Consequence |
|---|---|
| The **continuation** of a visit is held in a running process — a bare goroutine per step, with no lock, lease or claim anywhere | A restart abandons every in-flight visit: the step position survives in the database, but nothing scans for it and no timer survives at all. The message that triggered the work is acknowledged *before* it is executed, so it is never redelivered. A second server does not fix it — the two share a database and a consumer group, so it can resume the work, but with no lock it will just as happily start a **duplicate** execution ⚠️ *Corrected 9 Aug 2026 — the previous wording, "a second server cannot see the work", is not what the code does; see `docs/partner-event-api-from-1x.md` for the sweep that found it* |
| Tenant scoping is **roughly 816 hand-written conditions** | Forgetting one is a cross-tenant leak. There is no single place to fix it |
| Creating a work item and advancing the process are **two cross-service HTTP calls** | Either can half-apply; the console and the engine can disagree about what happened |
| A **Kafka VM per site** carries only point-to-point messages | A server to provision, patch and monitor at every site, for delivery a database table can provide |
| Device commands are **sent and assumed** | A command whose outcome is unknown is treated as done |
| Editing a screen **changes every in-flight execution instantly** | An administrator's edit at 14:00 changes what a truck mid-process sees. There is no rollback |
| The screen renderer exists in **four diverged copies** | "Every screen renders identically" cannot be checked, and is already false |
| Purge **orphans tickets** | Tickets are neither archived, purged, nor foreign-key constrained. Enabling purge leaves dangling references, silently |
| Retention is **inverted** | Extracted scan data is deleted; raw inbound payloads are kept forever |

⚠️ **There is also a private security document** — `docs/ORCA_SECURITY_FINDINGS_PRIVATE.md` in Lynxis-Gate. Five code-verified findings in the current system, including an unverified licence signature and an unauthenticated listener carrying key-rotation messages. **It is for the product owner and the technical lead only. Never include it in anything circulated, and never repeat its contents into a document with wider distribution.**

---

## 4 · The target, in one page

**Seven Java 25 / Spring Boot 4 services on Microsoft SQL Server, in one repository, deployed as six bootable applications** (the seventh, media, is inherited and not rebuilt).

| Service | Owns |
|---|---|
| `orca-core` | The world as configured — sites, lanes, users, devices, workflow and screen design |
| `orca-runtime` | The world as it happens — the process engine, visits, work items, connectors |
| `orca-edge` | Every hardware contract, the capture buffer, the command log |
| `orca-portal` | Carriers, drivers, tickets — the only internet-facing service |
| `orca-sync` | Replication between a site and a hosted tier |
| `orca-fleet` | Licence issuance and signing — cloud only |
| `orca-media` | Video and intercom — inherited, unchanged |

**The load-bearing choices:**

- **Embedded Flowable executing BPMN 2.0.** Administrators author in ORCA's own visual builder; each published process is **compiled to BPMN at publish**. The compiler is permanent runtime infrastructure, not migration tooling.
- **No message broker inside a site.** A transactional outbox, claimed with a skip-locked read.
- **Multi-instance by design.** Coordination is database-held with leases and fence tokens. Device ingestion elects one owner per lane, because cameras address a single endpoint.
- **One installation serves exactly one customer.** Cross-customer data (carriers, drivers) is cloud-authoritative.
- **Five shared primitives** — outbox, lease, scope, idempotency, web envelope — built once, before any service.

---

## 5 · Decisions that are settled — do not reopen these

Taken by the product owner on 6 August 2026 unless noted. Each cascaded further than it looks; the register records the consequences.

| Decision | Note |
|---|---|
| **ORCA 2.0 ships to new clients only. No migration, ever** | New clients do not run ORCA today. This voided every conversion, cutover and re-authoring premise, and **unfroze five of the nine frozen contracts** |
| **Keep the ORCA workflow builder; compile BPMN behind it** | Reverses the authoring half of ADR-006. The engine half stands |
| **Microsoft SQL Server only. PostgreSQL deferred** | Keep the engine seam; build no second implementation |
| **Keycloak stays** | With three trims: settle decision 7, de-fork the themes, drop the unused jar |
| **React for the console** | Closed earlier |
| **Availability: sell the arrangement, commit RTO/RPO after measurement** | A number invented before the device-retry characterisation becomes contractual |
| **Java 25, Spring Boot 4.0.7, Gradle Kotlin DSL, `com.lynxis.orca`** | Java 25 is LTS; a non-LTS JVM is unsuitable for an unattended appliance |
| **Each service owns its own migrations** | A schema defined outside the service that owns it is not owned by it |
| **Contract-first OpenAPI, interfaces generated** | Controllers implement generated interfaces, so a contract change breaks the build |

---

## 6 · What is open

`docs/ORCA_OPEN_QUESTIONS_REGISTER.md` — **25 items**, each with a problem statement grounded in code or the architecture, and a recommendation.

The ones that gate work:

- **NEW-1a** — the driver portal holds cross-customer data on the only internet-facing service, with no scoping mechanism designed. **Blocks portal work.**
- **NEW-1b** — whether the cloud tier is one instance per customer or one shared instance. Decides the whole tier's shape.
- **Spike 1** — whether Flowable can start exactly one process when two device events for the same truck arrive simultaneously. Gates the engine decision, and therefore runtime.
- **NEW-2** — who owns ORCA 1.x. Existing customers stay on it, and it has a documented security posture and no maintainer.
- **The frontend is unstaffed.** Two React applications, the kiosk mode and both builders are not among the seven services and are not covered by four backend developers.

---

## 7 · Onboard yourself in this order

**The document set is deliberately small — four documents in `Lynxis-Gate/docs/`.** Everything else has been deleted rather than left to rot, because a superseded document is worse than a missing one: an agent reads it and believes it.

1. **`Lynxis-Gate/docs/ORCA_ARCHITECTURE.md`** — the specification, and the only account of the target. ~19,000 words, Parts A–D. **Read §B10 first** (what the architecture guarantees and how each is verified); it is the shortest route to the design's intent.
2. **`Lynxis-Gate/docs/ORCA_OPEN_QUESTIONS_REGISTER.md`** — what is deliberately unsettled. **The least recoverable document in the set:** without it you cannot tell *unspecified because undecided* from *unspecified because forgotten*, and guessing at that difference is the most repeated failure of this engagement. Consult it before concluding anything is missing by accident.
3. **`Lynxis-Gate/docs/ORCA_SECURITY_FINDINGS_PRIVATE.md`** 🔒 — five code-verified findings in the existing system. **Product owner and technical lead only.**
4. **This handover** — the map.

**In `~/Documents/Projects/orca/docs/`**, for the build rather than the programme. Read these in this order — they are how you learn what ORCA 2.0 actually *is* today, as opposed to what it was designed to be:

5. **`README.md`** (repository root) — the front door: what it is, how the gate path works, how to run it locally.
6. **`AGENTS.md`** (root, plus the nested ones in `platform/`, `services/orca-runtime/`, `build-checks/`) — the rules that are **enforced by ten build checks**. They bind you as much as any build agent.
7. **The four phase reports, newest first** — `phase-3-report.md`, `phase-2-report.md`, `phase-1-hardening-report.md`, `phase-1-report.md`, then `phase-0-report.md`. **These are the real state of the build.** Each carries: what was built, what was *not* (named gaps), every decision the plan did not dictate, what was found wrong, and an adversarial review addendum. Read each one's "decisions the plan did not dictate" and "found wrong" sections — that is where the value is.
8. **`REPOSITORY_GUIDE.md`** (layout) · **`PLATFORM_PRIMITIVES.md`** (what each primitive prevents) · **`deployment.md`** (local dev and the production gap) · **`phase-1-demo.md`** (drive a truck through the gate — the standing regression canary).

**For the existing system — ORCA 1.x — you need both the manual and the extractions:**

- **`Lynxis-Gate/CLAUDE.md`** — the operating manual for that codebase: service layout, the multi-module Go structure, the conventions that cause real bugs if missed. **Read it before searching there**, or you will misread what you find.
- **The DERIVED-FROM-1X reference sheets in `orca/docs/`** — code-verified extractions of what 1.x actually does, produced when each phase needed them. They are the accumulated knowledge of the old system, and they carry both the facts *and* the defects deliberately not carried forward:
  - `lpr-wire-format-from-1x.md` — the camera protocol (STX/ETX ZapPacket), and the three 1.x behaviours 2.0 refuses to copy.
  - `device-host-outbound-from-1x.md` — the barrier/print/IO commands, and **§3, the open vendor question (register NEW-4)**.
  - `core-config-schema-from-1x.md` — the config tables, with **§0's eleven translation rules** that govern any further 1.x→2.0 work.
  - `entitlement-catalog-from-1x.md` · `device-catalog-completion-from-1x.md` — the exact seed rows, script-extracted.
  - `work-items-schema-from-1x.md` — the clerk workflow, and **§0's three behaviour inversions** where 2.0 deliberately reverses 1.x.

**The pattern to continue:** when a phase needs to know what 1.x does, extract it read-only into a new `*-from-1x.md` sheet with file:line evidence, state the translation rules, and let the architecture govern behaviour. Never port 1.x blindly; never guess what it does.

⚠️ **Everything else has been deleted, and that was deliberate.** The earlier corpus included a superseded architecture, a delivery plan, a technical design spec and a data dictionary, all of which had drifted from the decisions of 6 August — the design spec contradicted them in 44 places. **If you find a reference to a document that no longer exists, the document was removed, not lost.** Do not reconstruct it; the architecture and the register carry what survived.

⚠️ **The schema of record is the migrations**, not a document. Once Phase 0 lands, each service's own migration files define its schema. There is no separate data dictionary to keep in step, and that is intentional.

## 8 · How to work on this programme

These are not preferences. Each was learned by getting it wrong.

**Never invent a resolution to an open question.** The recurring failure across this engagement has been filling a gap with something plausible and writing it as settled design. It happened at least four times — an invented offline-redemption mechanism, invented service modules, an invented tenancy resolution, invented API endpoints. Every one read well and every one was wrong. **If something is unspecified, say so and leave it. A gap reported is worth more than a gap filled.**

**Verify against code, not against documents.** Several corpus documents have been wrong about the existing system, and one wrong claim propagated into four documents before it was caught. When a claim matters, check the source.

**Trace both directions before asserting a negative.** The most damaging error in this engagement was "no hardware fingerprint exists anywhere" — derived from tracing the issuance path only. The enforcement lived in a different service. Before writing *"X does not exist,"* trace the consumption path as well as the production path.

**Do not pattern-match prose with regular expressions.** Every defect introduced into the architecture document came from a bulk regex edit — mangled sentences, converted cross-references, silently reintroduced claims. Edit specific lines.

**Verify by executing, not by asserting.** Writing a test is not evidence it passes. A build check nobody has watched fail may not be wired in. A rendered document can contain a broken diagram that no file check will see.

**When compressing, count what leaves.** A 56,000-word architecture document was reduced to 10,000 — most of it legitimately, but it also silently dropped all 168 API endpoint rows and 16 of 24 diagrams, leaving something comprehensive on architecture and useless to a developer building a service. Distinguish *removed because obsolete* from *removed because compressing*.

**Ask the product owner when a decision is theirs.** Commercial questions, scope, staffing, what to promise a client, and anything security-shaped are not yours to settle. State the options and the trade, give a recommendation, and wait.

---

## 9 · Where things stand right now (updated 9 August 2026 — through Phase 3)

**Four phases are built and independently verified**, all on `phase-0-foundations` (kept as the trunk by product-owner ruling — do not create a `main`). Each was built by a focused agent session and then re-run and re-driven by the orchestrator — **the discipline is verify-by-executing, never trust the report**; continue it.

| Phase | Delivered | Report |
|---|---|---|
| **0 · Foundations** | 12 Gradle modules, 6 bootable services, 5 primitives, the build checks | `docs/phase-0-report.md` |
| **1 · The gate path** | plate → durable buffer → **exactly one visit** (1,000×) → TOS call → confirmed barrier → outbox fact; then a hardening pass | `docs/phase-1-report.md`, `docs/phase-1-hardening-report.md` |
| **2 · World as configured** | orca-core config world: identity + entitlement catalog, teams, device registry, settings — translated from 1.x, not copied | `docs/phase-2-report.md` |
| **3 · Clerk workflow** | the `MANUAL` branch made real: work item created in the engine transaction, completion advances the process atomically, SLA a real engine timer | `docs/phase-3-report.md` |

**Build checks are now ten** (platform purity, module walls, scope seam, error envelope, retention class, system context, engine confinement, scope-leading index, contract interface, internal surface) plus `ImportedSetGuard`. `./gradlew check integrationTest` is full verification; plain `test` skips the property suites. The stack and demo tools run in containers (`docker compose run --rm bootstrap`), so a Windows host needs only Docker + a JDK; `docs/deployment.md` is the run guide, `docs/phase-1-demo.md` the truck-through-the-gate walkthrough (the standing regression canary for every later phase).

**Decisions locked since this handover was first written** (all in the register / architecture): ADR-001 Java 25 / Boot 4; ADR-011 Keycloak authenticates people, services carry a per-installation shared credential on `/internal/**`; NEW-1b the cloud tier is per-customer and the programme is **on-site first** (portal/sync/fleet stay skeletons); **licensing (register item 20) = a concurrent-instance limit enforced by the database lease, not machine-binding, with hardware identity as heartbeat telemetry** (architecture §B6/§C6). Spike 1 was folded into Phase 1 WP0 and passed.

### What comes next

**The current goal is the full on-site backend** (product-owner direction). Remaining phases, none blocked on anything external, prepped the same proven way (extract the 1.x reference sheet → write `phase-N-plan.md` → a focused build session → orchestrator verification):

- **Phase 4 — partner event API & integration breadth** (the inbound external-event → workflow path, `event_dispatch`, the frozen partner endpoints, connector breadth). The recommended next build; more CRUD-shaped than Phase 3's engine internals.
- **Phase 5 — read models & notifications** · **Phase 6 — retention/purge jobs** · **Phase 7 — core remainder** (custom entities + DDL executor, the licensing-verification module). Then the on-site backend is complete.
- **The deployment phase** (installer, secrets provisioning, release images, licensing) is its own plan later — blocked on the vendor answer and a hosted repo. `docs/deployment.md` Part 2 is its backlog.

**Standing items that are the product owner's, and should not slip behind the build:**

- **Host the repository.** Everything is on one laptop and CI has never run — the cheapest risk with the biggest downside.
- **The vendor call (register NEW-4).** Does the .NET device host validate the `Authorization` token on the barrier command? It blocks the first real device host; the brief is `docs/device-host-outbound-from-1x.md` §3.
- **The frontend is unstaffed** — two React apps, the kiosk, both builders. Nothing can be demoed to a customer without it.
- **The builder-developer** owns the BPMN service-task boundary-timer question (`docs/BPMN_EXECUTION_PROFILE.md` §8) and should see the profile.

### For a fresh orchestrator session

Everything durable is in three places: this document (the map), the phase reports and reference sheets in `~/Documents/Projects/orca/docs/` (the detail), and git history. The orchestrator's own working memory (the `orca-rewrite-initiative` ledger) loads automatically and is current through Phase 3. Onboard via §7, then go deep per §10 before advising on anything.

---

## 10 · Going deep — the understanding this role actually needs

§7 gets you the documents. **The documents are the design; the code is the truth**, and this programme has been wrong about its own systems more than once. You are expected to reach the understanding of a senior engineer *and* a senior architect on both systems — able to answer "how does this actually work?" from the code, not from a summary. That is achievable, but only if you go deep **strategically**: ORCA 1.x alone is roughly 556,000 lines of Go across 25 services, and no one reads that end to end.

**The method that works, in order:**

1. **Run it before you read much of it.** An hour of executing teaches more than a day of reading. From `~/Documents/Projects/orca`: bring up the stack and bootstrap (`docs/deployment.md`), run `./gradlew check integrationTest --rerun-tasks`, boot the three gate-path services, then drive a truck through (`./gradlew sendPlate`) and follow it in the database. Then force the exception branch and take a work item through claim → complete. You now understand the product's spine from the outside.
2. **Read the load-bearing code directly** — this is the short list that carries the design:
   - `platform/` — the five primitives. Start with `platform/outbox` and `platform/scope`; they are the two everything else leans on.
   - `build-checks/src/test/java/com/lynxis/orca/checks/` — **ten rules; read all of them.** They are the architecture written as executable constraints, and reading them tells you what the codebase will and will not permit.
   - `services/orca-runtime/src/main/java/com/lynxis/orca/runtime/execution` (admission, the engine gateway, delegates) and `.../workitem` (the clerk lifecycle) — the two hardest pieces of domain logic.
   - `services/orca-runtime/src/main/resources/processes/gate-visit.bpmn20.xml` — the process the whole platform exists to run.
   - The property suites under `services/orca-runtime/src/integrationTest/` — `AdmissionPropertiesIT`, `WorkItemLifecycleIT`, `WorkItemSlaIT`. **In this codebase the tests are the specification**; they state the guarantees as executable claims.
3. **Sweep breadth with read-only agents, not by reading everything.** For questions spanning many files or the whole 1.x estate, dispatch explore/search agents that return conclusions and `file:line` anchors rather than file dumps. That is how every DERIVED-FROM-1X sheet in `orca/docs/` was produced.
4. **For ORCA 1.x, read with `CLAUDE.md` open.** Its conventions (soft-delete filters, singular table names, dual keys, per-engine migrations) are the difference between reading that code and misreading it. Trace both the production *and* the consumption path before asserting that something does not exist — the most damaging error in this engagement came from tracing only one direction.
5. **Write down what you learn, once, where it survives.** A new fact about 1.x becomes a `*-from-1x.md` reference sheet with evidence; a decision becomes a register row; a correction to the corpus becomes an edit to the architecture. Nothing important should live only in a conversation.

**What "good" looks like:** you can explain why the outbox exists and what breaks without it; why a query cannot escape its scope; why admission is one atomic operation and what proves it; why a service-task timer cannot fire but a wait-state timer can; and where ORCA 2.0 deliberately reverses ORCA 1.x rather than copying it.

### Implementation plans that a developer can take

The programme's output is increasingly **plans other people execute** — human developers, not only build sessions. Every phase so far followed one shape, and it is the shape to keep (`docs/phase-2-plan.md` and `docs/phase-3-plan.md` are the worked examples):

1. **Extract the reference first.** If the work touches anything ORCA 1.x does today, produce the DERIVED-FROM-1X sheet before the plan: real columns, real behaviour, `file:line` evidence, the defects deliberately not carried forward, and the translation rules that govern the port. **A plan without its reference sheet invites guessing.**
2. **Write the plan self-contained** — its reader may have none of your context. Include: what the phase delivers and what it explicitly does *not*; the ground rules; **work packages in dependency order**, each with its own "done when"; a verification table of commands to execute; what to do when blocked; and the report the work must end with.
3. **Make each work package delegable.** One concern, its own migration/code/tests together, acceptance criteria a reviewer can check, and an explicit statement of what it must not touch. Sequential dependencies named, so packages that can run in parallel are visible.
4. **State where behaviour comes from.** For anything ported: *1.x is the reference for the data; the architecture governs the behaviour.* Name the inversions explicitly, or they will be copied.
5. **Anything security-shaped, commercial, or scope-changing is the product owner's** — the plan says PROPOSE-and-report, never implement.
6. **Close the loop.** When the work lands, verify it yourself by executing: re-run the suite, re-drive the truck, break a check to prove it still fails. A report is a claim until it is re-run.
