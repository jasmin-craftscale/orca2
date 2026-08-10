# Credentials at rest and first connector consumer — implementation report

**Status:** complete on `feature/credentials-at-rest`

**Worktree:** `/Users/jasmintankic/Documents/Projects/orca-credentials-at-rest`

**Required base:** local `develop` at `8d2507f` (`docs: approve and hand over credentials at rest`)

**Decision implemented:** Option A and all seven conditions in
`docs/decision-connector-credentials.md`, following
`docs/connector-credentials-plan.md`.

The implementation review units before this report are:

1. `130945b feat(platform): add versioned secret box`
2. `550a386 feat(runtime): persist connector credentials`
3. `57d795c feat(runtime): authenticate outbound basic connectors`
4. `49f106c test(runtime): prove credential rotation and failure properties`

No commit was pushed or merged. The legacy repository was not modified or consulted;
the ORCA `docs/*-from-1x.md` evidence was sufficient.

## 1. Delivered boundary

### Code-only platform primitive

`platform/secrets` is the sixth platform primitive and the thirteenth Gradle module.
It has no schema and no connector vocabulary. It provides:

- immutable, thread-safe `SecretBox`, `SealedSecret` and `SecretPurpose` types;
- JDK `AES/GCM/NoPadding` with a 256-bit key, a fresh 12-byte nonce per seal and a
  128-bit authentication tag;
- binary AAD with a format byte, length-prefixed UTF-8 owner, an ordered component
  count and length-prefixed record components, and a length-prefixed credential kind;
- sealing with only the declared current key and opening with only the key id stored
  on the record, allowing old/new generation coexistence without fallback key search;
- fixed redacted failures for malformed Base64, bad nonce length, unknown generation,
  changed sealed material and wrong purpose;
- strict validation for missing configuration, an empty key ring, a current id absent
  from the ring, malformed Base64, keys other than exactly 32 bytes, and key ids
  outside `[A-Za-z0-9._-]{1,64}`;
- a committed public local fixture which is accepted only with the `local` profile.

Each seal/open operation creates its own JCE `Cipher`. Key bytes are defensively
copied and no key-byte accessor exists.

### Runtime-owned persistence and lifecycle

Runtime migration `V128__connector_credentials.sql` creates a bounded current-state
row and append-only traffic-growing audit. Runtime owns the repository and service;
all reads and writes pass through `ScopeSeam`, establish the installation site scope,
and execute lock/read/decide/current-state/audit as one transaction.

The application service implements the exact explicit intents:

- `SetBasic(principal, Replace(password))` creates or replaces sealed material;
- `SetBasic(principal, Preserve())` preserves ciphertext, nonce and key id byte for
  byte while allowing a principal change;
- `Clear()` retains a materialized metadata row in `NONE` mode and nulls every
  principal/sealed field;
- every successful command checks the supplied expected version and increments once;
- stale commands and invalid no-ops write neither current state nor audit;
- metadata is exactly `mode`, `configured`, `version`, `changedAt`, `changedBy`.

There is no plaintext read model, outbox event, log field or process variable for a
credential. A disabled connector can be provisioned because enablement and credential
lifecycle are deliberately separate.

### First consumer and rotation

`RestConnector` now supports absent/`NONE` and `BASIC` credentials. It loads and, for
Basic, opens the credential before entering the connector's circuit breaker or
bulkhead. It applies the UTF-8 Basic header to one request only. Cached clients contain
only endpoint and timeout configuration—never a default Authorization header or
decrypted value.

Transport cache identity is exactly:

```text
connectorName@baseUrl|deadlineMillis|cv<credentialVersion>
```

Obsolete entries for a connector are evicted when a new version is observed. The
credential row is still read for every call, so correctness does not depend on cache
eviction.

`CredentialRewrapService` selects an ordered locked batch of 1–100 old-key rows,
opens with the row generation, reseals with the current generation and a fresh nonce,
updates only on matching connector/version/old-key predicates, increments once, and
appends one `REWRAP` audit row in the transaction. Unknown-key or tampered rows roll
the whole batch back. `countNeedingRewrap()` supplies convergence evidence. There is
no scheduler or mutation trigger in this slice.

