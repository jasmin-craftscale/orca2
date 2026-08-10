package com.lynxis.orca.runtime.workitem.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;
import com.lynxis.orca.runtime.persistence.Utc;
import com.lynxis.orca.runtime.workitem.domain.PresenceTables.UserActivity;

import lombok.RequiredArgsConstructor;

/** {@code user_activity} through the seam — the open row is the current status. */
@RequiredArgsConstructor
public class PresenceRepository {

	private static final String SCOPE = "site_external_id";

	private static final String[] COLUMNS = { "user_activity_id", "site_external_id",
			"user_external_id", "status", "started_at", "ended_at" };

	private final ScopeSeam seam;

	public Optional<UserActivity> openRowOf(String userExternalId) {
		return seam.select(ScopedSelect.from("user_activity")
						.columns(COLUMNS)
						.scopedBy(SCOPE)
						.where("user_external_id = ? AND ended_at IS NULL", userExternalId),
				PresenceRepository::map).stream().findFirst();
	}

	/** @return whether an open row was closed (false = the operator had none) */
	public boolean closeOpenRow(String userExternalId, Instant now) {
		return seam.update(ScopedUpdate.table("user_activity")
				.set("ended_at", Utc.timestampOf(now))
				.scopedBy(SCOPE)
				.where("user_external_id = ? AND ended_at IS NULL", userExternalId)) > 0;
	}

	/**
	 * Opens the new current-status row.
	 *
	 * @throws org.springframework.dao.DuplicateKeyException when another
	 *         transition raced this one to the open slot — the filtered unique
	 *         index is the guard; the caller retries on top of the winner
	 */
	public void openRow(String userExternalId, String siteExternalId, String status, Instant now) {
		seam.insert(ScopedInsert.into("user_activity")
				.scopedBy(SCOPE)
				.value(SCOPE, siteExternalId)
				.value("user_external_id", userExternalId)
				.value("status", status)
				.value("started_at", Utc.timestampOf(now)));
	}

	/** Every operator whose open row is one of the given statuses, longest-in-state first. */
	public List<UserActivity> openRowsWithStatus(List<String> statuses) {
		String placeholders = String.join(", ", statuses.stream().map(s -> "?").toList());
		return seam.select(ScopedSelect.from("user_activity")
						.columns(COLUMNS)
						.scopedBy(SCOPE)
						.where("ended_at IS NULL AND status IN (" + placeholders + ")",
								statuses.toArray())
						.orderBy("started_at"),
				PresenceRepository::map);
	}

	public List<UserActivity> historyOf(String userExternalId, int limit) {
		return seam.select(ScopedSelect.from("user_activity")
						.columns(COLUMNS)
						.scopedBy(SCOPE)
						.where("user_external_id = ?", userExternalId)
						.orderByDescending("started_at")
						.limit(limit),
				PresenceRepository::map);
	}

	private static UserActivity map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new UserActivity(
				rs.getLong("user_activity_id"),
				rs.getString("site_external_id"),
				rs.getString("user_external_id"),
				rs.getString("status"),
				Utc.instantAt(rs, "started_at"),
				Utc.instantAt(rs, "ended_at"));
	}
}
