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

# The two stub addresses, from .env, so that the port is written down once. They
# are localhost URLs because the SERVICES run on the developer's machine while the
# stubs run in the compose network — a container name would resolve only from
# inside another container.
tos_url="http://localhost:${ORCA_TOS_STUB_PORT:-9200}"
device_host_url="http://localhost:${ORCA_DEVICE_HOST_STUB_PORT:-9300}"

docker exec -i "$container" "$sqlcmd" \
	-S localhost -U orca_core -P "$ORCA_CORE_DB_PASSWORD" -C -No -b -I -d orca \
	-v deviceHostUrl="$device_host_url" \
	< "$here/demo-site.sql"

# Part two, as orca_runtime — these rows are in the `runtime` schema and
# `orca_core` cannot write it. That is ADR-004 working, not an inconvenience: a
# single seed login that could write both would mean the database was not
# enforcing the confinement verify-isolation.sh asserts.
if ! docker exec -i "$container" "$sqlcmd" \
		-S localhost -U orca_runtime -P "$ORCA_RUNTIME_DB_PASSWORD" -C -No -b -I -d orca \
		-Q "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'runtime' AND TABLE_NAME = 'connector_config'" \
		2>/dev/null | grep -q 1; then
	echo "runtime.connector_config does not exist. Start orca-runtime once so it migrates its schema, then run this again." >&2
	exit 1
fi

docker exec -i "$container" "$sqlcmd" \
	-S localhost -U orca_runtime -P "$ORCA_RUNTIME_DB_PASSWORD" -C -No -b -I -d orca \
	-v tosUrl="$tos_url" \
	< "$here/demo-connector.sql"

echo
echo "Demo data seeded."
echo "  TOS stub          $tos_url"
echo "  device-host stub  $device_host_url"
