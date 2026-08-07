package com.lynxis.orca.platform.outbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.web.system.SystemContext;
import com.lynxis.orca.platform.web.system.SystemIdentity;

import lombok.extern.slf4j.Slf4j;

/**
 * Claims recorded facts and offers them to each registered consumer until
 * acknowledged.
 *
 * <p>Three properties, and each one is a specific failure avoided:
 *
 * <ul>
 *   <li><strong>Two relays never contend for a row.</strong> The claim is a
 *       skip-locked read — SQL Server's {@code WITH (UPDLOCK, READPAST, ROWLOCK)}
 *       — so a second instance steps over rows the first already holds instead of
 *       blocking behind them.</li>
 *   <li><strong>Facts are ordered per key.</strong> A row is claimable only when it
 *       is the oldest unacknowledged row for its ordering key. So an unacknowledged
 *       fact <em>blocks its own key</em> and no other, which is what "ordered per
 *       key, never globally" means in practice.</li>
 *   <li><strong>The relay runs under an explicit identity.</strong> No user invoked
 *       it (§B6, §D3), so it enters a {@link SystemContext} rather than running
 *       anonymously.</li>
 * </ul>
 *
 * <p>This class does not schedule itself. Polling interval, batch size and lease
 * duration are profile configuration, and a primitive that picks them for six
 * services is a primitive that is wrong in at least one of them.
 */
@Slf4j
public class OutboxRelay {

	/**
	 * Claim the oldest unacknowledged row per ordering key, for one consumer,
	 * skipping anything another instance holds.
	 *
	 * <p>{@code READPAST} is what makes two relays cooperative rather than
	 * serialised; {@code UPDLOCK} is what makes the claim survive to the end of
	 * this transaction. Dropping either turns this into a lock convoy or a double
	 * delivery, and both look fine in a single-instance test.
	 */
	private static final String CLAIM = """
			SELECT TOP (?) d.publish_seq
			FROM outbox_delivery d WITH (UPDLOCK, READPAST, ROWLOCK)
			JOIN outbox o ON o.publish_seq = d.publish_seq
			WHERE d.consumer = ?
			  AND d.status = 'PENDING'
			  AND (d.claimed_until IS NULL OR d.claimed_until < SYSUTCDATETIME())
			  AND d.publish_seq = (
			        SELECT MIN(head.publish_seq)
			        FROM outbox_delivery head
			        JOIN outbox ho ON ho.publish_seq = head.publish_seq
			        WHERE head.consumer = d.consumer
			          AND head.status = 'PENDING'
			          AND ho.ordering_key = o.ordering_key)
			ORDER BY d.publish_seq
			""";

	private static final String MARK_CLAIMED = """
			UPDATE outbox_delivery
			SET claimed_by = ?,
			    claimed_until = DATEADD(millisecond, ?, SYSUTCDATETIME()),
			    attempts = attempts + 1
			WHERE consumer = ? AND publish_seq = ?
			""";

	private static final String LOAD = """
			SELECT publish_seq, ordering_key, event_type, payload, created_at
			FROM outbox
			WHERE publish_seq = ?
			""";

	private static final String ACK = """
			UPDATE outbox_delivery
			SET status = 'ACKED', acked_at = SYSUTCDATETIME(), claimed_by = NULL, claimed_until = NULL
			WHERE consumer = ? AND publish_seq = ? AND status = 'PENDING'
			""";

