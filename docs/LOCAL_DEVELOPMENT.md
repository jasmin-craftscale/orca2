# Local development — from nothing to a truck through the gate

**Your first hour. Follow it top to bottom and you will have the platform running,
proven working, and your AI assistant onboarded.**

Every command here was executed against this repository. If one does not work,
that is a defect in this document — say so.

---

## 1 · What you need

| | |
|---|---|
| **Docker** | Running. Docker Desktop, Rancher Desktop or equivalent |
| **A JDK** | Any recent one. The Java 25 toolchain provisions itself |
| **Git** | Obviously |

**That is the whole list, including on Windows.** The setup and demo tools run
inside containers, so nothing else needs installing.

The commands below assume a **bash-like shell**. On Windows use WSL2 or Git Bash —
everything works there, but PowerShell will not run the loops and helper functions
as written.

## 2 · Clone both repositories, side by side

You need **two** repositories, and the layout matters:

```
<any directory>/
├── orca/            ← this repository. Where you work
└── Lynxis-Gate/     ← the system being replaced. Reference only, never edit
```

```bash
cd ~/where-you-keep-projects
git clone <orca-repo-url> orca
git clone <lynxis-gate-repo-url> Lynxis-Gate
```

**Clone them as siblings exactly as shown.** Every instruction in this repository,
and every prompt you give an AI, refers to the old system as `../Lynxis-Gate`. A
relative path works on everyone's machine; an absolute one works on nobody else's.

### Why you need the old repository

ORCA 2.0 is a ground-up rewrite of a system running in production today — 25 Go
microservices. When you reimplement a feature in Java, that Go code is the record
of what the feature really does: the actual wire formats, the real columns, the
edge cases somebody hit at three in the morning.

**How to use it — this part matters:**

- **Read it to understand.** That is what it is for, and reading it makes a port
  far more accurate than guessing.
- **`docs/*-from-1x.md` are the authority where one exists.** These are extractions
  with file-and-line evidence, and they carry both what the old system does *and*
  the defects deliberately **not** carried forward. Where a sheet and the old code
  disagree about what to build, **the sheet wins.**
- **If no sheet covers what you are doing, ask for one** rather than porting
  straight from the source.
- **Never assume a pattern is intentional because it ships.** Real examples from
  one path: a "unique" index that silently degrades to non-unique, statuses that
  nothing ever writes so the cleanup job never runs, and a deduplication that can
  start two workflows from one event. All of that is live in production.
- **Never modify anything in `Lynxis-Gate`.** It is read-only, always.
- ⚠️ **Clone it. Do not RUN it.** You need the source to read; you never need its
  stack. Starting its devcontainer brings up a second SQL Server and a second
  Keycloak alongside this project's, and it holds ports `8081`–`8086`, `1433` and
  `8080` — the ones this project wants. Two database servers competing for one
  machine is what turns a passing integration suite into a failing one (§6.1). If it
  is already running, `docker compose -p devcontainer -f .devcontainer/docker-compose.dev.yaml down`
  in that repository stops it.

⚠️ **Before searching that repository — with an AI or by hand — read
`../Lynxis-Gate/CLAUDE.md`.** It documents the conventions that make its code
readable: soft-delete flags on nearly every table, singular table names, and dual
integer/UUID keys. Search it without that and you will confidently reach wrong
conclusions.

## 3 · Start the stack

```bash
cd orca/deploy
cp .env.example .env          # ONCE. Never overwrite an existing .env
docker compose up -d
```

That gives you four containers: SQL Server, Keycloak, and two stubs standing in for
a customer's system and a device host. **There is deliberately no message broker** —
services hand work to each other through the database.

⚠️ **The first start takes a minute or two** while SQL Server initialises — longer
on Apple silicon, where it runs emulated. `docker compose up -d` returns
immediately, but the database is not ready yet. The next command waits for it, so
just run it; it is not stuck.

Then the one-time privileged step:

```bash
docker compose run --rm bootstrap
```

This creates the database, seven schemas, seven logins and their grants — each
service reaches only its own schema, enforced by database credentials rather than
by convention. It runs once and is a no-op afterwards.

Prove it worked:

```bash
docker compose run --rm verify-isolation
```

Expect `PASS — 36 checks.`

## 4 · Run the services

**Core first** — it publishes views that runtime and edge refuse to start without.
You were in `deploy/`, so come back up to the repository root:

```bash
cd ..
./gradlew bootRun -p services/orca-core    --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-edge    --args='--spring.profiles.active=local'
```