## 2. Deliberately not delivered

The runtime OpenAPI document, public controllers and `/internal/**` surface were not
changed. The existing security chain authenticates people but does not prove the
seeded `EditConnector` entitlement. Authenticated-only access and the installation-
wide internal credential are not authorization for credential administration.

The write-only administration contract and controller therefore remain gated on a
product-owner ruling for that authorization seam. No installer behavior, production
key custody, backup/recovery procedure, public/internal rewrap trigger, later auth
mode, core credential consumer or SFTP behavior was invented.

## 3. Exact storage and configuration shape

### `runtime.connector_credential`

| Column | SQL shape | Meaning |
|---|---|---|
| `site_external_id` | `VARCHAR(64) NOT NULL` | first part of scoped PK and FK |
| `connector_name` | `VARCHAR(64) NOT NULL` | second part of scoped PK and FK |
| `auth_mode` | `VARCHAR(16)` BIN2, not null | exact `NONE` or `BASIC` |
| `auth_principal` | `VARCHAR(256) NULL` | required nonblank/no-colon only for Basic |
| `secret_ciphertext` | `VARCHAR(MAX) NULL` | Base64 sealed bytes; Basic only |
| `secret_nonce` | `VARCHAR(64) NULL` | Base64 12-byte nonce; Basic only |
| `key_id` | `VARCHAR(64)` BIN2, nullable | exact case-sensitive sealing generation; Basic only |
| `credential_version` | `BIGINT NOT NULL` | positive materialized version |
| `updated_at` | `DATETIME2(7) NOT NULL` | shared UTC mapping |
| `updated_by` | `VARCHAR(128) NOT NULL` | nonblank trusted actor |

The composite primary key leads with site. The composite foreign key references the
runtime-owned connector configuration. Database checks enforce the exact mode/state,
positive version, actor, and principal constraints. Principal and actor checks reject
space and the ASCII control-whitespace range `CHAR(9)`–`CHAR(13)`, matching the Java
boundary while continuing to allow legitimate internal spaces.

### `runtime.connector_credential_audit`

| Column | SQL shape |
|---|---|
| `credential_audit_id` | `BIGINT IDENTITY` primary key |
| `site_external_id` | `VARCHAR(64) NOT NULL` |
| `connector_name` | `VARCHAR(64) NOT NULL` |
| `credential_version` | `BIGINT NOT NULL` |
| `auth_mode` | `VARCHAR(16)` BIN2, not null |
| `action` | `VARCHAR(16)` BIN2, not null |
| `occurred_at` | `DATETIME2(7) NOT NULL` |
| `actor` | `VARCHAR(128) NOT NULL` |

Actions are exactly `SET`, `REPLACE`, `CLEAR`, `REWRAP`. There is intentionally no
foreign key and no principal, secret, ciphertext, nonce or key-id column. The index
is `(site_external_id, connector_name, occurred_at)` and the table is declared
traffic-growing with an audit retention class.

The real SQL property observed this redacted lifecycle for one connector:

| Version | Mode | Action | Actor role |
|---:|---|---|---|
| 1 | BASIC | SET | setter |
| 2 | BASIC | REPLACE | preserver |
| 3 | BASIC | REPLACE | replacer |
| 4 | NONE | CLEAR | clearer |
| 5 | BASIC | SET | restorer |

The rotation property separately observed `SET` followed by `REWRAP`; it asserted
the audit schema and values contain only the columns above. Poisoning the audit insert
rolled back the current row; after healing, current state and one audit row committed
together.

### Configuration

The external shape is:

```yaml
orca:
  secrets:
    current-key-id: <key-id>
    keys:
      <key-id>: <Base64 encoding of exactly 32 key bytes>
```

No production default exists. Runtime's `local` profile supplies the public local
fixture. The integration task supplies one freshly generated in-memory key per task
execution, not a second committed fixture. The startup validator refuses the public
fixture outside `local`.

The connector password purpose is owner `runtime`, ordered identity components
`site_external_id` and `connector_name`, and kind `connector-basic-password`.

## 4. Executable evidence

### Baseline before implementation

A disposable SQL Server/Keycloak/stub stack was bootstrapped and core, runtime and
edge were booted before `demo-seed` ran.

