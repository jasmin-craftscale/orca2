#!/usr/bin/env bash
# ORCA — prove that one service's login cannot read another service's schema.
#
# §7 item 9b. This is not a test of the code; it is a test of the DATABASE, which
# is where ADR-004 says the enforcement lives. A grant that was never tested is a
# grant nobody knows the shape of.
#
#   cd deploy && ./bootstrap/verify-isolation.sh
#
# Every service login is pointed at every OTHER service's schema in turn. Each
# attempt must be refused. A single success fails this script.

set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(dirname "$here")"

set -a
# shellcheck disable=SC1091
source "$deploy_dir/.env"
set +a

container="${ORCA_SQLSERVER_CONTAINER:-orca-sqlserver}"
sqlcmd="/opt/mssql-tools18/bin/sqlcmd"

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
	docker exec -i "$container" "$sqlcmd" \
		-S localhost -U "orca_$svc" -P "$(password_for "$svc")" \
		-C -No -b -d orca -h -1 -W -Q "$sql" 2>&1
}

failures=0
checks=0

echo "=== Each login reads its OWN schema (must succeed) ==="
for svc in "${services[@]}"; do
	checks=$((checks + 1))
	# The Flyway history table is the one object every migrated schema has.
	out="$(as_service "$svc" "SELECT COUNT(*) FROM [$svc].[flyway_schema_history];")"
	if [[ $? -eq 0 ]]; then
		printf '  %-8s -> own schema readable (%s migrations applied)\n' "$svc" "$(echo "$out" | tr -d '[:space:]')"
	else
		printf '  %-8s -> FAILED to read its own schema:\n%s\n' "$svc" "$out"
		failures=$((failures + 1))
	fi
done

echo
echo "=== Each login reads EVERY OTHER schema (must be refused) ==="
for svc in "${services[@]}"; do
	for other in "${services[@]}"; do
		[[ "$svc" == "$other" ]] && continue
		checks=$((checks + 1))
		out="$(as_service "$svc" "SELECT COUNT(*) FROM [$other].[flyway_schema_history];")"
		rc=$?
		if [[ $rc -ne 0 ]]; then
			reason="$(echo "$out" | grep -oiE "permission denied[^.]*|The SELECT permission was denied[^.]*|Invalid object name[^.]*" | head -1)"
			printf '  %-8s -> %-8s REFUSED  (%s)\n' "$svc" "$other" "${reason:-refused}"
		else
			printf '  %-8s -> %-8s *** READ SUCCEEDED — ISOLATION IS BROKEN ***\n' "$svc" "$other"
			failures=$((failures + 1))
		fi
	done
done

echo
if [[ $failures -eq 0 ]]; then
	echo "PASS — $checks checks. Each login reaches its own schema and no other."
	exit 0
fi
echo "FAIL — $failures of $checks checks did not hold."
exit 1
