package com.lynxis.orca.runtime.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
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
 * <strong>Proves that Flyway owns Flowable's schema completely.</strong>
 *
 * <p>V110–V114 are the engine's own SQL Server DDL, extracted from the jars on the
 * runtime classpath. Extracting is the easy half. The half that can be wrong
 * quietly is <em>completeness</em>: miss one of the five create scripts, or get
 * their order wrong, and the service still starts — right up until the first
 * process instance touches the table that is not there.
 *
 * <p>So this builds the same schema twice and compares them:
 *
 * <ul>
 *   <li><strong>{@code runtime}</strong> — built by Flyway from the committed
 *       migrations, exactly as a deployment does.</li>
 *   <li><strong>{@code flowable_scratch}</strong> — built by the ENGINE, with
 *       {@code database-schema-update=true}, reproducing the earlier
 *       engine-managed schema that the migrations replace.</li>
 * </ul>
 *
 * <p>Tables, columns, indexes and foreign keys must be identical. Indexes and
 * foreign keys are included because a missing index on {@code ACT_RU_EXECUTION} is a real
 * defect that a column comparison cannot see, and it would surface as a slow gate
 * rather than as an error.
 *
 * <p>The last test is the one the whole package exists for: runtime boots against
 * the Flyway-built schema with self-migration <em>off</em>. That is the engine
 * accepting a schema it did not create.
 */
class FlowableSchemaUnderFlywayIT {

	private static final Logger log = LoggerFactory.getLogger(FlowableSchemaUnderFlywayIT.class);

	/**
	 * Deliberately the real schema name. V100 stamps extended properties on a schema
	 * called {@code runtime} by name, so the production migration set can only be
	 * applied to a schema called {@code runtime} — which is itself worth knowing.
	 */
	private static final String FLYWAY_BUILT = "runtime";

	private static final String ENGINE_BUILT = "flowable_scratch";

	/** Flowable's tables, and nothing of ORCA's. Both schemas carry other tables that are not the subject. */
	private static final String ENGINE_TABLES =
			"(t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%')";

	private static JdbcTemplate flywayBuilt;
	private static JdbcTemplate engineBuilt;

