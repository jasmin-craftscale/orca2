package com.lynxis.orca.platform.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

/**
 * The one implementation of {@link OutboxWriter}.
 *
 * <p>It uses the same {@link JdbcTemplate} — and therefore the same connection and
 * the same transaction — as the caller's own write. There is no second datasource,
 * no {@code REQUIRES_NEW}, and no asynchronous hand-off, because every one of
 * those would let the fact and its record commit separately.
 */
@RequiredArgsConstructor
public class JdbcOutboxWriter implements OutboxWriter {

	private static final String INSERT_FACT = """
			INSERT INTO outbox (ordering_key, event_type, payload)
			OUTPUT inserted.publish_seq
			VALUES (?, ?, ?)
			""";

	private static final String INSERT_DELIVERY = """
			INSERT INTO outbox_delivery (publish_seq, consumer, status)
			VALUES (?, ?, 'PENDING')
			""";

	private final JdbcTemplate jdbc;
	private final ConsumerRegistry consumers;

	@Override
	public long write(String orderingKey, String eventType, String payload) {
		require(orderingKey, "orderingKey");
		require(eventType, "eventType");
		if (payload == null) {
			throw new IllegalArgumentException("payload must not be null");
		}

		// Checked, not assumed. Without an active transaction this method would
		// still work — it would auto-commit — and the fact it describes could then
		// roll back around it. That is the precise defect the outbox removes, so it
		// is refused rather than tolerated.
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new OutboxWriteOutsideTransactionException(eventType);
		}

		Long publishSeq = jdbc.queryForObject(INSERT_FACT, Long.class, orderingKey, eventType, payload);
		if (publishSeq == null) {
			throw new IllegalStateException("Outbox insert returned no publish_seq");
		}

		// One delivery row per REGISTERED consumer, written now. Retention reads
		// these to decide what is deletable, so a consumer that is down still holds
		// its rows: absence of an acknowledgement is what protects the data.
		for (String consumer : consumers.names()) {
			jdbc.update(INSERT_DELIVERY, publishSeq, consumer);
		}
		return publishSeq;
	}

	private static void require(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