- `./gradlew sendPlate -Pplate=CRED-BASELINE-01` was acknowledged. SQL showed one
  completed visit, one executed `RAISE_GATE` command and one `visit.completed` fact.
- The TOS route was changed from 200 to 418. Plate
  `CRED-BASELINE-MANUAL` parked the visit at `MANUAL`, created one work item, and the
  linked demo clerk claimed and completed it. Work-item audit contained `TAKE` then
  `COMPLETE`. The route was restored.
- With all gate-path services stopped,
  `./gradlew check integrationTest --rerun-tasks` completed in 6m21s with **32
  integration suites / 240 tests / 0 failures**.

### Focused real-context properties

`ConnectorCredentialPropertiesIT` contains 25 SQL Server/local-receiver properties.
It proves the V128 constraints, exact mutations, concurrency, scoped locking,
redaction, audit atomicity, credential modes, two-instance visibility, failure
isolation, rotation, rewrap rollback, restore mismatch and UTC behavior.

Important observed outcomes:

- The Basic receiver saw the exact expected UTF-8 Authorization value; absent and
  `NONE` sent no Authorization header.
- A connector parent stored as `TOS` was mutated through `tos`; the credential kept
  the stored parent spelling, the next call authenticated, and rewrap retained a
  readable purpose. A case-only installation spelling difference likewise stored a
  new row with the parent spelling, while an existing differently-spelled child kept
  its own exact AAD identity through preserve, call and rewrap.
- Key generations `Key-v1` and `key-v1` remained distinct in SQL and Java. The old
  row was counted and selected, rewrapped to the exact current id, and the old-key
  count reached zero.
- Two independently constructed service/connector graphs had separate repositories
  and client maps. Instance A wrote version 1; instance B called with version 1. A
  replaced it with version 2; B's next call used version 2 without restart. B retained
  exactly one cached client, proving obsolete version eviction.
- Ciphertext/tag tamper, an unknown key id and a blob copied from another connector
  each made **0 receiver requests** and left the circuit-breaker metrics unchanged.
- The rolling-key test started with two old-key rows. Rewrap results were `(1,1,1)`
  then `(1,1,0)` for selected/rewrapped/remaining; the explicit old-key count was 0.
  The key id and nonce changed, the version incremented, and the receiver still saw
  the same plaintext effect. A bad old-key row rolled the complete batch back.
- A database restored without its referenced key failed closed. Old/new instances
  could read both generations during the simulated rolling deployment; there was no
  fallback search or unauthenticated retry.

`VisitLifecycleIT.oneBasicAuthenticatedTruckCompletesTheGateProcess` creates the
credential through `ConnectorCredentialService`, admits the camera event, observes
the exact Basic header at the real local TOS receiver, executes the barrier, completes
the visit and commits `visit.completed`. The test also finds **0** matching historic
Flowable variables for the password sentinel.

### Final uncached suite

After the focused checks and `./gradlew build`, all gate-path services were stopped
and this command was run unpiped:

```bash
./gradlew check integrationTest --rerun-tasks
```

The latest pre-push review rerun completed in 6m46s with all 73 tasks executed:

| Run | Integration suites | Tests | Failures/errors |
|---|---:|---:|---:|
| Baseline | 32 | 240 | 0 |
| Final | 33 | 266 | 0 |
| Delta | +1 | +26 | 0 |

Independent XML parsing also counted 19 unit-test suites and 80 tests, with zero
failures, errors or skips. The final integration count likewise had zero skips.

The focused runtime uncached gate also completed in 4m28s:

```bash
./gradlew :platform:secrets:check :services:orca-runtime:check \
  :services:orca-runtime:integrationTest --rerun-tasks
```

### Clean migration, startup and live regression

Before review remediation, the local database volume was removed and recreated.
Bootstrap created seven schemas and logins, and runtime applied all 15 migrations
through V128. The corrected, still-unmerged V128 is now also exercised on the
isolated integration schema by the focused and full suites. For a real startup proof,
the runtime service was pointed at a purpose-built empty review database with its own
runtime login. Flyway applied all 15 migrations through the corrected V128 before the
application reached the secrets validator. The review database and login were then
removed and confirmed absent.

