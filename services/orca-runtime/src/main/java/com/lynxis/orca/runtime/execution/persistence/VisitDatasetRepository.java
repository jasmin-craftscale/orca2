package com.lynxis.orca.runtime.execution.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * The visit's dataset — one current value per key — through the scope seam.
 *
 * <p>The write is an upsert expressed as update-then-insert: a key that exists is
 * rewritten in place, a key that does not is created, and the unique index on
 * {@code (execution_id, data_key)} decides the race when two branches of one
 * visit write the same new key in the same instant. The loser of that race
 * retries as an update — last write wins, which is the same answer the two
 * writes would have produced in either serial order.
 */
@RequiredArgsConstructor
public class VisitDatasetRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	private final ScopeSeam seam;

	/** Writes a key's current value for a visit, creating or replacing it. */
	public void write(String siteExternalId, long executionId, String key, String value) {
		if (updateExisting(executionId, key, value) > 0) {
			return;
		}
		try {
			seam.insert(ScopedInsert.into("visit_dataset")
					.scopedBy(SCOPE_COLUMN)
					.value(SCOPE_COLUMN, siteExternalId)
					.value("execution_id", executionId)
					.value("data_key", key)
					.value("data_value", value));
		}
		catch (DuplicateKeyException lostTheRace) {
			// Another branch created the key between our update and our insert.
			// Its row is the one row this key may have; rewrite it.
			updateExisting(executionId, key, value);
		}
	}

	/** One key's current value, or empty when the visit never wrote it. */
	public Optional<String> value(long executionId, String key) {
		return seam.select(ScopedSelect.from("visit_dataset")
						.columns("data_value")
						.scopedBy(SCOPE_COLUMN)
						.where("execution_id = ? AND data_key = ?", executionId, key),
				(rs, row) -> rs.getString("data_value"))
				.stream().findFirst();
	}

	/** Every key the visit has written, with its current value. */
	public Map<String, String> all(long executionId) {
		record Entry(String key, String value) {
		}
		List<Entry> rows = seam.select(ScopedSelect.from("visit_dataset")
						.columns("data_key", "data_value")
						.scopedBy(SCOPE_COLUMN)
						.where("execution_id = ?", executionId),
				(rs, row) -> new Entry(rs.getString("data_key"), rs.getString("data_value")));
		return rows.stream().collect(Collectors.toMap(Entry::key, e -> e.value() == null ? "" : e.value()));
	}

	private int updateExisting(long executionId, String key, String value) {
		return seam.update(ScopedUpdate.table("visit_dataset")
				.scopedBy(SCOPE_COLUMN)
				.set("data_value", value)
				.set("written_at", new java.sql.Timestamp(System.currentTimeMillis()))
				.where("execution_id = ? AND data_key = ?", executionId, key));
	}

	/**
	 * The dataset vocabulary this workflow has been OBSERVED to produce — the keys
	 * its newest {@code visitLimit} visits wrote, deduplicated and sorted.
	 *
	 * <p>Bounded by construction, and that is a deliberate change from the system
	 * this was translated from, which answered with a DISTINCT over the whole
	 * history through a join. The seam offers neither, on purpose — and for an
	 * autocomplete the newest visits ARE the answer: a key nothing current writes
	 * is a stale suggestion, not vocabulary.
	 */
	public java.util.SortedSet<String> observedKeysOfNewestVisits(long workflowId, int visitLimit) {
		List<Long> newest = seam.select(ScopedSelect.from("execution")
						.columns("execution_id")
						.scopedBy(SCOPE_COLUMN)
						.where("workflow_id = ?", workflowId)
						.orderByDescending("execution_id")
						.limit(visitLimit),
				(rs, row) -> rs.getLong("execution_id"));
		java.util.SortedSet<String> keys = new java.util.TreeSet<>();
		for (Long executionId : newest) {
			seam.select(ScopedSelect.from("visit_dataset")
							.columns("data_key")
							.scopedBy(SCOPE_COLUMN)
							.where("execution_id = ?", executionId),
					(rs, row) -> rs.getString("data_key"))
					.forEach(keys::add);
		}
		return keys;
	}

	/** The visit's key for an engine instance, or empty when none correlates. */
	public Optional<Long> visitIdByEngineInstance(String processInstanceId) {
		return seam.select(ScopedSelect.from("execution")
						.columns("execution_id")
						.scopedBy(SCOPE_COLUMN)
						.where("process_instance_id = ?", processInstanceId),
				(rs, row) -> rs.getLong("execution_id"))
				.stream().findFirst();
	}

	/** The visit's key for its stable external identifier. */
	public Optional<Long> visitIdByExternalId(String externalId) {
		return seam.select(ScopedSelect.from("execution")
						.columns("execution_id")
						.scopedBy(SCOPE_COLUMN)
						.where("external_id = ?", externalId),
				(rs, row) -> rs.getLong("execution_id"))
				.stream().findFirst();
	}

	/** The engine instance a visit runs as, for the step-payload lookaside. */
	public Optional<String> engineInstanceByExternalId(String externalId) {
		return seam.select(ScopedSelect.from("execution")
						.columns("process_instance_id")
						.scopedBy(SCOPE_COLUMN)
						.where("external_id = ?", externalId),
				(rs, row) -> rs.getString("process_instance_id"))
				.stream().findFirst();
	}
}
