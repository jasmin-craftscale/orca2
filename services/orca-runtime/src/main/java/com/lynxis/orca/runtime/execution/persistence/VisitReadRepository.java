package com.lynxis.orca.runtime.execution.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * Reading visits back — the "what happened to this truck?" question.
 *
 * <p><strong>Every read here is bounded, and that is the design rather than a
 * precaution.</strong> {@code execution} grows with every truck, so a query with no
 * predicate is not merely slow: its cost grows for the lifetime of the installation.
 * The search always carries a time window — the caller's or a default — and always a
 * row limit, and neither is optional at this layer.
 *
 * <p><strong>One table per statement.</strong> The scope seam allows no joins, by
 * design: it applies the site condition to a single table and an identifier that is
 * not a bare name is refused outright. So the lane's external identifier is resolved
 * through {@link AdmissionRepository}, which already owns both directions of that
 * mapping against core's published view, rather than duplicated into a join here.
 */
@RequiredArgsConstructor
public class VisitReadRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	/**
	 * Only root visits are searchable.
	 *
	 * <p>A child execution is a step inside a visit, not a visit of its own — the same
	 * distinction the admission backstop makes when it enforces one active root per
	 * lane. Listing children would show one truck as several.
	 */
	private static final String ROOT_ONLY = "parent_execution_id IS NULL";

	private final ScopeSeam seam;

	/**
	 * The search behind {@code GET /visits}.
	 *
	 * @param laneId the internal lane key, already resolved by the caller, or null for
	 *               every lane
	 * @param since  the start of the window. Never null — the service supplies the
	 *               default, so that "no window at all" cannot be expressed here
	 */
	public List<VisitRow> search(Long laneId, String status, String plate, Instant since, int limit) {
		StringBuilder filter = new StringBuilder(ROOT_ONLY + " AND started_at >= ?");
		List<Object> parameters = new ArrayList<>();
		parameters.add(java.sql.Timestamp.from(since));

		// Every caller-supplied value is a parameter. The column names are this
		// class's own constants, so nothing the caller sends becomes SQL text.
		if (laneId != null) {
			filter.append(" AND lane_id = ?");
			parameters.add(laneId);
		}
		if (status != null && !status.isBlank()) {
			filter.append(" AND status = ?");
			parameters.add(status);
		}
		if (plate != null && !plate.isBlank()) {
			filter.append(" AND plate = ?");
			parameters.add(plate);
		}

		return seam.select(ScopedSelect.from("execution")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where(filter.toString(), parameters.toArray())
						.orderByDescending("started_at")
						.limit(limit),
				VisitReadRepository::map);
	}

	/** One visit by its external identifier, or empty when this site has no such visit. */
	public Optional<VisitRow> byExternalId(String visitExternalId) {
		return seam.select(ScopedSelect.from("execution")
								.columns(COLUMNS)
								.scopedBy(SCOPE_COLUMN)
								.where(ROOT_ONLY + " AND external_id = ?", visitExternalId),
						VisitReadRepository::map)
				.stream().findFirst();
	}

	private static final String[] COLUMNS = { "external_id", "lane_id", "status", "plate",
			"started_at", "completed_at", "process_instance_id" };

	private static VisitRow map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new VisitRow(
				rs.getString("external_id"),
				rs.getLong("lane_id"),
				rs.getString("status"),
				rs.getString("plate"),
				instantOf(rs, "started_at"),
				instantOf(rs, "completed_at"),
				rs.getString("process_instance_id"));
	}

	/**
	 * Read as UTC explicitly.
	 *
	 * <p>{@code getTimestamp(column)} applies the JVM's default zone to a value the
	 * database wrote in UTC — invisible on a machine already running UTC, and wrong by
	 * the offset everywhere else. The shared primitives were corrected for exactly this
	 * and the same rule applies to every timestamp read in the product.
	 */
	private static Instant instantOf(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		java.sql.Timestamp value = rs.getTimestamp(column,
				java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
		return value == null ? null : value.toInstant();
	}

	/** One stored visit, before the lane's external identifier and the live step are added. */
	public record VisitRow(String externalId, long laneId, String status, String plate,
			Instant startedAt, Instant completedAt, String processInstanceId) {
	}
}
