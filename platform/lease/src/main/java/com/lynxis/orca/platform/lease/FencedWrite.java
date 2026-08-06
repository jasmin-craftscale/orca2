package com.lynxis.orca.platform.lease;

import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/**
 * Runs a write only if the caller still holds the lease that protects it —
 * <strong>decided by the database, in the same transaction as the write</strong>.
 *
 * <p>This is the half of the primitive that actually protects anything.
 * {@code acquire} tells you that you won at some moment in the past;
 * {@code FencedWrite} is what stops the zombie. Instance A stalls, loses the
 * lease, wakes up and completes the write it began — and this refuses it, because
 * the token it presents is no longer the one in the row.
 *
 * <pre>{@code
 * fencedWrite.execute(lease, () -> captureRepository.append(capture));
 * }</pre>
 *
 * <p>The guard runs <em>inside</em> the write's transaction. Checking first and
 * writing after would leave a window, and the window is the entire problem.
 */
@RequiredArgsConstructor
public class FencedWrite {

	/**
	 * The guard. Touching {@code renewed_at} is what makes it an UPDATE rather
	 * than a SELECT: it takes a row lock, so a concurrent acquisition serialises
	 * behind it instead of racing it.
	 */
	private static final String GUARD = """
			UPDATE service_lease
			SET renewed_at = SYSUTCDATETIME()
			WHERE service = ? AND lease_name = ?
			  AND holder_id = ? AND fence_token = ?
			  AND expires_at > SYSUTCDATETIME()
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final String service;

	/**
	 * Executes {@code work} only if the lease is still held with this token.
	 *
	 * @throws StaleFenceTokenException if it is not — and the transaction is rolled
	 *                                  back, so a partially completed write is not
	 *                                  left behind
	 */
	public <T> T execute(Lease lease, Supplier<T> work) {
		return transactions.execute(status -> {
			int held = jdbc.update(GUARD, service, lease.leaseName(), lease.holderId(), lease.fenceToken());
			if (held == 0) {
				// RowsAffected = 0 means: expired, taken by someone else, or taken
				// back by us with a higher token. All three mean the same thing to
				// this write — you do not hold it.
				throw new StaleFenceTokenException(lease);
			}
			return work.get();
		});
	}

	public void execute(Lease lease, Runnable work) {
		execute(lease, () -> {
			work.run();
			return null;
		});
	}
}
