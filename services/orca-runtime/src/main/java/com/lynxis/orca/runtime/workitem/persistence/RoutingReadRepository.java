package com.lynxis.orca.runtime.workitem.persistence;

import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * The routing world, through core's published views (§B4 mechanism 2 — the same
 * way admission reads {@code topology_lane}): screens, rules, memberships and
 * the operator directory. Runtime can reach nothing core has not deliberately
 * published, and every read leads with a scope predicate.
 */
@RequiredArgsConstructor
public class RoutingReadRepository {

	private static final String SCOPE = "site_external_id";

	/** Installation-realm reads (the operator directory) — phase-2 §5.1's second dimension. */
	private static final String REALM = "config_realm";

	private final ScopeSeam seam;

	/** The one active screen identity fronting this node at this site, if configured. */
	public Optional<ScreenIdentity> screenFor(String processDefinitionKey, String nodeReference) {
		return seam.select(ScopedSelect.from("core.topology_screen")
						.columns("screen_external_id", "screen_name", "below_expected_sec",
								"expected_sec", "max_sec")
						.scopedBy(SCOPE)
						.where("process_definition_key = ? AND node_reference = ?",
								processDefinitionKey, nodeReference),
				(rs, row) -> new ScreenIdentity(
						rs.getString("screen_external_id"),
						rs.getString("screen_name"),
						(Integer) rs.getObject("below_expected_sec"),
						(Integer) rs.getObject("expected_sec"),
						(Integer) rs.getObject("max_sec")))
				.stream().findFirst();
	}

	/** Every rule matching this screen on this lane — the eligibility evaluation's one read. */
	public List<RouteRule> rulesFor(String screenExternalId, String laneExternalId) {
		return seam.select(ScopedSelect.from("core.topology_team_routing")
						.columns("team_external_id", "handling_method", "priority")
						.scopedBy(SCOPE)
						.where("screen_external_id = ? AND lane_external_id = ?",
								screenExternalId, laneExternalId),
				RoutingReadRepository::rule);
	}

	/** Every rule for a team, for the team-filtered grid. */
	public List<TeamRule> rulesOfTeam(String teamExternalId) {
		return seam.select(ScopedSelect.from("core.topology_team_routing")
						.columns("team_external_id", "handling_method", "priority",
								"screen_external_id", "lane_external_id")
						.scopedBy(SCOPE)
						.where("team_external_id = ?", teamExternalId),
				RoutingReadRepository::teamRule);
	}

	/** Every rule at the site, for the unfiltered grid's ordering. */
	public List<TeamRule> allRules() {
		return seam.select(ScopedSelect.from("core.topology_team_routing")
						.columns("team_external_id", "handling_method", "priority",
								"screen_external_id", "lane_external_id")
						.scopedBy(SCOPE),
				RoutingReadRepository::teamRule);
	}

	/** The active members of one team — the Push selection's candidate pool. */
	public List<String> membersOf(String teamExternalId) {
		return seam.select(ScopedSelect.from("core.topology_team_member")
						.columns("user_external_id")
						.scopedBy(SCOPE)
						.where("team_external_id = ?", teamExternalId),
				(rs, row) -> rs.getString("user_external_id"));
	}

	/** Whether the operator belongs to any of these teams. */
	public boolean isMemberOfAny(String userExternalId, List<String> teamExternalIds) {
		if (teamExternalIds.isEmpty()) {
			return false;
		}
		String placeholders = String.join(", ", teamExternalIds.stream().map(t -> "?").toList());
		Object[] parameters = new Object[teamExternalIds.size() + 1];
		parameters[0] = userExternalId;
		for (int i = 0; i < teamExternalIds.size(); i++) {
			parameters[i + 1] = teamExternalIds.get(i);
		}
		return seam.count(ScopedSelect.from("core.topology_team_member")
				.scopedBy(SCOPE)
				.where("user_external_id = ? AND team_external_id IN (" + placeholders + ")",
						parameters)) > 0;
	}

	/**
	 * A setting's current value from core's published registry view — the value
	 * where one is set, else the seeded default, else empty. WP3's SLA fallback
	 * reads {@code MAX_PROCESSING_TIME_SEC} through this.
	 */
	public Optional<String> settingValue(String settingKey) {
		return seam.select(ScopedSelect.from("core.topology_setting")
						.columns("setting_value")
						.scopedBy(REALM, REALM)
						.where("setting_key = ?", settingKey),
				(rs, row) -> rs.getString("setting_value"))
				.stream().filter(java.util.Objects::nonNull).findFirst();
	}

	/**
	 * Resolves an identity-provider subject to the platform operator — the read
	 * that retires the "subject as actor" slice shape WP1 stated openly.
	 */
	public Optional<String> operatorBySubject(String subject) {
		return seam.select(ScopedSelect.from("core.topology_operator")
						.columns("user_external_id")
						.scopedBy(REALM, REALM)
						.where("keycloak_subject = ?", subject),
				(rs, row) -> rs.getString("user_external_id"))
				.stream().findFirst();
	}

	private static RouteRule rule(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new RouteRule(
				rs.getString("team_external_id"),
				rs.getString("handling_method"),
				(Integer) rs.getObject("priority"));
	}

	private static TeamRule teamRule(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new TeamRule(
				rs.getString("team_external_id"),
				rs.getString("handling_method"),
				(Integer) rs.getObject("priority"),
				rs.getString("screen_external_id"),
				rs.getString("lane_external_id"));
	}

	public record ScreenIdentity(String screenExternalId, String name, Integer belowExpectedSec,
			Integer expectedSec, Integer maxSec) {
	}

	public record RouteRule(String teamExternalId, String handlingMethod, Integer priority) {
	}

	public record TeamRule(String teamExternalId, String handlingMethod, Integer priority,
			String screenExternalId, String laneExternalId) {
	}
}
