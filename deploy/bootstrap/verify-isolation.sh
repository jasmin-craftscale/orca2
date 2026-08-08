#!/usr/bin/env bash
# ORCA — prove that one service's login cannot read another service's schema.
#
# §7 item 9b. This is not a test of the code; it is a test of the DATABASE, which
# is where ADR-004 says the enforcement lives. A build check cannot see a GRANT,
# and a grant that was never tested is a grant nobody knows the shape of.
#
#   cd deploy && ./bootstrap/verify-isolation.sh
#
# Self-contained: each login writes a probe table in its OWN schema — which also
# proves it owns that schema — and is then pointed at every other service's probe
# in turn. Every one of those must be refused. A single success fails this script.
#
# Runs against a bootstrapped database whether or not any service has migrated,
# so it works in CI before anything has started.

set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(dirname "$here")"

# Dual-mode: host wraps sqlcmd in `docker exec`; the tools container
# (ORCA_IN_CONTAINER=1, passwords passed by compose) calls sqlcmd directly.
if [[ -z "${ORCA_IN_CONTAINER:-}" ]]; then
	set -a
	# shellcheck disable=SC1091
	source "$deploy_dir/.env"
	set +a
fi

container="${ORCA_SQLSERVER_CONTAINER:-orca-sqlserver}"
sqlcmd="/opt/mssql-tools18/bin/sqlcmd"
db_host="${ORCA_DB_HOST:-sqlserver}"

run_sqlcmd() {
	if [[ -n "${ORCA_IN_CONTAINER:-}" ]]; then
		"$sqlcmd" -S "$db_host" "$@"
	else
		docker exec -i "$container" "$sqlcmd" -S localhost "$@"
	fi
}

services=(core runtime edge portal sync fleet)

password_for() {
	case "$1" in
		core)    echo "$ORCA_CORE_DB_PASSWORD" ;;
		runtime) echo "$ORCA_RUNTIME_DB_PASSWORD" ;;
		edge)    echo "$ORCA_EDGE_DB_PASSWORD" ;;
		portal)  echo "$ORCA_PORTAL_DB_PASSWORD" ;;
		sync)    echo "$ORCA_SYNC_DB_PASSWORD" ;;
		fleet)   echo "$ORCA_FLEET_DB_PASSWORD" ;;
	esac
}

as_service() {
	local svc="$1" sql="$2"
	run_sqlcmd \
		-U "orca_$svc" -P "$(password_for "$svc")" \
		-C -No -b -d orca -h -1 -W -Q "$sql" 2>&1
}

failures=0
checks=0

echo "=== Each login writes and reads a probe in its OWN schema (must succeed) ==="
for svc in "${services[@]}"; do
	checks=$((checks + 1))
	# Unqualified on purpose: it lands in this login's DEFAULT_SCHEMA, which is
	# the mechanism the whole arrangement depends on. If the default schema were
	# wrong, this would silently create the table in dbo.
	as_service "$svc" "
		IF OBJECT_ID('isolation_probe','U') IS NULL CREATE TABLE isolation_probe (owner VARCHAR(40));
		DELETE FROM isolation_probe;
		INSERT INTO isolation_probe (owner) VALUES ('orca_$svc');" > /dev/null
	# Asked as its own statement, so the schema name is the only thing on stdout.
	out="$(as_service "$svc" "SELECT TOP 1 s.name FROM sys.tables t
		JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE t.name = 'isolation_probe';")"
	rc=$?
	landed="$(echo "$out" | grep -vE "rows affected|^$" | tr -d "[:space:]" | head -1)"
	if [[ $rc -eq 0 && "$landed" == "$svc" ]]; then
		printf '  %-8s -> owns schema [%s] and wrote to it\n' "$svc" "$svc"
	else
		printf '  %-8s -> FAILED (landed in "%s"):\n%s\n' "$svc" "$landed" "$out"
		failures=$((failures + 1))
	fi
done

echo
echo "=== Each login reads EVERY OTHER schema's probe (must be refused) ==="
for svc in "${services[@]}"; do
	for other in "${services[@]}"; do
		[[ "$svc" == "$other" ]] && continue
		checks=$((checks + 1))
		out="$(as_service "$svc" "SELECT owner FROM [$other].[isolation_probe];")"
		rc=$?
		if [[ $rc -ne 0 ]]; then
			reason="$(echo "$out" | grep -oiE "The SELECT permission was denied[^.]*|permission denied[^.]*|Invalid object name[^.]*" | head -1)"
			printf '  %-8s -> %-8s REFUSED  (%s)\n' "$svc" "$other" "${reason:-refused}"
		else
			printf '  %-8s -> %-8s *** READ SUCCEEDED — ISOLATION IS BROKEN ***\n' "$svc" "$other"
			failures=$((failures + 1))
		fi
	done
done

echo
echo "=== Cleaning up the probes ==="
for svc in "${services[@]}"; do
	as_service "$svc" "IF OBJECT_ID('isolation_probe','U') IS NOT NULL DROP TABLE isolation_probe;" > /dev/null
done

if [[ $failures -eq 0 ]]; then
	echo "PASS — $checks checks. Each login owns its own schema and reaches no other."
	exit 0
fi
echo "FAIL — $failures of $checks checks did not hold."
exit 1
