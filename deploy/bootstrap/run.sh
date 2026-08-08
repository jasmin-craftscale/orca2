#!/usr/bin/env bash
# ORCA bootstrap — the privileged half of the database setup.
#
# Creates the database, seven schemas, seven logins and the grants that confine
# each login to its own schema. Runs ONCE against a fresh database, before any
# service starts. Re-running is a no-op.
#
#   cd deploy && ./bootstrap/run.sh
#
# Reads passwords from deploy/.env. It runs sqlcmd inside the SQL Server
# container, so nothing has to be installed on the host.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(dirname "$here")"

# Dual-mode. On a developer host: source .env and wrap sqlcmd in `docker exec`.
# Inside the tools container (ORCA_IN_CONTAINER=1 — see the `bootstrap` service
# in docker-compose.yml, which passes the passwords from .env itself): call
# sqlcmd directly against the `sqlserver` service. Same logic either way; the
# container path is what makes a Windows host need nothing but Docker.
if [[ -z "${ORCA_IN_CONTAINER:-}" ]]; then
	if [[ ! -f "$deploy_dir/.env" ]]; then
		echo "deploy/.env not found. Run: cp .env.example .env" >&2
		exit 1
	fi
	set -a
	# shellcheck disable=SC1091
	source "$deploy_dir/.env"
	set +a
fi

container="${ORCA_SQLSERVER_CONTAINER:-orca-sqlserver}"
sqlcmd="/opt/mssql-tools18/bin/sqlcmd"
db_host="${ORCA_DB_HOST:-sqlserver}"

if [[ -z "${ORCA_IN_CONTAINER:-}" ]] && ! docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; then
	echo "Container '$container' is not running. Run: docker compose up -d" >&2
	exit 1
fi

run_sqlcmd() {
	if [[ -n "${ORCA_IN_CONTAINER:-}" ]]; then
		"$sqlcmd" -S "$db_host" "$@"
	else
		docker exec -i "$container" "$sqlcmd" -S localhost "$@"
	fi
}

run_file() {
	local file="$1"
	local db="${2:-master}"
	echo "── $(basename "$file")"
	run_sqlcmd \
		-U sa -P "$MSSQL_SA_PASSWORD" -C -No -b \
		-d "$db" \
		-v CORE_PASSWORD="$ORCA_CORE_DB_PASSWORD" \
		-v RUNTIME_PASSWORD="$ORCA_RUNTIME_DB_PASSWORD" \
		-v EDGE_PASSWORD="$ORCA_EDGE_DB_PASSWORD" \
		-v PORTAL_PASSWORD="$ORCA_PORTAL_DB_PASSWORD" \
		-v SYNC_PASSWORD="$ORCA_SYNC_DB_PASSWORD" \
		-v FLEET_PASSWORD="$ORCA_FLEET_DB_PASSWORD" \
		-v MEDIA_PASSWORD="$ORCA_MEDIA_DB_PASSWORD" \
		< "$file"
}

# Versioned, and applied in order. The order is load-bearing: a schema cannot be
# authorised to a user that does not exist yet.
for f in "$here"/V*.sql; do
	run_file "$f"
done

echo
echo "Bootstrap complete. Every service can now migrate its own schema on startup."
