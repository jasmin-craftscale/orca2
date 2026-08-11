# Implementation plan — credentials at rest and the first connector consumer

**Audience:** one autonomous implementing agent in a fresh session

**Decision:** approved by the product owner on 10 August 2026

**Decision record:** `docs/decision-connector-credentials.md`, Option A and all seven conditions

**Integration branch:** `develop`

**Feature branch:** `feature/credentials-at-rest`

**Runtime migration allocation:** V128–V137; this plan uses **V128**

This plan builds the generic encryption primitive once, stores connector credentials
in the schema that owns their use, adds `NONE` and `BASIC` to the existing outbound
REST connector, supports key-version coexistence and bounded rewrap, and proves the
whole path under concurrency, tamper and restore failure.

It deliberately does **not** publish a credential-mutation HTTP endpoint yet. The
current shared security chain authenticates callers and explicitly implements no
per-route authorization. Exposing a write-only secret endpoint under
`anyRequest().authenticated()` would allow any authenticated user to change a
connector credential. The installation-wide `/internal/**` credential is not an
authorization substitute either: it grants the same `ROLE_ORCA_SERVICE` to every
service and its caller name is explicitly untrusted. The product owner approved how
credentials are protected at rest; they did not silently approve either of those
mutation policies.

The application service and future wire contract are specified here so the HTTP
surface becomes a small follow-on when authorization is ruled. The implementation
in this plan is still end to end: a real runtime context writes an encrypted record
through the application service, the existing gate process resolves and decrypts it,
and a real HTTP server observes the correct Basic header. Public administration is a
named gap, never a hidden insecure shortcut.

---

## 0 · Non-negotiable operating rules

### 0.1 · Use an isolated checkout

Four developers are intended to work at once. Prefer a separate clone or Git
worktree per developer. If this session is forced to use the shared checkout, assume
another agent can move `HEAD` between any two commands.

Start from the branch the product owner created:

```bash
cd ~/Documents/Projects/orca
git fetch --prune origin
git status --short --branch                 # must be clean

# A fresh clone may not have a local develop branch yet.
if git show-ref --verify --quiet refs/heads/develop; then
  git switch develop
else
  git switch --track -c develop origin/develop
fi

git pull --ff-only origin develop
git switch -c feature/credentials-at-rest
git status --short --branch
```

Do not commit, push or open a pull request unless the session prompt explicitly
authorises it. If staging is authorised, run the branch check in the same shell
command as every `git add`:

```bash
git rev-parse --abbrev-ref HEAD && git add <exact-paths>
```

Never add a `Co-Authored-By` trailer or tool attribution.

### 0.2 · Stop on a false premise

Before editing, verify every file and symbol named in this plan. If a path moved, a
migration number is occupied, `develop` diverged materially, or a production shape
does not match this plan, stop and report the discrepancy. Do not silently repair the
plan by inventing a neighbouring design.

Security, commercial scope, a new dependency, key custody, authorization and a
change to a public contract are product-owner decisions. Surface them; do not infer
them from convenience.

### 0.3 · Read before changing

Read these in order, in full where the repository says to:

1. `docs/ORCA_ORCHESTRATOR_HANDOVER.md`
2. `docs/BUILD_ROADMAP.md`
3. `docs/CODE_PATTERNS.md`
4. `docs/decision-connector-credentials.md`
5. `docs/MIGRATION_NUMBER_RANGES.md`
6. `docs/LOCAL_DEVELOPMENT.md`, especially §6.1
7. `docs/stream-1-plan.md` and `docs/partner-event-api-from-1x.md`
8. `platform/AGENTS.md` and `services/orca-runtime/AGENTS.md`

Then read the load-bearing production code directly:

- `platform/scope`: `ScopeSeam`, `ScopedSelect`, `ScopedInsert`, `ScopedUpdate`,
  `JdbcScopeSeam`
- `platform/web`: `PlatformSecurityAutoConfiguration`,
  `InternalCallAuthenticationFilter`, `InternalCredentialValidator`
- `services/orca-runtime/.../integration`: `IntegrationConfiguration`,
  `ConnectorTables`, `ConnectorConfigRepository`, `RestConnector`
- `services/orca-runtime/src/main/resources/db/migration/V102__connectors.sql`
- `services/orca-runtime/src/main/resources/openapi/orca-runtime.yaml`
- `settings.gradle.kts`, `build-checks/build.gradle.kts`, and every test under
  `build-checks`

The old system is read-only. If evidence must be rechecked there, first read
`../Lynxis-Gate/CLAUDE.md`; prefer `docs/*-from-1x.md` when one covers the path.

---

## 1 · Establish and record the real baseline

At plan-writing time `origin/main` and `origin/develop` both pointed to `be71b97`,
and the verified baseline carried 32 integration suites / 240 tests / 0 failures.
That is context, not evidence for a later session. Re-run it.

### 1.1 · Bring up and understand the existing product spine

On a fresh database the order is load-bearing: `demo-seed` writes application tables
and therefore runs only after the owning services have migrated them.

```bash
cd ~/Documents/Projects/orca/deploy
cp -n .env.example .env
docker compose up -d
docker compose run --rm bootstrap
docker compose run --rm verify-isolation       # expect PASS — 36 checks
cd ..
```

Boot core first, then runtime and edge, each in its own terminal:

```bash
./gradlew bootRun -p services/orca-core    --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-edge    --args='--spring.profiles.active=local'
```

After all three are healthy:

```bash
cd deploy && docker compose run --rm demo-seed && cd ..
./gradlew sendPlate -Pplate=CRED-BASELINE-01
```

