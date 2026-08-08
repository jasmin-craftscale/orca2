package com.lynxis.orca.core.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.TeamTables.Team;
import com.lynxis.orca.core.domain.TeamTables.TeamMember;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * {@code team} and {@code team_member} through the seam — both site-dimensional,
 * so every read here leads with the site predicate and a write outside the
 * caller's site scope is refused before any SQL exists.
 */
@RequiredArgsConstructor
public class TeamRepository {

	private static final String SCOPE = "site_external_id";

	private static final String[] TEAM_COLUMNS = {
			"team_id", "external_id", "site_external_id", "name", "description",
			"handling_method", "shift_template_id", "break_template_id", "retired_at", "created_at" };

	private static final RowMapper<Team> TEAM_MAPPER = (rs, row) -> new Team(
			rs.getLong("team_id"),
			rs.getString("external_id"),
			rs.getString("site_external_id"),
			rs.getString("name"),
			rs.getString("description"),
			rs.getString("handling_method"),
			(Long) rs.getObject("shift_template_id"),
			(Long) rs.getObject("break_template_id"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<Team> all() {
		return seam.select(ScopedSelect.from("team")
						.columns(TEAM_COLUMNS)
						.scopedBy(SCOPE)
						.orderBy("team_id"),
				TEAM_MAPPER);
	}

	public Optional<Team> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from("team")
						.columns(TEAM_COLUMNS)
						.scopedBy(SCOPE)
						.where("external_id = ?", externalId),
				TEAM_MAPPER)
				.stream().findFirst();
	}

	public boolean nameInUse(String siteExternalId, String name, long excludingTeamId) {
		return seam.count(ScopedSelect.from("team")
				.scopedBy(SCOPE)
				.where("site_external_id = ? AND name = ? AND retired_at IS NULL AND team_id <> ?",
						siteExternalId, name, excludingTeamId)) > 0;
	}

	public long insert(String externalId, String siteExternalId, String name, String description,
			String handlingMethod, Long shiftTemplateId, Long breakTemplateId) {
		return seam.insertReturningKey(ScopedInsert.into("team")
						.scopedBy(SCOPE)
						.value("external_id", externalId)
						.value(SCOPE, siteExternalId)
						.value("name", name)
						.value("description", description)
						.value("handling_method", handlingMethod)
						.value("shift_template_id", shiftTemplateId)
						.value("break_template_id", breakTemplateId),
				"team_id");
	}

	/**
	 * Applies the non-null changes. The template references are tri-state via
	 * {@code clearShiftTemplate}/{@code clearBreakTemplate}: the API's empty
	 * string means "detach", which arrives here as an explicit clear rather
	 * than a magic value in the id parameter.
	 */
	public int update(String externalId, String name, String description, String handlingMethod,
			Long shiftTemplateId, boolean clearShiftTemplate,
			Long breakTemplateId, boolean clearBreakTemplate,
			Boolean retired) {
		ScopedUpdate update = ScopedUpdate.table("team")
				.scopedBy(SCOPE)
				.where("external_id = ?", externalId);
		boolean touched = false;
		if (name != null) {
			update.set("name", name);
			touched = true;
		}
		if (description != null) {
			update.set("description", description);
			touched = true;
		}
		if (handlingMethod != null) {
			update.set("handling_method", handlingMethod);
			touched = true;
		}
		if (shiftTemplateId != null || clearShiftTemplate) {
			update.set("shift_template_id", clearShiftTemplate ? null : shiftTemplateId);
			touched = true;
		}
		if (breakTemplateId != null || clearBreakTemplate) {
			update.set("break_template_id", clearBreakTemplate ? null : breakTemplateId);
			touched = true;
		}
		if (retired != null) {
			update.set("retired_at", retired ? Utc.now() : null);
			touched = true;
		}
		return touched ? seam.update(update) : 0;
	}

	/** Whether any active team references the shift template — what blocks retiring it. */
	public boolean anyActiveTeamUsesShiftTemplate(long shiftTemplateId) {
		return seam.count(ScopedSelect.from("team")
				.scopedBy(SCOPE)
				.where("shift_template_id = ? AND retired_at IS NULL", shiftTemplateId)) > 0;
	}

	public boolean anyActiveTeamUsesBreakTemplate(long breakTemplateId) {
		return seam.count(ScopedSelect.from("team")
				.scopedBy(SCOPE)
				.where("break_template_id = ? AND retired_at IS NULL", breakTemplateId)) > 0;
	}

	// --- team_member --------------------------------------------------------

	public List<TeamMember> activeMembers() {
		return seam.select(ScopedSelect.from("team_member")
						.columns("team_member_id", "team_id", "user_id", "site_external_id",
								"retired_at", "created_at")
						.scopedBy(SCOPE)
						.where("retired_at IS NULL")
						.orderBy("team_member_id"),
				(rs, row) -> new TeamMember(
						rs.getLong("team_member_id"),
						rs.getLong("team_id"),
						rs.getLong("user_id"),
						rs.getString("site_external_id"),
						Utc.instantAt(rs, "retired_at"),
						Utc.instantAt(rs, "created_at")));
	}

	/** Retires the team's membership and writes the new set. */
	public void replaceMembers(long teamId, String siteExternalId, Collection<Long> userIds) {
		seam.update(ScopedUpdate.table("team_member")
				.set("retired_at", Utc.now())
				.scopedBy(SCOPE)
				.where("team_id = ? AND retired_at IS NULL", teamId));
		for (long userId : userIds) {
			seam.insert(ScopedInsert.into("team_member")
					.scopedBy(SCOPE)
					.value("team_id", teamId)
					.value("user_id", userId)
					.value(SCOPE, siteExternalId));
		}
	}
}
