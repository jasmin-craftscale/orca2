# ORCA — Orchestrator Handover

**Read this first. It is the entry point for a new session working on the ORCA rewrite programme.**

You are picking up an engagement that has been running for some weeks. The architecture is written and signed, the first build phase is about to start, and there is a register of things deliberately left open. **Your job is to help the product owner run the programme — not to redesign it.**

**Concretely, you will be asked to:** review what the build agent produces and find what its own report missed · run or supervise Spike 1 · work the open questions toward decisions · keep the document set honest as things change · and verify claims against the existing codebase when they matter.

**You will also work in the new codebase** at `~/Documents/Projects/orca` — reviewing what the build agent produced, extending it, debugging it, and eventually writing service code as Phase 1 begins. Treat it as yours to work in.

⚠️ **One boundary, and it is about timing rather than permission.** While the Phase 0 build session is running, stay out of that repository — it is being restructured wholesale and concurrent edits conflict on nearly every commit. Once Phase 0 has landed and been reviewed, the constraint lifts.

**When you do write code there:** `docs/phase-0-brief.md` §2 carries the ground rules, and they apply to you too — `platform/` holds no domain types, tests prove properties rather than exercise paths, and build checks land with the code they govern.

**Onboard yourself properly before acting.** §7 tells you what to read and in what order. Do not act on this handover alone; it is a map, not the territory.

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
| `~/Documents/Projects/orca` | **The new build.** Spring Boot 4.0.7 / Java 25, branch `phase-0-foundations`. **Phase 0 is built** — twelve Gradle modules, six bootable applications, five primitives, the build checks, per-service migrations and contracts. See §9 |

---

## 3 · The existing system, and where it falls short

You will be asked to verify claims against this codebase. It is the evidence base, and it is frequently the only reliable source — several documents in the corpus have been wrong about it.

**What it is:** 25 Go microservices (Gorilla Mux, GORM), four React applications, SQL Server, a dedicated Kafka virtual machine per customer site, Keycloak for identity.

**How to navigate it:** `Lynxis-Gate/CLAUDE.md` is the operating manual for that repository — the service layout, the multi-module Go structure, the conventions that cause real bugs if missed (soft-delete filters, singular table names, dual int/UUID keys, per-engine migrations), and its security rules. **Read it before searching that codebase**, or you will misread what you find.

**Where it falls short** — each of these is what a corresponding decision in the target exists to fix:

| Limitation | Consequence |
|---|---|
| Work in progress is held **in the memory of a running process** | A restart abandons every in-flight visit. A second server cannot see the work, so more than one server is impossible |
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

**In `~/Documents/Projects/orca/docs/`**, for the build rather than the programme: `phase-0-brief.md` (what the build agent did), `PLATFORM_PRIMITIVES.md` (what each shared primitive prevents, and the named pattern behind it), `REPOSITORY_GUIDE.md` (the repository layout), `phase-0-report.md` (what was built and verified). Phase work artifacts sit flat in `docs/` with their phase prefix — future phases follow the same pattern (`docs/phase-1-plan.md`); `phase-0-kickoff.md` was the build session's entry point.

**For the existing system:** `Lynxis-Gate/CLAUDE.md` — the operating manual for that codebase.

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

## 9 · Where things stand right now

**Phase 0 is built.** Eight work packages committed on `phase-0-foundations`, plus uncommitted work on top. What exists:

| | |
|---|---|
| Gradle modules | **12** registered — five primitives, six services, `build-checks`. Root is an aggregator with no `src/` |
| Bootable applications | **6** |
| Build-check rules | **6** — platform purity, module walls, scope seam, error envelope, retention class, **and a system-context rule the brief did not ask for** |
| Migrations | **13 files** — 4 bootstrap (`deploy/bootstrap/`), 3 platform-primitive, 6 service baselines, each service against its own schema |
| Contracts | **7 authored** — one per service, plus the shared envelope `_shared.yaml` in `platform/web` |
| Bootstrap · compose · CI | all present |

**After the eight packages, three more commits landed:** the `NON_NULL` envelope-serialisation fix (`5bd278e` — *"one shape on paper, two on the wire"*), the **ADR-011 narrowing** (`c4730b1`, a product-owner decision: Keycloak authenticates people only; services present a per-installation shared credential on `/internal/**`), the **§9 report** (`3b0bc3d` — now at `docs/phase-0-report.md`, read it before the code), and a review pass (`a11b8ec`) that closed a real loophole in the envelope rule (`ResponseEntity<Object>` passed the check) and removed dead springdoc.

### Verification — executed 7 August 2026, independently of the build session

The brief's rule was verify before trusting. Done, by the orchestrator session, against a moving-free tree:

1. **`./gradlew build`** — green.
2. **`./gradlew integrationTest --rerun-tasks`** — green: 31 property tests against real SQL Server via Testcontainers, including kill-between-writes on the outbox, the 16-way lease race with fence monotonicity, default-deny scope, and the 32-way idempotency race.
3. **All six build-check rules proven to fire** — one deliberate violation each, in throwaway copies, each failing the build with a message naming the violating class: platform purity (caught `VisitResponse`, the exact camelCase compound the pre-`ac0eb0d` matcher missed), module walls (caught all three dependency edges into another module's `persistence`), scope seam (Spring Data interface), error envelope (`ResponseEntity<String>` — confirming the `a11b8ec` loophole is closed), retention class, and system context (a naked `@Scheduled` method, with a green control run after removal).
4. **The primitives' tests were read for property-versus-path**: six of eight suites are genuinely property-style; the four strongest live in `src/integrationTest/`, so plain `./gradlew test` does not run them — use `check` plus `integrationTest`.

**Still open, deliberately:**

- **Six services on their committed ports** was verified only on a +10000 offset — this machine runs the ORCA 1.x devcontainer on exactly 8081–8086. The CI local-stack job (bootstrap twice, prove credential isolation) is the standing cover; a literal-ports boot needs a machine without the 1.x stack.
- **`ImportedSetGuard` has no vacuity tripwire for the system-context rule** — it registers emptiness for runtime's module packages but not for "no `@Scheduled` exists yet". The rule is proven to fire, but a guard entry mirroring `whatIsStillEmptyIsStated` would make its empty set visible. Small, worth adding.
- **Gradle materialises phantom `:platform` and `:services` container projects** from the nested include paths — two empty jars a naive publish task would ship. Cosmetic until someone wires publishing.

### What comes after Phase 0

**Phase 1 is one vertical slice, not four services in parallel:** a plate read producing a visit, a call to the customer's system, and a confirmed barrier — across `orca-edge`, `orca-runtime` and `orca-core`. Its acceptance test is the one that matters most: **two simultaneous plate reads for the same truck produce exactly one visit, a thousand times.** Building breadth per service before that path works proves nothing about whether the primitives compose.

**The recommended sequencing with four developers:**

- **Spike 1 starts immediately, regardless of Phase 0.** It is the only open item where a bad answer changes the architecture, it needs one person, and it runs in a throwaway project with no repo conflict. Every week it is not run is a week the runtime design is unconfirmed.
- **The build session is over and Phase 0 is verified** — the repository is the team's to work in. The high-value non-code work remains Spike 1, NEW-1a, NEW-1b and the volume measurement.
- **With Phase 0 verified:** two developers on the vertical slice, two continuing on the open questions.
- **Core and runtime need two developers each** when service build-out starts. Between them they are 76% of the tables and 62% of the endpoints.
