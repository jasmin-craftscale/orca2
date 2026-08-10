# Stream 3 — Implementation Plan: the core remainder

**For the developer building stream 3, and the AI they drive · August 2026 ·
Companion report: `docs/stream-3-report.md`**

Self-contained. Where it points at another document, read that document before
building the thing it describes.

**This is the cleanly parallel stream.** It is the only one in a different service
from every other stream — different schema, different migration band, no shared
files. You can work without coordinating with anyone, and that is deliberate.

⚠️ **It also contains the single most security-shaped component in the programme.**
See §5 before you write any of WP2.

---

## Read first, in this order

1. **`AGENTS.md` (root)** — the operating rules: contract-first, the scope seam, the
   ten build checks, tests-prove-properties, the definition of done. Enforced, not
   advisory.
2. **`docs/CODE_PATTERNS.md`** — the shape a change takes here. §1 (a request end to
   end) and §4 (the shape of a migration) are the ones you will use daily.
3. **`docs/MIGRATION_NUMBER_RANGES.md`** — **your band is `V111–V140` in the `core`
   schema.** Nobody else writes there, but read §3.1 anyway: it explains the failure
   you will see when you pull another stream's `runtime` migration.
4. **This plan, in full.**
5. **`docs/custom-entities-from-1x.md`** — the DERIVED-FROM-1X reference. **Read its
   §0 first: the six inversions**, and its vocabulary warning — 1.x calls this
   *reference data*, and searching for "custom entity" finds nothing.
6. **`docs/core-config-schema-from-1x.md` §0** — the eleven translation rules. They
   govern every 1.x→2.0 configuration port and they apply here in full.
7. **`docs/ORCA_ARCHITECTURE.md`** — §C1 (orca-core: what it owns, the module map,
   and the *"single controlled executor"* line), §B6 (licensing — the paragraph that
   was ruled on 8 August), §B10 (the guarantees).
8. **`docs/phase-2-report.md`** — the configuration world you are extending. Its §7
   ("found wrong") is where the collation trap is explained.

**Mirror the existing code — it is your template.** `orca-core` is the most complete
service in the repository; almost everything you need has a worked example there.

| For | Study |
|---|---|
| A config table with its API | `V105__teams_templates.sql` + the seam repository + controller above it |
| An enum CHECK that the database actually enforces | Any of V105–V107 — `COLLATE Latin1_General_100_BIN2` |
| A published view runtime reads | `V102__topology_views.sql` — also the worked example of the comment standard |
| A property test that proves a constraint refuses | Phase 2's `*PropertiesIT` — duplicate-insert proofs, seed byte-stability |
| The lease you will bind the instance count to | `platform/lease`, and its 16-way race suite |

---

## 1 · What this stream delivers

**Two features that have nothing to do with each other beyond living in the same
service:**

*Custom entities* — a customer declares their own reference list, ORCA creates and
evolves its physical table under a **single controlled executor**, and rows arrive by
spreadsheet import or scheduled ingestion. The console queries them through a
builder that allow-lists every identifier.

*Licence verification* — the signed artifact is verified **locally** at startup, its
entitlements are read, and the concurrent-instance limit becomes real by binding to
the database lease that already exists.

**What it explicitly does not deliver:**

- **No console.** The frontend is unstaffed; everything here is an API with tests.
- **No licence *issuance*.** That is `orca-fleet`, cloud-only, and out of scope while
  the programme is on-site first.
- **No machine binding.** Ruled 8 August: hardware identity is heartbeat telemetry,
  never an enforcement input. If you find yourself reading a DMI UUID to decide
  whether to start, stop — you are building the thing that was ruled against.
- **No retention jobs** — declare growth and a retention class; stream 4 purges.

⚠️ **This stream is called "the core remainder", and it does not finish `orca-core`.**
The architecture also gives core **workflow and screen design** and the publish
pipeline that pushes a compiled process to runtime — none of which is here, because
that work belongs with the developer building the visual builder and is not staffed.
`POST /internal/deployments/v1`, the endpoint core would push a published version to,
appears in **zero** contracts today. Say "core's configuration and licensing are
done", never "core is done".

## 2 · Ground rules

