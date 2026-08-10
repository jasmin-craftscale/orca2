# Decision brief — connector credentials at rest

**For the product owner · 10 August 2026 · Decision required**

**Blocked by this decision:** stream 1 Track B (outbound REST/SOAP authentication)
and stream 3 scheduled SFTP ingestion. This is one security decision with two
consumers. Choosing separate mechanisms would create two key lifecycles, two
rotation procedures and two failure modes.

## The decision

ORCA must recover credentials in plaintext when it calls a customer system. Hashing
is therefore not available. The choice is **where the recoverable value is stored,
what protects it there, and where the protecting key is kept**.

This brief recommends an answer. It does not authorise implementation.

## What has been verified

- ORCA 2.0 has no credential store or crypto abstraction. Runtime's current
  `connector_config` has six non-secret fields; the connector is REST-only and sends
  no authentication. Core's settings service actively rejects secret-shaped keys.
  The controller text that mentions a keystore describes a destination that does not
  exist. Production provisioning and rotation remain open in `docs/deployment.md`.
- Stream 1 plans runtime connector administration over HTTP. ORCA is multi-instance,
  so a credential change must be visible consistently to every instance without
  copying a mutable local file by hand.
- The old system stores an AES-GCM-encrypted connector blob and decrypts it when a
  call is made. SFTP passwords follow the same broad pattern. Its key custody is not
  a model to copy: a local `.gob` store can silently fall back to an environment
  variable.

The four inherited connector modes are:

| Mode | Recoverable secret |
|---|---|
| `NOAUTH` | None |
| `BASEAUTH` | Password; username is not secret |
| `OAUTH` | Client secret; client id, token URL, scope and tenant are configuration |
| `PRIVATEKEY` | An arbitrary HTTP header value |

⚠️ **`PRIVATEKEY` is a misleading inherited name.** The 1.x executor sets the
configured name/value directly as an HTTP header. It is not an asymmetric private
key and not mutual TLS. The target contract also says per-connector TLS verifies the
server only; certificates and SFTP host fingerprints are trust material, not these
credentials.

The inherited SFTP path uses username/password only. SSH-key authentication is not
specified and must not be smuggled into this ruling.

## Options and costs

| Option | Shape | Principal cost and commitment |
|---|---|---|
| **A. Application-encrypted records** | Each owning schema stores versioned AES-GCM ciphertext; installation keys live outside SQL Server | ORCA owns encryption, rotation, backup/restore and installer provisioning |
| **B. References into local keystores** | The database stores `secret_ref`; every node holds the value in PKCS#12 or an OS store | Strong database separation, but every runtime change must be distributed atomically to every instance and restored with the database |
| **C. Network secret manager** | The database stores a reference; Vault or an equivalent owns value and key | Best central audit/rotation, but adds infrastructure and a runtime dependency to an offline-first appliance |
| **D. Installation configuration only** | Credentials never enter the database; changing one means changing deployment configuration | Lowest build cost, but connectors cease to be runtime-administered and usually require a restart or rollout |

### A — application-encrypted records

One shared credential contract and implementation, with records in the schema that
owns their use: connector credentials in `runtime`, ingestion credentials in `core`.
That preserves the single-writer boundary; “one answer” does not mean one
cross-schema credential table or one blast-radius-wide key.

The database and its backups contain ciphertext, key id/version and nonce. Keys are
provisioned separately to the service by the installer. A stolen database alone does
not disclose credentials. A compromised running host still can: the service must
recover plaintext to make the call. That is the honest protection boundary.

Cost: a new shared security primitive, owner-schema migrations, key provisioning,
rotation/rewrap, redaction rules, and restore tests. It has no network dependency at
call time and naturally gives all instances the same committed value.

### B — local keystore references