	private static final String RELEASE = """
			UPDATE outbox_delivery
			SET claimed_by = NULL, claimed_until = NULL, last_error = ?
			WHERE consumer = ? AND publish_seq = ?
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ConsumerRegistry registry;
	private final List<OutboxConsumer> consumers;
	private final String holderId;
	private final String serviceName;
	private final Duration claimDuration;

	public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transactions, ConsumerRegistry registry,
			List<OutboxConsumer> consumers, String serviceName, String holderId, Duration claimDuration) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.registry = registry;
		this.consumers = List.copyOf(consumers);
		this.serviceName = serviceName;
		this.holderId = holderId;
		this.claimDuration = claimDuration;
		for (OutboxConsumer consumer : consumers) {
			if (!registry.isRegistered(consumer.name())) {
				// A consumer that takes delivery but is not registered would receive
				// facts while its acknowledgements protected nothing from retention.
				throw new IllegalStateException("Consumer '" + consumer.name()
						+ "' takes delivery but is not registered. Retention would not wait for it.");
			}
		}
	}

	/**
	 * One pass: claim up to {@code batchSize} facts per consumer and offer them.
	 *
	 * @return how many were acknowledged in this pass
	 */
	public int deliverPending(int batchSize) {
		return SystemContext.callAs(new SystemIdentity(serviceName, "outbox-relay"),
				() -> deliverPendingAsSystem(batchSize));
	}

	/**
	 * The same pass, for a caller that has <em>already</em> established a system
	 * identity.
	 *
	 * <p><strong>This exists because {@link #deliverPending} and
	 * {@code SystemContextRule} contradicted each other, and running it is what found
	 * it.</strong> The build check requires every {@code @Scheduled} method to enter a
	 * system context; {@code deliverPending} enters one itself; and
	 * {@link SystemContext} refuses to nest — deliberately, because a system entry
	 * point reached from inside another one is a call path nobody expected. So the
	 * first scheduled method that called the relay threw on every tick.
	 *
	 * <p>The guard is not weakened and the nesting rule is not relaxed. The identity
	 * is <em>asserted</em> here instead of established: a caller with none is refused
	 * by {@link SystemContext#require()}, so the relay still cannot run anonymously —
	 * which is the guarantee §B6 and §D3 actually ask for. What changes is who names
	 * the identity, and the scheduling service naming its own is the better answer:
	 * an operator reading the audit trail sees {@code orca-runtime}, not a primitive.
	 */
	public int deliverPendingUnderCurrentIdentity(int batchSize) {
		SystemContext.require();
		return deliverPendingAsSystem(batchSize);
	}

	private int deliverPendingAsSystem(int batchSize) {
		int acknowledged = 0;
		for (OutboxConsumer consumer : consumers) {
			// A refused fact blocks ITS OWN KEY and nothing else. That is what
			// "ordered per key, never globally" means when something goes wrong:
			// one unreachable destination must not stop every other lane's facts.
			Set<String> blockedKeys = new HashSet<>();
			for (OutboxRecord record : claim(consumer.name(), batchSize)) {
				if (blockedKeys.contains(record.orderingKey())) {
					continue;
				}
				if (offer(consumer, record)) {
					acknowledged++;
				}
				else {
					blockedKeys.add(record.orderingKey());
				}
			}
		}
		return acknowledged;
	}

	/** Claims in its own transaction, so the claim is durable before delivery is attempted. */
	private List<OutboxRecord> claim(String consumer, int batchSize) {
		return transactions.execute(status -> {
			List<Long> sequences = jdbc.queryForList(CLAIM, Long.class, batchSize, consumer);
			List<OutboxRecord> records = new ArrayList<>(sequences.size());
			for (Long publishSeq : sequences) {
				jdbc.update(MARK_CLAIMED, holderId, claimDuration.toMillis(), consumer, publishSeq);
				records.add(load(publishSeq));
			}
			return records;
		});
	}

	private OutboxRecord load(long publishSeq) {
		return jdbc.queryForObject(LOAD, (rs, rowNum) -> {
			Timestamp createdAt = rs.getTimestamp("created_at");
			return new OutboxRecord(
					rs.getLong("publish_seq"),
					rs.getString("ordering_key"),
					rs.getString("event_type"),
					rs.getString("payload"),
					createdAt == null ? null : createdAt.toInstant());
		}, publishSeq);
	}

	/**
	 * Offers one fact and records the outcome.
	 *
	 * <p>The acknowledgement is written in its own transaction <em>after</em> the
	 * consumer returns. Acknowledging first would make the relay's own crash
	 * indistinguishable from a successful delivery, which is exactly the
	 * at-most-once behaviour this must not have.
	 */
	private boolean offer(OutboxConsumer consumer, OutboxRecord record) {
		try {
			consumer.accept(record);
		}
		catch (RuntimeException e) {
			log.warn("consumer {} refused publish_seq {} ({}): {}",
					consumer.name(), record.publishSeq(), record.eventType(), e.toString());
			transactions.executeWithoutResult(status ->
					jdbc.update(RELEASE, truncate(e.toString()), consumer.name(), record.publishSeq()));
			return false;
		}
		transactions.executeWithoutResult(status ->
				jdbc.update(ACK, consumer.name(), record.publishSeq()));
		return true;
	}

	private static String truncate(String error) {
		return error.length() <= 1000 ? error : error.substring(0, 1000);
	}

	/** The consumers this relay delivers to. Not the same as the registry, which may name more. */
	public ConsumerRegistry registry() {
		return registry;
	}
}
