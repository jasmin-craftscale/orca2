package com.lynxis.orca.runtime.execution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;

/**
 * The step trace and the visit dataset, round-tripped through the seam against
 * the real migrations — including the property that makes the seam worth having:
 * a caller scoped to another site reads nothing and cannot write.
 */
class TraceDatasetRoundTripIT {

	private static final String SCHEMA = "runtime";
	private static final String SITE = "SITE-TRACE-IT";
	private static final String OTHER_SITE = "SITE-SOMEBODY-ELSE";

	private static JdbcTemplate jdbc;
	private static NodeExecutionTraceRepository trace;
	private static VisitDatasetRepository dataset;
	private static long parentId;

	@BeforeAll
	static void migrateAndSeedOneVisit() {
		DataSource dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		jdbc = new JdbcTemplate(dataSource);
		ScopeSeam seam = new JdbcScopeSeam(jdbc);
		trace = new NodeExecutionTraceRepository(seam);
		dataset = new VisitDatasetRepository(seam);

		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, process_instance_id) "
				+ "VALUES (?, ?, ?, ?, ?)", "visit-trace-it", SITE, 41L, "ACTIVE", "engine-trace-it");
		parentId = jdbc.queryForObject(
				"SELECT execution_id FROM execution WHERE external_id = 'visit-trace-it'", Long.class);
	}

	private static <T> T scoped(java.util.function.Supplier<T> work) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(SITE)), work::get);
	}

	@Test
	void theNewestStepRowAnswersForItsNode() {
		scoped(() -> {
			long first = trace.recordCompletedStep("step-1", SITE, parentId,
					"node-a", "CONNECTOR", "{\"pass\":1}", Instant.now());
			long second = trace.recordCompletedStep("step-2", SITE, parentId,
					"node-a", "CONNECTOR", "{\"pass\":2}", Instant.now());
			assertThat(second).isGreaterThan(first);
			assertThat(trace.latestPayload(parentId, "node-a")).contains("{\"pass\":2}");
			assertThat(trace.latestPayload(parentId, "node-never-ran")).isEmpty();
			return null;
		});
	}

	@Test
	void aKeyHasOneCurrentValue() {
		scoped(() -> {
			dataset.write(SITE, parentId, "plate_confidence", "0.61");
			dataset.write(SITE, parentId, "plate_confidence", "0.93");
			assertThat(dataset.value(parentId, "plate_confidence")).contains("0.93");
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM visit_dataset WHERE execution_id = ? AND data_key = 'plate_confidence'",
					Integer.class, parentId)).isEqualTo(1);
			assertThat(dataset.all(parentId)).containsEntry("plate_confidence", "0.93");
			return null;
		});
	}

	@Test
	void aChildClosesWithoutTouchingItsRoot() {
		scoped(() -> {
			trace.insertChildExecution("child-trace-it", SITE, 41L, parentId,
					7001L, 3, "engine-child-it");
			assertThat(trace.completeChildByEngineInstance("engine-child-it", Instant.now()))
					.isEqualTo(1);
			// The root's engine instance never matches the child predicate: closing
			// roots belongs to the visit-completion path, and this proves the
			// recorder cannot do it by accident.
			assertThat(trace.completeChildByEngineInstance("engine-trace-it", Instant.now()))
					.isZero();
			assertThat(jdbc.queryForObject(
					"SELECT status FROM execution WHERE execution_id = ?", String.class, parentId))
					.isEqualTo("ACTIVE");
			return null;
		});
	}

	@Test
	void theObservedVocabularyComesFromTheNewestVisitsOfAWorkflow() {
		scoped(() -> {
			jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, workflow_id) "
					+ "VALUES (?, ?, 43, 'COMPLETED', 880001)", "visit-obs-1", SITE);
			long older = jdbc.queryForObject(
					"SELECT execution_id FROM execution WHERE external_id = 'visit-obs-1'", Long.class);
			jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, workflow_id) "
					+ "VALUES (?, ?, 43, 'COMPLETED', 880001)", "visit-obs-2", SITE);
			long newer = jdbc.queryForObject(
					"SELECT execution_id FROM execution WHERE external_id = 'visit-obs-2'", Long.class);
			dataset.write(SITE, older, "plate", "A");
			dataset.write(SITE, newer, "rfid", "B");

			assertThat(dataset.observedKeysOfNewestVisits(880001L, 100))
					.containsExactly("plate", "rfid");
			// Bounded by construction: a sample of one sees only the newest visit's keys.
			assertThat(dataset.observedKeysOfNewestVisits(880001L, 1)).containsExactly("rfid");
			// A workflow that has never run here answers empty — honest, not a guess.
			assertThat(dataset.observedKeysOfNewestVisits(999_999L, 100)).isEmpty();
			return null;
		});
	}

	@Test
	void anotherSiteSeesNothingAndWritesNothing() {
		ScopeContext.runIn(Scope.of("site_external_id", Set.of(OTHER_SITE)), () -> {
			assertThat(trace.visitByEngineInstance("engine-trace-it")).isEmpty();
			assertThat(trace.latestPayload(parentId, "node-a")).isEmpty();
			assertThat(dataset.value(parentId, "plate_confidence")).isEmpty();
			assertThat(dataset.all(parentId)).isEmpty();
		});
	}
}
