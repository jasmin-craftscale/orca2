package com.lynxis.orca.runtime.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.runtime.RuntimeApplication;

/**
 * <strong>H4 · the migration path off {@code database-schema-update: true},
 * executed rather than described.</strong>
 *
 * <p>{@code phase-1-report.md} §7.4: <em>"There is no migration path off
 * `database-schema-update: true`, and the demo proved it by tripping over it."</em>
 * A database on which the engine created its own tables has them, and Flyway has no
 * history for them, so V110 fails on <em>"There is already an object named
 * 'ACT_GE_PROPERTY'"</em>. On this machine that was cleared by hand.
 *
 * <p>This suite is that clearing, made into a procedure and proven. It runs
 * <strong>the committed script itself</strong> — {@code deploy/adopt-flowable/} —
 * rather than a copy of its statements, because a test that reimplements the
 * procedure proves the test works.
 *
 * <h2>The four properties, in the order they matter</h2>
 *
 * <ol>
 *   <li>The failure is real: Flyway <strong>does</strong> refuse an
 *       engine-created schema, with that message. If this ever stops being true
 *       the procedure is solving nothing.</li>
 *   <li>After adoption, Flyway migrates cleanly and the <strong>engine boots</strong>
 *       against the schema it did not create.</li>
 *   <li>Adoption <strong>refuses</strong> a schema holding process data, and
 *       <strong>changes nothing</strong>. This is the safety property: a gate with a
 *       truck mid-visit is exactly the installation somebody runs this on in a
 *       hurry.</li>
 *   <li>Adoption is a <strong>no-op</strong> on a schema that needs none, so running
 *       it when in doubt cannot hurt.</li>
 * </ol>
 */
class FlowableAdoptionIT {

	private static final Logger log = LoggerFactory.getLogger(FlowableAdoptionIT.class);

	/**
	 * ⚠️ The real schema name, and it is not a choice.
	 *
	 * <p>{@code V100} stamps extended properties on a schema called {@code runtime}
	 * <em>by name</em>, so the production migration set can only be applied to a
	 * schema called {@code runtime} — {@code FlowableSchemaUnderFlywayIT} records the
	 * same constraint. This suite therefore breaks and rebuilds the shared one, and
	 * {@link #leaveTheSchemaAsWeFoundIt()} is not tidiness: three other suites read
	 * it after this one.
	 */
	private static final String SCHEMA = "runtime";

	private static DriverManagerDataSource dataSource;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void migrate() {
		dataSource = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		jdbc = new JdbcTemplate(dataSource);
	}

	@BeforeEach
	void aSchemaTheEngineMigratedItself() {
		// Rewind to what Phase 0 shipped: no Flyway history for the engine's tables,
		// and the engine let loose to create them. That is what an installation on
		// that build has today, and it is the only starting state worth testing from.
		forgetFlowableEverWentUnderFlyway();
		letTheEngineMigrateItself();

		assertThat(engineTables())
				.as("the fixture must actually reproduce a self-migrated schema, or every "
						+ "assertion below is about nothing")
				.isGreaterThan(40);
	}

	@AfterAll
	static void leaveTheSchemaAsWeFoundIt() {
		// Three other suites read `runtime` and this one has been dropping its
		// engine tables. Restoring it is part of the test, not cleanup around it.
		forgetFlowableEverWentUnderFlyway();
		flyway().migrate();
	}

	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("the failure is real: Flyway refuses an engine-created schema, naming the table")
	void flywayCannotMigrateOverTheEnginesOwnTables() {
		assertThatThrownBy(FlowableAdoptionIT::migrateWithFlyway)
				.as("""
						§7.4's incident, reproduced. If this ever stops throwing, the adoption \
						procedure below is solving a problem that no longer exists and should be \
						removed rather than kept as reassurance.""")
				.hasMessageContaining("ACT_GE_PROPERTY");
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("after adoption, Flyway migrates cleanly and the engine boots on a schema it did not create")
	void adoptionLetsFlywayTakeOverAndTheEngineStillStarts() {
		adopt();

		assertThat(engineTables())
				.as("adoption by rebuild removes the engine's objects; V110-V114 put them back")
				.isZero();

		migrateWithFlyway();

		assertThat(engineTables())
				.as("45 tables, built by the committed migrations this time")
				.isGreaterThan(40);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('110','111','112','113','114')",
				Integer.class))
				.as("and recorded, so the next start validates rather than re-runs them")
				.isEqualTo(5);

