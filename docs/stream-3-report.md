# Stream 3 implementation report — WP1 declared custom-entity model

**Status: independently reviewable · 12 August 2026**

This report covers only Stream 3 WP1 and the document-only WP2 decision proposal.
It does not claim that any declaration has been applied as a physical custom-entity
table. WP2–WP5 remain absent.

## What was built

### WP1 · declared model

- Contract-first public APIs in `orca-core`:
  - `POST /api/v1/custom-entities` declares metadata atomically;
  - `GET /api/v1/custom-entities` lists the installation site's declarations;
  - `PATCH /api/v1/custom-entities/{customEntityExternalId}` changes the display
    name and/or adds fields, explicitly without claiming physical application.
- `V111__custom_entity_declarations.sql` creates two bounded, site-scoped metadata
  tables:
  - `custom_entity`, with integer/external dual keys, semantic kind, display name,
    opaque reserved table identifier and declaration version;
  - `custom_entity_field`, with integer/external dual keys, exact field identifier,
    display name, closed type/shape, key/nullability flags and ordinal.
- SQL Server constraints hold the declared invariants: exact kinds and types,
  safe lower-case identifiers, fixed reserved-key exclusion, valid TEXT/NUMBER
  modifiers, non-null business key, positive ordinals, and every uniqueness rule,
  including the legacy model's per-entity field-display-name uniqueness.
- `core.topology_custom_entity` publishes one row per field to `orca_runtime` and
  hides retired sites. Core also assembles each declaration from this flattened
  view in one SQL statement, so a concurrent evolution cannot pair one declaration
  version with another version's field list. Runtime receives `SELECT` on the view
  and no access to the underlying metadata tables.
- All reads and writes go through `platform/scope`. Creation and evolution are
  Spring transactions and append `CREATED`/`UPDATED` audit facts atomically with
  the declaration. The controller implements the generated interface and maps
  declaration faults to `CUSTOM_ENTITY_DECLARATION_INVALID` (422), conflicts to
  `CONFLICT`, and cross-scope/unknown entities to `NOT_FOUND`.
- Four unit tests state identifier, display-name, business-key and field-shape
  validation. Eight real-SQL-Server properties cover create/list/evolution, SQL
  constraints, cross-site isolation, view permissions, the serialized typed error
  envelope, absence of a generated table, and rollback after deliberately injected
  mid-write and audit-write failures.

### WP2 · decision proposal only

`docs/decision-custom-entity-ddl-executor.md` remains OPEN. It records verified
current permissions and transaction behaviour; makes all nine requested decisions
independent; and compares:

1. a strictly additive executor;
2. staged evolution with governed destructive changes; and
3. operator-mediated execution.

The document advises Option A for the first release, but records no ruling and
authorises no implementation.

## The six 1.x inversions

| Inversion | WP1 evidence | Boundary still open |
|---|---|---|
| `AutoMigrate` is not a migration | `declarationRoundTripAndAdditiveEvolution` proves a reserved `ce_*` identifier has no object in `sys.objects`; WP1 stores only the declaration | The recorded-once executor and migration history are WP2 |
| Destructive DDL must not run from an HTTP rename | PATCH changes the display name, preserves the opaque table identifier, increments the declaration version and creates no table | Which later DDL verbs are permitted is Product decision 1–3 |
| Identifiers must be allow-listed | Unit and integration tests reject unsafe/reserved identifiers—including the fixed site-scope column—with the typed 422 envelope; direct SQL violates `ck_custom_entity_field_identifier` | WP2 must prove a rejected identifier never reaches its SQL adapter |
| One controlled executor, not general admin DDL | No DDL surface or executor exists. The 36-check isolation proof still confines each service to its schema, and runtime reads only the published view | Executor authority and public mutation authorisation are Product decisions |
| Drift is detected, never auto-applied | No reconciliation or drift correction was introduced | Drift reporting is WP2 and deliberately not claimed by WP1 |
| One identifier policy, not duplicated name transforms | Storage identifiers are client-declared exact lower-case tokens; table identifiers are server-minted and display names never transform into either | The future DDL compiler must consume only persisted, revalidated identifiers |

## Verification

### Safety and baseline

- Worktree: `/Users/jasmintankic/Documents/Projects/orca-stream-3-wp1` on
  `feature/stream-3-wp1`, directly from `origin/develop` at
  `62b5b3f8607cad56089514bf99db73261f0146ad`.
- Credentials baseline gate:
  `git merge-base --is-ancestor a0bc010 origin/develop` → exit 0.