Each in its own terminal. Check them:

```bash
for p in 8081 8082 8083; do curl -s -o /dev/null -w "$p %{http_code}\n" http://localhost:$p/actuator/health; done
```

⚠️ **`--spring.profiles.active=local` is not optional.** The committed
inter-service credential is recognised by name, and a service refuses to start with
it outside the `local` profile — so the public fixture cannot reach a customer site
by accident. Set the profile; do not weaken the check.

## 5 · Drive a truck through the gate

```bash
cd deploy && docker compose run --rm demo-seed && cd ..
./gradlew sendPlate -Pplate=T-HELLO-01
```

`sendPlate` speaks the real camera wire protocol at edge's listener. You should see
the plate go out and an acknowledgement come back:

```
→ lane LANE-DEMO-01, plate T-HELLO-01, EventGuid evt-1e27278d-...
← <ZapPacket Type="ACK" Id="pkt-evt-1e27278d-..." Version="4.4" SenderId="999"></ZapPacket>
```

⚠️ **If you get no acknowledgement on the very first try, wait five seconds and run
it again.** Edge claims each lane through a lease and only polls for newly seeded
lanes every five seconds — so immediately after `demo-seed` it may not own the lane
yet. This is expected, and it is not a defect. It caught me too.

What happens next, without you doing anything: edge delivers the capture to
runtime, admission starts exactly one visit, the process calls the stubbed customer
system, commands the barrier through edge, the barrier confirms, and the visit
closes with its outbound fact — all in about a second.

### Looking at the database

You will do this constantly, so define this helper once, from the repository root.
It reads the password out of `deploy/.env`, so it works in any fresh terminal:

```bash
q() {
  ( set -a; . deploy/.env; set +a
    docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd \
      -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -C -No -I -d orca -h -1 -W -Q "$1" )
}
```

⚠️ **The `-I` matters.** Several tables have filtered indexes, and SQL Server
refuses to write to those unless `QUOTED_IDENTIFIER` is on. Without `-I` a write
fails with an error that names SET options and no table, which is a genuinely
confusing twenty minutes.

Now check your truck:

```bash
q "SELECT external_id, status, plate FROM runtime.execution"
```

Expect your plate with status `COMPLETED`. **If you got that, everything works.**

The full walkthrough — including deliberate demonstrations of deduplication, the
admission race, an unrouted branch and an expired command — is
`docs/phase-1-demo.md`.

## 6 · Build and verify

### ⚠️ 6.1 · Stop the services first — every time

**The integration suite and the running services use the SAME `runtime` schema on the
SAME database.** The suites migrate it, create visits and let the real engine advance
them; a running `orca-runtime` has an async executor that will pick up *the suite's*
jobs, and a running `orca-edge` polls its buffer.

Run them together and you get failures that look exactly like real concurrency
defects and are not. **This has already cost this project two debugging sessions.**

```bash
# stop the services
for p in 18081 18082 18083; do
  PID=$(lsof -nP -iTCP:$p -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$PID" ] && kill $PID
done

# stop Gradle — orphaned workers survive a FAILED run and hold memory and connections
./gradlew --stop
ps aux | grep -c "[G]radleWorkerMain"     # expect 0
```

| Doing this | Services must be |
|---|---|
| `./gradlew check integrationTest` | **STOPPED** |
| Driving a truck, calling an endpoint | **RUNNING** |

**The signature to recognise:** `AdmissionThroughHttpIT` failing with
`EOFException: EOF reached while reading`, or a transient SQL Server connection loss.
That is contention, not your code. Stop everything, re-run once, and only then start
debugging.

### 6.2 · The commands

```bash
./gradlew build                                  # compile, unit tests, the ten build checks
./gradlew check integrationTest --rerun-tasks    # FULL verification — about 6 minutes
```

⚠️ **Use `--rerun-tasks`.** Without it Gradle answers from its cache in under a
second and prints `BUILD SUCCESSFUL` for a suite it never ran.

⚠️ **`./gradlew test` runs almost nothing that matters.** The property suites live
in a separate source set so the build works on a machine with no Docker. Full
verification is `check integrationTest` — **236 integration tests** against a real
SQL Server and a real workflow engine.

⚠️ **Count what ran, do not trust the word "SUCCESSFUL".** A suite that was filtered
out or never discovered still lets the build pass:

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

⚠️ **A green build does not mean a service starts.** Every suite constructs its
beans directly rather than starting a service, so a broken bean definition passes
every test. This repository has shipped a service that passed everything and could
not boot. **Start the services and drive a truck before you call something done.**

