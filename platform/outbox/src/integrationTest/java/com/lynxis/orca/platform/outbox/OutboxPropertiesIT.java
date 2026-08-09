package com.lynxis.orca.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * Proves the transactional outbox's four guarantees by making each failure happen.
 *
 * <p>None of these is "the outbox writes a row". Every one of them is a way the
 * naive implementation breaks in production and passes in development.
 */
class OutboxPropertiesIT {

	private static final String SCHEMA = "it_outbox";
	private static final String CONSUMER_A = "portal";
	private static final String CONSUMER_B = "sync";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private TransactionTemplate transactions;
	private ConsumerRegistry registry;
	private OutboxWriter writer;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA, "db/platform/outbox");
		// A stand-in for a service's own business table. The outbox does not know
		// what it is, which is the point — platform/ holds no domain.
		new JdbcTemplate(dataSource).execute(
				"IF OBJECT_ID('" + SCHEMA + ".fact', 'U') IS NULL "
						+ "CREATE TABLE fact (id INT PRIMARY KEY, body NVARCHAR(200))");
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		registry = new ConsumerRegistry(List.of(CONSUMER_A, CONSUMER_B));
		writer = new JdbcOutboxWriter(jdbc, registry);
		jdbc.execute("DELETE FROM outbox_delivery");
		jdbc.execute("DELETE FROM outbox");
		jdbc.execute("DELETE FROM fact");
	}

	// ------------------------------------------------------------------------
	// Property 1 — kill the process between the two writes; NEITHER survives.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("killing the session between the fact write and the outbox write leaves neither")
	void killingTheProcessBetweenTheTwoWritesLeavesNeither() throws Exception {
		// A process that dies mid-transaction looks, to the database, exactly like a
		// session that disappears mid-transaction. So that is what is done: the fact
		// is written, the session is killed from outside, and the outbox write never
		// happens.
		try (Connection victim = PlatformDatabase.ownedBy(SCHEMA).getConnection()) {
			victim.setAutoCommit(false);

			int spid = spidOf(victim);

			try (PreparedStatement insertFact =
					victim.prepareStatement("INSERT INTO fact (id, body) VALUES (?, ?)")) {
				insertFact.setInt(1, 1);
				insertFact.setString(2, "visit completed");
				insertFact.executeUpdate();
			}

			// --- the process dies here, before the outbox row is written ---
			kill(spid);

			assertThatThrownBy(victim::commit)
					.as("the killed session cannot commit")
					.isInstanceOf(java.sql.SQLException.class);
		}
		catch (java.sql.SQLException expectedOnClose) {
			// Closing a killed connection may itself throw. Not the property.
		}

		assertThat(count("fact"))
				.as("the FACT must not exist: it was written, and its outbox row was not")
				.isZero();
		assertThat(count("outbox"))
				.as("and neither does the outbox row")
				.isZero();
	}

	@Test
	@DisplayName("a fact and its outbox row commit together, or neither does")
	void aRolledBackTransactionLeavesNeither() {
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			jdbc.update("INSERT INTO fact (id, body) VALUES (?, ?)", 2, "visit completed");
			writer.write("lane:3", "visit.completed", "{}");
			throw new IllegalStateException("something later in the transaction failed");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count("fact")).isZero();
		assertThat(count("outbox")).isZero();
		assertThat(count("outbox_delivery")).isZero();
	}

	@Test
	@DisplayName("writing a fact with no transaction open is refused, not silently auto-committed")
	void writingOutsideATransactionIsRefused() {
		assertThatThrownBy(() -> writer.write("lane:3", "visit.completed", "{}"))
				.isInstanceOf(OutboxWriteOutsideTransactionException.class)
				.hasMessageContaining("must commit together");
		assertThat(count("outbox")).isZero();
	}

	// ------------------------------------------------------------------------
	// Property 2 — deliver the same batch twice; ONE effect.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a fact's recorded time is correct on a machine that is not in UTC")
	void theRecordedTimeDoesNotDependOnTheMachinesTimeZone() {
		// Forced rather than inherited: this defect is INVISIBLE on a machine already
		// in UTC, which a build server usually is. `outbox.created_at` defaults to
		// SYSUTCDATETIME(), so it is written by the database — and read through the
		// zone-less path it comes back skewed by exactly this offset.
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")); // UTC+14

			Instant before = Instant.now();
			writeFacts("lane:tz", 1);
			Instant after = Instant.now();

			List<OutboxRecord> captured = new CopyOnWriteArrayList<>();
			OutboxConsumer capture = new OutboxConsumer() {
				@Override
				public String name() {
					return CONSUMER_A;
				}

				@Override
				public void accept(OutboxRecord record) {
					captured.add(record);
				}
			};
			relay(List.of(capture), "instance-tz").deliverPending(10);

			assertThat(captured).hasSize(1);
			assertThat(captured.getFirst().createdAt())
					.as("the time the database stamped on the fact, read on a JVM at UTC+14")
					.isBetween(before.minusSeconds(90), after.plusSeconds(90));
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	@DisplayName("re-running the relay over an acknowledged batch delivers nothing a second time")
	void redeliveryOfAnAcknowledgedBatchHasNoEffect() {
		writeFacts("lane:1", 3);

		RecordingConsumer portal = new RecordingConsumer(CONSUMER_A);
		OutboxRelay relay = relay(List.of(portal), "instance-1");

		while (relay.deliverPending(10) > 0) {
			// drain
		}
		assertThat(portal.seen).hasSize(3);

		int again = relay.deliverPending(10);

		assertThat(again).as("nothing left to deliver").isZero();
		assertThat(portal.seen).as("the consumer saw each fact exactly once").hasSize(3);
	}

	// ------------------------------------------------------------------------
	// Property 3 — two relays, one outbox: nothing twice, nothing skipped.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("two relay instances against one outbox deliver every fact exactly once")
	void twoRelaysNeitherDuplicateNorSkip() throws Exception {
		int facts = 40;
		// Several ordering keys, so the two relays have independent work to contend
		// for. With one key the head-of-line rule would serialise them and the test
		// would prove much less.
		for (int i = 0; i < facts; i++) {
			writeFacts("lane:" + (i % 8), 1);
		}

		List<Long> deliveredByOne = Collections.synchronizedList(new ArrayList<>());
		List<Long> deliveredByTwo = Collections.synchronizedList(new ArrayList<>());

		OutboxRelay one = relay(List.of(collectingConsumer(CONSUMER_A, deliveredByOne)), "instance-1");
		OutboxRelay two = relay(List.of(collectingConsumer(CONSUMER_A, deliveredByTwo)), "instance-2");

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		for (OutboxRelay relay : List.of(one, two)) {
			pool.submit(() -> {
				start.await();
				long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
				while (System.nanoTime() < deadline) {
					if (relay.deliverPending(5) == 0 && pendingFor(CONSUMER_A) == 0) {
						return null;
					}
				}
				return null;
			});
		}
		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		List<Long> all = new ArrayList<>(deliveredByOne);
		all.addAll(deliveredByTwo);

		assertThat(all).as("no fact was delivered twice").doesNotHaveDuplicates();
		assertThat(all).as("no fact was skipped").hasSize(facts);
		assertThat(pendingFor(CONSUMER_A)).isZero();
		assertThat(deliveredByOne).as("both instances actually did work").isNotEmpty();
		assertThat(deliveredByTwo).as("both instances actually did work").isNotEmpty();
	}

	@Test
	@DisplayName("facts sharing an ordering key are delivered in sequence, and one that is refused blocks only its own key")
	void factsAreOrderedPerKeyAndBlockOnlyTheirOwnKey() {
		long blocked = writeFacts("lane:blocked", 1).getFirst();
		writeFacts("lane:blocked", 2);
		writeFacts("lane:free", 3);

		List<Long> delivered = new ArrayList<>();
		OutboxRelay relay = relay(List.of(new OutboxConsumer() {
			@Override
			public String name() {
				return CONSUMER_A;
			}

			@Override
			public void accept(OutboxRecord record) {
				if (record.publishSeq() == blocked) {
					throw new IllegalStateException("this consumer cannot take it yet");
				}
				delivered.add(record.publishSeq());
			}
		}), "instance-1");

		for (int pass = 0; pass < 5; pass++) {
			relay.deliverPending(10);
		}

		assertThat(delivered).as("the free key flowed").hasSize(3);
		assertThat(pendingFor(CONSUMER_A))
				.as("the blocked fact and the two behind it on the SAME key are still pending")
				.isEqualTo(3);
	}

	// ------------------------------------------------------------------------
	// Property 4 — hold one consumer back; nothing it has not taken is deleted.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("retention deletes nothing a registered consumer has not acknowledged, however old it is")
	void retentionWaitsForEveryRegisteredConsumer() {
		writeFacts("lane:1", 5);
		// Age every fact well past any window, so the ONLY thing keeping them is the
		// missing acknowledgement.
		jdbc.update("UPDATE outbox SET created_at = DATEADD(day, -30, SYSUTCDATETIME())");

		OutboxRetention retention = new OutboxRetention(jdbc, transactions, "orca-test");

		// Consumer A takes everything. Consumer B — down, unreachable, whatever —
		// takes nothing.
		OutboxRelay relayA = relay(List.of(new RecordingConsumer(CONSUMER_A)), "instance-1");
		while (relayA.deliverPending(10) > 0) {
			// drain
		}

		assertThat(retention.purgeAcknowledgedOlderThan(Duration.ofDays(1)))
				.as("B has acknowledged nothing, so nothing is deletable")
				.isZero();
		assertThat(count("outbox")).isEqualTo(5);

		// B comes back and takes them.
		OutboxRelay relayB = relay(List.of(new RecordingConsumer(CONSUMER_B)), "instance-1");
		while (relayB.deliverPending(10) > 0) {
			// drain
		}

		assertThat(retention.purgeAcknowledgedOlderThan(Duration.ofDays(1)))
				.as("now every registered consumer has acknowledged")
				.isEqualTo(5);
		assertThat(count("outbox")).isZero();
		assertThat(count("outbox_delivery")).isZero();
	}

	@Test
	@DisplayName("retention does not delete acknowledged facts that are still inside their window")
	void retentionRespectsTheWindowAsWellAsTheAcknowledgements() {
		writeFacts("lane:1", 3);
		for (String consumer : Set.of(CONSUMER_A, CONSUMER_B)) {
			OutboxRelay relay = relay(List.of(new RecordingConsumer(consumer)), "instance-1");
			while (relay.deliverPending(10) > 0) {
				// drain
			}
		}

		OutboxRetention retention = new OutboxRetention(jdbc, transactions, "orca-test");

		assertThat(retention.purgeAcknowledgedOlderThan(Duration.ofDays(7)))
				.as("acknowledged, but written seconds ago")
				.isZero();
		assertThat(count("outbox")).isEqualTo(3);
	}

	// ------------------------------------------------------------------------

	private List<Long> writeFacts(String orderingKey, int count) {
		return transactions.execute(status -> {
			List<Long> sequences = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				sequences.add(writer.write(orderingKey, "test.event", "{\"n\":" + i + "}"));
			}
			return sequences;
		});
	}

	private OutboxRelay relay(List<OutboxConsumer> consumers, String holderId) {
		return new OutboxRelay(jdbc, transactions, registry, consumers, "orca-test", holderId,
				Duration.ofSeconds(5));
	}

	private OutboxConsumer collectingConsumer(String name, List<Long> into) {
		return new OutboxConsumer() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public void accept(OutboxRecord record) {
				into.add(record.publishSeq());
			}
		};
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		return count == null ? 0 : count;
	}

	private int pendingFor(String consumer) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM outbox_delivery WHERE consumer = ? AND status = 'PENDING'",
				Integer.class, consumer);
		return count == null ? 0 : count;
	}

	private static int spidOf(Connection connection) throws java.sql.SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT @@SPID")) {
			rows.next();
			return rows.getInt(1);
		}
	}

	private static void kill(int spid) throws java.sql.SQLException {
		try (Connection assassin = PlatformDatabase.administrative().getConnection();
				Statement statement = assassin.createStatement()) {
			statement.execute("KILL " + spid);
		}
	}

	private static final class RecordingConsumer implements OutboxConsumer {

		private final String name;
		private final List<Long> seen = new CopyOnWriteArrayList<>();

		private RecordingConsumer(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public void accept(OutboxRecord record) {
			seen.add(record.publishSeq());
		}
	}
}