		// The assertion the whole procedure is for: the engine accepts a schema it
		// did not build, with self-migration off.
		try (ConfigurableApplicationContext runtime = boot("--flowable.database-schema-update=false")) {
			assertThat(runtime.isRunning()).isTrue();
		}
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("adoption REFUSES a schema holding process data, and changes nothing")
	void aSchemaWithProcessDataIsRefusedIntact() {
		// One historic process instance. On a real gate that is a visit somebody
		// completed and whose audit trail the site is required to keep.
		jdbc.update("INSERT INTO ACT_HI_PROCINST (ID_, PROC_INST_ID_, PROC_DEF_ID_, START_TIME_, "
				+ "REV_) VALUES ('adopt-guard', 'adopt-guard', 'gate-visit:1:1', SYSUTCDATETIME(), 1)");
		int before = engineTables();

		assertThatThrownBy(FlowableAdoptionIT::adopt)
				.as("""
						THE SAFETY PROPERTY. Adopting by rebuild destroys running visits and the \
						audit trail behind completed ones. A gate with a truck mid-visit is exactly \
						the installation somebody runs this on in a hurry, and the refusal is what \
						stands between them and it.""")
				.hasMessageContaining("REFUSING to adopt")
				.hasMessageContaining("process data");

		assertThat(engineTables())
				.as("refused means UNCHANGED — not partially dropped, which would be worse than "
						+ "either outcome")
				.isEqualTo(before);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM ACT_HI_PROCINST", Integer.class)).isEqualTo(1);
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("adoption REFUSES a schema built by a different Flowable version")
	void aDifferentEngineVersionIsRefused() {
		// The tables are then not what V110-V114 build, so rebuilding would change
		// the schema under an engine that has been running on the other one.
		jdbc.update("UPDATE ACT_GE_PROPERTY SET VALUE_ = '7.1.0.0' WHERE NAME_ = 'common.schema.version'");

		assertThatThrownBy(FlowableAdoptionIT::adopt)
				.hasMessageContaining("REFUSING to adopt")
				.hasMessageContaining("7.1.0.0");

		assertThat(engineTables()).isGreaterThan(40);
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("engine tables WITHOUT the version marker are refused — an unrecognisable state is not adopted")
	void aPartialSchemaWithoutThePropertyTableIsRefused() {
		// Not a state Flowable self-migration produces; a hand-cleared machine could
		// be in it. Neither the version nor completeness can be established, and a
		// script that drops tables does not proceed through what it cannot recognise.
		jdbc.execute("DROP TABLE ACT_GE_PROPERTY");

		assertThatThrownBy(FlowableAdoptionIT::adopt)
				.hasMessageContaining("REFUSING to adopt")
				.hasMessageContaining("ACT_GE_PROPERTY does not");

		assertThat(engineTables())
				.as("refused means untouched, minus only the table this test itself dropped")
				.isGreaterThan(40);
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("adoption is a no-op where none is needed — running it in doubt cannot hurt")
	void adoptingATwiceAdoptedSchemaDoesNothing() {
		adopt();
		migrateWithFlyway();
		int afterFlyway = engineTables();

		// The state a healthy installation is in. An operator who is not sure whether
		// this database was ever self-migrated must be able to run it and find out.
		adopt();

		assertThat(engineTables())
				.as("V110 is recorded in flyway_schema_history, so there is nothing to adopt and "
						+ "nothing is dropped")
				.isEqualTo(afterFlyway);

		// And on a schema with no engine tables at all — a fresh installation.
		forgetFlowableEverWentUnderFlyway();
		adopt();
		assertThat(engineTables()).isZero();
	}

	// ------------------------------------------------------------------------

	/** Runs the COMMITTED script, as the service's own login. Not a copy of it. */
	private static void adopt() {
		Path script = repositoryRoot()
				.resolve("deploy/adopt-flowable/adopt-engine-created-schema.sql");
		String sql;
		try {
			sql = Files.readString(script);
		}
		catch (IOException unreadable) {
			throw new UncheckedIOException("The adoption script this test exists to prove is not at "
					+ script, unreadable);
		}
		log.info("H4 · running {} against [{}]", script.getFileName(), SCHEMA);
		// One statement: the script is a single batch by construction, precisely so
		// that it can be executed by something other than sqlcmd.
		jdbc.execute(sql);
	}

	private static void migrateWithFlyway() {
		flyway().migrate();
	}

	private static org.flywaydb.core.Flyway flyway() {
		return org.flywaydb.core.Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.defaultSchema(SCHEMA)
				.createSchemas(false)
				.locations("db/migration", "db/platform/outbox", "db/platform/lease",
						"db/platform/idempotency")
				.validateOnMigrate(true)
				.load();
	}

	private static void letTheEngineMigrateItself() {
		try (ConfigurableApplicationContext engine = boot("--flowable.database-schema-update=true")) {
			assertThat(engine.isRunning()).isTrue();
		}
	}

	private static ConfigurableApplicationContext boot(String schemaUpdate) {
		return new SpringApplicationBuilder(RuntimeApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.datasource.url=" + dataSource.getUrl(),
						"--spring.datasource.username=" + dataSource.getUsername(),
						"--spring.datasource.password=" + dataSource.getPassword(),
						"--spring.flyway.enabled=false",
						"--spring.jpa.properties.hibernate.default_schema=" + SCHEMA,
						schemaUpdate,
						"--flowable.async-executor-activate=false",
						"--orca.required-views=");
	}

	private static int engineTables() {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM sys.tables t
				WHERE t.schema_id = SCHEMA_ID(?)
				  AND (t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%')""", Integer.class, SCHEMA);
		return count == null ? 0 : count;
	}

	/**
	 * Drops the engine tables and the V110–V114 history rows.
	 *
	 * <p>⚠️ Scoped to {@code SCHEMA_ID(?)} deliberately: {@code FlowableSchemaUnderFlyway}
	 * keeps a second copy of Flowable's tables in {@code flowable_scratch}, in the
	 * same database, and an unscoped drop would take those with it.
	 */
	private static void forgetFlowableEverWentUnderFlyway() {
		jdbc.update("""
				DECLARE @drop nvarchar(max) = N'';
				SELECT @drop = @drop + N'ALTER TABLE ' + QUOTENAME(SCHEMA_NAME(t.schema_id)) + N'.'
					+ QUOTENAME(t.name) + N' DROP CONSTRAINT ' + QUOTENAME(fk.name) + N';'
				FROM sys.foreign_keys fk JOIN sys.tables t ON t.object_id = fk.parent_object_id
				WHERE t.schema_id = SCHEMA_ID(?) AND (t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%');
				SELECT @drop = @drop + N'DROP TABLE ' + QUOTENAME(SCHEMA_NAME(t.schema_id)) + N'.'
					+ QUOTENAME(t.name) + N';'
				FROM sys.tables t
				WHERE t.schema_id = SCHEMA_ID(?) AND (t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%');
				EXEC sp_executesql @drop;""", SCHEMA, SCHEMA);
		// Rewinding "before the engine went under Flyway" now means rewinding
		// EVERYTHING from V110 up, because Phase 3's V115 is numbered after the
		// engine's five (Flyway refuses out-of-order, so it could not be V103).
		// A history holding 115 but not 110 is a state Flyway rejects outright —
		// so V115's tables go too, and re-migration puts all six back.
		//
		// ⚠️ This is a standing cost of the numbering: every future runtime
		// migration ≥ V115 must be droppable here, or this suite's fixture stops
		// being constructible. Recorded in the phase-3 report.
		jdbc.update("""
				IF OBJECT_ID(N'work_item_audit', 'U') IS NOT NULL DROP TABLE work_item_audit;
				IF OBJECT_ID(N'work_item', 'U') IS NOT NULL DROP TABLE work_item;""");
		jdbc.update("DELETE FROM flyway_schema_history WHERE TRY_CAST(version AS INT) >= 110");
	}

	private static Path repositoryRoot() {
		Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (candidate != null) {
			if (Files.exists(candidate.resolve("settings.gradle.kts"))) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("Could not find the repository root above "
				+ System.getProperty("user.dir"));
	}
}
