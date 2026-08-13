# Deployment — local development, and the road to a customer site

**Two audiences, one document.** Part 1 is the local development environment: real,
complete, and verified by execution (every command below was run against this
repository on 8 Aug 2026 — most recently during the Phase 1 closing verification).
Part 2 is production on a customer's on-site machine: the target sequence with an
honest per-step status. **Part 2 is not yet a runbook** — a runbook is only true once
it has been executed end to end on a clean machine, and several steps cannot be
executed yet. It becomes `docs/install-runbook.md` during the deployment phase (the
next implementation plan after Phase 2), with its execution transcript included.

---

## Part 1 · Local development environment

### Prerequisites

- **Docker**, running (Docker Desktop, Rancher Desktop, or equivalent).
- **A JDK** — any recent one; the Java 25 toolchain auto-provisions via the Gradle
  foojay resolver.
- That's it — **including on Windows**: the setup and demo tools run as one-shot
  Compose services (`docker compose run --rm …`), so the host needs nothing but
  Docker and a JDK. The `deploy/*.sh` scripts remain as Unix-host conveniences;
  both paths run the same script, in container or host mode.

### First-time setup

```bash
cd deploy
cp .env.example .env                 # ONCE — never overwrite an existing .env (machine-local values)
docker compose up -d                 # SQL Server 2022 + Keycloak + the TOS and device-host stubs
docker compose run --rm bootstrap    # the one-time privileged step: 7 schemas, 7 logins, grants,
                                     # then V004 asserts the isolation. No-op afterwards.
```

Isolation can be re-proven any time: `docker compose run --rm verify-isolation`
(36 checks: every login writes its own schema, every cross-schema read refused).

What the bootstrap is and why it exists: each service's login can only reach its own
schema — that wall is the isolation model (ADR-004). Creating the wall (database,
schemas, logins, grants) needs one privileged step that no service is allowed to
perform, so it lives here and runs once. No application ever holds admin credentials.

### Build and test

```bash
./gradlew build                      # compile, unit tests, the ten build checks
./gradlew check integrationTest      # FULL verification — plain `test` skips the
                                     # platform property suites (Testcontainers, real SQL Server)
```

### Run the services

```bash
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local'
```

- **The `local` profile is not optional on a dev machine** — the committed
  inter-service credential is recognised by name and the service refuses to start
  with it otherwise (ADR-011). Do not weaken the check.
- **Ports:** committed defaults are 8081–8086 (services), 1433 (SQL Server), 8080
  (Keycloak), 9100 (camera listener). If another stack holds them, use the `.env`
  port overrides plus `--server.port=...` and export `ORCA_DB_URL` /
  `ORCA_OIDC_ISSUER_URI` accordingly — `docs/phase-1-demo.md` §3 shows the exact
  offset incantation, verified on a machine where 1.x owns the default ports.
- **Start order matters and the platform enforces it**: core first (it publishes the
  views), then runtime and edge — they refuse to start before core has migrated,
  naming the views they wait for.

### See it work — one truck through the gate

```bash
cd deploy
docker compose run --rm demo-seed        # one demo site/lane/camera/barrier — a deliberate
                                         # act, never a profile or migration
cd ..
./gradlew sendPlate -Pplate=T-DEMO-01    # speaks the real camera wire format at edge's listener
```

`sendPlate` is a Gradle task, not a container: edge runs on the host during local
dev, so the plate sender must reach the host — and Gradle is already present on
every OS. Override with `-Pport=`, `-Plane=`, `-PeventGuid=`, `-Prepeat=2` (the last
two demonstrate the dedup key). `deploy/demo/send-plate.py` is the same framing as a
standalone script if you prefer Python.

Full walkthrough, inspection queries and the four deliberate demonstrations
(dedup, the admission race, the unrouted branch, the expired command):
**`docs/phase-1-demo.md`**. Teardown: `docker compose down` (add `-v` to drop the
database volume for a truly clean next run).

### Troubleshooting

`deploy/README.md` (stack details), `docs/phase-1-demo.md` §9 (the known failure
modes, including the Flowable self-migrated-schema recovery via
`deploy/adopt-flowable/`).

---

## Part 2 · Production — a customer's on-site machine

**Target model: the site appliance** (architecture §A7): everything runs on the
customer's hardware inside their network; gates keep working with no internet. The
sequence below is the install as it will be. The Status column is the truth as of
8 Aug 2026 — this table is the deployment phase's backlog, and nothing here is a
surprise: every ❌/🟡 is tracked in the register or a phase report.

| # | Step | Status today |
|---|---|---|
| 0 | Pre-install requirements handed to the customer: SQL Server licence, firewall, DNS/floating address where multi-server is wanted (§A5) | 🟡 Named in the architecture; checklist document not yet written |
| 1 | OS + container runtime on the machine | ✅ Standard |
| 2 | SQL Server installed (customer-licensed) | ✅ As in 1.x |
| 3 | **Install ORCA release artifacts** — container images from a registry, versioned, signed | ❌ **No images, no registry, no release pipeline exist.** The largest single gap |
| 4 | Database bootstrap — schemas, logins, grants, with **generated per-installation passwords** | 🟡 The four SQL files are built and proven; password generation/storage is the pending secrets design |
| 5 | Keycloak: per-site realm, first admin user | 🟡 Dev realm export exists; realm provisioning design open (register U3); first-admin bootstrap surfaces in Phase 2's report |
| 6 | Per-installation secrets provisioned (7 DB logins, inter-service credential, Keycloak, per-service credential-encryption keys) | 🟡 Connector/SFTP credentials-at-rest are ruled as application-encrypted records (`docs/decision-connector-credentials.md`); implementation and the installer-owned provisioning/backup/rotation procedure remain to be built. Existing fixture guards remain mandatory |
| 7 | Licence file installed and verified at startup | ❌ Licence verification module not yet built (deferred with the licensing cluster) |
| 8 | Reverse proxy + TLS in front of every surface | ❌ Not in the repository at all yet |
| 9 | Services started; health checks green | ✅ Proven |
| 10 | Site configured — lanes, devices, connectors — through the application | 🟡 APIs arrive through Phase 2; **the operator console (UI) is the unstaffed frontend workstream** |
| 11 | Cameras and the .NET device host pointed at edge | 🟡 Camera path real (DERIVED-FROM-1X); device host **blocked on register NEW-4** (the vendor auth question) |
| 12 | Verification: isolation check, smoke test, one truck through the real gate | ✅ Tooling exists — the demo is the smoke test's first draft |

**Rules that already bind production, today:**

- Never the committed `.env.example` values anywhere near a customer — the platform
  refuses the fixture credential outside `local` by design; do not work around it.
- Migrations create schema and product-owned reference data only. **Site and
  customer data enters through the application, never through seeds or scripts.**
- The bootstrap's privilege separation is the point: admin rights are used once, at
  install, and no service configuration ever contains them.

**The deployment phase** (next implementation plan after Phase 2) turns this table
into working software and this section into `docs/install-runbook.md` — written,
then **executed start-to-finish on a clean machine, with the transcript committed
alongside it**. Prerequisites it waits on: the NEW-4 vendor answer, implementation of
the approved secrets decision plus its installer/key-lifecycle procedure, and a
repository host (until then there is no registry to publish images to).