This separates database backups from secrets more strongly. It is attractive for
static device credentials and was proposed, but not decided, in the Phase 2 report.
For runtime-editable connector and SFTP credentials it creates a distributed-write
problem: every instance's keystore must change consistently with the database
reference, survive partial failure, and be backed up/restored at the same version.
A shared writable filesystem merely moves that availability and concurrency problem.

### C — network secret manager

This provides mature rotation and access audit if a customer already operates one.
As the default on-site answer it adds a server to install, secure, patch, monitor and
recover, plus another dependency whenever a gate process or scheduled ingestion
needs a credential. That conflicts with the self-contained site posture and ADR-007's
reason for declining an on-site broker. It remains a reasonable later adapter for a
cloud tier or customer-mandated environment.

### D — installation configuration only

This is defensible only if the product owner changes the product boundary: connectors
and SFTP jobs become install-time configuration. It is materially cheaper, but it
contradicts stream 1's planned `POST/PATCH /connectors` surface and the rule that site
configuration is performed through the application.

Plaintext columns protected only by disk encryption or SQL Server TDE are not a
credible fifth option: a database reader or logical backup still receives plaintext.

## Recommendation

**Recommend Option A: versioned AES-GCM ciphertext in separate credential records in
each owning schema, with versioned per-service keys provisioned outside SQL Server
through one installer-owned lifecycle.** This is advice; the product owner rules.

Treat the following as part of that ruling:

1. **Write-only API semantics.** A secret may be set, replaced or explicitly cleared.
   Reads return mode, presence and version only. Omitted means preserve; no API,
   view, outbox event or process variable returns plaintext.
2. **Purpose-bound encryption.** Use a fresh nonce and authenticated context binding
   the ciphertext to its owner, record and credential kind. A blob copied to another
   row must not decrypt as that row's credential.
3. **Rotation from release one.** Old and new key versions coexist during a bounded
   rewrap. Restore procedures cover database-without-keys and keys-without-database;
   neither failure silently falls back to unauthenticated operation.
4. **One lifecycle, scoped keys.** Runtime and core need not share raw key material.
   The installer provisions, backs up and rotates them through one procedure while
   preserving each service's blast radius.
5. **Cache identity includes credential version.** Otherwise a rotated secret is
   served by a pooled client still holding the old value — already called out by
   stream 1's plan.
6. **Redacted audit and failure.** Record who changed which mode/version and when,
   never the value. Resolution or decryption failure is visible and fails closed.
7. **Property tests are mandatory.** Multi-instance replacement, old/new-key
   rotation, tamper detection, restore mismatch, API redaction and log/error
   redaction are acceptance properties, not runbook claims.

## What each ruling commits the programme to

| Ruling | Product commitment |
|---|---|
| **A** | ORCA permanently owns a small cryptographic surface and an installer/key-recovery lifecycle; no new runtime service |
| **B** | ORCA owns reliable secret distribution and coordinated restore across every application node |
| **C** | Every site owns another secured service, or the product supports customer-specific secret-manager adapters |
| **D** | Connector/SFTP changes are deployment operations; stream 1's administration surface shrinks accordingly |

## Adjacent questions this does not decide

- Stream 3 says SFTP; the architecture says FTP/SFTP. That scope contradiction needs
  its own answer.
- SFTP host-key verification must be designed. 1.x uses
  `InsecureIgnoreHostKey`; copying it is not acceptable.
- Per-connector server-certificate trust is non-secret trust configuration. Mutual
  TLS remains added work, not a hidden fifth auth mode.
- The connector “test” endpoint's SSRF boundary is a separate security decision.
- Device credentials may later consume the same primitive, but adding them now would
  expand this ruling's scope.

## Ruling requested

1. Choose **A, B, C or D**.
2. If A, confirm the seven conditions above and that runtime-administered connectors
   remain product scope.
3. Authorise the installer/key-lifecycle design as a named prerequisite, rather than
   allowing each stream to invent how its key arrives.

Until that ruling, both streams should continue to stop at their planned
PROPOSE-and-report boundary.