The owner compose database has the earlier V128 checksum. It was neither repaired
nor deleted during remediation because that requires explicit approval. Therefore a
new fresh-volume live proof was not attempted; the prior independent `NONE` live path
below remains valid because the connector consumer did not change, while the full
`VisitLifecycleIT` supplies the corrected Basic gate-path proof. A new compose-volume
live run requires approval to recreate that owner data.

Two negative starts were proved independently against that clean migrated database,
with a non-fixture internal credential and no active `local` profile:

1. no secrets configuration failed in `SecretsConfigurationValidator` with
   `orca.secrets.current-key-id is missing or blank.`;
2. explicitly supplying the public local fixture without `local` failed in that
   validator because the committed local fixture was present while `local` was
   inactive.

Core, runtime and edge then booted in order with `local`; all three health endpoints
reported `UP`. `demo-seed` ran only after migration. The final sender/receiver/database
evidence was:

- sender: `./gradlew sendPlate -Pplate=CRED-REGRESSION-01`, acknowledged by edge;
- runtime: visit `vis-ee3089cf-0b77-4954-a710-7d81bc78dec6` reached `COMPLETED`;
- device-host receiver: latest command was `RAISE_GATE`, status `EXECUTED`;
- database: runtime outbox sequence 1 was `visit.completed` for the demo lane;
- database: `connector_credential=0` and `connector_credential_audit=0`, proving
  absent credential remains backward-compatible `NONE` without an administration
  surface.

The post-recreation isolation check passed **36/36**: every login wrote/read its own
schema and all 30 cross-schema reads were refused.

### Leakage scan

The scratch-schema properties assert the known test password is absent from the
materialized row values, audit values, metadata JSON, exceptions, secret-bearing
value string representations, captured TRACE log text, receiver-failure surfaces and
Flowable history. They also assert that the complete encoded Basic Authorization
header is absent from captured logs and metadata has no field capable of returning a
principal or sealed material.

After the final suite/live run, a constrained SQL diagnostic searched credential
state, audit, runtime outbox, Flowable historic variables, execution-event attributes
and edge command params/response/detail. All six result counts were 0.

The same two test-only sentinels were scanned in:

- platform test XML and HTML reports: 0 files with hits;
- runtime test XML and HTML reports: 0 files with hits;
- runtime generated API artifacts: 0 files with hits;
- root build reports: 0 files with hits;
- workspace log files: 0 files with hits.

Source test fixtures are deliberately outside that scan: they define the sentinel
used to prove all output and persistence boundaries are clean. No secret or sealed
value is reproduced in this report.

## 5. Deliberate mutation evidence

Every mutation was reverted immediately. `rg "deliberate mutation"` and production
diff checks returned no residue; the restored platform suite and final uncached tree
were green.

### Platform mutations

| # | Deliberate mutation | Focused property | Observed failure |
|---:|---|---|---|
| 1 | use a different key on open | `unicodePlaintextRoundTrips` | `SecretOpenException` instead of round-trip |
| 2 | constant nonce | `everySealUsesAFreshNonce` | two nonces were equal |
| 3 | swallow tag failure | `changedCiphertextOrTagFailsRedacted` | expected throwable was absent |
| 4 | ignore the stored nonce | `changedNonceFailsRedacted` | expected throwable was absent |
| 5 | remove purpose AAD | `aSealedValueCannotMoveToAnotherRecord` | copied value opened; expected throwable absent |
| 6 | always open with current key | `oldGenerationOpensAfterTheCurrentGenerationChanges` | old generation could not open |
| 7 | seal with first map entry | `sealAlwaysWritesTheDeclaredCurrentGeneration` | expected `v2`, got `v1` |
| 8 | remove missing-current-id guard | `missingCurrentIdIsRefusedIndependently` | wrong later error replaced required missing-id error |
| 9 | remove empty-ring guard | `emptyKeyMapIsRefusedIndependently` | wrong later error did not identify the key ring |
| 10 | remove current-id-in-ring guard | `currentIdMustNameAConfiguredKey` | expected throwable was absent |
| 11 | accept malformed Base64 | `malformedBase64IsRefusedWithoutPrintingTheValue` | expected throwable was absent |
| 12 | accept non-32-byte key | `everyKeyMustDecodeToExactlyThirtyTwoBytes` | expected throwable was absent |
| 13 | accept invalid/overlong key id | `blankInvalidAndOverlongKeyIdsAreRefused` | required invalid-id failure disappeared |
| 14 | remove public-fixture profile guard | `thePublicFixtureIsRefusedOutsideLocalRegardlessOfBase64Padding` | expected throwable was absent |
| 15 | remove decoded 12-byte nonce check | `nonceMustDecodeToExactlyTwelveBytes` | direct nonce-length assertion failed |
| 16 | allow null to reach low-level code | `nullPlaintextAndBlankPurposePartsAreDeliberatelyRefused` | low-level NPE escaped instead of deliberate refusal |
| 17 | append rejected crypto text | `changedCiphertextOrTagFailsRedacted` | exact fixed redacted message assertion failed |

