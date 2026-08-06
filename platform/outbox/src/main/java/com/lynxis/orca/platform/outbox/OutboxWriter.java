package com.lynxis.orca.platform.outbox;

/**
 * Records a fact, <strong>inside the same transaction as the fact itself</strong>.
 *
 * <p>That sentence is the whole primitive. The obvious alternative — save the
 * thing, then tell somebody — fails in two directions and neither is detectable:
 * the save commits and the call fails, so it happened and nobody was told; or the
 * call succeeds and the transaction rolls back, so everybody was told about
 * something that did not happen. Under a network blip that is perhaps one in ten
 * thousand, which means it is never seen in testing and constantly seen in
 * production.
 *
 * <pre>{@code
 * @Transactional
 * public void completeVisit(Visit visit) {
 *     visitRepository.save(visit);                                  // the fact
 *     outboxWriter.write(visit.laneId(), "visit.completed", body);  // and its record
 * }                                                                 // one commit, or neither
 * }</pre>
 *
 * <p>There is no {@code flush}, no {@code send} and no callback, deliberately.
 * Anything a caller could do <em>after</em> the commit is the failure this exists
 * to remove.
 */
public interface OutboxWriter {

	/**
	 * Writes the fact and one delivery row per registered consumer.
	 *
	 * <p>Must be called with a transaction already open — the caller's. Calling it
	 * without one is refused rather than silently committing on its own, because a
	 * fact recorded in its own transaction is exactly the decoupling this
	 * primitive exists to prevent.
	 *
	 * @param orderingKey what this fact is ordered against — a lane, an entity, a
	 *                    site pair. Facts sharing a key are delivered in sequence
	 * @param eventType   the owner's name for what happened
	 * @param payload     the serialised body
	 * @return the assigned {@code publish_seq}
	 * @throws OutboxWriteOutsideTransactionException if no transaction is active
	 */
	long write(String orderingKey, String eventType, String payload);
}
