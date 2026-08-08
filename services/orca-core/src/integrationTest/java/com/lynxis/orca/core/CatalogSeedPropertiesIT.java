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
	@DisplayName("two clean migrations produce identical rows, including every UUID — the seed is pinned, not minted")
	void theSeedIsByteStableAcrossCleanMigrations() {
		List<Map<String, Object>> firstRun = new JdbcTemplate(owner).queryForList(CATALOG_ROWS);

		// A second clean database: migratedSchema drops everything and re-runs
		// V100–V104 from nothing. If any UUID were minted at migration time
		// rather than pinned in the file, this comparison is where it dies.
		owner = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease");
		List<Map<String, Object>> secondRun = new JdbcTemplate(owner).queryForList(CATALOG_ROWS);

		assertThat(secondRun)
				.as("identical rows including UUIDs — verification item 4 of the plan")
				.isEqualTo(firstRun);
		assertThat(secondRun).hasSize(174);
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
