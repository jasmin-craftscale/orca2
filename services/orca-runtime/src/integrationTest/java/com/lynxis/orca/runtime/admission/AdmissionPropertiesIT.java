package com.lynxis.orca.runtime.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.runtime.RuntimeApplication;

/**
 * <strong>WP0 · the admission proof. This is the phase's halt condition.</strong>
 *
 * <p>§B10: <em>"Two events for one truck in the same millisecond, from two
 * instances, 1,000 times — one visit each time."</em> Everything else in Phase 1
 * is built on the assumption that an embedded engine can be made to satisfy that
 * against SQL Server. If it cannot, the phase stops here.
 *
 * <p><strong>Real Flowable 8, real SQL Server, real Spring transaction manager.</strong>
 * Not a mock and not H2: the property lives entirely in what {@code UPDLOCK} does
 * under concurrency, in what a filtered unique index refuses, and in whether the
 * engine's own writes join the caller's transaction. None of those three survives
 * being simulated.
 *
 * <p>The context is {@link RuntimeApplication} — the application that ships —
 * with only its datasource swapped for a schema on the shared container. Running
 * it non-web keeps the servlet security chain out of a test about locking; the
 * engine, the transaction manager and the datasource are the shipping ones.
 */
@SpringBootTest(
		classes = RuntimeApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				// The spike schema is migrated by PlatformDatabase before the context
				// starts. The service's own Flyway would try to apply orca-runtime's
				// V100 baseline, which names the `runtime` schema explicitly.
				"spring.flyway.enabled=false",
				"spring.jpa.properties.hibernate.default_schema=" + AdmissionPropertiesIT.SCHEMA,
				// The engine creates its own ~46 tables here. WP3 is what moves that
				// under Flyway; until then this is Flowable's own default, unchanged.
				"flowable.database-schema-update=true",
				// No async executor: this test is about one transaction's atomicity,
				// and a background job thread committing on its own schedule would be
				// noise in exactly the measurement being taken.
				"flowable.async-executor-activate=false",
				// Sixteen admissions and eight completions can be in flight at once.
				// The committed value is 10, which would turn the property under test
				// into a measurement of pool contention.
				"spring.datasource.hikari.maximum-pool-size=32",
		})
class AdmissionPropertiesIT {

	static final String SCHEMA = "it_admission";

	private static final Logger log = LoggerFactory.getLogger(AdmissionPropertiesIT.class);

	/** A gate with eight lanes. Enough that lanes contend with each other, few enough to be a real site. */
	private static final int LANES = 8;

	/** §B10 says one thousand times. It says it because a race that fails one time in two hundred passes a test run once. */
	private static final int ITERATIONS = 1_000;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private RuntimeService runtimeService;

	private JdbcTemplate jdbc;
	private AdmissionOperation admission;

	/**
	 * The datasource, and the only thing about the shipping application this test
	 * replaces.
	 *
	 * <p>It points the application's own properties at a schema on the shared
	 * container rather than substituting a {@code DataSource} bean, so the pool,
	 * the transaction manager and the engine's connection handling are the ones
	 * that ship. Running before the context refresh is what lets the schema exist —
	 * and the spike DDL be applied — by the time Flowable looks at it.
	 */
	@org.springframework.test.context.DynamicPropertySource
	static void pointTheApplicationAtItsSpikeSchema(
			org.springframework.test.context.DynamicPropertyRegistry registry) {
		// Creates the schema and its own login, drops anything a previous run left
		// (including the engine's tables), and applies the spike DDL. The login is
		// the schema's own, exactly as a service's is: nothing here runs as sa.
		var migrated = (org.springframework.jdbc.datasource.DriverManagerDataSource)
				PlatformDatabase.migratedSchema(SCHEMA, "db/spike/admission");

		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		admission = new AdmissionOperation(jdbc, new TransactionTemplate(transactionManager), runtimeService);
		jdbc.execute("DELETE FROM execution");
		jdbc.execute("DELETE FROM lane_session");
	}