- Shared checkout's untracked `docs/ORCHESTRATOR_KICKOFF.md` was left untouched.
- The documented local stack used machine-local ports SQL Server 21433, Keycloak
  18080, TOS 9200 and device host 9300, under an exclusive validation window.
- Clean baseline:
  `./gradlew check integrationTest --rerun-tasks` → **BUILD SUCCESSFUL in 9m21s**,
  73 tasks executed.
  - unit: 19 suites, 80 tests, 0 failures, 0 errors, 0 skipped;
  - integration: 33 suites, 266 tests, 0 failures, 0 errors, 0 skipped.
- Baseline isolation: `docker compose run --rm verify-isolation` → **36/36**.
- Baseline live gate: plate `S3-WP1-BASELINE` was ACKed; visit
  `vis-86e76838-...` completed; `RAISE_GATE` executed; `visit.completed` was
  recorded. The three gate services were stopped before implementation suites.

### Final commands on hardened implementation commit `10cea97`

| Evidence | Result |
|---|---|
| `./gradlew build` | **BUILD SUCCESSFUL in 4s**; 75 actionable tasks, 2 executed, 73 up-to-date |
| `./gradlew check --rerun-tasks` | **BUILD SUCCESSFUL in 11s**; 60/60 tasks executed |
| `./gradlew check integrationTest --rerun-tasks` | **BUILD SUCCESSFUL in 7m**; 75/75 tasks executed |
| XML count — unit | 20 suites, **84 tests**, 0 failures, 0 errors, 0 skipped |
| XML count — integration | 34 suites, **274 tests**, 0 failures, 0 errors, 0 skipped |
| Focused `CustomEntityServiceTest` + `CustomEntityPropertiesIT` with `--rerun-tasks` | 1 unit suite / 4 tests and 1 integration suite / 8 tests; all green; 22/22 tasks executed |
| Duplicate constraints | Real SQL Server refused duplicate entity external id, site/kind/name, table identifier, field external id, per-entity identifier, display name, ordinal and second business key |
| Cross-site isolation | Site A and B list only their declarations; A's PATCH of B returns typed `NOT_FOUND`; B's stored name is unchanged |
| Failed multi-write | A test-only CHECK rejects the second field after parent and first field writes; the transaction leaves zero parent, field and audit rows. A separately poisoned audit write rolls the declaration back too |
| View boundary | `orca_runtime` reads `core.topology_custom_entity`, cannot read `core.custom_entity`, cannot update the view, and sees no rows after site retirement. Core's owner read uses the same flattened result in one statement |
| Generated table absent | `OBJECT_ID(reserved_ce_identifier, 'U')` is null after declaration and evolution |

### Read-only cross-check against 1.x

The legacy checkout at `/Users/jasmintankic/Documents/Projects/lynxis/Lynxis-Gate`
was inspected read-only. The comparison used the actual request DTO, metadata
entity, validation/type mapping utilities and create/update repository paths:
`reference_data_request.go`, `customer_entity.go`, `utils.go` and
`reference_data_management_repository.go`.

- 1.x has two `rd_`/`ed_` families, site/customer/name/type/schema metadata,
  `string`/`float`/`boolean`/`date` fields, exactly one primary field, and rejects
  duplicate field names and mappings. WP1 preserves those semantics deliberately
  as REFERENCE/EVENT, TEXT/NUMBER/BOOLEAN/DATE, exactly one business key and both
  per-entity identifier/display-name uniqueness rules.
- 1.x also derives identifiers from mutable names and executes `AutoMigrate`,
  drop, rename-table and rename-column operations directly from the management
  path. WP1 does not copy those unsafe mechanics: identifiers are separated from
  display names, declarations perform no DDL, and destructive evolution remains
  behind the open WP2 decision.
- 1.x generated rows carry customer/site identity plus framework audit/status
  columns. WP1 now reserves and publishes a fixed `site_external_id` contract so
  future 2.0 generated-table reads can satisfy the existing scope seam. No claim
  is made yet about the remaining physical row columns because WP2 is unruled.

### Negative build-check mutations

Each mutation was applied alone, produced the expected red build, and was restored
with a clean diff before continuing.

- Removed `implements CustomEntitiesApi` from `CustomEntityController`:
  `ContractInterfaceRule` failed and named that controller as a `@RestController`
  implementing no generated API interface.
- Reordered `ux_custom_entity_site_kind_name` to lead with `entity_kind`:
  `ScopeIndexRule` failed and named `custom_entity`, reporting its index led with
  `entity_kind` instead of `site_external_id`.
