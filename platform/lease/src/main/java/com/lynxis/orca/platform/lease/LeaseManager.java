package com.lynxis.orca.platform.lease;

import java.time.Duration;
import java.util.Optional;

/**
 * Anything only one instance may do at a time.
 *
 * <p>The obvious implementations — an {@code is_owner} flag, a last-heartbeat
 * column, first-poller-wins — all fail the same way. Instance A owns a lane; its
 * network stalls for twenty seconds; the system declares it dead and hands the
 * lane to B; A wakes up, never having known it died, and finishes the write it
 * started. Two instances have now written as owner and both writes looked
 * legitimate.
 *
 * <p><strong>You cannot guarantee a stalled process is dead. You can guarantee its
 * writes are refused.</strong> That is what the fence token is for, and it is why
 * {@link FencedWrite} matters more than {@code acquire}.
 */
public interface LeaseManager {

	/**
	 * Takes the lease if it is free or expired.
	 *
	 * <p>A single conditional update guarded by rows-affected — never a read
	 * followed by a write. {@code RowsAffected = 0} means <em>you do not hold
	 * it</em>, and there is no window between the check and the act in which
	 * somebody else could.
	 *
	 * @return the lease with its token, or empty if another instance holds it
	 */
	Optional<Lease> acquire(String leaseName, String holderId, Duration duration);

	/**
	 * Extends a lease this holder still holds.
	 *
	 * <p>Guarded by the fence token as well as the holder id: an instance that lost
	 * and regained the lease has a new token, and renewing with the old one must
	 * not succeed.
	 *
	 * @return the renewed lease, or empty if it was lost in the meantime
	 */
	Optional<Lease> renew(Lease lease, Duration duration);

	/**
	 * Gives the lease up early.
	 *
	 * <p>Expiry is set to now rather than the row being deleted. The row carries
	 * the fence token, and deleting it would let the next acquisition start again
	 * from a lower number — which is the one thing the token must never do.
	 */
	void release(Lease lease);

	/** The current state, for diagnostics. Never a guard: see {@link Lease#looksExpired}. */
	Optional<Lease> find(String leaseName);
}