	@BeforeAll
	@Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
	static void buildTheSameSchemaBothWays() {
		// --- the way a deployment does it ---------------------------------
		// The production location list, from orca-runtime's own application.yaml.
		flywayBuilt = new JdbcTemplate(PlatformDatabase.migratedSchema(FLYWAY_BUILT,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency"));

		// --- the earlier engine-managed-schema path -----------------------
		// Everything except Flowable, then the engine is let loose on it.
		DriverManagerDataSource scratch = (DriverManagerDataSource) PlatformDatabase.migratedSchema(ENGINE_BUILT,
				"db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		engineBuilt = new JdbcTemplate(scratch);

		try (ConfigurableApplicationContext engine = new SpringApplicationBuilder(RuntimeApplication.class)
				.web(WebApplicationType.NONE)
				.run(
						"--spring.datasource.url=" + scratch.getUrl(),
						"--spring.datasource.username=" + scratch.getUsername(),
						"--spring.datasource.password=" + scratch.getPassword(),
						"--spring.flyway.enabled=false",
						"--spring.jpa.properties.hibernate.default_schema=" + ENGINE_BUILT,
						// The value this package removes, used once more to produce the
						// thing the committed migrations are compared against.
						"--flowable.database-schema-update=true",
						"--flowable.async-executor-activate=false",
						"--orca.required-views=")) {
			assertThat(engine.isRunning()).isTrue();
		}
	}

	// ------------------------------------------------------------------------

	@Test
	@DisplayName("Flyway builds exactly the tables the engine would have built — no more, and none missing")
	void theSameTablesExist() {
		List<String> byFlyway = tables(flywayBuilt);
		List<String> byEngine = tables(engineBuilt);

		log.info("WP3 · Flowable tables: {} by Flyway, {} by the engine", byFlyway.size(), byEngine.size());

		assertThat(byEngine).as("the engine must actually have built something to compare against")
				.isNotEmpty();
		assertThat(byFlyway)
				.as("a create script missed at extraction time still starts the service — it fails "
						+ "later, on the first process instance that touches the table")
				.containsExactlyElementsOf(byEngine);
	}

	@Test
	@DisplayName("every column matches — name, type, length, precision, scale and nullability")
	void everyColumnMatches() {
		assertThat(columns(flywayBuilt))
				.as("a column that differs in type or nullability is the defect that survives a "
						+ "table-name comparison")
				.containsExactlyElementsOf(columns(engineBuilt));
	}

	@Test
	@DisplayName("every index matches — a missing one is a slow gate, not an error")
	void everyIndexMatches() {
		// An absent index on
		// ACT_RU_EXECUTION does not fail anything; it just makes every visit slower,
		// and nothing in a column diff would ever mention it.
		assertThat(indexes(flywayBuilt)).containsExactlyElementsOf(indexes(engineBuilt));
	}

	@Test
	@DisplayName("every foreign key matches — including the thirty the engine script alone declares")
	void everyForeignKeyMatches() {
		assertThat(foreignKeys(flywayBuilt)).containsExactlyElementsOf(foreignKeys(engineBuilt));
	}

	@Test
	@DisplayName("the engine records its own schema version, which is what lets it start without migrating")
	void theEngineVersionIsRecordedByTheMigrations() {
		// With database-schema-update=false the engine does not inspect the tables:
		// it reads this row and refuses if it disagrees. A migration set that created
		// every table but not this row would start nothing.
		String flywayVersion = flywayBuilt.queryForObject(
				"SELECT VALUE_ FROM ACT_GE_PROPERTY WHERE NAME_ = 'schema.version'", String.class);
		String engineVersion = engineBuilt.queryForObject(
				"SELECT VALUE_ FROM ACT_GE_PROPERTY WHERE NAME_ = 'schema.version'", String.class);

		assertThat(flywayVersion).isEqualTo(engineVersion);
		log.info("WP3 · engine schema version recorded by both paths: {}", flywayVersion);
	}

	@Test
	@Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
	@DisplayName("runtime boots against the Flyway-built schema with self-migration OFF")
	void runtimeStartsWithSelfMigrationOff() {
		// The point of the whole package. The engine is handed a schema it did not
		// create and has to accept it — reading its recorded version, creating
		// nothing. This uses the committed `flowable.database-schema-update: false`
		// from application.yaml rather than overriding it, so what is proven is the
		// shipping configuration.
		DriverManagerDataSource built = (DriverManagerDataSource) PlatformDatabase.ownedBy(FLYWAY_BUILT);

		try (ConfigurableApplicationContext runtime = new SpringApplicationBuilder(RuntimeApplication.class)
				.web(WebApplicationType.NONE)
				.run(
						"--spring.datasource.url=" + built.getUrl(),
						"--spring.datasource.username=" + built.getUsername(),
						"--spring.datasource.password=" + built.getPassword(),
						"--spring.flyway.enabled=false",
						"--spring.jpa.properties.hibernate.default_schema=" + FLYWAY_BUILT,
						"--flowable.async-executor-activate=false",
						"--orca.required-views=")) {
			assertThat(runtime.isRunning()).isTrue();
			assertThat(runtime.getBean(org.flowable.engine.RuntimeService.class))
					.as("the engine is up, not merely the context")
					.isNotNull();
		}
	}

	// ------------------------------------------------------------------------

	private static List<String> tables(JdbcTemplate schema) {
		return schema.queryForList(
				"SELECT t.name FROM sys.tables t WHERE " + ENGINE_TABLES + " ORDER BY t.name",
				String.class);
	}

	private static List<String> columns(JdbcTemplate schema) {
		return schema.queryForList("""
				SELECT CONCAT(t.name, '.', c.name, ' ', ty.name,
				              '(', c.max_length, ',', c.precision, ',', c.scale, ')',
				              CASE WHEN c.is_nullable = 1 THEN ' NULL' ELSE ' NOT NULL' END)
				FROM sys.columns c
					JOIN sys.tables t ON t.object_id = c.object_id
					JOIN sys.types ty ON ty.user_type_id = c.user_type_id
				WHERE %s
				ORDER BY t.name, c.name
				""".formatted(ENGINE_TABLES), String.class);
	}

	private static List<String> indexes(JdbcTemplate schema) {
		// Flowable declares two constraints WITHOUT a name — a primary key and a
		// unique — so SQL Server generates one: PK__ACT_RU_E__C4971C0FE6FD6CED,
		// UQ__ACT_HI_P__C034157236C2E819. The hex suffix differs between any two runs
		// of the SAME script, so comparing those names compares nothing. They are
		// identified by shape instead — table, columns, uniqueness — using SQL
		// Server's own `is_system_named` rather than a pattern over the text, so a
		// real constraint that merely happened to be called PK_something is still
		// compared by name.
		//
		// Flowable's ordinary indexes ARE named (ACT_IDX_*, FLW_IDX_*) and are
		// compared by name, which is where a genuine omission would show.
		return schema.queryForList("""
				SELECT CONCAT(t.name, '.',
				              -- COLLATE DATABASE_DEFAULT: sys.key_constraints.type_desc
				              -- carries the catalog collation and i.name the database's,
				              -- and a CASE cannot mix them.
				              CASE WHEN kc.is_system_named = 1
				                   THEN CONCAT('<system-named ', kc.type_desc COLLATE DATABASE_DEFAULT, '>')
				                   ELSE i.name END,
				              ' unique=', i.is_unique,
				              ' pk=', i.is_primary_key, ' cols=', (
				    SELECT STRING_AGG(CONCAT(col.name, CASE WHEN ic.is_descending_key = 1 THEN ' desc' ELSE '' END), ',')
				           WITHIN GROUP (ORDER BY ic.key_ordinal)
				    FROM sys.index_columns ic
				        JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
				    WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id))
				FROM sys.indexes i
					JOIN sys.tables t ON t.object_id = i.object_id
					LEFT JOIN sys.key_constraints kc
						ON kc.parent_object_id = i.object_id AND kc.unique_index_id = i.index_id
				WHERE %s AND i.name IS NOT NULL
				ORDER BY t.name, i.is_primary_key DESC, i.is_unique DESC, i.name
				""".formatted(ENGINE_TABLES), String.class);
	}

	private static List<String> foreignKeys(JdbcTemplate schema) {
		return schema.queryForList("""
				SELECT CONCAT(t.name, '.', fk.name, ' -> ', rt.name)
				FROM sys.foreign_keys fk
					JOIN sys.tables t ON t.object_id = fk.parent_object_id
					JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
				WHERE %s
				ORDER BY t.name, fk.name
				""".formatted(ENGINE_TABLES), String.class);
	}
}
