# Stream 2 — Implementation Plan: read models and notifications

**For the developer building stream 2, and the AI they drive · August 2026 ·
Companion report: `docs/stream-2-report.md`**

Self-contained. Where it points at another document, read that document before
building the thing it describes.

**This stream fills the last two empty modules in `orca-runtime`.** When it lands,
all five are populated and the module wall stops being permissive — see WP0, which is
not optional and is not paperwork.

⚠️ **One decision is not yet made and it shapes the second half of this stream.** It
is stated in §5 as Q1. **The work is sequenced so you are not blocked by it:** the
read-model half does not touch it, so build that first while the answer arrives.

---

## Read first, in this order

1. **`AGENTS.md` (root) and `services/orca-runtime/AGENTS.md`** — the operating rules.
   Enforced, not advisory.
2. **`docs/CODE_PATTERNS.md`** — §2 (*never check, then act*) and §4 (the shape of a
   migration).
3. **`docs/MIGRATION_NUMBER_RANGES.md`** — **your band is `V138–V157` in the `runtime`
   schema.** ⚠️ **Read §3.1.** Stream 1 owns `V118–V137` and their migrations will
   land after yours have been applied to your database; the committed Flyway settings
   refuse that by default. It is expected, and the fix is written down.
4. **This plan, in full.**
5. **`docs/read-models-notify-from-1x.md`** — the DERIVED-FROM-1X reference. **Read its
   §0 first: the six inversions**, and **its §5**, which is Q1 below.
6. **`docs/ORCA_ARCHITECTURE.md`** — §C2 (orca-runtime, its modules, and the
   interface surface listing the grids and notification routes), §B7 (deployment
   profiles — why more than one instance is the normal case), §B10 (the guarantees).
7. **`docs/phase-3-report.md`** — work items are the data half of your first
   projection; its §7 is where the collation trap is explained.

**Mirror the existing code — it is your template.**

| For | Study |
|---|---|
| A projection maintained by an event, not recomputed | `platform/outbox` — the writer commits the fact with the business row |
| A read endpoint over a traffic-growing table | `workitem/api/WorkItemController.java` — note how "no status means the open queue" avoids serving history |
| A cross-module seam | `execution/api/ManualStepPort.java` and `workitem/api/WorkItemIntake.java` — the bidirectional pair |
| A migration | `V116__operator_presence.sql` (growth, retention class, scope-leading index, binary-collated CHECK) |
| A `@Scheduled` that is allowed to exist | Anything calling `SystemContext.runAs` — `SystemContextRule` fails the build otherwise |

---

## 1 · What this stream delivers

**The operator's view of the gate, and the way it stays current.**

*Read models* — pre-built projections for the console's grids: the lane-monitor board,
the queue, alerts, completed work. Maintained when something changes, **not
recomputed from nine tables every time somebody asks** — which is what the old system
does on every workflow step, and is the single strongest reason this stream exists.

*Notifications* — the durable record of what an operator was told, and the live
channel that tells them.

**What it explicitly does not deliver:**

- **No console.** The frontend is unstaffed. Everything here is an API with tests.
- **No new business behaviour.** A projection reflects what other modules decided; it
  never decides anything. If you find yourself writing a rule about *when* a visit is
  late, it belongs in `execution` or `workitem`, not here.
- **No partner-facing anything** — stream 1 owns `integration`.
- **No retention jobs** — declare growth and a retention class; stream 4 purges.
- ⚠️ **Email and push are NOT excluded — but the architecture disagrees with itself
  about them, so do not start there.** See §5 Q5. Build the in-app list and the live
  channel first; the email/push question is answered before you reach it.

## 2 · Ground rules

