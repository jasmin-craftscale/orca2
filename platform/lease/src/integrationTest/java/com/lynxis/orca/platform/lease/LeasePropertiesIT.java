package com.lynxis.orca.platform.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * Proves the lease primitive's three guarantees.
 *
 * <p>The third one is the one that matters. Acquiring a lease is easy and every
 * naive implementation gets it right; <em>refusing the zombie's write</em> is what
 * the fence token is for, and it is the part that is silently missing from an
 * {@code is_owner} column.
 */
class LeasePropertiesIT {

	private static final String SCHEMA = "it_lease";
	private static final String SERVICE = "orca-edge";
	private static final String LANE_LEASE = "edge.ingest:lane:3";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private LeaseManager leases;
	private FencedWrite fencedWrite;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA, "db/platform/lease");
		new JdbcTemplate(dataSource).execute(
				"IF OBJECT_ID('" + SCHEMA + ".capture', 'U') IS NULL "
						+ "CREATE TABLE capture (id INT IDENTITY PRIMARY KEY, written_by NVARCHAR(100))");
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		leases = new JdbcLeaseManager(jdbc, SERVICE);
		fencedWrite = new FencedWrite(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
				SERVICE);
		jdbc.execute("DELETE FROM service_lease");
		jdbc.execute("DELETE FROM capture");
	}

	// ------------------------------------------------------------------------
	// Property 1 — two instances race; exactly one wins.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("sixteen instances race for one lease; exactly one wins")
	void exactlyOneInstanceWinsTheRace() throws Exception {
		int instances = 16;
		ExecutorService pool = Executors.newFixedThreadPool(instances);
		CountDownLatch startTogether = new CountDownLatch(1);
		List<Callable<Optional<Lease>>> attempts = new ArrayList<>();
		for (int i = 0; i < instances; i++) {
			String holder = "instance-" + i;
			attempts.add(() -> {
				startTogether.await();
				return leases.acquire(LANE_LEASE, holder, Duration.ofSeconds(30));
			});
		}

		List<Future<Optional<Lease>>> futures = new ArrayList<>();
		for (Callable<Optional<Lease>> attempt : attempts) {
			futures.add(pool.submit(attempt));
		}
		startTogether.countDown();

		int winners = 0;
		for (Future<Optional<Lease>> future : futures) {
			if (future.get(30, TimeUnit.SECONDS).isPresent()) {
				winners++;
			}
		}
		pool.shutdownNow();

		assertThat(winners)
				.as("acquisition is a guarded conditional update, so there is no window for a second winner")
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_lease", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("the holder can re-acquire its own lease; nobody else can, until it expires")
	void anotherInstanceCannotTakeAHeldLease() {
		Lease held = leases.acquire(LANE_LEASE, "instance-a", Duration.ofSeconds(30)).orElseThrow();

		assertThat(leases.acquire(LANE_LEASE, "instance-b", Duration.ofSeconds(30))).isEmpty();
		assertThat(leases.acquire(LANE_LEASE, "instance-a", Duration.ofSeconds(30)))
				.as("the holder re-acquiring is legitimate — and still takes a new token")
				.isPresent()
				.hasValueSatisfying(reacquired ->
						assertThat(reacquired.fenceToken()).isGreaterThan(held.fenceToken()));
	}

	// ------------------------------------------------------------------------
	// Property 2 — expire the lease mid-operation; the write is REJECTED.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a holder whose lease expired mid-operation has its write rejected, not silently applied")
	void theZombiesWriteIsRefused() {
		// Instance A takes the lane and begins work.
		Lease a = leases.acquire(LANE_LEASE, "instance-a", Duration.ofSeconds(30)).orElseThrow();
		fencedWrite.execute(a, () -> jdbc.update("INSERT INTO capture (written_by) VALUES (?)", "instance-a"));
		assertThat(capturesBy("instance-a")).isEqualTo(1);

		// A's network stalls. The lease expires — by the DATABASE's clock, which is
		// the only one both instances agree on.
		expireNow(LANE_LEASE);

		// B takes over.
		Lease b = leases.acquire(LANE_LEASE, "instance-b", Duration.ofSeconds(30)).orElseThrow();
		assertThat(b.fenceToken()).isGreaterThan(a.fenceToken());

		// A wakes up. It never knew it died, and it finishes the write it started.
		assertThatThrownBy(() -> fencedWrite.execute(a,
				() -> jdbc.update("INSERT INTO capture (written_by) VALUES (?)", "instance-a-zombie")))
				.isInstanceOf(StaleFenceTokenException.class)
				.hasMessageContaining("no longer holds");

		assertThat(capturesBy("instance-a-zombie"))
				.as("the zombie's write was refused AND rolled back — not merely logged")
				.isZero();

		// And the legitimate holder still works.
		fencedWrite.execute(b, () -> jdbc.update("INSERT INTO capture (written_by) VALUES (?)", "instance-b"));
		assertThat(capturesBy("instance-b")).isEqualTo(1);
	}

	@Test
	@DisplayName("renewing with a token the holder no longer has does not resurrect it")
	void aStaleTokenCannotBeRenewed() {
		Lease a = leases.acquire(LANE_LEASE, "instance-a", Duration.ofSeconds(30)).orElseThrow();
		expireNow(LANE_LEASE);
		Lease b = leases.acquire(LANE_LEASE, "instance-b", Duration.ofSeconds(30)).orElseThrow();

		assertThat(leases.renew(a, Duration.ofSeconds(30)))
				.as("A's renewal must not take the lease back from B")
				.isEmpty();
		assertThat(leases.find(LANE_LEASE)).hasValueSatisfying(current -> {
			assertThat(current.holderId()).isEqualTo("instance-b");
			assertThat(current.fenceToken()).isEqualTo(b.fenceToken());
		});
	}

	@Test
	@DisplayName("expiry is judged by the database's clock, not the caller's")
	void expiryIsJudgedByTheDatabase() {
		leases.acquire(LANE_LEASE, "instance-a", Duration.ofMillis(200)).orElseThrow();

		// No local sleep and no local clock comparison: the row is aged in the
		// database, and the database decides. Two hosts with disagreeing clocks
		// cannot disagree about who holds this.
		jdbc.update("UPDATE service_lease SET expires_at = DATEADD(second, -1, SYSUTCDATETIME()) "
				+ "WHERE service = ? AND lease_name = ?", SERVICE, LANE_LEASE);

		assertThat(leases.acquire(LANE_LEASE, "instance-b", Duration.ofSeconds(30)))
				.as("expired by the database's reckoning, so B takes it")
				.isPresent();
	}

	// ------------------------------------------------------------------------
	// Property 3 — the token strictly increases across handovers.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("the fence token strictly increases across every handover, including release and re-take")
	void theTokenStrictlyIncreases() {
		List<Long> tokens = new ArrayList<>();
		String[] holders = { "a", "b", "a", "c", "b", "c" };

		for (String holder : holders) {
			expireNow(LANE_LEASE);
			Lease lease = leases.acquire(LANE_LEASE, "instance-" + holder, Duration.ofSeconds(30)).orElseThrow();
			tokens.add(lease.fenceToken());
			leases.release(lease);
		}

		assertThat(tokens).isSorted();
		assertThat(tokens).doesNotHaveDuplicates();
		assertThat(tokens.getFirst()).isEqualTo(1L);
		assertThat(tokens.getLast()).isEqualTo((long) holders.length);
	}

	@Test
	@DisplayName("releasing does not delete the row, so the next token cannot start lower")
	void releaseDoesNotResetTheToken() {
		Lease first = leases.acquire(LANE_LEASE, "instance-a", Duration.ofSeconds(30)).orElseThrow();
		leases.release(first);

		Lease second = leases.acquire(LANE_LEASE, "instance-b", Duration.ofSeconds(30)).orElseThrow();

		assertThat(second.fenceToken())
				.as("a deleted row would restart the sequence, and a stale token would then look current")
				.isGreaterThan(first.fenceToken());
	}

	@Test
	@DisplayName("concurrent guarded writes from the true holder all succeed; the count is exact")
	void theHolderIsNotBlockedByItsOwnGuard() throws Exception {
		Lease held = leases.acquire(LANE_LEASE, "instance-a", Duration.ofSeconds(60)).orElseThrow();

		int writes = 20;
		AtomicInteger succeeded = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch done = new CountDownLatch(writes);
		for (int i = 0; i < writes; i++) {
			pool.submit(() -> {
				try {
					fencedWrite.execute(held,
							() -> jdbc.update("INSERT INTO capture (written_by) VALUES (?)", "instance-a"));
					succeeded.incrementAndGet();
				}
				finally {
					done.countDown();
				}
			});
		}
		assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(succeeded.get()).isEqualTo(writes);
		assertThat(capturesBy("instance-a")).isEqualTo(writes);
	}

	private void expireNow(String leaseName) {
		jdbc.update("UPDATE service_lease SET expires_at = SYSUTCDATETIME() "
				+ "WHERE service = ? AND lease_name = ?", SERVICE, leaseName);
	}

	private int capturesBy(String holder) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM capture WHERE written_by = ?",
				Integer.class, holder);
		return count == null ? 0 : count;
	}
}
