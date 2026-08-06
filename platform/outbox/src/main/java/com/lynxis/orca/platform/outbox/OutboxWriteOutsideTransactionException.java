package com.lynxis.orca.platform.outbox;

/**
 * Thrown when {@link OutboxWriter#write} is called with no transaction open.
 *
 * <p>This is refused rather than tolerated. A write that opens its own transaction
 * would succeed, look correct, and reintroduce the exact defect the outbox exists
 * to remove: a fact and its record able to disagree. Failing loudly at the call
 * site is the only version of this anybody ever finds.
 */
public class OutboxWriteOutsideTransactionException extends IllegalStateException {

	public OutboxWriteOutsideTransactionException(String eventType) {
		super("Refusing to write outbox event '" + eventType + "' with no transaction open. "
				+ "The fact and its outbox row must commit together, so write() must be called "
				+ "from inside the transaction that writes the fact.");
	}
}