	// ------------------------------------------------------------------------
	// The property. Two events, one truck, one visit — one thousand times.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 45, unit = TimeUnit.MINUTES)
	@DisplayName("two simultaneous events for one truck start exactly one visit — 1,000 times out of 1,000")
	void twoSimultaneousEventsStartExactlyOneVisit() throws Exception {
		for (long lane = 1; lane <= LANES; lane++) {
			admission.ensureLaneSession(lane, "LANE-%02d".formatted(lane));
		}

		// Relative, not absolute. Earlier methods in this class leave running
		// instances behind — @BeforeEach clears our tables, not the engine's — and an
		// absolute count would make this assertion depend on the order JUnit happened
		// to pick.
		long engineInstancesBefore = runtimeService.createProcessInstanceQuery().count();

		int perLane = ITERATIONS / LANES;
		List<Long> startedLatenciesNanos = new CopyOnWriteArrayList<>();
		AtomicInteger doubleStarts = new AtomicInteger();
		AtomicInteger backstopSaves = new AtomicInteger();
		List<String> failures = new CopyOnWriteArrayList<>();

		ExecutorService lanes = Executors.newFixedThreadPool(LANES);
		ExecutorService admitters = Executors.newCachedThreadPool();
		long wallStart = System.nanoTime();
		try {
			List<Future<Integer>> perLaneVisits = new ArrayList<>();
			for (long lane = 1; lane <= LANES; lane++) {
				long laneId = lane;
				perLaneVisits.add(lanes.submit(() -> runLane(
						laneId, perLane, admitters, startedLatenciesNanos, doubleStarts, backstopSaves, failures)));
			}
			int visitsCreated = 0;
			for (Future<Integer> laneResult : perLaneVisits) {
				visitsCreated += laneResult.get();
			}
			assertThat(visitsCreated)
					.as("every iteration must create exactly one visit")
					.isEqualTo(ITERATIONS);
		}
		finally {
			lanes.shutdownNow();
			admitters.shutdownNow();
		}
		long wallNanos = System.nanoTime() - wallStart;

		// --- what the property actually claims ---------------------------------
		assertThat(failures)
				.as("an admission that threw is a truck's event dropped — including a deadlock "
						+ "victim that exhausted its retries")
				.isEmpty();

		assertThat(doubleStarts.get())
				.as("TWO visits for one truck. This is the failure the whole design exists to make "
						+ "impossible, and one occurrence is a red WP0")
				.isZero();

		assertThat(count("SELECT COUNT(*) FROM execution"))
				.as("2,000 admissions, 1,000 trucks, 1,000 visits")
				.isEqualTo(ITERATIONS);

		assertThat(count("SELECT COUNT(*) FROM execution WHERE process_instance_id IS NULL"))
				.as("a visit row with no process instance means the insert committed and the engine "
						+ "start did not — the two are one transaction, so there must be none")
				.isZero();

		assertThat(count("SELECT COUNT(DISTINCT process_instance_id) FROM execution"))
				.as("two visits sharing a process instance would mean the engine start was reused")
				.isEqualTo(ITERATIONS);

		assertThat(runtimeService.createProcessInstanceQuery().count() - engineInstancesBefore)
				.as("the engine's own count of running instances, read from the engine rather than "
						+ "from our table")
				.isEqualTo(ITERATIONS);

		writeLatencyReport(startedLatenciesNanos, wallNanos, backstopSaves.get());
	}

	/**
	 * One lane's share of the run: {@code iterations} trucks, each announced by two
	 * simultaneous events, each visit completed before the next truck arrives.
	 *
	 * @return how many visits this lane created — one per iteration if the property holds
	 */
	private int runLane(long laneId, int iterations, ExecutorService admitters,
			List<Long> startedLatenciesNanos, AtomicInteger doubleStarts, AtomicInteger backstopSaves,
			List<String> failures) {
		int visits = 0;
		for (int truck = 1; truck <= iterations; truck++) {
			String plate = "T-%03d-%04d".formatted(laneId, truck);

			// The two events are released by the same barrier, so "the same instant"
			// is a property of the test rather than of how the scheduler felt.
			CyclicBarrier sameInstant = new CyclicBarrier(2);
			Callable<Admission> event = () -> {
				sameInstant.await(2, TimeUnit.MINUTES);
				long began = System.nanoTime();
				Admission result = admission.admit(laneId, plate);
				if (result instanceof Admission.Started) {
					startedLatenciesNanos.add(System.nanoTime() - began);
				}
				return result;
			};

			Future<Admission> first = admitters.submit(event);
			Future<Admission> second = admitters.submit(event);

			Admission a;
			Admission b;
			try {
				a = first.get(5, TimeUnit.MINUTES);
				b = second.get(5, TimeUnit.MINUTES);
			}
			catch (Exception dropped) {
				failures.add("lane %d truck %d: %s".formatted(laneId, truck, rootCause(dropped)));
				first.cancel(true);
				second.cancel(true);
				continue;
			}

			long started = Arrays.stream(new Admission[] { a, b })
					.filter(Admission.Started.class::isInstance).count();
			if (started == 2) {
				doubleStarts.incrementAndGet();
				failures.add("lane %d truck %d: BOTH events started a visit (%d and %d)"
						.formatted(laneId, truck, a.executionId(), b.executionId()));
			}
			else if (started == 0) {
				failures.add("lane %d truck %d: neither event started a visit".formatted(laneId, truck));
			}
			else {
				visits++;
				if (a.executionId() != b.executionId()) {
					failures.add("lane %d truck %d: the two events landed on different visits (%d, %d)"
							.formatted(laneId, truck, a.executionId(), b.executionId()));
				}
				if (a instanceof Admission.Correlated c && c.viaBackstop()
						|| b instanceof Admission.Correlated c2 && c2.viaBackstop()) {
					backstopSaves.incrementAndGet();
				}
			}

			// The truck leaves. Until it does, the filtered index will not let the
			// next one in — which is the behaviour, not a limitation.
			admission.completeVisit(a.executionId());
		}
		return visits;
	}

