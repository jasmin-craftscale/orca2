package com.lynxis.orca.core.persistence;

import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.domain.TeamTables.ShiftTemplate;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** {@code shift_template} through the seam — installation-realm scoped. */
@RequiredArgsConstructor
public class ShiftTemplateRepository {

	private static final String TABLE = "shift_template";
	private static final String REALM = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final String[] COLUMNS = {
			"shift_template_id", "external_id", "config_realm", "name", "time_zone",
			"start_time", "end_time", "is_overnight", "retired_at", "created_at" };

	private static final RowMapper<ShiftTemplate> MAPPER = (rs, row) -> new ShiftTemplate(
			rs.getLong("shift_template_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("name"),
			rs.getString("time_zone"),
			rs.getObject("start_time", LocalTime.class),
			rs.getObject("end_time", LocalTime.class),
			rs.getBoolean("is_overnight"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<ShiftTemplate> all() {
		return seam.select(ScopedSelect.from(TABLE)
						.columns(COLUMNS)
						.scopedBy(REALM)
						.orderBy("shift_template_id"),
				MAPPER);
	}

	public Optional<ShiftTemplate> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from(TABLE)
						.columns(COLUMNS)
						.scopedBy(REALM)
						.where("external_id = ?", externalId),
				MAPPER)
				.stream().findFirst();
	}

	public boolean nameInUse(String name, long excludingId) {
		return seam.count(ScopedSelect.from(TABLE)
				.scopedBy(REALM)
				.where("name = ? AND retired_at IS NULL AND shift_template_id <> ?", name, excludingId)) > 0;
	}

	public void insert(String externalId, String name, String timeZone,
			LocalTime startTime, LocalTime endTime, boolean overnight) {
		seam.insert(ScopedInsert.into(TABLE)
				.scopedBy(REALM)
				.value("external_id", externalId)
				.value(REALM, IdentityTables.INSTALLATION_REALM)
				.value("name", name)
				.value("time_zone", timeZone)
				.value("start_time", Time.valueOf(startTime))
				.value("end_time", Time.valueOf(endTime))
				.value("is_overnight", overnight));
	}

	/**
	 * Times and the overnight flag always travel together — the CHECK ties them,
	 * so a caller changing either time recomputes and passes all three.
	 */
	public int update(String externalId, String name, String timeZone,
			LocalTime startTime, LocalTime endTime, Boolean overnight, Boolean retired) {
		ScopedUpdate update = ScopedUpdate.table(TABLE)
				.scopedBy(REALM)
				.where("external_id = ?", externalId);
		boolean touched = false;
		if (name != null) {
			update.set("name", name);
			touched = true;
		}
		if (timeZone != null) {
			update.set("time_zone", timeZone);
			touched = true;
		}
		if (startTime != null) {
			update.set("start_time", Time.valueOf(startTime));
			touched = true;
		}
		if (endTime != null) {
			update.set("end_time", Time.valueOf(endTime));
			touched = true;
		}
		if (overnight != null) {
			update.set("is_overnight", overnight);
			touched = true;
		}
		if (retired != null) {
			update.set("retired_at", retired ? Utc.now() : null);
			touched = true;
		}
		return touched ? seam.update(update) : 0;
	}
}
