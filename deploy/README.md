# deploy — the local stack

```
cd deploy
cp .env.example .env
docker compose up -d          # SQL Server + Keycloak, both with health checks
docker compose run --rm bootstrap   # ONCE, against a fresh database: schemas, logins, grants
                                    # (./bootstrap/run.sh does the same from a Unix host)
```

Then start any service **with the `local` profile** — each migrates its own
schema on startup, with its own login:

```
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local'
```

The profile is not optional on a laptop: the committed inter-service credential
is recognised by name and refused outside `local`, so the public fixture cannot
reach a customer site by inertia (ADR-011).

| | |
|---|---|
| SQL Server | `localhost:1433`, `sa` / see `.env` |
| Keycloak | `http://localhost:8080`, admin console `admin` / see `.env` |
| Realm | `orca` — issuer `http://localhost:8080/realms/orca` |

Both host ports are settable in `.env` (`MSSQL_PORT`, `KEYCLOAK_PORT`) for a machine
that already has something on them.

## What is here, and what is deliberately not

| | |
|---|---|
| `docker-compose.yml` | SQL Server and Keycloak. Nothing else |
| `.env.example` | Committed, with working local values. `.env` is gitignored |
| `keycloak/realm-export.json` | One realm, one client per service, a service account on each. Imported on startup |
| `bootstrap/` | The privileged half of the database setup: schemas, logins, grants. Versioned SQL, run once, before any service |

**No message broker** (ADR-007). Work is handed between services through the
database. If you are about to add one here, read §B4 first — its absence is a
decision with a stated cost, not an omission.

**No reverse proxy.** A site runs one, and it is what terminates TLS on the site
network. Nothing in Phase 0 needs TLS terminated locally.

**No PostgreSQL.** SQL Server is the only database (ADR-003). Advice elsewhere
that assumes PostgreSQL — particularly about row-level security — does not apply.

## Two things that will bite you

**The SA password needs 8+ characters with upper, lower, digit and symbol.** SQL
Server exits during startup otherwise, and the message it prints does not say
that. This is the single most common cause of "Docker will not start SQL Server".

**The SQL Server image is `linux/amd64` only.** On Apple silicon, Docker Desktop
runs it under emulation. It works, and it is slow — a first start takes tens of
seconds. Do not swap in `azure-sql-edge` to get a native image: it is a different
engine, and testing against it is not testing against what ships.