| Rule | Why |
|---|---|
| **Never invent a resolution to an open question.** Q1 is genuinely open — build around it, do not settle it | The programme's most-repeated failure |
| **1.x is the reference for the DATA; the architecture governs the BEHAVIOUR.** The sheet's §0 inverts six things | You are replacing a design, not porting one |
| ⚠️ **`readmodel` maintains its own tables and reads nobody else's** | §3, WP1. This is the way this stream most plausibly breaks the architecture |
| **Your migration band is `V138–V157`** | `docs/MIGRATION_NUMBER_RANGES.md` |
| **Tests prove properties, not paths.** The load-bearing property here is that **the projection and the truth cannot disagree** | "The row is written" is not a test |
| **Anything security-shaped is PROPOSE-and-report** | §5 |

## 3 · Work packages, in order

### WP0 · Let the modules stop being empty · *first commit, with WP1*

A build check asserts `notify` and `readmodel` contain **zero** classes
(`ImportedSetGuard.whatIsStillEmptyIsStated`). Your first class fails the build, by
design — so nobody fills these modules by accident.

Its failure message tells you what to do: remove the module from the list. **Do the
second half too.** `ModuleWallRule` carries `allowEmptyShould(true)` because some
modules were empty; **once you populate both of these, all five are populated**, and
that exemption should come out. **This stream is the one that finally makes the module
wall unconditional** — that is worth doing deliberately rather than leaving for
whoever notices.

⚠️ **Watch it fail before you fix it.** Add a class, see the build stop and name the
module, then update the guard. A check nobody has watched fail may not be wired in —
this repository has shipped exactly that.

**Done when** the guard asserts the populated set, `allowEmptyShould(true)` is gone
from `ModuleWallRule`, and both were watched to fail first.

### WP1 · The lane-monitor projection · *the core of the stream*

One projection table in `runtime`, in your band, owned by `readmodel`.

