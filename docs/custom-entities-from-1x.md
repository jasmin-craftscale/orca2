# Custom entities and their runtime schema changes — extracted from ORCA 1.x

**⚠️ Provenance: derived from the fielded ORCA 1.x Go code on 10 August 2026, not from a design document.** Every claim below was read out of the source and the load-bearing ones were re-verified by hand. Sources: `services/admin-service/internal/repository/reference_data_management_repository.go` (3,467 lines — the whole feature), `services/admin-service/internal/utils/utils.go`, `services/license-management-service/`.

**This is the reference for the DATA and the BEHAVIOUR. The target architecture governs the design.** Where the two disagree, the architecture wins and the disagreement is named below rather than silently resolved.

**Read §0 first.** It is the list of things 2.0 must deliberately do differently, and it is the acceptance criteria for the stream that rebuilds this path.

---

## ⚠️ Vocabulary — the reason you cannot find this in 1.x

**The architecture calls the feature *custom entities*. ORCA 1.x calls it *reference data*, and nothing in that codebase contains the string "custom entity."** Search for `reference_data`, `ReferenceData`, `rd_` and `ed_` instead. This document exists partly so nobody repeats the search that finds nothing and concludes the feature was never built.

**A second search trap, and it comes from the old repository's own manual.** `Lynxis-Gate/CLAUDE.md` states that table names are singular (*"`workflow`, not `workflows`"*). That is not reliably true and will cost you searches: `sites`, `areas`, `lanes_and_portals`, `work_items`, `workflow_executions`, `device_states` and `reference_datas` are all plural, while `connector_config` and `event_dispatch` are singular. **Search for both forms.**

---

## 0 · The six inversions — what 2.0 must not carry forward

Each is code-verified, and each is a defect rather than a design choice.

1. **`AutoMigrate` is not a migration.** A custom entity's physical table is created and evolved by GORM's schema reconciler at runtime — `tx.Table(tableName).AutoMigrate(referenceDataStruct)` (`:561`, `:1068`, `:1143`). It is a best-effort *reconcile*: it adds what is missing and takes no position on what it cannot safely change. **There is no version, no history, no checksum and no rollback** — nothing anywhere records which shape was applied or when. **2.0: a declared migration, executed once, recorded — the architecture's phrase is "executed as a controlled, declared migration," and that is the whole inversion.**

2. **Destructive DDL runs from an HTTP call.** The same repository calls `Migrator().DropTable(oldLookUpTableName)` (`:1010`), `Migrator().RenameTable(...)` (`:1083`) and `Migrator().RenameColumn(...)` (`:1098`, `:1113`) while servicing a request to rename a reference list. A rename is implemented as *drop-and-recreate-or-rename* depending on the branch taken. **2.0: the single controlled executor decides what DDL is permissible; renaming a declared model does not license dropping a table.**

3. **The table name is user input through a formatter, not an allow-list.** The physical name is `prefix + ConvertToLowercaseWithUnderscores(name)` (`:538`), and that function is, in full:

   ```go
   func ConvertToLowercaseWithUnderscores(input string) string {
       lowercaseString := strings.ToLower(input)
       resultString := strings.ReplaceAll(lowercaseString, " ", "_")
       return resultString
   }
   ```

   It lowercases and replaces spaces. **It removes no quote, semicolon, bracket or any other identifier metacharacter**, and there is no validation of the result before it is concatenated into a name handed to `Table(...)` and `Migrator()`. Whether that is reachable depends on the driver's identifier quoting rather than on anything the application does — which is precisely the property you do not want to depend on. ⚠️ **Security-shaped.** *Its severity assessment lives in a restricted document held by the product owner and the technical lead. It is **not** in either repository you cloned — deliberately excluded from version control, so do not go looking for the file. You do not need it to build the replacement.* **2.0: every identifier is allow-listed** — the architecture already requires it for the query builder (*"allow-listed identifiers, parameterized values"*), and the same rule governs DDL.

4. **The DDL privilege sits inside the general-purpose admin service.** The service that renames a lane and the service that drops a table are the same process with the same database login. **2.0 inverts this twice over:** each service already has its own login reaching only its own schema (ADR-004, asserted by `V004__verify.sql`), and the architecture names `orca-core` as *"the **single controlled executor** of the schema changes they require"*, with a stated non-goal of *"letting any other service alter the schema."*