| Rule | Why |
|---|---|
| **Never invent a resolution to an open question.** Unspecified → the register → still unspecified → **report the gap** | The programme's most-repeated failure |
| **1.x is the reference for the DATA; the architecture governs the BEHAVIOUR.** For this stream the gap is unusually wide — §0 of the sheet inverts almost everything 1.x does | You are not porting a feature; you are rebuilding one whose old implementation is the counter-example |
| **The eleven translation rules in `core-config-schema-from-1x.md` §0 apply in full** | They are why Phase 2's port worked |
| **Your migration band is `V111–V140`** | `docs/MIGRATION_NUMBER_RANGES.md` |
| **Every table lands with its feature surface in one work package** | A table nothing reads is drift |
| **Anything security-shaped is PROPOSE-and-report** — and in this stream that is WP2's whole design | §5 |

## 3 · Work packages, in order

### WP1 · The declared model

The metadata: what a custom entity *is*, before any physical table exists. 1.x keeps
this in `reference_datas`; you are designing the 2.0 equivalent from the sheet's §1,
under the translation rules.

Carry forward deliberately:

- **The dual-key convention** — an int key and an external identifier.
- **The two prefixes** (`rd_` / `ed_`) or a successor to them, so a generated table is
  identifiable as generated at a glance. State which you chose and why.

Drop deliberately:

- ⚠️ **The table's own name embedded in its key columns** (`<table>_id`,
  `<table>_uuid`). That is what makes a rename a schema rewrite in 1.x. Sheet §1.

**Publish `topology.custom_entity`** — the view runtime's query builder reads, and
which the architecture calls *"the allow-list every selector is checked against."*
Follow `V102__topology_views.sql` for the shape and the comment standard.

**Done when** an entity can be declared, listed and evolved *as a declaration*, the
view is published, and a property test proves the uniqueness rules are database
constraints rather than hopeful checks.

### WP2 · The single controlled executor · ⚠️ *propose before you build — see §5*

The component that turns a declared model into a physical table, and a declared
change into a schema change.

**The four properties the architecture requires**, each of which is an inversion of
1.x:

1. **A declared migration, executed once, recorded.** Not a reconcile. There must be
   an answer to "what was applied to this table, and when" — 1.x has none.
2. **Every identifier allow-listed.** Not transformed, not escaped — *checked against
   a permitted set*. Sheet §0.3 shows what 1.x does instead and why it is not a
   defence.
3. **One executor.** Nothing else alters the schema. The database already helps you:
   each service's login reaches only its own schema (ADR-004), so this is a rule
   about `orca-core`'s internals, not about other services.
4. **Drift is detected and never auto-applied** — `GET /custom-entities/{id}/drift`.
   ⚠️ **This is new, not a port**: 1.x has no schema-drift concept at all (sheet
   §0.5, traced both directions).

**What to build first:** the declared-migration record and the allow-list, with a
property test proving a rejected identifier never reaches the database. Then the
executor.

**Done when** a declared change produces a recorded, replayable migration; an
identifier outside the allow-list is refused with a typed error and no DDL is
attempted; and drift between declared and physical is reported without being
corrected.

### WP3 · Getting rows in

`POST /custom-entities/{id}/import` (spreadsheet) and
`PUT /custom-entities/{id}/ingestion` (scheduled SFTP).

Two things 1.x leaves implicit and you must make explicit (sheet §2):

- **What happens to a row that fails validation mid-import** — all-or-nothing, or
  accept-and-report? Pick, state it, prove it.
- ⚠️ **Where the SFTP credential lives.** Same unanswered question stream 1 hits for
  connector credentials. **Do not invent a scheme** — §5.

⚠️ **A scheduled ingestion is a `@Scheduled` method, and `SystemContextRule` will fail
the build unless it enters a system identity.** Not a formality: background work with
no identity is unattributable, and the check exists because of that.

**Done when** an import round-trips with its failure rule proven, and the scheduled
path runs under a system identity.

### WP4 · The query builder

`POST /custom-entities/{id}/query` — the console's read path over a customer-declared
table.

**The rule is in the architecture and it is not negotiable:** *allow-listed
identifiers, parameterized values.* Identifiers are checked against the declared
model — which is exactly why WP1 publishes `topology.custom_entity` as the
allow-list. Values are parameters, never concatenated.

**Done when** a query composes only from declared identifiers, a query naming an
undeclared column is refused, and a property test proves both — including that a
value containing SQL metacharacters is stored and returned intact, because that
proves it was parameterized rather than filtered.

### WP5 · Licence verification

**Read the architecture's paragraph (§B6) before anything else; it is narrow, and
narrower than people expect.** Verify the signed artifact **locally** at startup, read
its entitlements, and bind the concurrent-instance limit to `platform/lease`.

- **The enforcement mechanism already exists.** `platform/lease` has a fence token and
  a proven 16-way race suite. An instance beyond the licenced count **cannot acquire
  the right to work**. You are not designing enforcement.
