package com.lynxis.orca.runtime.workitem;

import java.util.function.Consumer;

/**
 * Stand-ins for core's Phase 3 WP2 published views, as INSERTABLE TABLES under
 * the names runtime reads (`core.topology_screen`, `core.topology_team_routing`,
 * `core.topology_team_member`, `core.topology_operator`) — the same trick every
 * runtime suite already plays with `core.topology_lane`, widened because a test
 * needs to vary rules and memberships per case, which a literal view cannot.
 *
 * <p>The real views' column contract is what these mirror; the core-side suite
 * ({@code RoutingScreensPropertiesIT}) is what proves the real views produce
 * these columns.
 */
public final class RoutingTopologyFixture {

	private RoutingTopologyFixture() {
	}

	public static void publish(Consumer<String> admin, String grantee) {
		for (String table : new String[] { "topology_screen", "topology_team_routing",
				"topology_team_member", "topology_operator", "topology_setting" }) {
			admin.accept("IF OBJECT_ID(N'core." + table + "', 'U') IS NOT NULL DROP TABLE core." + table);
			admin.accept("IF OBJECT_ID(N'core." + table + "', 'V') IS NOT NULL DROP VIEW core." + table);
		}
		admin.accept("""
				CREATE TABLE core.topology_screen (
					screen_external_id VARCHAR(64), screen_name NVARCHAR(255),
					process_definition_key VARCHAR(255), node_reference VARCHAR(255),
					below_expected_sec INT NULL, expected_sec INT NULL, max_sec INT NULL,
					site_external_id VARCHAR(64))""");
		admin.accept("""
				CREATE TABLE core.topology_team_routing (
					site_external_id VARCHAR(64), team_external_id VARCHAR(64),
					team_name NVARCHAR(255), handling_method VARCHAR(16),
					screen_external_id VARCHAR(64), process_definition_key VARCHAR(255),
					node_reference VARCHAR(255), lane_external_id VARCHAR(64), priority INT NULL)""");
		admin.accept("""
				CREATE TABLE core.topology_team_member (
					site_external_id VARCHAR(64), team_external_id VARCHAR(64),
					user_external_id VARCHAR(64))""");
		admin.accept("""
				CREATE TABLE core.topology_operator (
					config_realm VARCHAR(16), user_external_id VARCHAR(64),
					keycloak_subject VARCHAR(64), display_name NVARCHAR(200))""");
		admin.accept("""
				CREATE TABLE core.topology_setting (
					config_realm VARCHAR(16), setting_key VARCHAR(200),
					setting_value NVARCHAR(2000) NULL)""");
		for (String table : new String[] { "topology_screen", "topology_team_routing",
				"topology_team_member", "topology_operator", "topology_setting" }) {
			admin.accept("GRANT SELECT ON core." + table + " TO [" + grantee + "]");
		}
	}
}
