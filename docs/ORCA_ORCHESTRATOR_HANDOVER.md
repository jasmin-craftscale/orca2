# ORCA — Orchestrator Handover

**Read this first. It is the entry point for a new session working on the ORCA rewrite programme.**

You are picking up an engagement that has been running for some weeks. The architecture is written and signed, the first build phase is about to start, and there is a register of things deliberately left open. Your job is to help the product owner run the programme — not to redesign it.

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
| `~/Documents/Projects/orca` | **The new build.** A Spring Boot 4.0.7 / Java 25 project, one commit of Initializr base plus the documents the build agent needs. Phase 0 has not started |

---

## 3 · The existing system, and where it falls short

You will be asked to verify claims against this codebase. It is the evidence base, and it is frequently the only reliable source — several documents in the corpus have been wrong about it.

**What it is:** 25 Go microservices (Gorilla Mux, GORM), four React applications, SQL Server, a dedicated Kafka virtual machine per customer site, Keycloak for identity.

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

**Seven Java 21+ / Spring Boot services on Microsoft SQL Server, in one repository, deployed as six bootable applications** (the seventh, media, is inherited and not rebuilt).

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

1. **`Lynxis-Gate/docs/ORCA_ARCHITECTURE.md`** — the specification. ~19,000 words, Parts A–D. **Read §B10 first** (what the architecture guarantees, with how each is verified); it is the shortest route to understanding the design's intent.
2. **`Lynxis-Gate/docs/ORCA_OPEN_QUESTIONS_REGISTER.md`** — what is deliberately unsettled. Consult it before assuming something is missing by accident.
3. **`orca/docs/ORCA_PHASE0_BUILD_BRIEF.md`** — what the build agent does first. **This copy is authoritative**; the Lynxis-Gate copy is a mirror.
4. **`Lynxis-Gate/docs/ORCA_IMPLEMENTATION_PLAN.md`** — phases, and what is deliberately not planned yet.
5. **`orca/docs/REPOSITORY_GUIDE.md`** — the repository layout and the purpose of each folder.

Skim only if relevant: `ORCA_SPIKE_STOP_RULES.md`, `ORCA_CLIENT_SOLUTION_OVERVIEW.md`, `ORCA_SOLUTION_AND_DELIVERY_PLAN.md`.

⚠️ **`Lynxis-Gate/docs/ORCA_SOFTWARE_ARCHITECTURE.md` is superseded** by `ORCA_ARCHITECTURE.md` and still contradicts it — it describes a BPMN modeller rebuild, workflow re-authoring and a frozen screen format, all of which were reversed. Do not read it as current, and do not circulate it.

---

## 8 · How to work on this programme

These are not preferences. Each was learned by getting it wrong.

**Never invent a resolution to an open question.** The recurring failure across this engagement has been filling a gap with something plausible and writing it as settled design. It happened at least four times — an invented offline-redemption mechanism, invented service modules, an invented tenancy resolution, invented API endpoints. Every one read well and every one was wrong. **If something is unspecified, say so and leave it. A gap reported is worth more than a gap filled.**

**Verify against code, not against documents.** Several corpus documents have been wrong about the existing system, and one wrong claim propagated into four documents before it was caught. When a claim matters, check the source.

**Trace both directions before asserting a negative.** The most damaging error in this engagement was "no hardware fingerprint exists anywhere" — derived from tracing the issuance path only. The enforcement lived in a different service. Before writing *"X does not exist,"* trace the consumption path as well as the production path.

**Do not pattern-match prose with regular expressions.** Every defect introduced into the architecture document came from a bulk regex edit — mangled sentences, converted cross-references, silently reintroduced claims. Edit specific lines.

**Verify by executing, not by asserting.** Writing a test is not evidence it passes. A build check nobody has watched fail may not be wired in. A rendered document can contain a broken diagram that no file check will see.

**Ask the product owner when a decision is theirs.** Commercial questions, scope, staffing, what to promise a client, and anything security-shaped are not yours to settle. State the options and the trade, give a recommendation, and wait.

---

## 9 · Where things stand right now

**Phase 0 has not started.** The `orca` repository holds the Initializr base and the documents, on branch `phase-0-foundations`.

**Next action:** a fresh session, working directory `~/Documents/Projects/orca`, pointed at `docs/ORCA_PHASE0_BUILD_BRIEF.md`, Opus with high effort, committing once per work package. It builds the five primitives, the build checks, per-service migrations, the local stack and CI — and **no business logic at all**.

**The team:** four developers. The plan is that all four work together through Phase 0 rather than taking one service each, because the five primitives are what every guarantee depends on and four parallel implementations would recreate the defect class the rewrite exists to remove.

**Open with the product owner:** the review of the repository structure with the lead developer, and the four questions at the end of `REPOSITORY_GUIDE.md`.
