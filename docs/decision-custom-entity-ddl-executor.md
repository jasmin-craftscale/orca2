# Decision intake — controlled custom-entity DDL executor

**For the product owner · Open · Required before Stream 3 WP2 implementation**

## Status

No product-owner ruling is recorded. This document is the durable handoff location
for Stream 3's required proposal. Its existence does not authorise implementation.

`docs/stream-3-plan.md` Q1 remains authoritative: Developer 3 writes the proposal
early, stops WP2 and continues the unblocked work packages while Product decides.

## The decision to prepare

ORCA core is intended to contain the one component permitted to turn a declared
custom-entity model into physical schema changes. Product must rule the executor's
allowed power and failure semantics before code gives it DDL authority.

The proposal must make each of these independently decidable:

1. Which DDL verbs and type/constraint changes are allowed.
2. What happens when a change narrows a type, length or nullability and existing data
   does not fit.
3. Whether a column or entity may ever be dropped, who may request it and what
   evidence/approval is required.
4. What an authored migration record contains, how it is ordered and how replay or a
   duplicate request behaves.
5. What the identifier allow-list is derived from and how an unlisted identifier is
   proven never to reach SQL.
6. How declared-versus-physical drift is detected and reported without automatic
   correction.
7. Which authenticated/authorised principal may declare or execute a schema change.
8. How generated traffic-growing tables enter the retention controls that currently
   inspect authored migrations and annotations.
9. Operational limits: transaction boundaries, locks/timeouts, failure recovery,
   observability and concurrent-instance coordination.

## Required proposal format

Developer 3 adds:

- verified current code/database constraints;
- viable options, including the smallest safe option;
- cost and failure modes of each option;
- a recommendation with reasons;
- what choosing each option commits the product, operations and future migrations to;
- property tests and live evidence that would accept the chosen option;
- explicit exclusions.

The proposal must not be inferred from 1.x. Its dynamic SQL is evidence of the problem,
not authority for the new design. The architecture's allow-listed identifiers,
parameterized values, recorded-once migrations and report-without-auto-correct drift
requirements remain fixed.

## Decision record

**OPEN — product owner has not ruled.**

Only the product owner replaces this line with a dated ruling and its conditions.