For mutation 17 an initial malformed-Base64 target remained green because an earlier
decode boundary already redacted the value. The mutation was then placed on the
authentication/tag failure path; the exact-message property failed, proving the
crypto-error boundary itself.

### High-risk runtime mutations

| Deliberate mutation | Focused property | Observed failure |
|---|---|---|
| drop connector-bound AAD | `copiedBlobFailsAtDestinationPurpose` | destination copy opened; expected failure absent |
| fall back to `NONE` after open failure | `credentialFailuresMakeNoCallAndDoNotTouchBreaker` | receiver was called instead of fail-closed |
| remove expected-version update predicate | `guardedUpdatePredicateCannotBeRemoved` | stale update count was 1, expected 0 |
| cache default Authorization and omit version behavior | `independentInstanceSeesReplacementWithoutRestart` | second call retained version-1 header |
| omit audit insert | `exactMutationSemantics` | expected audit action sequence was empty |
| move audit outside mutation transaction | `poisonedAuditRollsBackCurrentState` | current row advanced to version 2 instead of remaining at 1 |
| append nested transport exception text | `transportFailureMessageIsFixedAndRedacted` | fixed-message assertion exposed URL/exception text |

## 6. Operational key rotation sequence

1. Distribute old + new keys to every runtime instance while old remains current.
2. Verify every instance can open existing old-key rows.
3. Make the new key current and roll every instance. Do not begin final rewrap while
   an old-current writer can still create old-key rows.
4. Execute bounded rewrap batches until the old-key count reaches zero and remains
   zero after normal connector activity.
5. Verify live connector calls and database/key backups together.
6. Only then remove the old key in a later controlled deployment.

A stolen database or database backup alone does not disclose the credentials. A
compromised running runtime host can disclose them because runtime must present the
plaintext to the customer system; this implementation does not claim otherwise.

## 7. Findings and decisions not dictated by the plan

- **SQL Server JDBC lock specificity:** the driver binds Java strings as NVARCHAR.
  Against the composite VARCHAR connector key, the original `UPDLOCK` predicate
  caused a scan and blocked a different connector at the same site. The repository
  now keeps the scope predicate first and explicitly casts both lock parameters to
  `VARCHAR(64)`. The real concurrency property proves different connectors no longer
  share that lock. This is a query-shape correction, not a product/security decision.
- **Schema-rewind fixture:** `FlowableAdoptionIT` rewinds runtime migrations. V128's
  new FK made its old cleanup order incomplete, so it now drops audit then current
  credential state before connector configuration. This is test-fixture maintenance,
  not a migration rollback mechanism.
- **Baseline environment:** the first local compose volume contained a stale active
  visit. Because the local database is documented as disposable, it was recreated
  before recording baseline evidence. No production code was changed to accommodate
  that local state.
- **Audit naming:** preserving sealed bytes while changing credential state records
  the approved `REPLACE` action; no extra action value was invented.
- **Exact database identity:** SQL Server compares connector/site identifiers without
  case while purpose AAD is byte-exact. The locked parent read now supplies the stored
  identity for new rows; existing rows keep their stored identity, and only the
  already-authorized site scope is restated with that database spelling for writes.