	// ------------------------------------------------------------------------
	// Atomicity. Die between the insert and the engine start; leave NEITHER.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("killing the database session between the insert and the engine start leaves neither")
	void killingTheSessionBetweenInsertAndEngineStartLeavesNeither() {
		admission.ensureLaneSession(41L, "LANE-41");
		long instancesBefore = runtimeService.createProcessInstanceQuery().count();

		// A process that dies mid-transaction looks, to the database, exactly like a
		// session that disappears mid-transaction. So that is what is done — the
		// visit row is inserted, and the session is killed from outside before the
		// engine is ever asked to start anything.
		AtomicInteger killedSpid = new AtomicInteger();
		AtomicInteger insertedRow = new AtomicInteger();
		admission.injectFault(executionId -> {
			// Prove the row was really written before it is made to vanish. Without
			// this the test passes just as happily if the insert never happened, and
			// "neither survives" becomes a statement about nothing.
			insertedRow.set((int) count(
					"SELECT COUNT(*) FROM execution WHERE execution_id = " + executionId));
			Integer spid = jdbc.queryForObject("SELECT @@SPID", Integer.class);
			killedSpid.set(spid == null ? 0 : spid);
			kill(spid);
		});

		assertThatThrownBy(() -> admission.admit(41L, "GHOST-1"))
				.as("the killed session cannot commit")
				.isInstanceOf(Exception.class);

		admission.injectFault(AdmissionFault.NONE);

		assertThat(insertedRow.get())
				.as("the visit row was visible inside the transaction — so what follows is a "
						+ "disappearance, not an insert that never ran")
				.isEqualTo(1);
		assertThat(killedSpid.get()).as("a session was actually killed").isPositive();

		assertThat(count("SELECT COUNT(*) FROM execution WHERE lane_id = 41"))
				.as("the visit row must not exist: it was written, and its process instance was not")
				.isZero();
		assertThat(runtimeService.createProcessInstanceQuery().count())
				.as("and no process instance was left running with no visit row to name it")
				.isEqualTo(instancesBefore);
	}

	@Test
	@DisplayName("a failure between the insert and the engine start rolls back both")
	void aFailureBetweenInsertAndEngineStartRollsBackBoth() {
		admission.ensureLaneSession(42L, "LANE-42");
		long instancesBefore = runtimeService.createProcessInstanceQuery().count();

		admission.injectFault(executionId -> {
			throw new IllegalStateException("the instance died here");
		});

		assertThatThrownBy(() -> admission.admit(42L, "GHOST-2"))
				.isInstanceOf(IllegalStateException.class);

		admission.injectFault(AdmissionFault.NONE);

		assertThat(count("SELECT COUNT(*) FROM execution WHERE lane_id = 42")).isZero();
		assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(instancesBefore);
	}