Follow the visit in SQL and then force the existing exception branch and carry its
work item through claim and completion exactly as `docs/phase-1-demo.md` and
`docs/phase-3-report.md` §4.1 describe. Record the commands and results; do not
substitute a report from another session.

### 1.2 · Stop services before the suite

The live services and integration tests share the runtime schema. Running them
together produces false concurrency failures.

```bash
for p in 8081 8082 8083 18081 18082 18083; do
  PID=$(lsof -nP -iTCP:$p -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$PID" ] && kill "$PID"
done
./gradlew --stop
ps aux | grep -c "[G]radleWorkerMain"       # expect 0
```

Run the uncached baseline, unpiped:

```bash
./gradlew check integrationTest --rerun-tasks
```

Count what actually executed:

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
suites = tests = failures = 0
for path in glob.glob('**/build/test-results/integrationTest/*.xml', recursive=True):
    root = ET.parse(path).getroot()
    suites += 1
    tests += int(root.get('tests', 0))
    failures += int(root.get('failures', 0)) + int(root.get('errors', 0))
print(f'integrationTest: suites={suites} tests={tests} failures={failures}')
EOF
```

Save the counts in the implementation report. If failures are not zero, stop; do
not build security work on a red baseline. `--rerun-tasks` is mandatory and the
Gradle command must not be piped into `tail`, `head` or `grep`.

---

## 2 · Fixed design and scope

### 2.1 · What this slice implements

| Mode | Principal | Recoverable secret | Behaviour |
|---|---|---|---|
| `NONE` | none | none | preserve today's unauthenticated connector call |
| `BASIC` | username, stored as non-secret configuration | password | one per-request HTTP Basic header |

The old names `NOAUTH` and `BASEAUTH` are translated at the boundary to the clearer
2.0 values above. Do not add `OAUTH`, custom-header authentication, SOAP, SFTP,
mutual TLS, client certificates, server trust material or host fingerprints. They
are later consumers or separate decisions.

The inherited `PRIVATEKEY` mode is actually an arbitrary HTTP header value. It is
not an asymmetric private key. Do not let that inherited name shape this primitive.

### 2.2 · Protection boundary

- SQL Server and backups hold ciphertext, key id, nonce and metadata.
- Runtime receives its own set of versioned AES keys from deployment configuration.
- Runtime and core will use the same primitive but not the same raw key material.
- A stolen database alone does not disclose the credential.
- A compromised running runtime host can disclose it because runtime must present it
  to the customer system. Do not claim otherwise.
- No plaintext secret may enter an API response, view, outbox row, process variable,
  audit record, exception message, structured log field or client cache.

### 2.3 · Write semantics implemented below the future controller

The domain/application service must support these exact operations even though the
public controller is deferred:

| Operation | Required expected version | Result |
|---|---:|---|
| create `BASIC` | `0` | insert version `1` with principal and newly sealed password |
| set `BASIC` after a prior clear | current `NONE` row version | update to `BASIC`, action `SET`, increment once |
| replace `BASIC` password and/or principal | current version | update and increment version once |
| preserve password while changing principal | current version | decrypt nothing; keep sealed fields, increment version once |
| clear an existing `BASIC` credential | current version | retain metadata row as `NONE`, null principal/sealed fields, increment version once |
| read metadata | — | mode, configured flag, version, changed-at/by only |

An omitted password means preserve. It is valid only when an existing `BASIC`
credential is present and the principal actually changes; a preserve command with no
other change is a typed no-op refusal, not a version bump. Explicit clear is valid only
for an existing `BASIC` row and means mode `NONE`; clearing absence/already-`NONE` is
a typed no-op refusal. `BASIC` with no recoverable password is never a stored state.
Reject a Basic username containing `:` because the wire encoding makes the first
colon the principal/password separator.

Audit action meanings are fixed: `SET` is any `NONE`/absence → `BASIC` transition,
`REPLACE` is a real `BASIC` → `BASIC` mutation, `CLEAR` is `BASIC` → `NONE`, and
`REWRAP` changes only key generation. Rejected/no-op commands write neither current
state nor audit.

The expected version is not optional. It prevents two administration instances from
silently overwriting each other. A stale version produces a typed conflict for the
future controller to map to HTTP 409.

### 2.4 · Authorization boundary

Do not edit the runtime OpenAPI document and do not add a credential controller in
this feature. Leave a concise TODO in the report, not production code. The follow-on
may expose the write-only contract only after the programme chooses how runtime can
prove the caller holds the seeded `EditConnector` entitlement. That likely needs a
published authorization seam from core or a ruled identity-provider claim mapping;
this plan chooses neither.

Do not place the endpoint under `/internal/**`. The shared installation credential
authenticates service-shaped traffic, not a connector administrator, and every
service receives the same authority.

### 2.5 · Ruling-to-delivery map

No condition in the decision record is optional. This slice delivers each condition
at the deepest safe layer available now:

| Approved condition | This feature's executable commitment | Named follow-on |
|---|---|---|
| write-only semantics | explicit replace/preserve/clear commands; metadata contains mode/presence/version/time/actor only; serialization and leakage properties | publish that same contract only after the `EditConnector` authorization seam is ruled |
| purpose-bound encryption | fresh GCM nonce and binary AAD bound to runtime + site + connector + credential kind; cross-row copy property | core supplies its own owner/record/kind purpose when SFTP consumes the primitive |
| rotation from release one | key-id coexistence, bounded rewrap, fail-closed restore-mismatch properties and the ordered operational sequence in §9 | installer-owned trigger, backup and recovery procedure |
| one lifecycle, scoped keys | one generic primitive and owner-schema records; runtime configuration accepts only runtime's key ring | installer provisions distinct runtime/core production material through one procedure |
| credential version in cache identity | versioned transport identity, per-request header and obsolete-entry eviction | later auth modes reuse the same rule |
| redacted audit and failure | append-only audit in the mutation transaction; fixed failures; rollback and leakage properties | authorized HTTP boundary supplies the ruled human identity |
| property tests | platform, SQL Server, two-instance, full gate-path, tamper, rotation, restore and redaction properties in §10 | API-specific request/response redaction property ships with the authorized controller |

The HTTP-specific cells are openly deferred because the authorization decision is
still open. Do not report the public administration surface complete; report the
storage, application-service and existing gate consumer complete only when all of
their properties pass.

---

## 3 · Work-package dependency map

```text
WP1 module/build wiring ──> WP2 platform cipher/configuration ──┐
                                                               ├─> WP4 repository/service ─> WP5 connector consumer
WP3 runtime V128 migration ─────────────────────────────────────┘

WP6 rotation/rewrap uses WP2 + WP4
WP7 integration properties uses WP2–WP6
WP8 documentation/report follows all verification
```

WP1 and WP3 can be developed independently after the migration number is rechecked.
Everything else is intentionally sequential. Keep each work package reviewable; do
not combine it with Stream 1 Track A or the future connector administration surface.

---

## 4 · WP1 — register the sixth platform primitive

Create `platform/secrets` as a generic, code-only module. It owns no table and has no
Flyway migration.

### 4.1 · Gradle and importer wiring

1. Add `:platform:secrets` beside the other platform modules in
   `settings.gradle.kts`.
2. Update the module-count comment from twelve to thirteen.
3. Model `platform/secrets/build.gradle.kts` on the other `java-library` primitives.
   Use only JDK crypto and existing Spring Boot libraries. Add no third-party crypto
   or secret-manager dependency.
4. Add `testImplementation(project(":platform:secrets"))` to
   `build-checks/build.gradle.kts`.
5. Add one representative production class from the module to
   `ImportedSetGuard.everyModuleIsVisible()` and rename its “twelve modules” display
   text/comment to thirteen.
6. Add `implementation(project(":platform:secrets"))` to
   `services/orca-runtime/build.gradle.kts`.

### 4.2 · Current documentation inventories

Update every **current-state** inventory affected by the thirteenth module and sixth
primitive:

- `README.md`
- `AGENTS.md` — update the current navigation question, but keep the sentence that
  Phase 0 historically delivered twelve modules/five primitives
- `platform/AGENTS.md`
- `build-checks/AGENTS.md`
- `docs/CODE_PATTERNS.md`
- `docs/PLATFORM_PRIMITIVES.md`
- `docs/REPOSITORY_GUIDE.md` — update the current layout and current quick-reference
  rows, but keep its historical Phase 0 statement
- `docs/ORCA_ORCHESTRATOR_HANDOVER.md` — update the current navigation paragraph

Add the secret primitive's pattern, purpose and failure property to the two primitive
guides; do not merely change “five” to “six”. Historical phase reports remain
historical. Do not add a platform migration location to service Flyway configuration;
this primitive has no schema.

`platform/AGENTS.md` carries a Phase 0 note saying the table-declaration annotations
were the first thing to move if another module was ever added. Do **not** turn this
security feature into an unrelated repository-wide package move. Rewrite that note
accurately: the approved secrets module supersedes the old fixed-at-twelve premise;
the annotations remain in `platform/scope` as deliberate technical debt until a
separate cohesion refactor is authorized. Moving them would touch every service and
does not improve secret handling.

### 4.3 · Acceptance

- `./gradlew :platform:secrets:check` resolves the module.
- `ImportedSetGuard` fails if the build-check dependency is deliberately removed.
- `PlatformPurityRule` remains green: no class or identifier in `platform/` names a
  connector, lane, visit, ticket, driver or truck.

---

## 5 · WP2 — implement the generic cryptographic primitive

Package: `com.lynxis.orca.platform.secrets`.

### 5.1 · Public types

Implement a deliberately small API:

- `SecretBox`
  - `SealedSecret seal(String plaintext, SecretPurpose purpose)`
  - `String open(SealedSecret sealed, SecretPurpose purpose)`
  - `String currentKeyId()`
- `SealedSecret`
  - `String keyId`
  - `String nonceBase64`
  - `String ciphertextBase64`
- `SecretPurpose`
  - owner namespace
  - ordered record-identity components
  - credential kind
- `SecretConfigurationException` for invalid key/configuration state
- `SecretOpenException` for unknown key, malformed sealed data, wrong purpose or
  authentication failure

The platform module must not contain `ConnectorSecret`, `SftpSecret` or any other
consumer vocabulary.

`SecretBox` must be immutable and thread-safe. JCE `Cipher` instances are not
thread-safe, so create one per seal/open operation; never cache one in a field. Keep
an immutable defensive copy of decoded key material and expose no key byte array.

### 5.2 · Cipher rules

- JDK `AES/GCM/NoPadding` only.
- AES-256: each decoded key is exactly 32 bytes.
- Fresh 12-byte nonce from `SecureRandom` for every seal.
- 128-bit authentication tag.
- Store key id, nonce and ciphertext/tag separately through `SealedSecret`.
- Base64 is a storage encoding, not encryption.
- `seal` always uses the configured current key.
- `open` uses the record's key id, so old and new generations coexist during
  rotation.
- Key ids must match `[A-Za-z0-9._-]{1,64}`. Validate every configured id at
  startup so a value can never seal successfully and then fail the consumer's
  `VARCHAR(64)` column.
- Reject a decoded nonce whose length is not exactly 12 bytes before invoking JCE.
- Reject null plaintext, null purpose or a null/blank purpose component with a fixed
  message; do not let a low-level `NullPointerException` become the contract.
- Unknown key id, invalid Base64, wrong purpose and authentication-tag failure all
  throw a fixed, redacted exception. Do not include input, plaintext, ciphertext,
  nonce, key bytes or the nested crypto exception's message.
- Never fall back to a different key and never return the input on failure.

### 5.3 · Unambiguous authenticated context

`SecretPurpose` is GCM additional authenticated data. Encode it as a versioned binary
sequence, not string concatenation:

1. one format-version byte (`1`);
2. owner namespace as UTF-8 preceded by its four-byte length;
3. a four-byte count followed by each ordered record-identity component, each
   length-prefixed;
4. credential kind as one final length-prefixed UTF-8 component.

For the runtime consumer use:

- owner namespace: `runtime`
- record identity components: `List.of(siteExternalId, connectorName)`; do not join
  them into one ambiguous string
- credential kind: `connector-basic-password`

Copying the three sealed fields from connector A to connector B must fail to open.

### 5.4 · Configuration and startup validation

Add `SecretsProperties` with prefix `orca.secrets`:

- `currentKeyId`
- `Map<String,String> keys`, key id to Base64-encoded 32-byte AES key

Add `SecretsConfigurationValidator implements InitializingBean` and
`SecretsAutoConfiguration`; register the auto-configuration in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Model the startup shape on `platform/web`'s internal credential configuration and
validator. Runtime must refuse startup when the module is on its classpath and any
of these independent clauses fails:

1. current key id is absent or blank;
2. the key map is null or empty;
3. the current key id is not a key in the map;
4. any configured value is malformed Base64;
5. any decoded key is not exactly 32 bytes;
6. any key id is blank, longer than 64 characters or contains a character outside
   `[A-Za-z0-9._-]`;
7. the committed local fixture is present while the `local` profile is inactive.

Each clause gets its own test. Exception text names the property or key id where
useful but never prints a configured value.

The auto-configuration must enable `SecretsProperties`, expose exactly one
`SecretsConfigurationValidator` and one `SecretBox`, and make `SecretBox` depend on
the successfully initialized validator (constructor/bean dependency, not assumed
bean discovery order). The validator may expose an immutable validated key ring to
the box; neither that ring nor decoded key bytes are public API. `SecretBox` must
also defend its direct-construction path so a unit test or future non-Spring caller
cannot bypass key-id, Base64 or key-length validation.

Do not make the whole auto-configuration conditional on a property being present.
Once runtime depends on this module, missing secret configuration is a startup
failure, not a feature toggle that silently disables decryption.

### 5.5 · Runtime configuration

Do not put a usable key or forgiving default in the base part of runtime's
`application.yaml`. Production receives `orca.secrets.current-key-id` and the key
map through installer-owned external Spring configuration.

Add this public development fixture only to the existing `local` profile document:

```yaml
orca:
  secrets:
    current-key-id: local-dev-v1
    keys:
      local-dev-v1: b3JjYS1sb2NhbC1zZWNyZXQta2V5LWZpeHR1cmUtMzI=
```

The decoded text is `orca-local-secret-key-fixture-32`, exactly 32 bytes. It is
public and the validator must refuse it outside `local`.

Do not rely on `deploy/.env.example` to configure host `bootRun`; Docker Compose's
environment does not automatically reach a Gradle process on the host. A future
installer supplies production external configuration. Record that deployment work
in the report; do not invent the installer here.

Spring integration tests that boot runtime also need a valid key. Configure the
runtime module's `integrationTest` Gradle task with one freshly generated in-memory
32-byte Base64 value per task execution, using the property names
`orca.secrets.current-key-id=integration-test-v1` and
`orca.secrets.keys[integration-test-v1]=<generated value>`. Do not commit a second
fixed key that the production validator would accept outside `local`, and do not
edit dozens of tests one by one.

---

## 6 · WP3 — runtime migration V128

Before writing it:

```bash
find services/orca-runtime/src/main/resources/db/migration -maxdepth 1 -type f -print | sort
```

If V128 is occupied, stop. Do not take V129 silently; the range document is the
coordination contract.

Create
`services/orca-runtime/src/main/resources/db/migration/V128__connector_credentials.sql`.
Create the bounded current-state table `connector_credential`:

| Column | SQL Server type | Rule |
|---|---|---|
| `site_external_id` | `VARCHAR(64) NOT NULL` | scope; first PK/FK column |
| `connector_name` | `VARCHAR(64) NOT NULL` | second PK/FK column |
| `auth_mode` | `VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL` | exactly `NONE`, `BASIC` |
| `auth_principal` | `VARCHAR(256) NULL` | Basic username; null for `NONE` |
| `secret_ciphertext` | `VARCHAR(MAX) NULL` | Base64 ciphertext plus GCM tag |
| `secret_nonce` | `VARCHAR(64) NULL` | Base64 12-byte nonce |
| `key_id` | `VARCHAR(64) NULL` | key generation used to seal |
| `credential_version` | `BIGINT NOT NULL` | starts at 1 for a materialized row |
| `updated_at` | `DATETIME2(7) NOT NULL` | application writes UTC |
| `updated_by` | `VARCHAR(128) NOT NULL` | actor identifier, never secret |

Constraints:

1. primary key `(site_external_id, connector_name)`;
2. composite foreign key to
   `connector_config(site_external_id, connector_name)`;
3. binary-collated mode check over exactly `('NONE','BASIC')`;
4. version check `credential_version >= 1`;
5. state consistency:
   - `NONE` requires principal, ciphertext, nonce and key id all null;
   - `BASIC` requires all four non-null and nonblank;
   - a `BASIC` principal may not contain `:`;
6. `updated_by` must be nonblank.

There is no row until the credential is first administered. Absence therefore means
`NONE`, `configured=false`, version `0`, preserving all existing connectors. Clear
keeps a metadata row in `NONE` and increments its version; do not delete it.

Add a `@PersistentTable(name = "connector_credential", growth = Growth.BOUNDED)`
record beside the existing connector declarations. One row per connector is bounded,
so it gets no retention class. The PK already leads with the scope column.

The ruling also requires a **historical redacted audit**, not merely the current
row's `updated_*` fields. In the same V128 migration create append-only
`connector_credential_audit`:

| Column | SQL Server type | Rule |
|---|---|---|
| `credential_audit_id` | `BIGINT IDENTITY(1,1)` | primary key |
| `site_external_id` | `VARCHAR(64) NOT NULL` | scope |
| `connector_name` | `VARCHAR(64) NOT NULL` | identifies what changed; deliberately no FK |
| `credential_version` | `BIGINT NOT NULL` | resulting version |
| `auth_mode` | `VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL` | resulting `NONE`/`BASIC` |
| `action` | `VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL` | `SET`, `REPLACE`, `CLEAR`, `REWRAP` |
| `occurred_at` | `DATETIME2(7) NOT NULL` | UTC |
| `actor` | `VARCHAR(128) NOT NULL` | who caused it |

Add binary checks for `auth_mode` and `action`, positive version and nonblank actor.
Add an index beginning `(site_external_id, connector_name, occurred_at)`. Do not put
principal, ciphertext, nonce, key id, plaintext, request JSON or a free-text details
column in this table. It deliberately has no FK: an audit row must survive later
connector retirement and must not make the historical record mutable through a
cascade.

Declare it as `Growth.TRAFFIC_GROWING` with the existing provisional
`@RetentionClass("audit")`. It grows with configuration changes rather than truck
traffic, but it is append-only and unbounded; `BOUNDED` would be a false declaration.
Stream 4 will reconcile the provisional catalogue.

Do not edit V102. A shipped migration is immutable.

---

## 7 · WP4 — scoped repository and transactional application service

Keep connector vocabulary in runtime's `integration` module. The platform primitive
knows nothing about it.

### 7.1 · Domain records

Add runtime-owned types for:

- `ConnectorCredential` — site, connector, mode, principal, `SealedSecret`, version,
  changed-at/by;
- `CredentialMetadata` — mode, configured, version, changed-at/by; no principal and
  no sealed fields;
- `ConnectorCredentialAudit` — connector, resulting mode/version, action, time and
  actor only;
- a credential mutation command that represents `Replace`, `Preserve` and `Clear`
  explicitly; do not encode those three meanings as null/empty-string guesses;
- typed not-found, invalid-state and stale-version exceptions whose messages cannot
  include a value.

### 7.2 · Repository

Create `ConnectorCredentialRepository` using `ScopeSeam` only. No `JdbcTemplate`,
`DataSource`, `EntityManager`, raw connection or cross-schema query.

Required operations:

1. lock the parent `connector_config` row by name with
   `ScopedSelect.lockMatchedRows()`;
2. read credential by connector name;
3. insert first row;
4. update existing row with `where connector_name = ? AND credential_version = ?`
   and `increment("credential_version", 1)`;
5. append one redacted audit row in the same transaction as every successful
   credential mutation;
6. select a bounded, ordered batch whose `key_id` is not the current key id for
   rewrap, with row locks;
7. count old-key rows for operational completion evidence.

The parent-row lock is required even before a credential row exists. Two first
writes cannot lock an absent child row; locking the existing connector row
serializes create/create and create/clear for that connector. All lock/read/decide/
write sequences must run inside one transaction.

Map `updated_at` as UTC with the shared runtime UTC helper present on `develop`.
Do not call plain `rs.getTimestamp(column)` for a database-written `DATETIME2`.

### 7.3 · `ConnectorCredentialService`

Wire a `TransactionTemplate` in `IntegrationConfiguration`; do not annotate an
engine-dependent adapter in a way that creates a bean cycle.

Keep the application boundary explicit and small:

```java
CredentialMetadata mutate(
        String connectorName,
        long expectedVersion,
        CredentialMutation mutation,
        String actor);

CredentialMetadata metadata(String connectorName);
```

`CredentialMutation` is a sealed, exhaustive value: `SetBasic(principal,
PasswordChange)` or `Clear`, where `PasswordChange` is exactly `Replace(plaintext)`
or `Preserve`. Do not add an `actor` field to the mutation value: the caller passes
the actor separately from its trusted request/system identity seam. `metadata`
returns the synthetic `NONE`, `configured=false`, version `0` view when no row
exists, with null changed-at/by because no change occurred; it never returns the
principal or sealed fields.

For every mutation:

1. require the caller to have established the installation site scope;
2. start a transaction;
3. lock the parent connector row; a missing connector is a typed refusal. Do not
   require it to be enabled—administrators must be able to provision a disabled
   connector before enabling it;
4. read the credential row;
5. compare the expected version (`0` for no row);
6. validate mode/principal/secret transition;
7. bind the purpose to owner + site + connector + credential kind;
8. seal only when a password is replaced;
9. take one `Instant.now()` and convert it through the UTC helper for current-state
   and audit rows;
10. insert/update, incrementing the version exactly once;
11. append the matching `SET`, `REPLACE` or `CLEAR` audit row in that same
    transaction;
12. return metadata only.

Preserve must copy the sealed fields without opening them. Clear must null principal
and all sealed fields. Validate principal/actor nonblank and within their database
bounds before writing. The actor comes from a trusted request/system identity seam,
never from a future request body. Do not publish an outbox fact carrying credential
data.

For the existing background connector call, runtime explicitly establishes the
installation's site scope as it already does in `RestConnector.call()`.

---

## 8 · WP5 — consume credentials in `RestConnector`

### 8.1 · Resolve before entering resilience guards

Within the existing installation scope:

1. load `ConnectorConfig` as today;
2. load its credential row separately — the scope seam does not support joins;
3. treat no row or `NONE` as no Authorization header;
4. for `BASIC`, open the password with the connector-bound purpose;
5. only then enter the circuit breaker and bulkhead and send the request.

A key/configuration/tamper failure is an ORCA configuration failure, not a failed
call to the customer's system. It must not increment that customer's circuit
breaker and must not make an outbound request.

### 8.2 · Per-request header; no decrypted cache

The cached `RestClient` must contain endpoint/timeouts only. Apply Basic
Authorization on the request itself, for example with Spring's charset-explicit
Basic-auth support using UTF-8. Never put a password into the client's default
headers, the cache key, a lambda retained by the cache or a resilience context.

Include the credential version in the transport-cache identity as ruled:

```text
connectorName@baseUrl|deadlineMillis|cv<credentialVersion>
```

Evict obsolete cache entries for that connector when a newer version is used so
repeated rotations cannot grow the map forever. Correctness must not depend on the
eviction: the header is derived from the current row on every call.

### 8.3 · Redacted failures

The current catch path includes `notReached.toString()` in a public/domain message.
Replace that with a fixed connector-level message while preserving the throwable as
the cause for internal diagnostics. No log or response may interpolate a throwable
whose message could carry a header, password, ciphertext or configuration value.

On decrypt failure, name the connector and failure category only. Never degrade to
the `NONE` path. This is a hard fail-closed property.

---

## 9 · WP6 — key rotation and bounded rewrap

Supporting old-key reads is not a complete rotation story. Add a runtime-owned
`CredentialRewrapService`; do not put database concepts in `platform/secrets`.

`rewrapBatch(int limit, String actor)` must:

1. accept `1..100` and reject every other limit; 100 is the fixed transaction/lock
   cap for this slice, not a configurable deployment value;
2. run in the current site scope and one transaction;
3. lock an ordered batch of `BASIC` records whose key id differs from
   `SecretBox.currentKeyId()`;
4. open each with its stored key id and row-bound purpose;
5. reseal with the current key and a fresh nonce;
6. update only where connector name, version and old key id still match;
7. increment credential version and update time/actor;
8. append one `REWRAP` audit row carrying only connector, resulting mode/version,
   time and actor;
9. return redacted counts;
10. stop and roll back on unknown key or tamper; never skip a bad row silently.

Provide `countNeedingRewrap()` so an installer/future authorized operation can prove
the batch loop reached zero before an old key is removed. Do not add a scheduler or
public/internal trigger in this feature; the production trigger and backup/recovery
procedure belong to the installer plan.

The operational sequence recorded in the report is:

1. distribute a key map containing old + new to every instance while old remains
   current;
2. verify every instance can open old-key rows;
3. make new key current and roll every instance; do not begin final rewrap while an
   old-current instance can still create old-key rows;
4. execute bounded rewrap until old-key count is zero and remains zero after normal
   connector activity;
5. verify live calls and database/key backups together;
6. only then remove old key in a later controlled deployment.

Database restored without matching keys and keys restored without matching database
must fail visibly. There is no fallback key search and no unauthenticated retry.

---

## 10 · WP7 — executable specification

Tests are the specification. Name each property for the failure it prevents and,
where a compound guard exists, write one test per independent clause.

### 10.1 · Platform unit properties

| # | Property | Deliberate mutation that must make it fail |
|---:|---|---|
| 1 | seal/open round-trips Unicode plaintext | use a different key for open |
| 2 | same plaintext/purpose seals differently twice | replace random nonce with a constant |
| 3 | changed ciphertext or tag fails | swallow authentication-tag failure |
| 4 | changed nonce fails | ignore stored nonce |
| 5 | connector A's blob fails for connector B | remove purpose from AAD |
| 6 | old-key record opens after current key changes | always select current key |
| 7 | seal always writes current key id | use first map entry |
| 8 | missing current id refuses startup | delete only that validator clause |
| 9 | empty key map refuses startup | delete only that clause |
| 10 | current id absent from map refuses startup | default to another map entry |
| 11 | malformed Base64 refuses startup | accept decoder failure |
| 12 | decoded length other than 32 refuses startup | accept AES-128/192 |
| 13 | invalid/overlong key id refuses startup | accept an id the schema cannot store |
| 14 | public local fixture outside `local` refuses startup | remove profile guard |
| 15 | decoded nonce length other than 12 fails with a redacted error | pass it to JCE unchecked |
| 16 | null plaintext/purpose and blank purpose parts are refused deliberately | let a low-level NPE escape |
| 17 | every crypto/configuration error is redacted | append the rejected value |

### 10.2 · Migration and repository properties

- table/PK/FK/constraint shape exists after a clean migration;
- audit table/check/index shape exists after a clean migration;
- lowercase/mixed-case modes are rejected by the database;
- `NONE` with any principal or sealed field is rejected;
- `BASIC` with any required field missing is rejected;
- `BASIC` with a blank field or colon-bearing principal is rejected by both service
  validation and the database backstop;
- version zero is rejected for a materialized row;
- a credential for a nonexistent connector is rejected;
- the table declaration is bounded and the scope-leading-index rule sees its PK;
- a database row never contains the known plaintext password;
- a copied blob fails under the destination row's purpose;
- two concurrent first writes for one connector serialize; exactly one expected-
  version-0 command succeeds;
- two concurrent replacements from the same version do not lose an update;
- different connectors are not globally serialized;
- clear retains metadata, removes all secret fields and increments once;
- preserve leaves ciphertext/nonce/key id byte-for-byte unchanged and increments
  once;
- clear against absence/`NONE`, and preserve with no other change, are refused and
  write neither current state nor audit;
- each set/replace/clear writes exactly one redacted audit row in the same
  transaction;
- poison the audit write: current-state mutation and audit both roll back; heal it
  and both apply once;
- audit rows contain mode/version/action/actor/time and have no column or value for
  principal, secret, sealed material or key id;
- timestamps round-trip under a forced non-UTC JVM default zone.

Use real SQL Server integration properties through the established
`PlatformDatabase` test fixture. In-memory databases do not specify SQL Server
locks, collation or constraint behaviour.

### 10.3 · Runtime/connector properties

- no credential row sends no Authorization header and preserves the existing
  outcome routing;
- `NONE` sends no Authorization header;
- `BASIC` sends exactly the expected header to a real local HTTP server;
- username containing `:` is refused before any outbound request;
- two independent connector/application-service instances share one migrated SQL
  Server schema but have separate repositories and client caches: instance A writes,
  instance B calls; A replaces, B's next call uses the new password without restart;
- cache identity changes with credential version and obsolete entries are evicted;
- tamper, unknown key and wrong purpose make zero outbound requests;
- credential failures do not increment/open the customer circuit breaker;
- restored database with missing key fails closed;
- old/new key coexistence continues to serve while rewrap is incomplete;
- during a simulated roll, old-current and new-current instances can both read both
  generations; after the old-current writer is removed, rewrap converges to zero;
- bounded rewrap reaches zero, changes key id and nonce, preserves the plaintext
  effect, increments version and appends one `REWRAP` audit row per changed record;
- one bad row rolls back the batch rather than being skipped;
- successful and failed calls place the chosen sentinel password in no captured log,
  exception message, API error or process variable;
- `CredentialMetadata` serialization has no principal, sealed fields or plaintext
  property available to serialize.

Extend an existing full runtime property suite such as `VisitLifecycleIT` only when
that keeps its setup comprehensible; otherwise create a focused
`ConnectorCredentialPropertiesIT` using the real runtime context and a real local
HTTP server. Do not make WireMock accept every header and then claim authentication
was proved. Assert the header at the receiver.

Add at least one full gate-process property to `VisitLifecycleIT` (or an equally full
runtime suite): create the Basic credential through `ConnectorCredentialService`,
admit a visit, assert the TOS receiver saw the exact header, and assert the visit
completed. Update test cleanup order to delete `connector_credential_audit` and then
`connector_credential` before deleting `connector_config`; the new FK makes the old
order invalid once a test materializes a credential.

The multi-instance property must use two independently constructed connector/service
graphs with separate `RestConnector.clients` maps over the same SQL Server schema.
Two calls on one object do not prove cross-instance visibility. A second complete
Flowable engine is unnecessary; the property is about database visibility and
transport-cache independence.

### 10.4 · Mutation evidence

Actually make every mutation named in §10.1 and the high-risk runtime mutations
(drop AAD, fall back to NONE, remove expected-version predicate, cache a default
Authorization header, omit the audit insert, move the audit outside the transaction,
append exception text), run the focused test, observe the failure, and revert. Record
test name and observed failure in the report. A test no one has watched fail may be
wired to the wrong path.

---

## 11 · Verification sequence

### 11.1 · Focused and full gates

With all services stopped:

```bash
./gradlew :platform:secrets:check
./gradlew :services:orca-runtime:check :services:orca-runtime:integrationTest --rerun-tasks
./gradlew build
./gradlew check integrationTest --rerun-tasks
```

Do not pipe the commands. Count integration XML again and record baseline, final,
delta and zero failures. Also run:

```bash
docker compose -f deploy/docker-compose.yml run --rm verify-isolation
```

Use the compose invocation already documented for the checkout if its file/name
differs; do not improvise a second stack.

### 11.2 · Clean database and boot proof

Because V128 and startup validation are the feature, prove both from a clean local
database. The local database is disposable, but confirm no material local data is
needed before removing its volume.

```bash
cd deploy
docker compose down -v
docker compose up -d
docker compose run --rm bootstrap
cd ..
```

Boot core, runtime and edge in order with `local`. Prove runtime starts with the
local fixture. Prove the two negative starts separately and assert the failure text
comes from `SecretsConfigurationValidator`, rather than accepting any startup
failure as evidence:

1. no `orca.secrets` configuration, while supplying a non-fixture
   `orca.internal.shared-credential`, must name the missing current key;
2. pass the public local key/current-id explicitly without activating `local`, again
   overriding the internal credential with a non-fixture value; startup must refuse
   the public secret fixture specifically.

The internal-call validator otherwise refuses its own public fixture first and masks
the property this test is meant to prove. Restore the correct local profile
afterwards.

Run `demo-seed` only after the services have migrated, then drive a normal truck:

```bash
cd deploy && docker compose run --rm demo-seed && cd ..
./gradlew sendPlate -Pplate=CRED-REGRESSION-01
```

Confirm the visit reaches `COMPLETED`. This live path is `NONE`/absent-credential
backward compatibility; the Basic path and no-restart replacement are proved in the
real-context integration property until an authorized administration surface exists.

### 11.3 · Data and leakage proof

Keep a single distinctive sentinel password for test/log scanning. After tests and
live run:

- query `runtime.connector_credential` after the clean live demo; with no authorized
  administration surface it should be empty, proving absence still means `NONE`;
  use the focused scratch-schema assertions—not pasted ciphertext—as evidence for
  stored mode, version, key id and timestamps;
- prove the sentinel does not appear in the database via a constrained diagnostic
  query, logs, test reports or generated API artifacts;
- prove metadata contains no field capable of returning it;
- show the audit rows for set/replace/clear/rewrap and prove they contain only the
  approved redacted fields;
- show a tampered/unknown-key call made zero requests at the receiver;
- show replacement in one runtime context used the new header without restart;
- show rewrap ended with zero old-key records.

Never paste a real or generated secret into the report. The sentinel is test-only
and must be recognisably non-production.

---

## 12 · Expected changed-file set

Use the names below unless a verified existing symbol conflicts; a conflict is a plan
discrepancy to report, not permission to rename the design silently. The change should
remain close to this set:

```text
settings.gradle.kts
README.md
AGENTS.md
platform/AGENTS.md
build-checks/AGENTS.md
docs/CODE_PATTERNS.md
docs/PLATFORM_PRIMITIVES.md
docs/REPOSITORY_GUIDE.md
docs/ORCA_ORCHESTRATOR_HANDOVER.md
build-checks/build.gradle.kts
build-checks/src/test/java/.../ImportedSetGuard.java

platform/secrets/build.gradle.kts
platform/secrets/src/main/java/.../SecretBox.java
platform/secrets/src/main/java/.../SealedSecret.java
platform/secrets/src/main/java/.../SecretPurpose.java
platform/secrets/src/main/java/.../SecretsProperties.java
platform/secrets/src/main/java/.../SecretsConfigurationValidator.java
platform/secrets/src/main/java/.../SecretsAutoConfiguration.java
platform/secrets/src/main/java/.../SecretConfigurationException.java
platform/secrets/src/main/java/.../SecretOpenException.java
platform/secrets/src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
platform/secrets/src/test/java/.../*

services/orca-runtime/build.gradle.kts
services/orca-runtime/src/main/resources/application.yaml
services/orca-runtime/src/main/resources/db/migration/
  V128__connector_credentials.sql
services/orca-runtime/src/main/java/.../integration/IntegrationConfiguration.java
services/orca-runtime/src/main/java/.../integration/domain/ConnectorTables.java
services/orca-runtime/src/main/java/.../integration/domain/RestConnector.java
services/orca-runtime/src/main/java/.../integration/domain/CredentialMode.java
services/orca-runtime/src/main/java/.../integration/domain/ConnectorCredential.java
services/orca-runtime/src/main/java/.../integration/domain/CredentialMetadata.java
services/orca-runtime/src/main/java/.../integration/domain/CredentialMutation.java
services/orca-runtime/src/main/java/.../integration/domain/ConnectorCredentialAudit.java
services/orca-runtime/src/main/java/.../integration/domain/ConnectorCredentialException.java
services/orca-runtime/src/main/java/.../integration/domain/
  ConnectorCredentialService.java
services/orca-runtime/src/main/java/.../integration/domain/
  CredentialRewrapService.java
services/orca-runtime/src/main/java/.../integration/persistence/
  ConnectorCredentialRepository.java
services/orca-runtime/src/integrationTest/java/.../<credential properties>.java

docs/credentials-at-rest-report.md
```

Unexpected edits to core, edge, public OpenAPI, security filters, deployment scripts,
CI, Dockerfiles, environment-variable definitions or the legacy repository are a
scope alarm. Stop and explain before making them.

---

## 13 · Commit and review structure, if commits are authorised

Recommended review units:

1. `feat(platform): add versioned secret box`
2. `feat(runtime): persist connector credentials`
3. `feat(runtime): authenticate outbound basic connectors`
4. `test(runtime): prove credential rotation and failure properties`
5. `docs: report credentials-at-rest evidence`

Before each commit, inspect `git diff --check`, `git status`, and the exact staged
diff. Do not mix unrelated user changes into these commits. Never push directly to
`main`; the path is feature branch → `develop` → `main`.

---

## 14 · Required report — `docs/credentials-at-rest-report.md`

The report is evidence, not a substitute for rerunning. Include:

1. commit/branch and the exact decision implemented;
2. what was built and deliberately not built;
3. the authorization gate and why no HTTP mutation endpoint shipped;
4. exact current-state/audit schema and configuration shapes, without any key or
   ciphertext value;
5. baseline/final suite counts and zero-failure evidence;
6. clean migration and three-service boot evidence;
7. normal `sendPlate` result and resulting visit state;
8. Basic header, two-instance no-restart replacement, fail-closed, redacted-audit
   atomicity and rewrap evidence from the real-context property suite;
9. mutation-test table: mutation, test, observed failure, revert confirmation;
10. leakage scan locations and zero-hit results;
11. every discrepancy or decision the plan did not dictate;
12. follow-ons:
    - authorize connector administration using a ruled entitlement seam;
    - add the write-only OpenAPI/controller surface;
    - installer provisioning, backup, recovery and production rewrap trigger;
    - Stream 1 Track B consumes the primitive for OAuth/custom-header modes;
    - Stream 3 consumes it for SFTP after SFTP scope and host-key verification are
      separately settled;
    - evaluate promotion of the now-repeated UTC helper if not already resolved.

Finish with the exact commands a reviewer must rerun. Do not say “green” without
counts, and do not say “end to end” without naming the sender, receiver and database
state that were observed.
