#!/usr/bin/env bash
# ORCA demo data — one site, one area, one lane, one camera, one barrier.
#
#   cd deploy && ./demo/seed.sh
#
# Run it after orca-core has migrated, because it writes into tables core's own
# migrations create. Re-running is a no-op.
#
# It is a deliberate act rather than a profile, and demo-site.sql explains why in
# full: neither a versioned nor a repeatable Flyway migration can be made to
# follow the active profile without giving up `validate-on-migrate`, and a Java
# seeder cannot touch a DataSource until the scope seam can write. Nothing runs
# this but a person.

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

# As orca_core, not as sa: the seed uses only rights the service itself has, so a
# permission this data needs cannot be one the service lacks.
if ! docker exec -i "$container" "$sqlcmd" \
		-S localhost -U orca_core -P "$ORCA_CORE_DB_PASSWORD" -C -No -b -I -d orca \
		-Q "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'core' AND TABLE_NAME = 'lane'" \
		2>/dev/null | grep -q 1; then
	echo "core.lane does not exist. Start orca-core once so it migrates its schema, then run this again." >&2
	exit 1
fi

docker exec -i "$container" "$sqlcmd" \
	-S localhost -U orca_core -P "$ORCA_CORE_DB_PASSWORD" -C -No -b -d orca \
	< "$here/demo-site.sql"

echo
echo "Demo data seeded."
