package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * Proves core's published topology views.
 *
 * <p>A published read-only view is the <em>only</em> permitted cross-schema read.
 * That is two claims, and both are testable against the database rather than
 * asserted in prose:
 *
 * <ul>
 *   <li>a consumer's login <strong>can</strong> read the view, and</li>
 *   <li>the same login <strong>cannot</strong> read the table underneath it.</li>
 * </ul>
 *
 * <p>The second is the one that makes the first mean anything. A view a consumer
 * can read <em>because it could already read everything</em> is not a contract,
 * it is a convenience — and nothing in Java can tell the two apart, which is why
 * this runs against real SQL Server with real per-service logins.
 *
 * <p>Core's actual migrations are applied: {@code db/migration} plus the primitive
 * locations core's own {@code application.yaml} lists. A test that hand-wrote the
 * views would be testing the test.
 */
class TopologyViewsIT {

	/** Deliberately the real schema name: the views are `core.topology_lane`, prefix-inside-core (ruled 7 Aug 2026). */
	private static final String SCHEMA = "core";

	private static DataSource owner;
	private static DataSource asRuntime;
	private static DataSource asEdge;

	private JdbcTemplate core;

	@BeforeAll
	static void migrate() {
		// The consumers must exist BEFORE core migrates: V102's grant refuses to run
		// against a database that has not been bootstrapped, on the grounds that a
		// published view no consumer can read is not published. Creating them here
		// is what deploy/bootstrap does in a real database — and it also proves that
		// guard does not false-fire when they are present.
		asRuntime = PlatformDatabase.consumerLogin("orca_runtime");
		asEdge = PlatformDatabase.consumerLogin("orca_edge");

		owner = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease");
	}

	@BeforeEach
	void freshWorld() {
		core = new JdbcTemplate(owner);
		core.execute("DELETE FROM device");
		core.execute("DELETE FROM lane");
		core.execute("DELETE FROM area");
		core.execute("DELETE FROM site");
	}

	// ------------------------------------------------------------------------
	// The contract: what a consumer can read, and what it cannot.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("orca_runtime can read core.topology_lane — the view is granted, not merely created")
	void aConsumerCanReadThePublishedLaneView() {
		seedOneLaneWithTwoDevices();

		JdbcTemplate runtime = new JdbcTemplate(asRuntime);
		String laneExternalId = runtime.queryForObject(
				"SELECT lane_external_id FROM core.topology_lane WHERE lane_code = 'L01'", String.class);

		assertThat(laneExternalId).isEqualTo("LANE-IT-01");
	}

