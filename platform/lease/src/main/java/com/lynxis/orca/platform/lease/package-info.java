/**
 * Lease with a fence token — anything only one instance may do at a time.
 *
 * <p>Acquisition is a conditional update guarded by rows-affected, never a
 * read-then-write. The fence token increases every time the lease changes hands,
 * and a write presenting a stale token is rejected. Expiry is judged by the
 * database's clock, never an instance's.
 *
 * <p>You cannot guarantee a stalled process is dead. You can guarantee its writes
 * are refused — which is why the token matters more than the lease.
 */
package com.lynxis.orca.platform.lease;