**The architecture already names your tables** (§C2's schema map): `readmodel` owns
`lane_monitor` and `queue_monitor`, described as *"Grid projections, rebuilt from
events — **not a system of record**."* That last phrase is the licence for Q2's
answer: a projection that can be rebuilt does not need the retention rule a system of
record needs.

⚠️ **`readmodel` is the one sanctioned exception to the module walls, and the exception
is narrow.** The lane monitor legitimately needs running visits *beside* queued work
items — two modules' data. **It must not read another module's tables.** It maintains
its **own** projection, built from both, and `ModuleWallRule` will stop you if you
reach into `execution.persistence` or `workitem.persistence`. That is the check doing
its job, not an obstacle to route around.

**What the board carries** — from the sheet's §1, which lists the nine tables 1.x joins
and what it produces: site and area identity, lane identity, the lane's traffic status
and its colour, the queued work item and when it was queued, the loop inputs, and the
indicator values.

**Three inversions are the acceptance criteria:**

- **Maintained, not recomputed** (sheet §0.2). 1.x re-runs a nine-table join on every
  workflow step. Yours updates when something changes.
- **One copy** (sheet §0.1). 1.x has five, in three services, and only one takes
  `WITH (NOLOCK)` — so the same board read two ways can disagree about isolation. You
  are building the single writer.
- **Scoped by construction** (sheet §0.3). 1.x's read has no site predicate at all.
  Yours goes through the seam, and `ScopeIndexRule` reads your migration and refuses a
  scoped table with no index leading with the scope column.

**Two design points to decide and state**, because they are yours:

- **What triggers an update.** The outbox is the obvious candidate and is already
  proven; a projection updated in the same transaction as the fact is another. State
  the trade you took — in particular, whether a reader can observe a board that is
  briefly behind, and whether that is acceptable for this surface.
- **The indicator set.** 1.x hard-codes four (`gate_arm`, red, orange, green) plus
  anything matching `%loop%`, in the join condition, in five places. Adding a fifth is
  a code change. Decide whether yours is configuration; if it is not, say why.

**Property tests (the acceptance):** the projection and the underlying truth **cannot
disagree** — drive a visit through, assert the board matches at every step, including
after a fault that rolls back the underlying write; and a board read under one site's
scope never returns another site's lane.

**Done when** those hold, and a truck driven through the gate is visible on the board
without anything recomputing a nine-table join.

### WP2 · The remaining grids

Queue, alerts, completed work, and the export job. Contract-first, `/api/v1/**`.

**Reuse rather than rebuild:** `GET /api/v1/work-items` already serves the queue with
the routing rules' priority ordering, and already avoids serving history when no
status is given. Do not build a second queue read — extend or project, and say which.

⚠️ **1.x has three routes that resolve to the same handler** (`/lanealerts`,
`/lanemonitors`, `/sitemonitors` — sheet §0.6), differing only in the permission
attached. **If three surfaces genuinely differ, they get three projections. If they do
not, they get one route.** Decide, and record the decision — do not reproduce three
names for one query.

**Done when** each grid is contract-first with a distinct reason to exist, and a
property test proves the ordering each one claims.

### WP3 · The durable notification

The persisted record: what an operator was told, and whether they have read it. The
sheet's §2 gives 1.x's ten columns; the architecture names the table `notification`
(§C2's schema map), singular.

Carry forward:

- **`read_at` as a nullable timestamp rather than a boolean.** It records *when*, and
  the unread state falls out of it. 1.x got this right.
- **The site column, indexed.** 1.x's notification table *is* scoped, unlike its read
  path.

⚠️ **Do not copy the missing `is_active`.** The sheet flags it: this is the one table
in that system carrying only half the soft-delete pair, so the repository-wide "filter
both" habit is wrong there specifically. In 2.0 you are not inheriting either column
by default — the growth declaration and retention class are what bound this table.

**Done when** notifications round-trip, unread state is derived from `read_at`, and the
table passes all ten checks.

### WP4 · The live channel · ⚠️ *blocked on Q1 — read §5 first*

Two halves, and only one of them is blocked.

**Not blocked — build it:** `POST /notifications/ws-ticket`. The architecture already
names it: a short-lived ticket obtained over an authenticated call and redeemed to
open the socket. **This exists because of what 1.x does** — its WebSocket takes
identity from a `?userUUID=` query parameter with no auth middleware on the router
(sheet §0.5). The ticket is the inversion; build it and prove a socket cannot be
opened without one.

**Blocked — do not guess:** how a published update reaches a subscriber **on another
instance**. 1.x keeps subscriptions in two in-memory maps, which cannot work across
instances and cannot survive a restart. ORCA 2.0 is multi-instance by design with no
broker inside a site. **That is Q1. Build the ticket, build the durable half, and
leave the fan-out until the answer arrives.**

**One thing 1.x does not do, which you should decide deliberately:** nothing
correlates the live delivery with the persisted row — a notification can be delivered
over the socket with no row, or written with no delivery, and nothing reconciles them.
State your rule.

**Done when** a socket cannot be opened without a valid, short-lived, single-use
ticket, and that is a property test rather than a claim.

## 4 · Verification — run these, record real results

**Running the stack:** `docs/deployment.md` Part 1, and `docs/phase-1-demo.md` §3 for
the port-offset incantation. **Keep the output of every command; the report needs it.**

| # | Item |
|---|---|
| 1 | `./gradlew build` green from a clean tree |
| 2 | `./gradlew check integrationTest --rerun-tasks` — **`--rerun-tasks` is not optional.** Without it Gradle answers from cache in under a second and reports a success it did not run |
| 3 | **The Phase 1 demo still runs end to end** — the standing regression canary |
| 4 | **A truck driven through the gate appears on the lane-monitor projection**, live — and nothing recomputed a nine-table join to make it so |
| 5 | **The projection and the truth cannot disagree** — including after a fault that rolls back the underlying write |
| 6 | A board read under one site's scope never returns another site's lane |
| 7 | **A WebSocket cannot be opened without a valid ticket**, and a ticket cannot be replayed |
| 8 | **WP0 watched to fail**: the guard stopped the build and named the module before you updated it; `allowEmptyShould(true)` is gone from `ModuleWallRule` |
| 9 | Break each of: a new traffic-growing table with no retention class · a projection table whose index leads wrong · a `readmodel` class reaching into `workitem.persistence`. **Each must fail the build, then revert.** The third is the one that matters most for this stream |
| 10 | `docker compose run --rm verify-isolation` — 36 checks still green |
| 11 | **All six services boot.** A green suite does not prove a service starts |

## 5 · Open questions — surface these, do not settle them

| # | The question | What to do |
|---|---|---|
| **Q1** | ⚠️ **How does a live update reach a browser when the site runs more than one instance and there is no broker?** 1.x never had to answer it — its hub is an in-memory map on one process. ORCA 2.0 is multi-instance in every profile (§B7) and has deliberately no broker inside a site (ADR-007). The two facts collide exactly here | **Do not choose in code.** Candidate shapes: a database-backed fan-out every instance polls; sticky routing at the reverse proxy; something else. ⚠️ One of those touches the reverse proxy, **which is not in the repository at all yet** (`deployment.md` Part 2, step 8). **Product owner's call.** Build WP0–WP3 and the ticket half of WP4 while you wait — none is blocked |
| **Q2** | **Retention classes for your projection and notification tables.** `@RetentionClass` takes a free-form string and the check only requires it non-blank, so you are not blocked — but the closed eighteen-value list is unreconciled and nine provisional values exist in code | Name them, follow the existing naming, **mark them provisional in your report**. Stream 4 reconciles and must be able to find yours. ⚠️ **A projection is a special case worth stating:** it can be rebuilt from its sources, so its retention rule may be *"discard freely"* rather than a period. Say so if you conclude that |
| **Q3** | **Three routes or one?** (WP2). 1.x's `/lanealerts`, `/lanemonitors` and `/sitemonitors` are one handler with three permissions | This one **is** yours — decide, state it, and do not reproduce three names for one query by default |
| **Q4** | **Should a live delivery and a persisted notification be correlated?** 1.x does not correlate them at all | Yours to decide. Recorded so it is decided rather than inherited |
| **Q5** | ⚠️ **Does `notify` deliver email and push, or only in-app?** **The architecture says both and neither.** §C2's module table gives `notify` *"Notifications, the WebSocket hub, push"* and the module diagram labels it *"WebSocket hub · email · push"* — but runtime's published interface surface carries **no** email or push route, and the only web-push endpoint in the whole document (`/me/push-subscription`) sits in the **Driver Portal**, a different service. 1.x has both, in its notification service | **Do not resolve this by picking the reading you prefer** — that is how scope silently grows or silently vanishes. Report the contradiction and ask. ⚠️ *Found while reviewing this plan: an earlier draft excluded email and push outright, on the strength of the endpoint table alone. That was a negative asserted from one direction, which is the error this programme guards hardest against.* **Operator push and driver push are different features on different services; whatever is decided, keep them apart** |

**When a 1.x behaviour contradicts the architecture:** the architecture wins, and the
divergence is recorded rather than resolved quietly. **When something is genuinely
unspecified in both:** report the gap. A gap reported is worth more than a gap filled.

## 6 · The report — `docs/stream-2-report.md`

- **What was built, per work package** — and what was not, named.
- **The §4 verification table with real results**, including command output.
- **Each of the sheet's six inversions, with the property test that proves it.**
- **What you did about WP0** — and confirmation you watched the guard fail first.
- **Every decision this plan did not dictate**, with the reasoning — Q2, Q3 and Q4 at
  minimum, plus what triggers a projection update in WP1 and whether a reader can
  observe a stale board.
- **Anything found wrong** in this plan, the reference sheet, the architecture, or
  1.x's shapes — **reported, not silently corrected.**

---

*Start at WP0 + WP1 together. If Q1 is still open when you reach WP4, build the ticket
and stop there — that is the plan working, not the plan failing.*