- **A cold standby on other hardware is a supported arrangement, not a violation.**
  Whatever you build must not make it one.
- **Hardware identity is telemetry on the heartbeat, never an input to the decision.**
- ⚠️ **Read 1.x only for the licence file's field list.** Its verification behaviour
  differs from the ruled model in ways recorded in the restricted findings document —
  sheet §3. Build the architecture's model.

**Done when** a valid licence starts the service, an invalid or tampered one refuses
it with a clear message, and a property test proves the instance limit holds under a
concurrent race — the lease's own suite is the template.

## 4 · Verification — run these, record real results

**Running the stack:** `docs/deployment.md` Part 1, and `docs/phase-1-demo.md` §3 for
the port-offset incantation if 8081–8086 are taken. **Keep the output of every
command; the report needs it.**

| # | Item |
|---|---|
| 1 | `./gradlew build` green from a clean tree |
| 2 | `./gradlew check integrationTest --rerun-tasks` — **`--rerun-tasks` is not optional.** Without it Gradle answers from cache in under a second and reports a success it did not run |
| 3 | **The Phase 1 demo still runs end to end.** You are changing the service the gate path reads its views from, so this is the canary that matters most for your stream |
| 4 | A declared entity becomes a physical table through the executor, and the migration is **recorded and replayable** |
| 5 | **An identifier outside the allow-list is refused and no DDL is attempted** — prove the "no DDL attempted" half, not just the refusal |
| 6 | Declared-versus-physical drift is **reported and not corrected** |
| 7 | A query with a value containing SQL metacharacters stores and returns it intact |
| 8 | The instance limit holds under a concurrent race; a cold standby on other hardware is not refused |
| 9 | Break each of: a new traffic-growing table with no retention class · a scheduled method with no system identity · a controller implementing no generated interface. **Each must fail the build, then revert** |
| 10 | `docker compose run --rm verify-isolation` — 36 checks still green. ⚠️ **Your stream is the one most likely to move this number.** If the executor needs a grant it does not have, that is a finding to report, not a grant to add quietly |
| 11 | **All six services boot.** A green suite does not prove a service starts |

## 5 · Open questions — surface these, do not settle them

| # | The question | What to do |
|---|---|---|
| **Q1** | **The DDL executor's design is the product owner's, not yours.** It is the one component permitted to change the schema at runtime, and the roadmap says so explicitly: *"Its design is surfaced to the product owner, not settled by whoever implements it."* | **Write the design down and stop.** What DDL verbs are permitted; what happens to data on a narrowing change; whether a drop is ever allowed and who can ask for one; what the recorded migration looks like; what the allow-list is derived from. **Then wait.** Build WP1, WP3, WP4 and WP5 while you wait — none of them is blocked on the answer |
| **Q2** | **Where an SFTP credential lives at rest.** Stream 1 hits the identical question for connector credentials | **Security-shaped.** Propose; do not implement a scheme. ⚠️ Flag to the product owner that **two streams need one answer** — two schemes would be worse than either |
| **Q3** | **The retention class for any traffic-growing table you add.** `@RetentionClass` takes a free-form string and the check only requires it non-blank, so you are not blocked — but the closed eighteen-value list is unreconciled and nine provisional values exist in code | Name one, follow the existing naming, **mark it provisional in your report**. Stream 4 reconciles and must be able to find yours |
| **Q4** | **What a failed row does mid-import** (WP3). 1.x's behaviour is per-call-site and stated nowhere | This one **is** yours — decide it, state it, prove it. Recorded here so it is not decided silently |

**When a 1.x behaviour contradicts the architecture:** the architecture wins, and the
divergence is recorded rather than resolved quietly. **When something is genuinely
unspecified in both:** report the gap. A gap reported is worth more than a gap filled.

## 6 · The report — `docs/stream-3-report.md`

- **What was built, per work package** — and what was not, named.
- **The §4 verification table with real results**, including command output.
- **Each of the sheet's six inversions, with the property test that proves it.**
- **The DDL executor design you proposed** (Q1), written so the product owner can rule
  on it without reading your code.
- **Every decision this plan did not dictate**, with the reasoning — Q3 and Q4 at
  minimum, plus the prefix choice in WP1.
- **Anything found wrong** in this plan, the reference sheet, the architecture, or
  1.x's shapes — **reported, not silently corrected.**

---

*Start at WP1. Write the Q1 design early and hand it over — it is the one thing that
unblocks somebody else's decision rather than your own work.*
