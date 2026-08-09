package com.lynxis.orca.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * Proves the three guarantees of the recorded-key store.
 *
 * <p>The second one is the one the gate depends on. A device host that
 * <em>did</em> raise the barrier but answered slowly must not have its step
 * failed, so an in-flight replay reports in progress and that is explicitly not a
 * terminal answer.
 */
class IdempotencyPropertiesIT {

	private static final String SCHEMA = "it_idempotency";
	private static final String OPERATION = "raise-gate";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private IdempotencyStore store;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA, "db/platform/idempotency");
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		store = new JdbcIdempotencyStore(jdbc);
		jdbc.execute("DELETE FROM idempotency_record");
	}

	// ------------------------------------------------------------------------
	// Property 1 — replay a completed operation; get the RECORDED OUTCOME.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("replaying a completed operation returns the recorded outcome, not a bare duplicate")
	void replayReturnsTheRecordedOutcome() {
		String key = "node-exec-4711";

		assertThat(store.begin(key, OPERATION, "instance-a")).isInstanceOf(IdempotencyOutcome.Fresh.class);
		store.complete(key, OPERATION, "{\"status\":\"EXECUTED\",\"ackedAt\":\"2026-08-07T10:00:00Z\"}");

		IdempotencyOutcome replay = store.begin(key, OPERATION, "instance-a");

		assertThat(replay).isInstanceOfSatisfying(IdempotencyOutcome.Completed.class, completed -> {
			// The caller retried BECAUSE it never saw the first answer. This is the
			// answer. "409 Duplicate" is the one response that cannot help it.
			assertThat(completed.outcome()).contains("EXECUTED");
			assertThat(completed.successful()).isTrue();
		});
	}

	@Test
	@DisplayName("a recorded FAILURE is also an outcome — the replay does not re-run the operation")
	void aRecordedFailureIsAlsoAnOutcome() {
		String key = "node-exec-4712";
		store.begin(key, OPERATION, "instance-a");
		store.fail(key, OPERATION, "{\"status\":\"FAILED\",\"reason\":\"device host rejected\"}");

		assertThat(store.begin(key, OPERATION, "instance-a"))
				.isInstanceOfSatisfying(IdempotencyOutcome.Completed.class, completed -> {
					assertThat(completed.successful()).isFalse();
					assertThat(completed.outcome()).contains("FAILED");
				});
	}

	@Test
	@DisplayName("the same key under a DIFFERENT operation is a different record")
	void keysAreScopedToTheirOperation() {
		String key = "shared-key";
		store.begin(key, "raise-gate", "instance-a");
		store.complete(key, "raise-gate", "raised");

		assertThat(store.begin(key, "print-ticket", "instance-a"))
				.as("two callers legitimately reusing a key must not receive each other's answers")
				.isInstanceOf(IdempotencyOutcome.Fresh.class);
	}

	// ------------------------------------------------------------------------
	// Property 2 — replay an IN-FLIGHT operation; in progress, NOT terminal.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("replaying an in-flight operation reports in progress, and in progress is not an answer")
	void inFlightReplayIsNotTerminal() {
		String key = "node-exec-4713";
		assertThat(store.begin(key, OPERATION, "instance-a")).isInstanceOf(IdempotencyOutcome.Fresh.class);

		IdempotencyOutcome replay = store.begin(key, OPERATION, "instance-b");

		assertThat(replay).isInstanceOfSatisfying(IdempotencyOutcome.InProgress.class,
				inProgress -> assertThat(inProgress.holderId()).isEqualTo("instance-a"));

		// The distinction that matters, expressed as the compiler sees it: an
		// in-flight replay is NOT Completed, so a caller that switches on the result
		// cannot accidentally read an outcome that does not exist yet.
		assertThat(replay).isNotInstanceOf(IdempotencyOutcome.Completed.class);

		// ...and when the first attempt finishes, the SAME replay now gets the answer.
		store.complete(key, OPERATION, "{\"status\":\"EXECUTED\"}");
		assertThat(store.begin(key, OPERATION, "instance-b"))
				.isInstanceOf(IdempotencyOutcome.Completed.class);
	}

	@Test
	@DisplayName("a terminal record can never exist without an outcome — the database refuses it")
	void aTerminalRecordAlwaysCarriesItsOutcome() {
		String key = "node-exec-4714";
		store.begin(key, OPERATION, "instance-a");

		// The application refuses first...
		assertThat(catchIllegalArgument(() -> store.complete(key, OPERATION, null)))
				.isTrue();

		// ...and the CHECK constraint refuses even if something bypassed it, which is
		// what makes "a replay always finds an answer" a property of the data rather
		// than of this class.
		assertThat(catchAnything(() -> jdbc.update(
				"UPDATE idempotency_record SET status = 'COMPLETED', outcome = NULL, "
						+ "completed_at = SYSUTCDATETIME() WHERE operation = ? AND idempotency_key = ?",
				OPERATION, key))).isTrue();
	}

	// ------------------------------------------------------------------------
	// Property 3 — concurrent first attempts; EXACTLY ONE executes.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("thirty-two concurrent first attempts with the same key: exactly one executes")
	void exactlyOneConcurrentFirstAttemptExecutes() throws Exception {
		String key = "node-exec-race";
		int attempts = 32;

		AtomicInteger executions = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		CountDownLatch startTogether = new CountDownLatch(1);

		List<Callable<IdempotencyOutcome>> callers = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			String holder = "instance-" + i;
			callers.add(() -> {
				startTogether.await();
				IdempotencyOutcome outcome = store.begin(key, OPERATION, holder);
				if (outcome instanceof IdempotencyOutcome.Fresh) {
					// This is "raise the barrier". It must happen once.
					executions.incrementAndGet();
					store.complete(key, OPERATION, "{\"status\":\"EXECUTED\",\"by\":\"" + holder + "\"}");
				}
				return outcome;
			});
		}

		List<Future<IdempotencyOutcome>> futures = new ArrayList<>();
		for (Callable<IdempotencyOutcome> caller : callers) {
			futures.add(pool.submit(caller));
		}
		startTogether.countDown();

		int fresh = 0;
		for (Future<IdempotencyOutcome> future : futures) {
			if (future.get(60, TimeUnit.SECONDS) instanceof IdempotencyOutcome.Fresh) {
				fresh++;
			}
		}
		pool.shutdownNow();

		assertThat(fresh).as("only one caller was told to execute").isEqualTo(1);
		assertThat(executions.get()).as("the barrier rose once").isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("a released claim can be attempted again — but only by the holder that made it")
	void releaseAllowsAnotherAttempt() {
		String key = "node-exec-4715";
		store.begin(key, OPERATION, "instance-a");

		store.release(key, OPERATION, "instance-b");
		assertThat(store.begin(key, OPERATION, "instance-c"))
				.as("a different instance cannot release somebody else's claim")
				.isInstanceOf(IdempotencyOutcome.InProgress.class);

		store.release(key, OPERATION, "instance-a");
		assertThat(store.begin(key, OPERATION, "instance-c"))
				.isInstanceOf(IdempotencyOutcome.Fresh.class);
	}

	private static boolean catchIllegalArgument(Runnable work) {
		try {
			work.run();
			return false;
		}
		catch (IllegalArgumentException e) {
			return true;
		}
	}

	private static boolean catchAnything(Runnable work) {
		try {
			work.run();
			return false;
		}
		catch (RuntimeException e) {
			return true;
		}
	}
}
