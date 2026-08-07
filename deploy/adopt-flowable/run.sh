#!/usr/bin/env bash
# Adopt a database whose Flowable tables were created by the engine itself.
#
#   cd deploy && ./adopt-flowable/run.sh
#
# Run it ONCE, with orca-runtime STOPPED, before starting a build that carries
# V110–V114. On a database that needs no adoption it prints so and changes
# nothing, so running it when in doubt is safe.
#
# ⚠️ Unlike bootstrap/run.sh this does NOT run as sa. It runs as orca_runtime,
# and the script works on that login's default schema — so the database itself
# is what stops it touching a schema that is not runtime's (ADR-004). The script
# drops tables; that confinement is the point, not a formality.
#
# What it will not do is in the SQL beside this file and in
# docs/flowable-adoption.md: it refuses, without changing anything, when the
# engine schema holds process data or was built by a different Flowable version.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(dirname "$here")"

if [[ ! -f "$deploy_dir/.env" ]]; then
	echo "deploy/.env not found. See deploy/README.md — do not blind-copy .env.example over an existing .env." >&2
	exit 1
fi

set -a
# shellcheck disable=SC1091
source "$deploy_dir/.env"
set +a

container="${ORCA_SQLSERVER_CONTAINER:-orca-sqlserver}"
sqlcmd="/opt/mssql-tools18/bin/sqlcmd"

if ! docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; then
	echo "Container '$container' is not running. Run: docker compose up -d" >&2
	exit 1
fi

echo "Adopting Flowable's schema in [runtime], as orca_runtime."
echo "⚠️  orca-runtime must NOT be running."
echo

# -b so a RAISERROR — which is how the script refuses — exits non-zero rather
# than printing a message the operator scrolls past.
docker exec -i "$container" "$sqlcmd" \
	-S localhost -U orca_runtime -P "$ORCA_RUNTIME_DB_PASSWORD" -C -No -b -I -d orca \
	< "$here/adopt-engine-created-schema.sql"

echo
echo "Done. Start orca-runtime; Flyway will build V110–V114."
