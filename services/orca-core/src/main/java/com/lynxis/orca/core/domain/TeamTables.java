package com.lynxis.orca.core.domain;

import java.time.Instant;
import java.time.LocalTime;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The five tables {@code V105__teams_templates.sql} creates — declarations for
 * the build check, mirroring the migration column for column, never
 * {@code @Entity}. All {@link Growth#BOUNDED}: rows appear when an
 * administrator shapes a team or a template.
 *
 * <p>{@code team} and {@code team_member} are site-dimensional
 * ({@code site_external_id}, FK-backed, scope-leading indexes); the templates
 * are installation-realm — a template is a catalog the site-scoped team points
 * at, and 1.x's nullable {@code site_id} deliberately did not port (a nullable
 * scope column is invisible to every scoped read).
 */
public final class TeamTables {

	private TeamTables() {
	}

	/**
	 * A clerk team at a site.
	 *
	 * @param handlingMethod {@code PUSH} or {@code PROMPT} (§C1) — one casing,
	 *                       checked by the database; 1.x's two-row lookup table
	 *                       with random UUIDs did not port
	 */
	@PersistentTable(name = "team", growth = Growth.BOUNDED)
	public record Team(
			long teamId,
			String externalId,
			String siteExternalId,
			String name,
			String description,
			String handlingMethod,
			Long shiftTemplateId,
			Long breakTemplateId,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** A user's membership of a team. The pair is unique among active rows. */
	@PersistentTable(name = "team_member", growth = Growth.BOUNDED)
	public record TeamMember(
			long teamMemberId,
			long teamId,
			long userId,
			String siteExternalId,
			Instant retiredAt,
			Instant createdAt) {
	}

	/**
	 * A shift, with one validated IANA timezone reference.
	 *
	 * <p>Duration is deliberately NOT a column: 1.x stored a duration in a TIME
	 * column, and the honest fix is not a better type but not storing a
	 * derivable at all. {@code end <= start} is the overnight shape (equal is
	 * the 24-hour shift), and the database CHECK ties {@code isOvernight} to
	 * the times so the flag can never contradict them.
	 */
	@PersistentTable(name = "shift_template", growth = Growth.BOUNDED)
	public record ShiftTemplate(
			long shiftTemplateId,
			String externalId,
			String configRealm,
			String name,
			String timeZone,
			LocalTime startTime,
			LocalTime endTime,
			boolean isOvernight,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** A break plan a team follows. */
	@PersistentTable(name = "break_template", growth = Growth.BOUNDED)
	public record BreakTemplate(
			long breakTemplateId,
			String externalId,
			String configRealm,
			String name,
			String description,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** One break inside a plan — NOT NULL start and positive minutes, the 1.x intent (rule 4). */
	@PersistentTable(name = "break_timing", growth = Growth.BOUNDED)
	public record BreakTiming(
			long breakTimingId,
			long breakTemplateId,
			String configRealm,
			LocalTime breakStartTime,
			int durationMinutes,
			Instant retiredAt,
			Instant createdAt) {
	}
}
