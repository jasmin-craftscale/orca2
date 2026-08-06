package com.lynxis.orca.platform.idempotency;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;

/**
 * The one implementation of {@link IdempotencyStore}.
 *
 * <p>The claim is an insert whose failure mode <em>is</em> the concurrency
 * control: the primary key on {@code (operation, idempotency_key)} means a second
 * concurrent attempt cannot insert, and the database says so rather than the
 * application checking first. There is no {@code SELECT} before the
 * {@code INSERT}, because that is the version that lets the barrier rise twice.
 */
@RequiredArgsConstructor
public class JdbcIdempotencyStore implements IdempotencyStore {

	private static final String CLAIM = """
			INSERT INTO idempotency_record (idempotency_key, operation, status, holder_id)
			VALUES (?, ?, 'IN_PROGRESS', ?)
			""";

	private static final String READ = """
			SELECT status, outcome, holder_id
			FROM idempotency_record
			WHERE operation = ? AND idempotency_key = ?
			""";

	private static final String RECORD_OUTCOME = """
			UPDATE idempotency_record
			SET status = ?, outcome = ?, completed_at = SYSUTCDATETIME()
			WHERE operation = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
			""";

	private static final String RELEASE = """
			DELETE FROM idempotency_record
			WHERE operation = ? AND idempotency_key = ? AND holder_id = ? AND status = 'IN_PROGRESS'
			""";

	private final JdbcTemplate jdbc;

	@Override
	public IdempotencyOutcome begin(String key, String operation, String holderId) {
		require(key, "key");
		require(operation, "operation");
		require(holderId, "holderId");

		try {
			jdbc.update(CLAIM, key, operation, holderId);
			return new IdempotencyOutcome.Fresh(key, operation);
		}
		catch (DuplicateKeyException alreadyKnown) {
			// The key exists. Read what is known about it — which is the whole
			// point: the answer, if there is one, rather than the fact of a clash.
			return read(key, operation)
					// A record that vanished between the failed insert and this read
					// means another instance released it. Racing that is legitimate;
					// the caller retries and one of them wins the insert.
					.orElseGet(() -> new IdempotencyOutcome.InProgress(key, operation, "unknown"));
		}
	}

	@Override
	public void complete(String key, String operation, String outcome) {
		record(key, operation, "COMPLETED", outcome);
	}

	@Override
	public void fail(String key, String operation, String outcome) {
		record(key, operation, "FAILED", outcome);
	}

	@Override
	public void release(String key, String operation, String holderId) {
		jdbc.update(RELEASE, operation, key, holderId);
	}

	private void record(String key, String operation, String status, String outcome) {
		if (outcome == null) {
			// The database's CHECK constraint refuses a terminal row with no
			// outcome; refusing it here too means the caller finds out at the call
			// site rather than as a constraint violation three frames up.
			throw new IllegalArgumentException(
					"A completed operation must record an outcome. A replay that finds the key, "
							+ "learns nothing and has to guess is the failure this store prevents.");
		}
		int updated = jdbc.update(RECORD_OUTCOME, status, outcome, operation, key);
		if (updated == 0) {
			throw new IllegalStateException(
					"No in-progress record for operation '" + operation + "' key '" + key
							+ "'. It was already completed, or was never begun.");
		}
	}

	private java.util.Optional<IdempotencyOutcome> read(String key, String operation) {
		List<IdempotencyOutcome> found = jdbc.query(READ, (rs, rowNum) -> {
			String status = rs.getString("status");
			if ("IN_PROGRESS".equals(status)) {
				return new IdempotencyOutcome.InProgress(key, operation, rs.getString("holder_id"));
			}
			return new IdempotencyOutcome.Completed(key, operation, rs.getString("outcome"),
					"COMPLETED".equals(status));
		}, operation, key);
		return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.getFirst());
	}

	private static void require(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
