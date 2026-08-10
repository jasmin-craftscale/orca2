package com.lynxis.orca.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.execution.domain.ProcessEngineGateway;
import com.lynxis.orca.runtime.execution.domain.VisitQueryService;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitReadRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitReadRepository.VisitRow;

/**
 * The properties the visit read surface claims, stated as executable assertions.
 *
 * <p>These are deliberately not "the endpoint returns rows". Each one below is a
 * claim that would let something bad through if it stopped being true:
 *
 * <ul>
 *   <li>a read cannot see another site's visits — the tenancy guarantee, and the one
 *       the whole scope seam exists for;</li>
 *   <li>a read with no scope at all returns nothing, rather than everything;</li>
 *   <li>the search is bounded by its window, so a traffic-growing table is never
 *       scanned whole;</li>
 *   <li>a child execution is not a visit, so one truck is never listed as several.</li>
 * </ul>
 */
class VisitReadPropertiesIT {

	private static final String SCHEMA = "runtime";
	private static final String OURS = "SITE-READ-A";
	private static final String THEIRS = "SITE-READ-B";

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;
	private static VisitReadRepository visits;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		jdbc = new JdbcTemplate(dataSource);
		ScopeSeam seam = new JdbcScopeSeam(jdbc);
		visits = new VisitReadRepository(seam);
	}

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM execution WHERE site_external_id IN (?, ?)", OURS, THEIRS);
		jdbc.update("DELETE FROM execution WHERE external_id LIKE 'vis-batch-%'");
	}

	@Test
	@DisplayName("a read under one site's scope never returns another site's visits")
	void scopeIsolatesSites() {
		insertVisit("vis-ours", OURS, 1L, "ACTIVE", Instant.now(), null);
		insertVisit("vis-theirs", THEIRS, 2L, "ACTIVE", Instant.now(), null);

		List<VisitRow> found = asSite(OURS,
				() -> visits.search(null, null, null, Instant.now().minus(1, ChronoUnit.DAYS), 100));

		assertThat(found).extracting(VisitRow::externalId)
				.as("the other site's visit must not be reachable at all — the seam applies the "
						+ "site condition before the filter, so it is never selected rather than "
						+ "selected and then discarded")
				.containsExactly("vis-ours");

		assertThat(asSite(OURS, () -> visits.byExternalId("vis-theirs")))
				.as("naming another site's visit directly must answer empty, which is what makes "
						+ "the 404 honest rather than a disguised 403")
				.isEmpty();
	}

	@Test
	@DisplayName("with no scope set, a read returns nothing rather than everything")
	void unsetScopeDeniesByDefault() {
		insertVisit("vis-ours", OURS, 1L, "ACTIVE", Instant.now(), null);

		// No ScopeContext at all. The dangerous failure is not an exception — it is a
		// query that quietly drops its predicate and serves every site.
		List<VisitRow> found =
				visits.search(null, null, null, Instant.now().minus(1, ChronoUnit.DAYS), 100);

		assertThat(found).as("deny-by-default: an unset scope is not permission").isEmpty();
	}

	@Test
	@DisplayName("the search is bounded by its window — an older visit is outside it")
	void theWindowBounds() {
		Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
		insertVisit("vis-old", OURS, 1L, "COMPLETED", old, old);
		insertVisit("vis-new", OURS, 1L, "ACTIVE", Instant.now(), null);

		List<VisitRow> recent = asSite(OURS,
				() -> visits.search(null, null, null, Instant.now().minus(1, ChronoUnit.DAYS), 100));
		assertThat(recent).extracting(VisitRow::externalId)
				.as("a traffic-growing table is never read without a window")
				.containsExactly("vis-new");

		List<VisitRow> wider = asSite(OURS,
				() -> visits.search(null, null, null, Instant.now().minus(90, ChronoUnit.DAYS), 100));
		assertThat(wider).extracting(VisitRow::externalId)
				.as("widening the window is how history is reached — newest first")
				.containsExactly("vis-new", "vis-old");
	}

	@Test
	@DisplayName("a child execution is a step, not a visit, and is never listed as one")
	void childExecutionsAreNotVisits() {
		long parentId = insertVisit("vis-parent", OURS, 1L, "ACTIVE", Instant.now(), null);
		insertChild("vis-child", OURS, 1L, parentId);

		List<VisitRow> found = asSite(OURS,
				() -> visits.search(null, null, null, Instant.now().minus(1, ChronoUnit.DAYS), 100));

		assertThat(found).extracting(VisitRow::externalId)
				.as("one truck must not appear as several — the same root-only distinction the "
						+ "admission backstop enforces")
				.containsExactly("vis-parent");
	}

	@Test
	@DisplayName("a lane's current visit is the ACTIVE root one, and a clear lane answers empty")
	void laneCurrentVisit() {
		insertVisit("vis-lane-done", OURS, 9L, "COMPLETED", Instant.now(), Instant.now());
		assertThat(asSite(OURS, () -> visits.activeOnLane(9L)))
				.as("a finished visit is not the lane's current visit — a clear lane must read as clear")
				.isEmpty();

		insertVisit("vis-lane-live", OURS, 9L, "ACTIVE", Instant.now(), null);
		assertThat(asSite(OURS, () -> visits.activeOnLane(9L)))
				.as("the ACTIVE root visit is the lane's current visit")
				.isPresent();
	}

	@Test
	@DisplayName("one site cannot read another site's lane")
	void laneCurrentVisitIsScoped() {
		insertVisit("vis-theirs-lane", THEIRS, 11L, "ACTIVE", Instant.now(), null);

		assertThat(asSite(OURS, () -> visits.activeOnLane(11L)))
				.as("the seam applies the site condition before the filter, so another site's "
						+ "lane is never selected rather than selected and refused")
				.isEmpty();
	}

	@Test
	@DisplayName("a page of visits resolves its lanes ONCE, not once per row")
	void laneNamesAreResolvedOncePerPage() {
		// The defect this guards against was real and was measured before it was fixed:
		// 67 lane lookups for 66 rows, because the page resolved lanes a row at a time.
		// The cost scaled with the page, so the largest permitted page was the worst case.
		for (int i = 0; i < 40; i++) {
			insertVisit("vis-batch-" + i, OURS, 1L + (i % 3), "COMPLETED", Instant.now(), null);
		}

		CountingLanes lanes = new CountingLanes();
		VisitQueryService service = new VisitQueryService(visits, lanes, NO_ENGINE);

		List<com.lynxis.orca.runtime.execution.domain.VisitView> page = asSite(OURS,
				() -> service.search(null, null, null, Instant.now().minus(1, ChronoUnit.DAYS), 500));

		assertThat(page).as("the page itself must still be complete").hasSize(40);
		assertThat(lanes.singleLookups)
				.as("not one lookup per row — that is the N+1 this batch call exists to remove")
				.isZero();
		assertThat(lanes.batchLookups)
				.as("exactly one batched lookup for the whole page, however many rows it holds")
				.isEqualTo(1);
		assertThat(page).extracting(
						com.lynxis.orca.runtime.execution.domain.VisitView::laneExternalId)
				.as("and the names must actually be attached, so the batch is not merely cheap")
				.doesNotContainNull();
	}

	/** Counts how the service asks for lane names, so "once per page" is an assertion. */
	private static final class CountingLanes extends AdmissionRepository {

		private int singleLookups;
		private int batchLookups;

		private CountingLanes() {
			super(null);
		}

		@Override
		public java.util.Optional<String> laneExternalIdOf(long laneId) {
			singleLookups++;
			return java.util.Optional.of("LANE-" + laneId);
		}

		@Override
		public java.util.Map<Long, String> laneExternalIdsOf(java.util.Collection<Long> laneIds) {
			batchLookups++;
			return laneIds.stream().distinct()
					.collect(java.util.stream.Collectors.toMap(id -> id, id -> "LANE-" + id));
		}
	}

	/** The list path must not touch the engine at all; a stub that would fail loudly if it did. */
	private static final ProcessEngineGateway NO_ENGINE = new ProcessEngineGateway() {
		@Override
		public String startVisit(String processKey, String businessKey,
				java.util.Map<String, Object> correlationKeys) {
			throw new UnsupportedOperationException("a read must never start a process");
		}

		@Override
		public boolean isRunning(String processInstanceId) {
			throw new UnsupportedOperationException("the list path must not ask the engine anything");
		}

		@Override
		public java.util.Optional<String> currentActivity(String processInstanceId) {
			throw new UnsupportedOperationException(
					"the list path must not read live positions — one engine call per row is the "
							+ "same defect as one lane query per row");
		}

		@Override
		public void terminate(String processInstanceId, String reason) {
			throw new UnsupportedOperationException("a read must never terminate a process");
		}
	};

	private static <T> T asSite(String site, java.util.concurrent.Callable<T> work) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(site)), work);
	}

	private long insertVisit(String externalId, String site, long laneId, String status,
			Instant startedAt, Instant completedAt) {
		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, plate, "
						+ "started_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
				externalId, site, laneId, status, "T-" + externalId,
				java.sql.Timestamp.from(startedAt),
				completedAt == null ? null : java.sql.Timestamp.from(completedAt));
		return jdbc.queryForObject("SELECT execution_id FROM execution WHERE external_id = ?",
				Long.class, externalId);
	}

	private void insertChild(String externalId, String site, long laneId, long parentId) {
		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, "
						+ "started_at, parent_execution_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
				externalId, site, laneId, java.sql.Timestamp.from(Instant.now()), parentId);
	}
}
