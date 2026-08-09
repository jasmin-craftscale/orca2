package com.lynxis.orca.core.persistence;

import java.util.List;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.RoutingTables.TeamRouting;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * {@code team_routing} through the seam. Replacing a team's rule set retires the
 * active rows and inserts the new set in the caller's transaction — the same
 * declarative-set shape as {@code team_member}, and the seam's shape (it has no
 * delete; retirement is the platform's removal).
 */
@RequiredArgsConstructor
public class TeamRoutingRepository {

	private static final String SCOPE = "site_external_id";

	private static final String[] COLUMNS = { "team_routing_id", "external_id", "site_external_id",
			"team_id", "screen_id", "lane_id", "priority", "created_at" };

	private static final RowMapper<TeamRouting> MAPPER = (rs, row) -> new TeamRouting(
			rs.getLong("team_routing_id"),
			rs.getString("external_id"),
			rs.getString("site_external_id"),
			rs.getLong("team_id"),
			rs.getLong("screen_id"),
			rs.getLong("lane_id"),
			(Integer) rs.getObject("priority"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<TeamRouting> activeOfTeam(long teamId) {
		return seam.select(ScopedSelect.from("team_routing")
						.columns(COLUMNS)
						.scopedBy(SCOPE)
						.where("team_id = ? AND retired_at IS NULL", teamId)
						.orderBy("team_routing_id"),
				MAPPER);
	}

	public void retireOfTeam(long teamId) {
		seam.update(com.lynxis.orca.platform.scope.ScopedUpdate.table("team_routing")
				.set("retired_at", Utc.now())
				.scopedBy(SCOPE)
				.where("team_id = ? AND retired_at IS NULL", teamId));
	}

	public void insert(String externalId, String siteExternalId, long teamId, long screenId,
			long laneId, Integer priority) {
		seam.insert(ScopedInsert.into("team_routing")
				.scopedBy(SCOPE)
				.value("external_id", externalId)
				.value(SCOPE, siteExternalId)
				.value("team_id", teamId)
				.value("screen_id", screenId)
				.value("lane_id", laneId)
				.value("priority", priority));
	}
}