5. **No drift detection exists.** Traced in both directions: five files mention "drift" and every one is about *configuration* drift or comment-value drift — none is about schema. The closest 1.x comes is reading `Migrator().ColumnTypes(tableName)` (`:1700`) to render a column list. **So `GET /custom-entities/{id}/drift` — "declared-versus-physical, detected, never auto-applied" — is a new capability, not a port.** Design it; do not go looking for it.

6. **The name transform exists in two copies.** `ConvertToLowercaseWithUnderscores` is defined identically in `admin-service/internal/utils/utils.go:1008` and `shared-apis-service/internal/utils/utils.go:738`. Two copies of the function that decides a physical table's name is two places for that decision to diverge. **2.0: one identifier policy, in one place, and it is an allow-list rather than a transform.**

---

## 1 · The shape, exactly as it exists

**Two families of generated table, distinguished only by prefix:**

| Prefix | Constant | What it holds |
|---|---|---|
| `rd_` | `REFERENCEDATAPREFIX` | Reference data — the customer's own lookup lists |
| `ed_` | `EVENTDATAPREFIX` | Event data |

**The declared model lives in `reference_datas`** (the metadata table); the rows live in the generated `rd_*` / `ed_*` table.

**Column conventions on a generated table**, from the rename path (`:1098`, `:1113`) and the column builder (`:2938`):

- A surrogate key named `<table>_id` and an external identifier named `<table>_uuid` — the dual-key convention, with the **table's own name embedded in the column name**. This is why a rename has to rename columns too, and it is why rename is as expensive as it is.
- A user-facing field produces two derived names: `columnAliasName` (lowercased, spaces to underscores) and `columnName` (title-cased, underscores to spaces). The display form and the storage form are computed from the same input at different call sites.

⚠️ **Embedding the table name in its own key columns is the thing to drop.** It converts a rename — a metadata operation — into a schema rewrite. 2.0's convention (`<x>_id` int key plus `<x>_uuid` external identifier) does not need to inherit the table's name into the column.

## 2 · Bulk import and scheduled ingestion

Both halves live in `admin-service`: spreadsheet import (the `xlsx` path, 12 files) and scheduled **SFTP** ingestion (11 files). The architecture keeps both — `POST /custom-entities/{id}/import` and `PUT /custom-entities/{id}/ingestion`.

**Two things to decide rather than inherit**, because 1.x's answers are implicit:

- **What happens to a row that fails validation mid-import** — 1.x's behaviour is per-call-site and not stated anywhere as a rule.
- **Where an SFTP credential lives.** This is the same unanswered question stream 1 hits for connector credentials, arriving from a second direction. ⚠️ **Security-shaped; it belongs to the product owner and the two streams should get one answer, not two.**

## 3 · Licence verification, the other half of this stream

The architecture's position is settled and narrow (§B6, and register item 20 ruled 8 August 2026):

> A licence is a signed artifact issued by Lynxis operations, **verified locally at startup** — no runtime path ever calls a Lynxis service to validate it. It records what the installation is entitled to run, **including how many instances may be active at a site — and that limit is the enforcement**: instances arbitrate through the database lease, an instance beyond the licenced count cannot acquire the right to work. **Machine identity is telemetry carried by the heartbeat, never an enforcement input.**

**What that means for the build, and it is less than it sounds:** the enforcement mechanism — a database lease with a fence token — **already exists** as `platform/lease` and is already proven under a 16-way race. This stream builds *verification and entitlement reading*, then binds the instance count to the lease. It does not invent an enforcement mechanism.

⚠️ **Do not read 1.x for how to do this.** Its licensing behaviour differs from the ruled 2.0 model in two ways, and one of them is restricted — the assessment is held by the product owner and the technical lead and is not in either repository. The part you may safely know: **1.x licences *are* hardware-bound in the enforcement path**, even though the issuance path suggests otherwise, and that binding is precisely what the 8 August ruling replaced with a concurrent-instance limit. **The 2.0 model is a decision, not a port** — build the architecture's, and read 1.x only for the licence file's field list.

## 4 · What this document cannot tell you

- **How many custom entities a real site declares, and how large they get.** The input to the retention and indexing decisions.
- **Whether any fielded site has a reference list whose name would break an allow-list** — relevant only to 1.x, since 2.0 takes no data from it, but it is the question that would tell you whether inversion 3 has ever been exercised in anger.
- **Whether the SFTP ingestion is used at all.** It is built; nothing in the repository says a customer runs it.

*Extracted 10 August 2026. Companions: `docs/core-config-schema-from-1x.md` (whose §0 eleven translation rules govern any further 1.x→2.0 configuration work and apply here in full), `docs/partner-event-api-from-1x.md`, `docs/read-models-notify-from-1x.md`.*