- Retention mutation: not part of this slice. Both new tables are honestly
  `Growth.BOUNDED`; WP1 introduces no traffic-growing table.
- After restoration, the two focused build checks passed together.

### SQL Server facts used by the WP2 proposal

- Under impersonation of `orca_core`, `HAS_PERMS_BY_NAME` returned `1 / 1 / 1` for
  control of schema `core`, database `CREATE TABLE`, and database `CREATE VIEW`.
- A uniquely named probe executed `CREATE TABLE` and `ALTER TABLE ADD` in one
  transaction as `orca_core`; rollback left `OBJECT_ID(..., 'U') = NULL`.
- These facts establish capability and the smallest transaction case only. They do
  not approve an executor or generalise to long backfills/destructive operations.

### Isolation, boots and live regression

- `docker compose run --rm verify-isolation` → **PASS — 36 checks**: six own-schema
  writes/reads and all thirty cross-schema reads refused.
- Because the unpublished V111 migration was hardened after an earlier local boot,
  Flyway correctly rejected the stale checksum in the disposable validation
  database. The local ORCA Docker volumes were rebuilt using the documented
  bootstrap path; no source or shared environment was reset. Core then applied all
  14 migrations to the fresh schema and reached health 200. Runtime, edge, portal,
  sync and fleet also booted, and all six health checks returned 200 on ports
  18081–18086.
- Current-hash gate proof:
  - plate `S3-WP1-10CEA97`, event
    `evt-26569476-b45d-4a02-832f-2afdd60c132e` → camera ACK;
  - edge buffer → `ACKED`, attempts `0`;
  - visit `vis-ffb3678e-1246-4f11-9b19-1b8043cba7e6` → `COMPLETED`;
  - command `549e4e7c-95eb-11f1-bd45-f68083829ce1` →
    `RAISE_GATE / EXECUTED`;
  - runtime outbox → `visit.completed`, ordering key `lane:LANE-DEMO-01`.
- All six owned service sessions were interrupted afterwards. Ports 18081–18086
  and 9100 were confirmed stopped; `./gradlew --stop` left zero Gradle workers.

### Stream plan verification table

| Plan item | Result in this slice |
|---|---|
| 0 · stop services/daemons | Pass before both full suite runs; zero workers before validation |
| 1 · build | Pass, exact output above |
| 2 · full uncached verification | Pass, exact counts above |
| 3 · Phase 1 demo | Pass on the current implementation commit |
| 4 · recorded physical executor migration | **Not part of WP1; WP2 not built** |
| 5 · executor rejects identifier and attempts no DDL | **Not part of WP1; no executor exists**. WP1's declaration and DB identifier rejection is proven separately |
| 6 · physical drift report/no correction | **Not part of WP1; WP2 not built** |
| 7 · query values with metacharacters | **Not part of WP1; WP4 not built** |
| 8 · licence instance race | **Not part of WP1; WP5 not built** |
| 9 · negative checks | The two WP1-applicable mutations passed as negative evidence. No traffic-growing table or scheduled method was added |
| 10 · isolation | Pass, 36/36 |
| 11 · six service boots | Pass, six health 200 responses |

## What was not built

- WP2: no migration record, DDL compiler/executor, generated physical table,
  physical alteration, drift endpoint, runtime row grant, locking or executor job.
- WP3: no spreadsheet import, SFTP ingestion, scheduler or ingestion credential.
- WP4: no dynamic query builder and no read/write API for generated rows.
- WP5: no licence verification or instance-limit binding.
- No deletion/retirement of declarations or fields, narrowing, type changes,
  nullability changes, business-key replacement, arbitrary SQL, data backfill,
  automatic drift correction, licensing or commercial behaviour.
- No resolution of SFTP host trust/rotation, credential-mutation authorisation,
  licence-expiry behaviour, retention durations or the final retention catalogue.

## Decisions made within delegated authority

1. **One successor prefix: `ce_`.** REFERENCE and EVENT remain explicit metadata
   rather than two physical-name dialects. The prefix identifies generated tables
   at a glance; 32 UUID hex characters make the identifier stable and collision
   resistant. A display rename never becomes a table rename.
2. **Fixed future row keys and scope: `row_id`, `external_id` and
   `site_external_id`.** The keys retain the dual-key convention without embedding
   the mutable table name in every column. The scope column is reserved because a
   future generated-table read must remain expressible through `platform/scope`.
