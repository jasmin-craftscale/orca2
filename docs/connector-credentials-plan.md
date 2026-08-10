# Implementation plan — credentials at rest, and the connector consumer

**For an autonomous agent. Prescriptive: every choice here is made. Do not substitute
your own.**

**What this delivers:** the shared mechanism ORCA uses to hold a secret it must
*present* to somebody else's system — built once in `platform/`, so stream 1
(connectors) and stream 3 (SFTP ingestion) consume one implementation — plus its
first consumer, connector authentication, proven end to end.

**Ruling this implements:** `docs/decision-connector-credentials.md`, **Option A** —
versioned AES-GCM ciphertext in separate credential records in each owning schema,
with versioned per-service keys provisioned outside SQL Server. Read that brief in
full before you start; its seven ruling conditions are acceptance criteria here, not
background.

⚠️ **If that ruling has not been confirmed by the product owner, stop and say so.**
This is security-shaped work and the plan is void without the ruling.

---

## 0 · Before anything — read this section twice

**This repository moves under you.** Other agents work in the same checkout.

```bash
cd ~/Documents/Projects/orca
git fetch origin
git rev-parse --abbrev-ref HEAD          # know where you are
git status                                # must be clean before you start
git checkout main && git pull --ff-only origin main
git checkout -b feature/credentials-at-rest
```

**Run `git rev-parse --abbrev-ref HEAD` in the same command as every `git add`.**
There is one `HEAD` in this checkout and it moves when another agent branches.

**The single most important instruction in this document:**

> **If anything here is wrong — a symbol that does not exist, a command that fails, a
> file whose shape is not what this plan says — STOP and report it. Do not work
> around it, do not improvise a substitute, do not "fix" the plan silently.**

Five defects were found in the last plan this programme handed out, and **all five
were in the plan rather than in the work**. They surfaced only because the agent
stopped and asked. **This plan has already had one factual error corrected during
writing** — assume it contains others.

**When something is genuinely unspecified, report the gap. Never fill it with
something plausible.**

## 1 · What you are storing — read before designing anything

The old system's four inherited modes, **code-verified**, with what is actually secret
in each:

| Mode | The recoverable secret | Not secret |
|---|---|---|
| `NOAUTH` | none | — |
| `BASEAUTH` | the password | the username |
| `OAUTH` | the client secret | client id, token URL, scope, tenant |
| `PRIVATEKEY` | an arbitrary HTTP header **value** | the header name |

⚠️ **`PRIVATEKEY` is a misleading inherited name and must not mislead your design.**
The old executor does exactly this — `req.Header.Set(privateKeyName, privateKeyValue)`
(`work-flow-executor-service/internal/services/make_api_call_service.go:248`). It is an
API key in a custom header. **It is not an asymmetric private key and not mutual TLS.**
Do not build key-material handling, PEM parsing or client-certificate support for it.

Per-connector TLS trust verifies the **server** only. Certificates and any SFTP host
fingerprint are **trust material, not credentials** — they are out of scope here and
must not be smuggled into this mechanism.

**This plan implements `NONE` and `BASIC` only.** `OAUTH` and the header mode belong
to stream 1's Track B, which will consume your primitive. **Do not build them.**

## 2 · Environment — run these in order, all of them

```bash
# 1 · the stack (SQL Server, Keycloak, two stubs). First start takes a minute or two.
cd ~/Documents/Projects/orca/deploy
cp -n .env.example .env            # -n: NEVER overwrite an existing .env
docker compose up -d

# 2 · one-time privileged bootstrap: database, 7 schemas, 7 logins, grants
docker compose run --rm bootstrap

# 3 · prove the isolation holds
docker compose run --rm verify-isolation      # expect: PASS — 36 checks.

# 4 · seed the demo world (site, lane, connector rows)
docker compose run --rm demo-seed
cd ..
```

**The database helper — define it once, you will use it constantly:**

```bash
q() {
  ( set -a; . deploy/.env; set +a
    docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd \
      -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -C -No -I -d orca -h -1 -W -Q "$1" )
}
```

⚠️ **The `-I` is load-bearing.** Several tables carry filtered indexes and SQL Server
refuses writes to those without `QUOTED_IDENTIFIER ON`. Without it you get an error
naming SET options and no table.

