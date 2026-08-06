package com.lynxis.orca.platform.outbox;

/**
 * Something that takes delivery of facts.
 *
 * <p><strong>Delivery is at-least-once.</strong> A consumer will see the same fact
 * more than once — after a relay dies mid-delivery, after a network timeout, after
 * a restart. Making that harmless is {@code platform/idempotency}'s job and not
 * this one's, and a consumer that assumes exactly-once is a consumer that is
 * wrong on a schedule nobody controls.
 *
 * <p>Throwing means "not taken": the row stays pending, the attempt is counted,
 * and it is offered again. Returning normally means acknowledged, and an
 * acknowledgement is not retractable.
 */
public interface OutboxConsumer {

	/**
	 * The registered name. Stable across releases — it is a key in
	 * {@code outbox_delivery}, so renaming it orphans every unacknowledged row.
	 */
	String name();

	/**
	 * Takes one fact. Throwing leaves it pending for another attempt.
	 *
	 * <p>Facts sharing an ordering key arrive in sequence: the next one is not
	 * offered until this one is acknowledged.
	 */
	void accept(OutboxRecord record);
}
