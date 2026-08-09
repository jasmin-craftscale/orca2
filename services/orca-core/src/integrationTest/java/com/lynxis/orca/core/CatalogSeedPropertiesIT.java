package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * <strong>WP1 · the entitlement catalog seed (V103 + V104).</strong>
 *
 * <p>The plan's done-when: the seed is byte-stable across two clean migrations,
 * and the counts match the sheet's script-verified numbers (GATE = 3/30/174).
 * Stability matters because the catalog's identity is the contract — a seed
 * that minted different UUIDs per install would recreate 1.x's load-bearing
 * display-string matching one layer down.
 */
class CatalogSeedPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String CATALOG_ROWS =
			"SELECT a.code AS app_code, a.external_id AS app_uuid, m.code AS mod_code, m.external_id AS mod_uuid, "
					+ "s.code AS sub_code, s.external_id AS sub_uuid, i.code AS item_code, i.external_id AS item_uuid, "
					+ "i.name AS item_name, i.licence_route "
					+ "FROM entitlement_action_item i "
					+ "JOIN entitlement_sub_module s ON s.sub_module_id = i.sub_module_id "
					+ "JOIN entitlement_module m ON m.module_id = s.module_id "
					+ "JOIN entitlement_application a ON a.application_id = m.application_id "
					+ "ORDER BY i.action_item_id";

	private static DataSource owner;

	@BeforeAll
	static void migrate() {
		// V102's grant guard: the consumer principals must exist before core migrates.
		PlatformDatabase.consumerLogin("orca_runtime");
		PlatformDatabase.consumerLogin("orca_edge");
		owner = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease");
	}

	@Test
	@DisplayName("the GATE tree seeds at exactly the sheet's counts: 1 application, 3 modules, 30 sub-modules, 174 action items")
	void theCountsMatchTheSheet() {
		JdbcTemplate core = new JdbcTemplate(owner);

		assertThat(core.queryForObject("SELECT COUNT(*) FROM entitlement_application", Long.class)).isEqualTo(1);
		assertThat(core.queryForObject("SELECT COUNT(*) FROM entitlement_module", Long.class)).isEqualTo(3);
		assertThat(core.queryForObject("SELECT COUNT(*) FROM entitlement_sub_module", Long.class)).isEqualTo(30);
		assertThat(core.queryForObject("SELECT COUNT(*) FROM entitlement_action_item", Long.class))
				.as("the count the generator verified against the catalog file before emitting a row")
				.isEqualTo(174);
	}

	@Test
	@DisplayName("two clean migrations produce identical rows, including every UUID — every catalog seed is pinned, not minted")
	void theSeedIsByteStableAcrossCleanMigrations() {
		String deviceTypes = "SELECT external_id, code, name FROM device_type ORDER BY device_type_id";
		String portNames = "SELECT external_id, port_type, code, name FROM device_io_port_name ORDER BY port_name_id";
		String deviceKinds = "SELECT external_id, port_type, code, name FROM io_device_kind ORDER BY io_device_kind_id";

		JdbcTemplate first = new JdbcTemplate(owner);
		List<Map<String, Object>> entitlementsFirst = first.queryForList(CATALOG_ROWS);
		List<Map<String, Object>> typesFirst = first.queryForList(deviceTypes);
		List<Map<String, Object>> portsFirst = first.queryForList(portNames);
		List<Map<String, Object>> kindsFirst = first.queryForList(deviceKinds);

		// A second clean database: migratedSchema drops everything and re-runs
		// V100 onward from nothing. If any UUID were minted at migration time
		// rather than pinned in the file, this comparison is where it dies.
		owner = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease");
		JdbcTemplate second = new JdbcTemplate(owner);

		assertThat(second.queryForList(CATALOG_ROWS))
				.as("identical rows including UUIDs — verification item 4 of the plan")
				.isEqualTo(entitlementsFirst)
				.hasSize(174);
		assertThat(second.queryForList(deviceTypes))
				.as("the device-type catalog is pinned the same way — complete at 16 since V108 (Phase 3 WP0)")
				.isEqualTo(typesFirst)
				.hasSize(16);
		assertThat(second.queryForList(portNames))
				.as("and the port-name catalog — complete at 40 since V108 seeded the five Audio rows")
				.isEqualTo(portsFirst)
				.hasSize(40);
		assertThat(second.queryForList(deviceKinds))
				.as("and io_device_kind — seeded empty by V106, complete at 19 since V108")
				.isEqualTo(kindsFirst)
				.hasSize(19);
	}

	@Test
	@DisplayName("WP0 closed the seeded gaps: the three catalogs are complete and the provisional PTZ codes are the 1.x-exact ones")
	void theDeviceCatalogsAreComplete() {
		JdbcTemplate core = new JdbcTemplate(owner);

		assertThat(core.queryForObject("SELECT COUNT(*) FROM io_device_kind", Long.class)).isEqualTo(19);
		assertThat(core.queryForObject("SELECT COUNT(*) FROM device_io_port_name", Long.class)).isEqualTo(40);
		assertThat(core.queryForObject("SELECT COUNT(*) FROM device_type", Long.class)).isEqualTo(16);

		// The V106 provisional codes are gone, corrected in place by V108 — the
		// external id followed the code, because the code is the identity (rule 7).
		assertThat(core.queryForList("SELECT code FROM device_type WHERE code IN "
				+ "('PTZ_CAMERA', 'PELCO_CAMERA', 'MILESIGHT_CAMERA')", String.class))
				.as("the provisional codes must not survive V108")
				.isEmpty();
		assertThat(core.queryForObject("SELECT external_id FROM device_type WHERE code = 'AXIS_PTZ_CAMERA'",
				String.class))
				.as("the corrected row carries the UUID its code always mints in the catalog namespace")
				.isEqualTo("06d4788b-de8e-5d8c-a7b8-6389bd99841e");

		// The port-name/io-device-kind naming drift the sheet flags (1.x 'FrontMic'
		// vs 'Front Mic') is unified: the audio kinds and the audio port names
		// agree on display names where both catalogs carry the same concept.
		assertThat(core.queryForList("SELECT name FROM io_device_kind WHERE code IN "
				+ "('FRONT_MIC', 'FRONT_SPEAKER', 'REAR_SPEAKER') ORDER BY code", String.class))
				.containsExactly("Front Mic", "Front Speaker", "Rear Speaker");
	}

	@Test
	@DisplayName("codes are the contract: unique per level, and the catalog's one real name collision is disambiguated, not merged")
	void codesAreUniqueAndTheEventDataCollisionIsExplicit() {
		JdbcTemplate core = new JdbcTemplate(owner);

		// The unique constraints are schema facts; this asserts the interesting
		// content half: GATE·Admin genuinely contains TWO sub-modules displayed
		// as "Event Data" (1.x uuids 27c4… and de3d…), and the seed keeps both
		// under distinct codes rather than silently collapsing them.
		List<String> eventDataCodes = core.queryForList(
				"SELECT code FROM entitlement_sub_module WHERE name = N'Event Data' ORDER BY code",
				String.class);
		assertThat(eventDataCodes).containsExactly(
				"GATE.ADMIN.EVENT_DATA",
				"GATE.ADMIN.EVENT_DATA_2",
				"GATE.INSIGHTS.EVENT_DATA");

		// And the licence route is deliberately NOT unique — ExportExcel recurs
		// across sub-modules. If this ever becomes unique the licence gate has
		// been quietly redesigned, which is the licensing phase's decision.
		Long exportExcelRoutes = core.queryForObject(
				"SELECT COUNT(*) FROM entitlement_action_item WHERE licence_route = 'ExportExcel'", Long.class);
		assertThat(exportExcelRoutes)
				.as("licence filtering by route is coarser than the catalog — a 1.x fact carried, not fixed")
				.isGreaterThan(10);
	}
}