**Running the services** (each in its own terminal, **core first** — it publishes views
the others refuse to start without):

```bash
./gradlew bootRun -p services/orca-core    --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-edge    --args='--spring.profiles.active=local'
```

⚠️ `--spring.profiles.active=local` is **not optional** — the committed inter-service
credential is recognised by name and a service refuses to boot with it outside the
`local` profile. Set the profile; never weaken the check.

⚠️ **Every endpoint you add is authenticated.** A `401` is the platform working, not
your code failing. `docs/phase-1-demo.md` §3 has the token incantation; use it rather
than disabling security.

## 3 · The baseline gate — before you change one line

```bash
# services MUST be stopped: the suite and a running service share the `runtime` schema
for p in 18081 18082 18083; do
  PID=$(lsof -nP -iTCP:$p -sTCP:LISTEN -t 2>/dev/null | head -1); [ -n "$PID" ] && kill $PID
done
./gradlew --stop
ps aux | grep -c "[G]radleWorkerMain"        # expect 0

./gradlew check integrationTest --rerun-tasks
```

⚠️ **`--rerun-tasks` is not optional.** Without it Gradle answers from cache in under a
second and prints `BUILD SUCCESSFUL` for a suite it never ran.

⚠️ **Do not pipe this command into `tail`, `head` or `grep`.** A pipeline returns the
*last* command's exit status, so a failed build reports success. This has already
fooled one session on this programme.

**Then count what ran — "SUCCESSFUL" is not evidence:**

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
tot=f=0; n=0
for p in glob.glob('**/build/test-results/integrationTest/*.xml', recursive=True):
    r=ET.parse(p).getroot(); n+=1
    tot+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0))
