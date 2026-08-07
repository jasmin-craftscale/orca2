# platform/scope — the seam, and the trap that awaits its successor

## What is settled, and what is not

**Settled (§B6, ADR-005):**

> Scope is enforced in one place, applied by construction, with a build-time check
> that fails when a query bypasses it. No query carries its own scoping condition.

**Not settled: the mechanism.** ADR-005's own reversal note says it — *"the
requirement is the decision; the mechanism is owned by the security design."*

So this module is the seam, its default-deny behaviour, and the build rule that
makes bypassing it a build failure. It is **not** a tenancy design, and it takes
no position on the three genuinely different enforcement problems §B6 lists.

`Scope` is deliberately opaque about what a dimension *is*. It does not know that
`site_id` means a site. That is not fastidiousness — open register item **NEW-1a**
records that the driver portal's rule is *per-principal, not per-tenant*: a driver
sees their own bookings across every terminal they visit, and nine `portal` tables
carry neither `site_id` nor `customer_id` by design. A tenant column baked into
this type would decide NEW-1a by accident, in the direction that cannot express
what the portal actually needs.

## Why a write throws where a read returns nothing

Phase 0's seam read only. WP2 added the write half, and it is deliberately **not**
symmetrical: an out-of-scope read returns an empty list, an out-of-scope write
throws `ScopeViolationException`.

The asymmetry is the point. The two failures look identical to the code that
follows them — no exception either way — but they are not identical to whoever has
to find the problem later:

| | What the caller sees | What is left behind |
|---|---|---|
| Read outside scope | An empty list, which is a thing you can branch on | Nothing |
| Write "outside scope", if it returned 0 | The same absence of an exception a success gives | **No row, and no record that one was wanted** |

A write that quietly changed nothing is indistinguishable from a write that
worked, and the symptom arrives much later as missing data with no log line. So it
is loud.

An **update that matches no rows** is a different thing again, and returns `0`
normally — the caller holds the scope, the rows had simply moved on. Only *not
holding the scope at all* throws.

**There is no delete.** §D3 retires records rather than removing them, so a
retirement is an update — which means the one operation that makes a row vanish
from every published view acquires the scope predicate like any other write.

## How a scope gets established

The seam applies whatever `ScopeContext` carries and **never invents one**. Two
deliberate acts, and nothing else:

- **A request path** derives it at the request boundary from claims and
  configuration, and runs the work inside `ScopeContext.callIn`. *What* that
  derivation is belongs to the security design and to register item **NEW-1a** —
  this module takes no position, which is why `Scope` is opaque about what a
  dimension means.
- **Background work** sets the installation's own scope explicitly, inside the
  system context it is already required to enter.

⚠️ **Entering `SystemContext` grants an identity, not an entitlement**, and the two
are deliberately not wired together. A background job that acquired scope merely by
being a background job would be a bypass — and the one bypass nobody would ever
notice, because system work has no user to notice on its behalf. Work that
establishes neither runs under `Scope.DENY`: reads return nothing, writes are
refused. That is the intended outcome, not a gap.

## ⚠️ The connection-pool trap

**If SQL Server row-level security is ever chosen, the connection pool is the trap,
not the SQL.** This is recorded here so that whoever implements it does not
rediscover it in production.

SQL Server's `sp_set_session_context` is **session-scoped**. A JDBC connection pool
hands out shared connections. Set the tenant key on a pooled connection and it stays
set for whoever borrows that connection next.

Unpinned, **that is worse than no RLS at all**:

| | |
|---|---|
| With no RLS | Every query is unscoped, visibly, and the build check catches it |
| With unpinned RLS | Every request is silently scoped to *an earlier request's* tenant — and it looks like it is working |

The second failure produces correct-looking results for the wrong tenant, with no
error and no log line, and only under concurrency. It will not appear in
development.

Anyone implementing RLS here must state, in writing, how the session context is
bound to the unit of work — connection pinning for the duration of the transaction,
a `Connection` wrapper that sets and clears the key on borrow and return, or
something else — **and test it under concurrent load with more requests than
connections.** A single-threaded test passes with the bug present.

Note also that SQL Server RLS is keyed on a predicate the *database* evaluates. It
cannot express "this driver, across customers", which is what NEW-1a is about. RLS
may be right for the appliance's site filter and is not a general answer.

## What is here

| | |
|---|---|
| `Scope` · `ScopeContext` | The ambient scope. Default is `Scope.DENY`, and a denied scope returns zero rows — never all rows |
| `ScopedSelect` | A read, described. There is nowhere in it to put a scope predicate, and it refuses to be built without saying which column it is scoped by |
| `ScopedInsert` · `ScopedUpdate` | A write, described. Same rule, same allow-list — and an insert must say which scope the row lands in, because the seam will not choose one for it |
| `ScopeSeam` · `JdbcScopeSeam` | The one place the predicate is applied |
| `readiness/` | The startup gate that refuses to serve when a published view this service requires is absent (Package 3) |
| `table/` | `@PersistentTable` and `@RetentionClass` — how a table declares whether it grows with traffic, and under which retention class |

### Why `table/` lives here

The retention-class build check needs a way for a table to *declare* that it grows
with traffic. That declaration has to be visible to every service, and the brief
fixes the module count at twelve — five under `platform/`, six services and
`build-checks` — so a sixth platform module is not available.

It is in `platform/scope` because this is the data-access primitive: the seam
through which rows are read, and now the declaration of what tables exist and how
they are bounded. **This placement is a decision the architecture did not dictate
and is flagged for review** — if the team wants a sixth primitive, this is the
first thing that should move into it.

## What the build check does and does not prove

`ScopeSeamRule` fails the build when a class outside `platform/` constructs a query
directly. It proves that services go through the seam.

It does **not** prove the predicate is correct, and it cannot. Correctness of the
predicate is the security design's problem, and this module is the place that
design will be implemented — not a substitute for it.