	@Test
	@DisplayName("the engine's own state rolls back with the visit row — a rollback after the start leaves no instance")
	void aFailureAfterTheEngineStartRollsBackTheEngineToo() {
		admission.ensureLaneSession(43L, "LANE-43");
		long instancesBefore = runtimeService.createProcessInstanceQuery().count();

		TransactionTemplate transactions = new TransactionTemplate(transactionManager);
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			jdbc.update("INSERT INTO execution (external_id, lane_id, parent_execution_id, status, plate) "
					+ "VALUES (?, 43, NULL, 'ACTIVE', ?)", "vis-" + UUID.randomUUID(), "GHOST-3");
			runtimeService.startProcessInstanceByKey(AdmissionOperation.PROCESS_KEY, "vis-rollback-probe");
			throw new IllegalStateException("something later in the transaction failed");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count("SELECT COUNT(*) FROM execution WHERE lane_id = 43")).isZero();
		assertThat(runtimeService.createProcessInstanceQuery().count())
				.as("the engine is not a second resource that commits on its own — its tables are in "
						+ "this schema and this transaction")
				.isEqualTo(instancesBefore);
	}

	// ------------------------------------------------------------------------
	// The backstop. What the index refuses, and what it must NOT refuse.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("with the lane lock bypassed, the filtered index still refuses a second active root visit")
	void theIndexRefusesASecondActiveRootVisitEvenWithoutTheLaneLock() {
		insertRootVisit(51L, "FIRST");

		assertThatThrownBy(() -> insertRootVisit(51L, "SECOND"))
				.as("the lane lock is the mechanism; this is what keeps the property true when a "
						+ "future inbound path forgets it")
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.MINUTES)
	@DisplayName("with the lane lock removed, the backstop is WATCHED to fire — and one visit still results")
	void theBackstopIsWatchedToFireUnderTrueConcurrency() throws Exception {
		// The thousand-truck run reports ZERO backstop saves, which is the right
		// answer and a useless one: it means the lane lock serialised every round,
		// so the index was never reached and its behaviour was never observed. A
		// check that has never been seen to fire is the failure mode Phase 0's
		// verification item 8 caught twice. So the lock comes off and the index is
		// made to do the work alone.
		int rounds = 100;
		long laneId = 61L;
		admission.ensureLaneSession(laneId, "LANE-61");

		AtomicInteger backstopSaves = new AtomicInteger();
		int visits = 0;
		ExecutorService admitters = Executors.newCachedThreadPool();
		try {
			for (int truck = 1; truck <= rounds; truck++) {
				CyclicBarrier sameInstant = new CyclicBarrier(2);
				String plate = "NOLOCK-%04d".formatted(truck);
				Callable<Admission> event = () -> {
					sameInstant.await(2, TimeUnit.MINUTES);
					return admission.admitBypassingLaneLock(laneId, plate);
				};
				Future<Admission> first = admitters.submit(event);
				Future<Admission> second = admitters.submit(event);
				Admission a = first.get(5, TimeUnit.MINUTES);
				Admission b = second.get(5, TimeUnit.MINUTES);

				assertThat(List.of(a, b).stream().filter(Admission.Started.class::isInstance).count())
						.as("even with no lane lock, the index must leave exactly one visit "
								+ "(round %d)", truck)
						.isEqualTo(1);
				visits++;
				if (a instanceof Admission.Correlated c && c.viaBackstop()
						|| b instanceof Admission.Correlated c2 && c2.viaBackstop()) {
					backstopSaves.incrementAndGet();
				}
				admission.completeVisit(a.executionId());
			}
		}
		finally {
			admitters.shutdownNow();
		}

		assertThat(visits).isEqualTo(rounds);
		assertThat(backstopSaves.get())
				.as("the filtered unique index was never observed refusing a second active root "
						+ "visit. Either the two events did not overlap, or the index is not doing "
						+ "what the design says it does — and this test cannot tell you which")
				.isPositive();
		log.info("WP0 · backstop fired on {} of {} lock-free rounds", backstopSaves.get(), rounds);
	}

	@Test
	@DisplayName("the index does NOT refuse a map-iterator's children, which share their parent's lane")
	void theIndexAdmitsMapIteratorChildren() {
		long parent = insertRootVisit(52L, "PARENT");

		// §C2 names this trap by name: a naive unique index on the lane would reject
		// every iterator child and break those workflows at runtime rather than at
		// the point the index was written.
		jdbc.update("INSERT INTO execution (external_id, lane_id, parent_execution_id, status, plate) "
				+ "VALUES (?, 52, ?, 'ACTIVE', 'CHILD-1')", "vis-" + UUID.randomUUID(), parent);
		jdbc.update("INSERT INTO execution (external_id, lane_id, parent_execution_id, status, plate) "
				+ "VALUES (?, 52, ?, 'ACTIVE', 'CHILD-2')", "vis-" + UUID.randomUUID(), parent);

		assertThat(count("SELECT COUNT(*) FROM execution WHERE lane_id = 52")).isEqualTo(3);
	}

	@Test
	@DisplayName("the next truck gets a NEW visit once the previous one has finished")
	void aCompletedVisitFreesTheLane() {
		admission.ensureLaneSession(53L, "LANE-53");

		Admission first = admission.admit(53L, "TRUCK-A");
		assertThat(first).isInstanceOf(Admission.Started.class);

		assertThat(admission.admit(53L, "TRUCK-A"))
				.as("while the truck is at the lane, its second event joins the running visit")
				.isInstanceOfSatisfying(Admission.Correlated.class,
						correlated -> assertThat(correlated.executionId()).isEqualTo(first.executionId()));

		admission.completeVisit(first.executionId());

		Admission second = admission.admit(53L, "TRUCK-B");
		assertThat(second)
				.as("the lane is free; the next truck is a new visit, not a correlation to the last one")
				.isInstanceOf(Admission.Started.class);
		assertThat(second.executionId()).isNotEqualTo(first.executionId());
	}

	// ------------------------------------------------------------------------

	private long insertRootVisit(long laneId, String plate) {
		Long id = jdbc.queryForObject(
				"INSERT INTO execution (external_id, lane_id, parent_execution_id, status, plate) "
						+ "OUTPUT INSERTED.execution_id VALUES (?, ?, NULL, 'ACTIVE', ?)",
				Long.class, "vis-" + UUID.randomUUID(), laneId, plate);
		return id == null ? -1 : id;
	}

	private long count(String sql) {
		Long value = jdbc.queryForObject(sql, Long.class);
		return value == null ? 0 : value;
	}

	private static void kill(Integer spid) {
		try (Connection assassin = PlatformDatabase.administrative().getConnection();
				Statement statement = assassin.createStatement()) {
			statement.execute("KILL " + spid);
		}
		catch (SQLException e) {
			throw new IllegalStateException("could not kill session " + spid, e);
		}
	}

	private static String rootCause(Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	/**
	 * The measurement §11 of the register asks for: recorded, deliberately not
	 * thresholded. There is no baseline yet, so a threshold invented here would
	 * become the number everyone tunes to.
	 */
	private void writeLatencyReport(List<Long> startedLatenciesNanos, long wallNanos, int backstopSaves)
			throws IOException {
		long[] sorted = startedLatenciesNanos.stream().mapToLong(Long::longValue)
				.sorted().toArray();
		String report = """
				WP0 · admission latency — RECORDED, NOT THRESHOLDED
				===================================================
				Machine        : %s, %d cores, SQL Server 2022 via Testcontainers
				Shape          : %d lanes, %d iterations, 2 simultaneous events each
				Visits started : %d
				Wall clock     : %.1f s
				Deadlock retries (SQL Server error 1205/1222, retried inside the operation) : %d
				Backstop saves (filtered index refused a second root visit)                 : %d

				Admission latency, correlate-or-start including the engine start,
				measured on the event that STARTED the visit (the losing event does
				less work and would flatter the number):

				  min    %8.2f ms
				  p50    %8.2f ms
				  p95    %8.2f ms
				  p99    %8.2f ms
				  max    %8.2f ms
				  mean   %8.2f ms

				NOTE: this machine is Apple silicon and the SQL Server image is amd64
				only, so the engine runs emulated. These numbers are an upper bound,
				not a site measurement.
				"""
				.formatted(
						System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
						LANES, ITERATIONS, sorted.length, wallNanos / 1e9,
						admission.deadlockRetries(), backstopSaves,
						ms(percentile(sorted, 0)), ms(percentile(sorted, 50)), ms(percentile(sorted, 95)),
						ms(percentile(sorted, 99)), ms(percentile(sorted, 100)), ms(mean(sorted)));

		log.info("\n{}", report);
		Path target = Path.of("build", "reports", "wp0", "admission-latency.txt");
		Files.createDirectories(target.getParent());
		Files.writeString(target, report);
	}

	private static double percentile(long[] sortedNanos, int percentile) {
		if (sortedNanos.length == 0) {
			return 0;
		}
		int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
		return sortedNanos[Math.clamp(index, 0, sortedNanos.length - 1)];
	}

	private static double mean(long[] nanos) {
		return nanos.length == 0 ? 0 : Arrays.stream(nanos).average().orElse(0);
	}

	private static double ms(double nanos) {
		return nanos / 1_000_000.0;
	}
}