print(f"integrationTest: suites={n} tests={tot} failures={f}")
EOF
```

At the time of writing this was `suites=31 tests=236 failures=0`. ⚠️ **Another branch
was in flight and will have added tests.** **Record whatever you get as YOUR baseline**;
at the end require *baseline + your new tests*, `failures=0`. **If your baseline has
any failures, stop and report — never start work on a red tree.**

## 4 · Ground rules that fail the build

- **`platform/` may not name a visit, lane, ticket, driver or truck** — verbatim, in
  any identifier (`PlatformPurityRule`). Your primitive is generic secret handling;
  naming a class `ConnectorSecret` inside `platform/` fails the build.
- **No `JdbcTemplate`, `EntityManager` or `DataSource` in a service class.** All data
  access goes through the scope seam.
- **A controller implements a generated interface and returns the shared envelope.**
  Contract first: edit the OpenAPI document, regenerate, then satisfy the interface.
- **A migration that has shipped is never edited.** Fix it with a new one.
- **Binary collation on every enum `CHECK`** — `COLLATE Latin1_General_100_BIN2`. Copy
  the shape in `V117__execution_status_collation.sql`.
- **A traffic-growing table needs a growth declaration, a retention class and an index
  leading with the scope column.**

## 5 · Work packages, in dependency order

### WP1 · The platform primitive — `platform/secrets`

**A sixth platform module. Code only — it creates no table and needs no migration.**

Register it in `settings.gradle.kts` in the existing platform `include(...)` block
(`:platform:outbox`, `:platform:lease`, `:platform:scope`, `:platform:idempotency`,
`:platform:web`). Model `build.gradle.kts` on `platform/idempotency/build.gradle.kts`.

**1 · `SecretBox` — the cipher, and the only place cryptography appears.**

- **AES-256-GCM** from the JDK's own `javax.crypto`. **Add no third-party dependency**
  — that needs the product owner's agreement and is not granted here.
- `seal(String plaintext, SecretPurpose purpose)` → fresh **12-byte random nonce** from
  `SecureRandom`, 128-bit tag, key id and nonce carried with the ciphertext, Base64.
- `open(String sealed, SecretPurpose purpose)` → decrypts, **throws on tamper** (GCM's
  tag does this — do not catch and swallow it).
- ⚠️ **Purpose-bound, via GCM's additional authenticated data.** `SecretPurpose` binds
  the ciphertext to its **owner, record identity and credential kind**. A blob copied
  from one connector's row into another's **must fail to open**. This is ruling
  condition 2 and it is tested (§6, test 4).
- **Rotation from release one:** the record carries the **key id** that sealed it;
  `open` selects that key. `seal` always uses the current key. Both generations coexist
  during a bounded rewrap.
- ⚠️ **No method may log, or put into an exception message, the plaintext or any key.**
  A failure says what failed and which key id was wanted — never a value.

**2 · `SecretsProperties`** — `@ConfigurationProperties("orca.secrets")`: the current
key id, a map of key id → Base64 key (32 bytes decoded). Model it on
`platform/web/.../internal/InternalCallProperties.java`.

**3 · `SecretKeyValidator implements InitializingBean`** — refuses to start a service
whose keys are unusable. **Model it closely on
`platform/web/.../internal/InternalCredentialValidator.java`**, the established pattern.

**Four independent clauses, each getting its own test (§6):**
- no key configured, or the current key id resolves to nothing → refuse, naming the property;
- the key is the committed local fixture **and** the `local` profile is not active → refuse, saying the value is public and in `.env.example`;
- a configured key does not decode to exactly 32 bytes → refuse, giving the length found;
- the current key id is not present in the key map → refuse, naming it.

⚠️ **Fail closed.** A service that cannot resolve its keys does not start, and a
credential that cannot be opened fails the call visibly. **There is no path that
degrades to calling a customer system unauthenticated.** Ruling condition 6.

**4 · Wire it** as `InternalCredentialValidator` is wired in
`platform/web/.../PlatformSecurityAutoConfiguration.java`.

**5 · Add the local development key to `deploy/.env.example`**, commented as public and
refused outside `local`.

**Done when:** `./gradlew :platform:secrets:check` is green and every §6 test 1–8 passes.

### WP2 · The migration — `V118` on the `runtime` schema

**Use `V118`** — the first number in stream 1's band (`runtime` V118–V137,
`docs/MIGRATION_NUMBER_RANGES.md`); the schema is at `V117` today. **Verify before
writing:** `ls services/orca-runtime/src/main/resources/db/migration/`.

⚠️ **A separate record, not columns on `connector_config`.** The ruling puts credentials
in their own record in the schema that owns their use. Create `connector_credential`:

| Column | Type | Notes |
|---|---|---|
| `site_external_id` | `VARCHAR(64) NOT NULL` | the scope column |
| `connector_name` | `VARCHAR(64) NOT NULL` | |
| `auth_mode` | `VARCHAR(16) NOT NULL` | **binary-collated `CHECK` over exactly `('NONE','BASIC')`** |
| `secret_cipher` | `VARCHAR(MAX) NULL` | the sealed blob: key id, nonce and ciphertext. `NULL` when the mode needs no secret |
| `key_id` | `VARCHAR(32) NULL` | which key generation sealed it — makes rewrap possible |
| `credential_version` | `INT NOT NULL` | default `0`; **incremented on every write**; feeds the client cache key |
| `updated_at` | `DATETIME2 NULL` | |
| `updated_by` | `VARCHAR(128) NULL` | |

Primary key `(site_external_id, connector_name)` — it leads with the scope column.
Declare it `@PersistentTable(..., growth = Growth.BOUNDED)`: one row per connector, so
it needs no retention class.

⚠️ **The `CHECK` lists only the two modes this plan implements.** The database must
never hold a value the code cannot serve. Track B adds its modes with its own migration
in its own band **when it implements them** — **you do not add them here.**

Write the comment header in the style of `V117`: what it does, and *why*.

**Done when:** services start clean against a migrated database and
`q "SELECT connector_name, auth_mode, credential_version FROM runtime.connector_credential"`
answers.

### WP3 · Use it on the outbound call

**Files, verified in this shape today:**

- `services/orca-runtime/.../integration/domain/ConnectorTables.java` — the
  `ConnectorConfig` record (`siteExternalId, connectorName, baseUrl, requestPath,
  deadlineMillis, enabled`).
- `services/orca-runtime/.../integration/persistence/ConnectorConfigRepository.java` —
  `byName(...)` selects those six columns through `ScopedSelect`.
- `services/orca-runtime/.../integration/domain/RestConnector.java` — the caller.

**Do:**

1. Add a `ConnectorCredentialRepository` in `integration/persistence`, reading through
   the scope seam exactly as `ConnectorConfigRepository` does. ⚠️ **The seam refuses
   joins** — read the credential as its own scoped select, do not try to join it to
   `connector_config`.
2. ⚠️ **Never put the sealed blob or the opened secret on the `ConnectorConfig`
   record.** It is passed around and logged where you do not control. Open the secret
   at the point of use and let it go out of scope.
3. **Extend the client cache key in `RestConnector`.** Today, at line ~150:
   ```java
   config.connectorName() + "@" + config.baseUrl() + "|" + config.deadlineMillis()
   ```
   becomes that **plus `"|v" + credentialVersion`**.
   ⚠️ **This is the single most important line in the work package.** Without it, an
   administrator changing a credential is silently served by a cached client holding
   the old one — the same defect class the existing comment on that field describes for
   repointing a host, and far harder to see. Ruling condition 5.
4. `BASIC` applies an HTTP Basic `Authorization` header; `NONE` sends no header —
   today's behaviour exactly.
5. ⚠️ **Never log the secret**, at any level, in any exception message, or in the
   circuit breaker's context. A failing connector logs its name, never its secret.

**Done when:** a `BASIC` connector completes a gate visit against the stub and the
secret appears in no log line.

### WP4 · The HTTP surface — write-only

**Contract first.** Edit
`services/orca-runtime/src/main/resources/openapi/orca-runtime.yaml`, regenerate, then
implement the generated interface. **Read the document first and match its existing
conventions** for path prefix, tags, security and the shared envelope.

- **`PUT /connectors/{connectorName}/credential`** — body carries `mode` and the secret
  fields. Seals, writes, **increments `credential_version`**, stamps `updated_at` /
  `updated_by`.
  - ⚠️ **Omitted secret means preserve** the stored one (so a mode change need not
    resend it); an **explicit clear** removes it and sets mode `NONE`. Both are ruling
    condition 1 and both are tested.
- **`GET /connectors/{connectorName}/credential`** — returns `mode`, `configured`
  (boolean), `version`, `updatedAt`, `updatedBy`. **It never returns the secret, in any
  field, under any query parameter, for any caller.**

⚠️ **A deliberate divergence from ORCA 1.x**, which decrypts the credential into its
edit-screen response. Do not reproduce it. **There is no reveal endpoint and you must
not add one.**

⚠️ **You own only the `/credential` sub-resource.** The rest of `/connectors` — create,
list, patch, response-routing, test, health — is stream 1's Track B and is **not yours
to build.** If you find yourself creating them, stop and report.

**On timestamps:** read `updated_at` back **explicitly in UTC**. A plain
`rs.getTimestamp(column)` reads a database-written `DATETIME2` as wall-clock in the
JVM's zone — a live defect elsewhere in this repository. Use whatever shared helper is
on `main` when you start (`orca-edge`'s `Utc` is the reference); if one has landed from
`feature/utc-timestamps`, use it rather than writing a second.

**Done when:** a credential can be set, replaced and cleared over HTTP; `GET` never
discloses it; the audit records **that** it changed, never its value.

## 6 · Tests — and exactly which mutation must fail which test

⚠️ **A compound guard needs one test per clause.** A previous plan here prescribed a
single test for an `if (a || b)` guard that only ever triggered `a`, so deleting `b`
left it green — it certified a guard it never executed. **That is what this section
prevents.**

**Write each test, then break the thing it guards on purpose and watch it fail.** A
check nobody has watched fail may not be wired in. Record in your report that you did
this, per row.

| # | Test | The mutation that must make it fail |
|---|---|---|
| 1 | `seal` → `open` round-trips | — (correctness baseline) |
| 2 | The same plaintext sealed twice gives **different** ciphertexts | Replace the random nonce with a constant |
| 3 | A tampered sealed value **fails to open** | Swallow the `AEADBadTagException` and return a value |
| 4 | A blob sealed for connector A **fails to open** as connector B | Drop the purpose from the AAD |
| 5 | `open` succeeds after rotation, using the record's **key id** | Always use the current key |
| 6 | **No key / unresolvable current key id** → service refuses to start | Delete that clause |
| 7 | **Local fixture key outside `local`** → refuses to start | Delete that clause |
| 8 | **Wrong-length key** → refuses to start | Delete that clause |
| 9 | **Current key id absent from the key map** → refuses to start | Delete that clause |
| 10 | **A credential change is not served by a pooled client** | Remove `"\|v" + credentialVersion` from the cache key |
| 11 | `GET .../credential` **never** contains the secret | Return the opened value in the response |
| 12 | The secret appears in **no** log line, on a successful and a failed call | Add the secret to the connector's log context |
| 13 | **Omitted secret preserves**; **explicit clear** removes and sets `NONE` | Treat omitted as clear |
| 14 | **Database restored without its keys fails closed** — the call fails visibly, never proceeds unauthenticated | Fall back to sending no header when `open` fails |

Tests 6–9 are the four clauses of one validator — **four tests, not one.**

Follow this repository's idioms: property tests over path tests, fault injection, true
concurrency where two instances could race, and where a test could pass by accident on
your machine, force the condition. Integration tests go in `src/integrationTest`.

## 7 · Verification — execute every row, keep the output

| # | Item |
|---|---|
| 0 | **Services and Gradle stopped** before any suite run |
| 1 | `./gradlew build` green from a clean tree |
| 2 | `./gradlew check integrationTest --rerun-tasks` — **not piped** — then count tests from the XML. Total = your baseline + your new tests, `failures=0` |
| 3 | **The services start.** A green suite does not prove a service boots — every suite builds its beans directly, and this repository has shipped a service that passed everything and could not start |
| 4 | **The Phase 1 demo still runs end to end** — `./gradlew sendPlate -Pplate=T-HELLO-01`, visit `COMPLETED`. The standing regression canary |
| 5 | **A `BASIC` connector completes a real gate visit** against the stub, driven live — not only in a test |
| 6 | **Replacement works live:** change the credential over HTTP, drive another truck, show the new value was used **without restarting the service** |
| 7 | **`GET` discloses nothing** — paste the actual response body into the report |
| 8 | **Grep your own logs** for the test secret after a full run: zero hits |
| 9 | Each of the fourteen mutations in §6 breaks its test, then revert |
| 10 | `docker compose run --rm verify-isolation` — 36 checks still green |

## 8 · What to do when you are blocked

**Stop and report. Do not improvise.** In particular, report rather than resolve:

- any symbol in §5 not in the shape described;
- anything suggesting the credential should be readable back out;
- any need for a third-party dependency;
- any need to touch `/connectors` beyond `/credential`;
- any need to edit a migration that has already shipped;
- any need to handle certificates, host fingerprints or mutual TLS — that is trust
  material and out of scope (§1).

## 9 · Traps — costly and invisible in the code

- **A running service poisons the suite.** They share the `runtime` schema. Stop them.
- **`--rerun-tasks`, or Gradle reports a success it did not run.**
- **Piping the Gradle command hides a failed build** behind `tail`'s exit code.
- **A running service holds the old classes** — restart before verifying against it.
- **A `@RestController` taking a configuration value needs an explicit `@Bean`.**
- **A generated enum's `fromValue` throws** rather than returning null.
- **A lookup inside `.map()` is an N+1.**
- **The scope seam refuses joins.** Do not try to make one work.
- **`sendPlate` may get no acknowledgement on the first run** after `demo-seed` — edge
  claims lanes on a five-second poll. Wait and retry before assuming a defect.
- **A migration whose checksum changed** stops every database that ran it.

## 10 · The report — `docs/credentials-at-rest-report.md`

Write it as the last commit:

1. **What was built**, and **what was not** — named gaps, not silence.
2. **Every decision this plan did not dictate**, and what you chose.
3. **Anything that looked wrong** — reported, not silently corrected, including defects
   in this plan.
4. **Verification evidence**: baseline and final counts, the demo output, the `GET`
   response body, and the fourteen mutations with which test caught each.
5. **What the next streams need** to consume `platform/secrets` rather than reinvent
   it — specifically stream 1's Track B (`OAUTH`, the header mode, SOAP) and stream 3
   (SFTP username/password).

**Commits:** one concern each, in the repository's existing style. **Do not add a
`Co-Authored-By` trailer or any tool attribution.** Never commit, push or merge beyond
your own feature branch unless told to.