	@Test
	@DisplayName("orca_runtime CANNOT read core.lane — the table underneath is not published")
	void aConsumerCannotReadTheTableUnderTheView() {
		seedOneLaneWithTwoDevices();

		JdbcTemplate runtime = new JdbcTemplate(asRuntime);

		assertThatThrownBy(() -> runtime.queryForObject("SELECT COUNT(*) FROM core.lane", Long.class))
				.as("if this succeeds the view is decoration: the consumer could reach core's own "
						+ "tables anyway, and core could not restructure them without breaking it")
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("permission was denied");

		assertThatThrownBy(() -> runtime.queryForObject("SELECT COUNT(*) FROM core.device", Long.class))
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> runtime.queryForObject("SELECT COUNT(*) FROM core.site", Long.class))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	@DisplayName("a published view is read-only — a consumer cannot write through it")
	void aConsumerCannotWriteThroughTheView() {
		seedOneLaneWithTwoDevices();

		JdbcTemplate runtime = new JdbcTemplate(asRuntime);

		assertThatThrownBy(() -> runtime.update(
				"UPDATE core.topology_lane SET is_out_of_service = 1 WHERE lane_code = 'L01'"))
				.as("§B4 mechanism 2: a view cannot be written to. Only SELECT was granted")
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	@DisplayName("orca_edge reads the same two views — both consumers §C1 names were granted")
	void bothNamedConsumersWereGranted() {
		seedOneLaneWithTwoDevices();

		JdbcTemplate edge = new JdbcTemplate(asEdge);

		assertThat(edge.queryForObject("SELECT COUNT(*) FROM core.topology_lane", Long.class)).isEqualTo(1);
		assertThat(edge.queryForObject("SELECT COUNT(*) FROM core.topology_device", Long.class)).isEqualTo(2);
	}

	// ------------------------------------------------------------------------
	// What the views carry, and what they deliberately hide.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a retired lane disappears from the view, so no consumer has to remember the convention")
	void retiredRowsAreAbsentFromTheView() {
		seedOneLaneWithTwoDevices();
		JdbcTemplate runtime = new JdbcTemplate(asRuntime);
		assertThat(runtime.queryForObject("SELECT COUNT(*) FROM core.topology_lane", Long.class)).isEqualTo(1);

		// Ordinary records are retired rather than removed. The row is still there.
		core.update("UPDATE lane SET retired_at = SYSUTCDATETIME() WHERE external_id = 'LANE-IT-01'");

		assertThat(core.queryForObject("SELECT COUNT(*) FROM lane", Long.class))
				.as("retired, not removed — the row survives in core's own table")
				.isEqualTo(1);
		assertThat(runtime.queryForObject("SELECT COUNT(*) FROM core.topology_lane", Long.class))
				.as("but a consumer never sees it, and never had to know the rule")
				.isZero();
		assertThat(runtime.queryForObject("SELECT COUNT(*) FROM core.topology_device", Long.class))
				.as("nor its devices, which are retired with it by the join")
				.isZero();
	}

	@Test
	@DisplayName("the lane view carries what §C1 says it carries, joined up from three tables")
	void theLaneViewCarriesTheHierarchy() {
		seedOneLaneWithTwoDevices();

		JdbcTemplate runtime = new JdbcTemplate(asRuntime);
		var row = runtime.queryForMap("SELECT * FROM core.topology_lane WHERE lane_code = 'L01'");

		assertThat(row).containsKeys("lane_id", "lane_external_id", "lane_code", "device_host_url",
				"is_out_of_service", "lane_priority", "area_external_id", "site_external_id", "site_is_primary");
		assertThat(row.get("site_external_id")).isEqualTo("SITE-IT");
		assertThat(row.get("device_host_url")).isEqualTo("http://device-host.invalid:9000");
	}

	@Test
	@DisplayName("the device view carries type and lane, so a consumer can find a lane's barrier")
	void theDeviceViewCarriesTypeAndLane() {
		seedOneLaneWithTwoDevices();

		JdbcTemplate runtime = new JdbcTemplate(asRuntime);
		// The provisional 'BARRIER' string became GATE_ARM when the legacy device-type
		// catalog was seeded; the view now serves that stable catalog code.
		String barrier = runtime.queryForObject(
				"SELECT device_external_id FROM core.topology_device "
						+ "WHERE lane_external_id = 'LANE-IT-01' AND device_type = 'GATE_ARM'", String.class);

		assertThat(barrier).isEqualTo("DEV-IT-BARRIER");
	}

	// ------------------------------------------------------------------------
	// The one invariant V101 encodes.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a second primary site is refused by the database, not by a code path someone can forget")
	void atMostOnePrimarySite() {
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES ('SITE-IT', 'IT', 'IT', 1)");

		assertThatThrownBy(() -> core.update(
				"INSERT INTO site (external_id, code, name, is_primary) VALUES ('SITE-IT-2', 'IT2', 'IT2', 1)"))
				.as("§C1: exactly one site is primary. A constraint holds the at-most-one half")
				.isInstanceOf(DuplicateKeyException.class);

		// A retired primary does not block its replacement — which is what the
		// `retired_at IS NULL` half of the filtered index is for.
		core.update("UPDATE site SET retired_at = SYSUTCDATETIME() WHERE external_id = 'SITE-IT'");
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES ('SITE-IT-3', 'IT3', 'IT3', 1)");

		assertThat(core.queryForObject(
				"SELECT COUNT(*) FROM site WHERE is_primary = 1 AND retired_at IS NULL", Long.class))
				.isEqualTo(1);
	}

	// ------------------------------------------------------------------------

	private void seedOneLaneWithTwoDevices() {
		core.update("INSERT INTO site (external_id, code, name, is_primary) "
				+ "VALUES ('SITE-IT', 'IT', 'IT Terminal', 1)");
		core.update("INSERT INTO area (external_id, site_id, code, name) "
				+ "SELECT 'AREA-IT', site_id, 'GATE', 'Main Gate' FROM site WHERE external_id = 'SITE-IT'");
		core.update("INSERT INTO lane (external_id, area_id, code, name, device_host_url) "
				+ "SELECT 'LANE-IT-01', area_id, 'L01', 'Lane 1', 'http://device-host.invalid:9000' "
				+ "FROM area WHERE external_id = 'AREA-IT'");
		core.update("INSERT INTO device (external_id, lane_id, site_external_id, device_type_id, name) "
				+ "SELECT 'DEV-IT-CAMERA', lane_id, 'SITE-IT', "
				+ "(SELECT device_type_id FROM device_type WHERE code = 'LPR_CAMERA'), 'Plate camera' "
				+ "FROM lane WHERE external_id = 'LANE-IT-01'");
		core.update("INSERT INTO device (external_id, lane_id, site_external_id, device_type_id, name) "
				+ "SELECT 'DEV-IT-BARRIER', lane_id, 'SITE-IT', "
				+ "(SELECT device_type_id FROM device_type WHERE code = 'GATE_ARM'), 'Barrier' "
				+ "FROM lane WHERE external_id = 'LANE-IT-01'");
	}
}