3. **Exact field identifiers.** Lower-case ASCII, start with a letter, maximum 63
   characters, letters/digits/underscore only; the two future row keys and future
   scope column are reserved. Display names remain separate Unicode values and are
   unique per entity, carrying forward 1.x's duplicate-label refusal as a database
   constraint rather than an application-only check.
4. **Closed declaration vocabulary.** Entity kinds are REFERENCE/EVENT. Field types
   translate 1.x's shape as TEXT, NUMBER, BOOLEAN and DATE. TEXT requires length
   1–4000; NUMBER requires SQL Server-compatible precision 1–38 and scale
   0–precision; the other types accept no modifiers.
5. **Exactly one immutable, non-null business key.** The service requires one at
   creation; a filtered unique database index enforces at most one, and additive
   evolution cannot introduce another.
6. **Additive declaration evolution only.** Display-name changes and added
   non-business-key fields are honest before an executor exists. Deletion,
   narrowing or mutation of existing fields would pretend to settle WP2 and is not
   in the contract. One request increments one declaration version atomically.
7. **Name uniqueness is per site and entity kind.** The database is the race guard.
   Different sites remain independent; REFERENCE and EVENT are separate semantic
   namespaces.
8. **No arbitrary field-count limit.** A draft limit was removed during review
   because the plan supplied no customer evidence or ruling for one.
9. **Physical view name follows the ruled repository convention.** The architecture's
   logical `topology.custom_entity` is implemented as `core.topology_custom_entity`,
   matching V102's prefix-inside-core ruling rather than inventing an eighth schema.

## Product decisions still required

The OPEN proposal requests independent rulings on:

1. permitted DDL verbs and type/constraint changes;
2. incompatible data during narrowing;
3. physical drop and its request/approval/evidence;
4. migration record, ordering, replay and idempotency;
5. executor identifier derivation and proof that rejected identifiers never reach SQL;
6. drift blocking/reporting policy without auto-correction;
7. declaration and execution authentication/authorisation;
8. retention treatment and enforceable inventory for generated EVENT tables; and
9. transaction boundaries, locks, timeouts, recovery, observability and
   concurrent-instance coordination.

Advice is Option A, a strictly additive first executor, with nullable additions
(and non-null only on a proven-empty table), separate declaration/execution
authorities, and EVENT execution gated on enforceable retention. This is advice,
not a ruling.

## Found wrong or incomplete

1. The architecture and Stream 3 plan name the logical view
   `topology.custom_entity`, while the later product ruling documented in V102 says
   published views physically live in `core` with a `topology_` prefix. WP1 follows
   the executed convention as `core.topology_custom_entity`; the logical notation
   should be clarified in architecture prose.
2. The current public security chain proves authentication only. It does not have a
   per-route mutation entitlement, so “authorised caller” has no approved finer
   meaning today. WP1 did not invent one; this is Product decision 7.
3. The retention check sees Java annotations, and the scope-index check parses
   authored migrations. Neither can see a future runtime-generated table. The WP2
   decision must extend enforceable inventory/check evidence before EVENT DDL is
   allowed; merely attaching metadata would not make either existing check true.
4. No other claim in the plan or reference sheet was disproved by execution. The
   known old-repository singular-table-name claim remains false, as already recorded
   in `custom-entities-from-1x.md`; both singular and plural searches were required.
5. The first WP1 implementation assembled parent and field rows with separate
   read-committed statements. A concurrent evolution could therefore expose a
   declaration version and field list from different snapshots. The owning read now
   consumes `topology_custom_entity` once and assembles that one result set.
6. The first WP1 implementation did not audit declaration mutations, despite core's
   existing audit trail being designed to share the configuration mutation's
   transaction. Create/evolve now append audit facts; a poisoned audit write proves
   the declaration rolls back rather than becoming unaudited.
7. The first WP1 identifier reservation omitted `site_external_id`. The old generated
   tables carried site identity, and ORCA 2.0 requires every application read through
   the scope seam. Leaving the name customer-claimable would block a safe generated
   table later; it is now a fixed, published and database-enforced reserved column.
8. Direct inspection of 1.x confirmed it refuses duplicate `FieldName` and
   `FieldMapping` values. WP1 initially constrained only the stable identifier. The
   per-entity display-name rule is now also validated and enforced by SQL Server.

## Next slice — proposed only

After Product records the nine WP2 rulings, create a fresh branch from the then
latest `origin/develop`, proposed name `feature/stream-3-wp2`. Prerequisites are the
approved executor decision, declaration/execution authorisation, and a ruled
retention path for any executable EVENT table. Do not begin from this branch or
before those conditions are recorded.
