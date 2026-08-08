package com.lynxis.orca.core.persistence;

import java.sql.Time;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.domain.TeamTables.BreakTemplate;
import com.lynxis.orca.core.domain.TeamTables.BreakTiming;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** {@code break_template} and its {@code break_timing} rows — installation-realm scoped. */
@RequiredArgsConstructor
public class BreakTemplateRepository {

	private static final String REALM = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final RowMapper<BreakTemplate> TEMPLATE_MAPPER = (rs, row) -> new BreakTemplate(
			rs.getLong("break_template_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("name"),
			rs.getString("description"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<BreakTiming> TIMING_MAPPER = (rs, row) -> new BreakTiming(
			rs.getLong("break_timing_id"),
			rs.getLong("break_template_id"),
			rs.getString("config_realm"),
			rs.getObject("break_start_time", LocalTime.class),
			rs.getInt("duration_minutes"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<BreakTemplate> all() {
		return seam.select(ScopedSelect.from("break_template")
						.columns("break_template_id", "external_id", "config_realm", "name",
								"description", "retired_at", "created_at")
						.scopedBy(REALM)
						.orderBy("break_template_id"),
				TEMPLATE_MAPPER);
	}

	public Optional<BreakTemplate> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from("break_template")
						.columns("break_template_id", "external_id", "config_realm", "name",
								"description", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("external_id = ?", externalId),
				TEMPLATE_MAPPER)
				.stream().findFirst();
	}

	public boolean nameInUse(String name, long excludingId) {
		return seam.count(ScopedSelect.from("break_template")
				.scopedBy(REALM)
				.where("name = ? AND retired_at IS NULL AND break_template_id <> ?", name, excludingId)) > 0;
	}

	public long insert(String externalId, String name, String description) {
		return seam.insertReturningKey(ScopedInsert.into("break_template")
						.scopedBy(REALM)
						.value("external_id", externalId)
						.value(REALM, IdentityTables.INSTALLATION_REALM)
						.value("name", name)
						.value("description", description),
				"break_template_id");
	}

	public int update(String externalId, String name, String description, Boolean retired) {
		ScopedUpdate update = ScopedUpdate.table("break_template")
				.scopedBy(REALM)
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
		if (retired != null) {
			update.set("retired_at", retired ? Utc.now() : null);
			touched = true;
		}
		return touched ? seam.update(update) : 0;
	}

	/** Active timings of every template, in start-time order per template. */
	public List<BreakTiming> activeTimings() {
		return seam.select(ScopedSelect.from("break_timing")
						.columns("break_timing_id", "break_template_id", "config_realm",
								"break_start_time", "duration_minutes", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("break_timing_id"),
				TIMING_MAPPER);
	}

	/** Retires the template's timing set and writes the new one. */
	public void replaceTimings(long breakTemplateId, Collection<BreakTiming> timings) {
		seam.update(ScopedUpdate.table("break_timing")
				.set("retired_at", Utc.now())
				.scopedBy(REALM)
				.where("break_template_id = ? AND retired_at IS NULL", breakTemplateId));
		for (BreakTiming timing : timings) {
			seam.insert(ScopedInsert.into("break_timing")
					.scopedBy(REALM)
					.value("break_template_id", breakTemplateId)
					.value(REALM, IdentityTables.INSTALLATION_REALM)
					.value("break_start_time", Time.valueOf(timing.breakStartTime()))
					.value("duration_minutes", timing.durationMinutes()));
		}
	}
}