- **Key-generation collation:** key ids are Java map keys and therefore
  case-sensitive. V128 now gives `key_id` BIN2 collation so SQL selection, guarded
  rewrap and convergence counts share that contract.
- **Database blank backstop:** SQL `LEN(LTRIM(RTRIM(...)))` ignores ordinary spaces
  but not control whitespace. The actor/principal checks now require a character
  outside space and `CHAR(9)`–`CHAR(13)`; real SQL tests cover each form and internal
  spaces.
- **Separate edge reliability signal:** an independent review saw one timeout in
  unchanged `EdgeIngestPropertiesIT.aCaptureWithNoDedupKeyIsRefused`; its individual
  property, class and a second full-tree run passed. This credentials branch makes
  no edge change; timing reliability remains a separate follow-up.
- No authorization, key-custody, installer, public-contract, commercial or adjacent
  scope decision was needed or made. No third-party dependency was added.

The shared UTC helper was already present and is reused; there is no new duplicate to
promote from this slice.

## 8. Ruled follow-ons

1. Rule and implement an authorization seam proving `EditConnector`.
2. Add the write-only OpenAPI/controller surface and its HTTP-specific redaction
   property only after that ruling.
3. Define installer provisioning of distinct runtime/core production key material,
   database/key backup and recovery, and an authorized production rewrap trigger.
4. Reuse the primitive for later connector authentication modes while preserving
   purpose binding and credential-version cache identity.
5. Let Stream 3 consume the primitive for SFTP only after SFTP scope and host-key
   verification are separately settled.

## 9. Reviewer rerun commands

From this worktree, with Docker available and no gate-path services running:

```bash
git status --short --branch
git log --oneline --decorate -7

cd deploy
docker compose up -d
docker compose run --rm bootstrap
docker compose run --rm verify-isolation
cd ..

./gradlew :platform:secrets:test --rerun-tasks
./gradlew :services:orca-runtime:integrationTest \
  --tests '*ConnectorCredentialPropertiesIT' --rerun-tasks
./gradlew :services:orca-runtime:integrationTest \
  --tests '*VisitLifecycleIT' --rerun-tasks
./gradlew build --rerun-tasks
./gradlew check integrationTest --rerun-tasks
```

For the clean migration/live proof, first confirm the local database is disposable:

```bash
cd deploy
docker compose down -v
docker compose up -d
docker compose run --rm bootstrap
cd ..

./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-edge --args='--spring.profiles.active=local'

cd deploy
docker compose run --rm demo-seed
cd ..
./gradlew sendPlate -Pplate=CRED-REVIEW-01
```

Run the three services in separate terminals and run `demo-seed` only after their
migrations complete. Query the runtime execution/outbox and edge command log for the
review plate using `docs/LOCAL_DEVELOPMENT.md` §5, “Looking at the database”. Follow
§6.1 only to stop all three services and Gradle workers before any integration suite.

The two negative runtime starts use a non-fixture internal credential and deliberately
do not activate `local`:

```bash
./gradlew bootRun -p services/orca-runtime \
  --args='--server.port=18082 --orca.internal.shared-credential=review-only-internal-credential-0000000000000000'
# expected: orca.secrets.current-key-id is missing or blank.

./gradlew bootRun -p services/orca-runtime \
  --args='--server.port=18082 --orca.internal.shared-credential=review-only-internal-credential-0000000000000000 --orca.secrets.current-key-id=local-dev-v1 --orca.secrets.keys[local-dev-v1]=b3JjYS1sb2NhbC1zZWNyZXQta2V5LWZpeHR1cmUtMzI='
# expected: orca.secrets.keys contains the committed local development fixture while the `local` profile is inactive.
```

That key is committed, public, development-only material. It is shown here solely to
prove the startup refusal and must never be used or copied into a deployment profile.

Count the final XML without relying on Gradle's console summary:

```bash
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
paths = list(Path('.').glob('**/build/test-results/integrationTest/TEST-*.xml'))
suites = tests = failures = 0
for path in paths:
    root = ET.parse(path).getroot()
    suites += 1
    tests += int(root.get('tests', 0))
    failures += int(root.get('failures', 0)) + int(root.get('errors', 0))
print(f'integrationTest: suites={suites} tests={tests} failures={failures}')
PY
```
