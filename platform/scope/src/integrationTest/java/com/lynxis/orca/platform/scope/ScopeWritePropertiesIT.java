package com.lynxis.orca.platform.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;

/**
 * <strong>WP2 · the seam's write half.</strong>
 *
 * <p>Phase 0's seam could only read, and the build check forbids a service from
 * touching JDBC — so until now a service physically could not insert a row. That
 * was correct while there was no business logic. The moment there is, the same
 * question arrives from the other side: <em>can a caller write a row into a scope
 * it does not hold?</em>
 *
 * <p>It is the worse half of the problem. A read that escapes its scope shows the
 * caller data they should not see, which at least surfaces somewhere. A write that
 * escapes its scope puts a row where the writer will never look again and where
 * the rightful owner sees it as their own. Nothing reports it, and no later read
 * distinguishes it from data that was always there.
 *
 * <p>Every property below is asserted against real SQL Server with real rows,
 * because "refused" has to mean the row is not in the table afterwards — not that
 * a string was built a particular way.
 */
class ScopeWritePropertiesIT {

	private static final String SCHEMA = "it_scope_write";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private ScopeSeam seam;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA);
		new JdbcTemplate(dataSource).execute("""
				IF OBJECT_ID('%s.work_item', 'U') IS NULL
				CREATE TABLE work_item (
					id INT IDENTITY PRIMARY KEY,
					site_id VARCHAR(20) NOT NULL,
					status VARCHAR(20) NOT NULL)
				""".formatted(SCHEMA));
		// H5's counter. Added rather than included above because the table survives
		// between runs, and a CREATE guarded by IF OBJECT_ID would skip a new column.
		new JdbcTemplate(dataSource).execute("""
				IF COL_LENGTH('%s.work_item', 'attempts') IS NULL
				ALTER TABLE work_item ADD attempts INT NOT NULL CONSTRAINT df_wi_attempts DEFAULT 0
				""".formatted(SCHEMA));
		// A nullable column, standing in for edge's last_error: the column a caller
		// legitimately sets to null.
		new JdbcTemplate(dataSource).execute("""
				IF COL_LENGTH('%s.work_item', 'note') IS NULL
				ALTER TABLE work_item ADD note VARCHAR(50) NULL
				""".formatted(SCHEMA));
	}

	@BeforeEach
	void seed() {
		jdbc = new JdbcTemplate(dataSource);
		seam = new JdbcScopeSeam(jdbc);
		jdbc.execute("DELETE FROM work_item");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-1', 'QUEUED')");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-2', 'QUEUED')");
		jdbc.update("INSERT INTO work_item (site_id, status) VALUES ('site-3', 'QUEUED')");
	}

	// ------------------------------------------------------------------------
	// Property 1 — an unscoped write is REFUSED, not quietly applied to nothing.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("with NO scope established, a write is refused — and refused loudly")
	void anUnsetScopeRefusesTheWrite() {
		// No ScopeContext.runIn anywhere. This is the background job that forgot, the
		// relay nobody scoped, the reconciler added last month.
		assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
				.isInstanceOf(ScopeViolationException.class)
				.hasMessageContaining("permits nothing in dimension 'site_id'");

		assertThatThrownBy(() -> seam.update(retireQueued()))
				.as("an update under no scope must not come back as 'zero rows changed', which is "
						+ "what a successful no-op looks like")
				.isInstanceOf(ScopeViolationException.class);

		assertThat(rowCount()).as("nothing was written").isEqualTo(3);
	}

	@Test
	@DisplayName("entering a system identity is not the same as holding a scope — there is no implicit one")
	void systemWorkGetsNoImplicitScope() {
		// The rule the seam's Javadoc states, made executable. Background work is
		// required to enter a system context (§B6) — and that grants an IDENTITY, not
		// an ENTITLEMENT. Wiring the two together would be the one silent bypass
		// nobody would ever notice, because system work has no user to notice on its
		// behalf. Here, "background work" is simply a thread that set no scope.
		assertThat(ScopeContext.current().isDeny())
				.as("the default is deny, for system work exactly as for anything else")
				.isTrue();

		assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
				.isInstanceOf(ScopeViolationException.class);

		// And when it sets one deliberately, it writes.
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")),
				() -> assertThat(seam.insert(insertFor("site-1"))).isEqualTo(1));
		assertThat(rowCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("an explicit DENY refuses too — it is not a special 'unset' case that behaves differently")
	void explicitDenyRefusesTheWrite() {
		ScopeContext.runIn(Scope.DENY, () -> {
			assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
					.isInstanceOf(ScopeViolationException.class);
			assertThatThrownBy(() -> seam.update(retireQueued()))
					.isInstanceOf(ScopeViolationException.class);
		});
		assertThat(rowCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("a scope that says nothing about THIS dimension is deny, not permission")
	void aScopeInAnotherDimensionIsNotPermission() {
		// The caller holds a real scope — over carriers. It says nothing about sites,
		// and "says nothing" must not read as "unconstrained". This is the silent
		// widening the whole primitive exists to prevent, and it is the one that
		// looks most like working code.
		ScopeContext.runIn(Scope.of("carrier_id", Set.of("carrier-9")), () -> {
			assertThatThrownBy(() -> seam.insert(insertFor("site-1")))
					.isInstanceOf(ScopeViolationException.class)
					.hasMessageContaining("dimension 'site_id'");
			assertThatThrownBy(() -> seam.update(retireQueued()))
					.isInstanceOf(ScopeViolationException.class);
		});
		assertThat(rowCount()).isEqualTo(3);
	}

	// ------------------------------------------------------------------------
	// Property 2 — a permitted write lands; an unpermitted VALUE does not.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a write into a scope the caller holds lands")
	void aPermittedWriteLands() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")),
				() -> assertThat(seam.insert(insertFor("site-1"))).isEqualTo(1));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM work_item WHERE site_id = 'site-1' AND status = 'NEW'", Long.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a caller scoped to site-1 cannot create a row in site-2 — the row it would never see again")
	void aWriteIntoAnotherScopeIsRefused() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				assertThatThrownBy(() -> seam.insert(insertFor("site-2")))
						.isInstanceOf(ScopeViolationException.class)
						.hasMessageContaining("does not permit the value 'site-2'"));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM work_item WHERE site_id = 'site-2'", Long.class))
				.as("site-2 has exactly the row it started with")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a caller holding two sites may write into either, and into no third")
	void aMultiSiteScopePermitsExactlyItsSites() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1", "site-2")), () -> {
			assertThat(seam.insert(insertFor("site-1"))).isEqualTo(1);
			assertThat(seam.insert(insertFor("site-2"))).isEqualTo(1);
			assertThatThrownBy(() -> seam.insert(insertFor("site-3")))
					.isInstanceOf(ScopeViolationException.class);
		});
		assertThat(rowCount()).isEqualTo(5);
	}

	// ------------------------------------------------------------------------
	// Property 3 — an update cannot reach beyond the scope, however it is filtered.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("an update touches only rows inside the scope, however broadly it is written")
	void anUpdateCannotReachOutsideTheScope() {
		int changed = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.update(retireQueued()));

		assertThat(changed).as("one row — site-1's").isEqualTo(1);
		assertThat(statusOf("site-1")).isEqualTo("RETIRED");
		assertThat(statusOf("site-2")).as("untouched, and the caller was never told it existed").isEqualTo("QUEUED");
		assertThat(statusOf("site-3")).isEqualTo("QUEUED");
	}

	@Test
	@DisplayName("a caller filter of 'status = ? OR 1 = 1' still cannot escape the scope")
	void aCallerFilterCannotWidenTheScope() {
		// The filter is ANDed AFTER the scope predicate, so the most permissive
		// fragment a caller can write still intersects with what they hold. The same
		// property the read half proves, from the side where getting it wrong writes
		// rather than reads.
		int changed = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.update(ScopedUpdate.table("work_item")
						.set("status", "RETIRED")
						.scopedBy("site_id")
						.where("status = ? OR 1 = 1", "QUEUED")));

		assertThat(changed).isEqualTo(1);
		assertThat(statusOf("site-2")).isEqualTo("QUEUED");
		assertThat(statusOf("site-3")).isEqualTo("QUEUED");
	}

	@Test
	@DisplayName("zero rows changed is an ordinary outcome, and is NOT a refusal")
	void changingNothingIsNotTheSameAsBeingRefused() {
		// The distinction the exception exists to preserve. Here the caller holds the
		// scope and the rows simply do not match — that is a number, not an error.
		int changed = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.update(ScopedUpdate.table("work_item")
						.set("status", "RETIRED")
						.scopedBy("site_id")
						.where("status = ?", "ALREADY_GONE")));

		assertThat(changed).isZero();
	}

	// ------------------------------------------------------------------------
	// Property 4 — an unscoped write is not expressible, and identifiers are shapes.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a write that never says what it is scoped by cannot be built")
	void anUnscopedWriteIsNotExpressible() {
		assertThatThrownBy(() -> seam.insert(ScopedInsert.into("work_item")
				.value("site_id", "site-1").value("status", "NEW")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no unscoped write through the seam");

		assertThatThrownBy(() -> seam.update(ScopedUpdate.table("work_item").set("status", "X")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no unscoped write through the seam");
	}

	@Test
	@DisplayName("an insert scoped by a column it supplies no value for is refused — the seam will not pick a scope")
	void anInsertMustSayWhichScopeTheRowLandsIn() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				assertThatThrownBy(() -> seam.insert(ScopedInsert.into("work_item")
						.scopedBy("site_id")
						.value("status", "NEW")))
						.as("defaulting it — to the only permitted site, say — would be the seam "
								+ "deciding what the caller is entitled to")
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("supplies no value"));

		assertThat(rowCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("identifiers are allow-listed by shape on the write side too, not just the read side")
	void identifiersAreAllowListedOnWrites() {
		assertThatThrownBy(() -> ScopedInsert.into("work_item; DROP TABLE work_item"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ScopedInsert.into("work_item").value("status --", "x"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ScopedUpdate.table("work_item").set("status", "x").scopedBy("site_id)"))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(rowCount()).isEqualTo(3);
	}

	// ------------------------------------------------------------------------
	// WP6 · the two things the seam had to learn for admission.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a nullable column can be written as NULL — a seam that cannot express it forces raw JDBC")
	void aNullableColumnCanBeWrittenAsNull() {
		// Latent from WP2 until WP7's first insert with a genuinely absent value: the
		// builder copied its values with Map.copyOf, which rejects a null with a bare
		// NullPointerException out of ImmutableCollections. A nullable column is
		// entirely ordinary — a device that did not identify itself, a command with no
		// parameters — and a seam that cannot express NULL pushes every such write
		// back to raw JDBC, which ScopeSeamRule correctly forbids.
		jdbc.execute("IF COL_LENGTH('work_item', 'note') IS NULL ALTER TABLE work_item ADD note VARCHAR(50) NULL");

		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				assertThat(seam.insert(ScopedInsert.into("work_item")
						.scopedBy("site_id")
						.value("site_id", "site-1")
						.value("status", "NEW")
						.value("note", null))).isEqualTo(1));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM work_item WHERE status = 'NEW' AND note IS NULL", Long.class))
				.as("NULL, and not the string 'null' — which is what a workaround at the call site "
						+ "would most likely have produced")
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("insertReturningKey gives back the assigned key — and is refused by scope exactly as insert is")
	void theAssignedKeyComesBackFromTheInsertItself() {
		long key = ScopeContext.callIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.insertReturningKey(insertFor("site-1"), "id"));

		assertThat(key)
				.as("the identity of the row this caller just created, from the insert's own "
						+ "statement — reading it back afterwards against a non-unique column is a "
						+ "correctness bug the moment two callers insert equal-looking rows")
				.isEqualTo(jdbc.queryForObject(
						"SELECT MAX(id) FROM work_item WHERE site_id = 'site-1'", Long.class));

		// The scope check is the same one, in the same place. A second write surface
		// with a weaker check is how the seam acquires a hole.
		assertThatThrownBy(() -> ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")),
				() -> seam.insertReturningKey(insertFor("site-2"), "id")))
				.isInstanceOf(ScopeViolationException.class);
		assertThatThrownBy(() -> seam.insertReturningKey(insertFor("site-1"), "id"))
				.as("and with no scope at all it is refused, not applied")
				.isInstanceOf(ScopeViolationException.class);

		assertThat(rowCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("lockMatchedRows actually serialises two readers — a shared lock would let both in")
	void aLockedReadHoldsOffTheSecondReaderUntilCommit() throws Exception {
		// The property the lane lock rests on, asserted on the seam rather than on
		// the caller: two threads read-decide-write the same row, and if BOTH reads
		// can proceed the decision is made twice. A plain read passes that test; only
		// an exclusive one does not.
		java.util.concurrent.CountDownLatch firstHasTheLock = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch firstMayCommit = new java.util.concurrent.CountDownLatch(1);
		java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();

		org.springframework.transaction.support.TransactionTemplate transactions =
				new org.springframework.transaction.support.TransactionTemplate(
						new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

		try (java.util.concurrent.ExecutorService both =
				java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {

			both.submit(() -> ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
					transactions.executeWithoutResult(status -> {
						seam.select(lockedRead(), (rs, row) -> rs.getInt("id"));
						order.add("first-read");
						firstHasTheLock.countDown();
						await(firstMayCommit);
						order.add("first-commit");
					})));

			assertThat(firstHasTheLock.await(2, java.util.concurrent.TimeUnit.MINUTES)).isTrue();

			java.util.concurrent.Future<?> second = both.submit(() ->
					ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
							transactions.executeWithoutResult(status -> {
								seam.select(lockedRead(), (rs, row) -> rs.getInt("id"));
								order.add("second-read");
							})));

			// Long enough that a second read which was going to proceed has done so.
			Thread.sleep(1_000);
			assertThat(order)
					.as("if 'second-read' is here, the lock is shared and two instances are both "
							+ "about to decide that no visit is running on this lane")
					.containsExactly("first-read");

			firstMayCommit.countDown();
			second.get(2, java.util.concurrent.TimeUnit.MINUTES);
		}

		assertThat(order).containsExactly("first-read", "first-commit", "second-read");
	}

	private static ScopedSelect lockedRead() {
		return ScopedSelect.from("work_item")
				.columns("id")
				.scopedBy("site_id")
				.where("status = ?", "QUEUED")
				.lockMatchedRows();
	}

	private static void await(java.util.concurrent.CountDownLatch latch) {
		try {
			if (!latch.await(2, java.util.concurrent.TimeUnit.MINUTES)) {
				throw new IllegalStateException("the other thread never arrived");
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	// ------------------------------------------------------------------------
	// H5 — the allow-listed atomic increment, and the update it stops losing.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("concurrent increments all land — the read-write-back they replace loses some")
	void anAtomicIncrementDoesNotLoseUpdates() throws Exception {
		int threads = 8;
		int each = 25;

		// --- what the seam does now ---------------------------------------
		long counted = countUp(threads, each, () -> seam.update(ScopedUpdate.table("work_item")
				.increment("attempts", 1)
				.scopedBy("site_id")
				.where("site_id = ?", "site-1")));

		assertThat(counted)
				.as("""
						`attempts = attempts + 1` is evaluated by the database inside the statement, \
						so two callers cannot both read the same value first. Every failure is \
						recorded.""")
				.isEqualTo((long) threads * each);

		// --- what WP5 had to do, in the same conditions --------------------
		// EventBufferRepository read every row and wrote it back, because the seam
		// could not express the arithmetic. This is that, and it is here so the
		// assertion above is a comparison rather than an assertion about nothing.
		jdbc.update("UPDATE work_item SET attempts = 0");
		long readWriteBack = countUp(threads, each, () -> {
			int current = seam.select(ScopedSelect.from("work_item")
							.columns("attempts")
							.scopedBy("site_id")
							.where("site_id = ?", "site-1"),
					(rs, row) -> rs.getInt("attempts")).getFirst();
			pause();  // the window every one of these has, widened to make it visible
			return seam.update(ScopedUpdate.table("work_item")
					.set("attempts", current + 1)
					.scopedBy("site_id")
					.where("site_id = ?", "site-1"));
		});

		assertThat(readWriteBack)
				.as("""
						THE DEFECT, DEMONSTRATED. Two deliveries that both read attempts = 3 and \
						both write 4 record ONE failure between them — so an event that has failed \
						twice as often as its counter says is retired later than the limit promises, \
						and nothing in either transaction could notice.""")
				.isLessThan((long) threads * each);
	}

	@Test
	@DisplayName("an increment outside the caller's scope is refused, and the counter is untouched")
	void anIncrementCannotReachAnotherSitesCounter() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				seam.update(ScopedUpdate.table("work_item")
						.increment("attempts", 5)
						.scopedBy("site_id")));

		assertThat(attemptsOf("site-1")).isEqualTo(5);
		assertThat(attemptsOf("site-2"))
				.as("the scope predicate applies to an increment exactly as it does to a set — the "
						+ "expression is the only thing that changed")
				.isZero();

		assertThatThrownBy(() -> seam.update(ScopedUpdate.table("work_item")
				.increment("attempts", 1)
				.scopedBy("site_id")))
				.as("no scope established: refused loudly, not applied to nothing")
				.isInstanceOf(ScopeViolationException.class);

		assertThat(attemptsOf("site-1")).isEqualTo(5);
	}

	@Test
	@DisplayName("a set and an increment travel in one statement — which is why the status still moves")
	void setAndIncrementCombineInOneStatement() {
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () ->
				seam.update(ScopedUpdate.table("work_item")
						.set("status", "RETRYING")
						.increment("attempts", 3)
						.scopedBy("site_id")));

		assertThat(statusOf("site-1")).isEqualTo("RETRYING");
		assertThat(attemptsOf("site-1")).isEqualTo(3);
	}

	@Test
	@DisplayName("a SET to null lands as NULL — the pump's error message is allowed to be absent")
	void aNullValueCanBeSet() {
		// Found in review, fixed incidentally by H5: the previous assignments map went
		// through Map.copyOf, which rejects null VALUES — so set(column, null) threw
		// NullPointerException at execution. A real caller hits this: the pump records
		// a failure with exception.getMessage(), and a message can be null. This test
		// is what keeps the fix from regressing if the assignment plumbing changes.
		ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () -> {
			seam.update(ScopedUpdate.table("work_item")
					.set("note", "an error from last time")
					.scopedBy("site_id"));
			seam.update(ScopedUpdate.table("work_item")
					.set("note", null)
					.scopedBy("site_id"));
		});

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM work_item WHERE site_id = 'site-1' AND note IS NULL",
				Long.class))
				.as("null was WRITTEN, clearing the earlier value — not refused and not skipped")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("setting AND incrementing the same column is refused before any SQL exists")
	void oneColumnCannotBeBothAssignedAndAdvanced() {
		assertThatThrownBy(() -> ScopedUpdate.table("work_item")
				.set("attempts", 5)
				.increment("attempts", 1))
				.as("\"assign 5\" and \"add 5\" in one statement is a question, not an instruction, "
						+ "and the seam will not answer it by ordering")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("both set and incremented");
	}

	/** Runs `each` operations on `threads` threads at once and returns the counter afterwards. */
	private long countUp(int threads, int each, java.util.concurrent.Callable<Integer> operation)
			throws Exception {
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.List<Thread> workers = new java.util.ArrayList<>();
		java.util.List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

		for (int t = 0; t < threads; t++) {
			Thread worker = new Thread(() -> ScopeContext.runIn(Scope.of("site_id", Set.of("site-1")), () -> {
				try {
					start.await();
					for (int i = 0; i < each; i++) {
						operation.call();
					}
				}
				catch (Exception thrown) {
					failures.add(thrown);
				}
			}));
			worker.start();
			workers.add(worker);
		}

		start.countDown();
		for (Thread worker : workers) {
			worker.join();
		}
		assertThat(failures).as("no worker may fail for an unrelated reason").isEmpty();

		return attemptsOf("site-1");
	}

	private static void pause() {
		try {
			Thread.sleep(2);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private long attemptsOf(String site) {
		Integer attempts = jdbc.queryForObject(
				"SELECT attempts FROM work_item WHERE site_id = ?", Integer.class, site);
		return attempts == null ? 0 : attempts;
	}

	// ------------------------------------------------------------------------

	private static ScopedInsert insertFor(String site) {
		return ScopedInsert.into("work_item")
				.scopedBy("site_id")
				.value("site_id", site)
				.value("status", "NEW");
	}

	private static ScopedUpdate retireQueued() {
		return ScopedUpdate.table("work_item")
				.set("status", "RETIRED")
				.scopedBy("site_id")
				.where("status = ?", "QUEUED");
	}

	private long rowCount() {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM work_item", Long.class);
		return count == null ? 0 : count;
	}

	private String statusOf(String site) {
		return jdbc.queryForObject("SELECT status FROM work_item WHERE site_id = ?", String.class, site);
	}
}