## 7 · Onboard your AI

Open your AI assistant in the `orca` directory. It picks up the repository rules
automatically from `AGENTS.md` and `CLAUDE.md`.

Then paste this, once, at the start of a fresh session:

```
You are joining the ORCA 2.0 build as a developer's assistant.

Read these two documents in full before doing anything else, in this order:

  1. docs/DEVELOPER_ONBOARDING.md — what this system is, the rules that fail the
     build, how the old system may and may not be used, how to verify your work.
  2. docs/BUILD_ROADMAP.md — what is already built, what is being built now, what
     is deliberately not started, and where your stream fits in that.

The system being replaced is cloned as a sibling of this repository at
../Lynxis-Gate. It is READ-ONLY — never modify anything there. Before you search
it, read ../Lynxis-Gate/CLAUDE.md, or you will misread what you find.

Two standing rules. They look like housekeeping and they are not — they are the
two things that have gone wrong most often here:

  1. WHERE A docs/*-from-1x.md SHEET COVERS YOUR AREA, THE SHEET IS THE AUTHORITY,
     not the old source. Each one carries both what the old system does and the
     defects deliberately NOT carried forward, and its §0 is the acceptance
     criteria for the work. DEVELOPER_ONBOARDING.md §5 lists every sheet that
     exists. If none covers your work, say so and ask for one — do not port from
     source.
  2. WHEN SOMETHING IS UNSPECIFIED, REPORT THE GAP — never fill it with something
     plausible. Your stream plan's §5 lists the questions already known to be
     open; anything you find beyond them goes into your report unresolved. A gap
     reported is worth more than a gap filled.

When you have read both, tell me in your own words: what ORCA does, the two
properties that shape every decision in it, the rules you must never break, which
parts of the system do NOT exist yet, and what you will do when you hit something
the documents do not answer. Then wait for my task — do not start work.
```

**The comprehension check at the end is deliberate.** If the answer is vague, the
onboarding did not land and anything built on it will be wrong. Re-point it at the
document rather than proceeding.

⚠️ **The last clause of that check — *what you will do when you hit something the
documents do not answer* — is the one worth reading carefully.** An assistant that
answers "I'll make a reasonable assumption and note it" has not understood the
rule, and that is precisely the failure this programme has repeated most. The
answer you want is that it stops and reports. Correct it before you give it work,
not after.

For a specific piece of work you will also be given a **stream plan** — a
self-contained document naming what to build, what not to touch, and how to prove
it. That is what you hand your AI after this.

## 8 · When something does not work

| Symptom | Cause |
|---|---|
| SQL Server exits at startup | The `sa` password needs 8+ characters with upper, lower, digit and symbol. The error message does not say so |
| SQL Server is very slow to start | On Apple silicon it runs emulated — Microsoft publishes the image for amd64 only. It works. Do not swap in `azure-sql-edge`; it is a different engine |
| Runtime or edge will not start, complaining about views | Core has not migrated yet. Start core first |
| Ports 8081–8086 already taken | Set `MSSQL_PORT` and `KEYCLOAK_PORT` in `deploy/.env`, pass `--server.port=` to each service, and export `ORCA_DB_URL` / `ORCA_OIDC_ISSUER_URI` to match. `docs/phase-1-demo.md` §3 has the exact incantation |
| `sendPlate` gets no acknowledgement | Edge has not yet won the lane's lease — it polls every five seconds. Wait and retry before assuming a defect |
| A service refuses to start on a Flyway checksum | A migration changed since your database ran it. Nothing is deployed anywhere, so rebuild: `docker compose down -v && docker compose up -d && docker compose run --rm bootstrap` |
| `There is already an object named 'ACT_GE_PROPERTY'` | This database once let the workflow engine create its own tables. `cd deploy && ./adopt-flowable/run.sh` |

More detail lives in `deploy/README.md` — the stack itself, what each container is
for, and what is deliberately absent.

## 9 · Where to go next

| To learn | Read |
|---|---|
| What you are building and why | `docs/DEVELOPER_ONBOARDING.md` |
| The target design and its guarantees | `docs/ORCA_ARCHITECTURE.md` |
| What is deliberately still undecided | `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` |
| Where anything lives in this repository | `docs/REPOSITORY_GUIDE.md` |
| The rules that fail the build | `AGENTS.md` |
| One truck, end to end, in detail | `docs/phase-1-demo.md` |
