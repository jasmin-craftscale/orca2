package com.lynxis.orca.platform.outbox.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A real schema on the shared SQL Server container, owned by its own login, and
 * migrated by the same Flyway locations a service uses.
 *
 * <p>Integration tests here run against <strong>the engine that ships</strong>.
 * Nothing about the outbox's skip-locked claim, the lease's guarded update or the
 * idempotency store's duplicate-key path can be tested honestly on another
 * database: those are the mechanism, not an implementation detail behind it.
 *
 * <p><strong>Each schema gets its own login, exactly as a service does.</strong>
 * That is not ceremony. SQL Server has no usable per-connection schema switch —
 * {@code Connection.setSchema} does nothing, and Flyway says outright that
 * changing the default schema is unsupported here — so the <em>only</em> thing
 * that makes an unqualified {@code CREATE TABLE} land in the right place is the
 * login's {@code DEFAULT_SCHEMA}. Production depends on precisely that
 * (deploy/bootstrap sets it, and V004 asserts it), so a test that qualified its
 * names instead would be testing something the services never do.
 */
public final class PlatformDatabase {

	/** The integration-test database, separate from anything docker compose is running. */
	public static final String DATABASE = "orca_it";

	private static final String PASSWORD = "Orca!IntegrationTest2026";

	private PlatformDatabase() {
	}

	/**
	 * Creates {@code schema} and its owning login on the shared container, drops
	 * anything left from a previous run, and applies the given Flyway locations.
	 *
	 * @param schema    a schema name unique to the calling test class
	 * @param locations Flyway classpath locations, e.g. {@code db/platform/outbox}
	 */
	public static DataSource migratedSchema(String schema, String... locations) {
		createDatabase();
		createSchemaAndLogin(schema);
		dropAllTables(schema);

		DataSource owner = ownedBy(schema);
		Flyway.configure()
				.dataSource(owner)
				.schemas(schema)
				.defaultSchema(schema)
				.createSchemas(false)
				.locations(locations)
				.load()
				.migrate();
		return owner;
	}

	/** A datasource authenticating as the schema's own login, the way a service does. */
	public static DataSource ownedBy(String schema) {
		return new DriverManagerDataSource(url(DATABASE), "it_" + schema, PASSWORD);
	}

	/**
	 * Creates a login and database user with <em>exactly</em> the given name, owning
	 * no schema and granted nothing.
	 *
	 * <p>For the other side of ADR-009. A published view is only published if some
	 * <em>other</em> service's login can select it, and that cannot be tested
	 * unless a principal by that name exists — {@code GRANT SELECT ON
	 * core.topology_lane TO [orca_runtime]} fails outright otherwise.
	 * {@code deploy/bootstrap} creates these in a real database; this is the
	 * test's equivalent, and it deliberately grants nothing beyond the ability to
	 * connect, so a view the consumer can read is a view it was granted rather
	 * than one it could reach anyway.
	 *
	 * @param login the exact login name, e.g. {@code orca_runtime} — not prefixed
	 */
	public static DataSource consumerLogin(String login) {
		createDatabase();
		DataSource admin = administrative();
		execute(admin, "IF SUSER_ID(N'" + login + "') IS NULL "
				+ "EXEC('CREATE LOGIN [" + login + "] WITH PASSWORD = ''" + PASSWORD + "'', CHECK_POLICY = OFF')");
		execute(admin, "IF DATABASE_PRINCIPAL_ID(N'" + login + "') IS NULL "
				+ "EXEC('CREATE USER [" + login + "] FOR LOGIN [" + login + "]')");
		return new DriverManagerDataSource(url(DATABASE), login, PASSWORD);
	}

	/** An administrative datasource, for the few things a schema owner cannot do. */
	public static DataSource administrative() {
		return new DriverManagerDataSource(url(DATABASE), OrcaSqlServer.saUsername(), OrcaSqlServer.saPassword());
	}

	private static String url(String database) {
		// The container's URL already carries encrypt/trust settings; only the
		// database is swapped.
		String base = OrcaSqlServer.jdbcUrl();
		return base.contains("databaseName=")
				? base.replaceAll("databaseName=[^;]*", "databaseName=" + database)
				: base + ";databaseName=" + database;
	}

	private static void createDatabase() {
		DriverManagerDataSource master = new DriverManagerDataSource(
				url("master"), OrcaSqlServer.saUsername(), OrcaSqlServer.saPassword());
		execute(master, "IF DB_ID(N'" + DATABASE + "') IS NULL CREATE DATABASE [" + DATABASE + "]");
	}

	private static void createSchemaAndLogin(String schema) {
		String login = "it_" + schema;
		DataSource admin = administrative();
		execute(admin, "IF SUSER_ID(N'" + login + "') IS NULL "
				+ "EXEC('CREATE LOGIN [" + login + "] WITH PASSWORD = ''" + PASSWORD + "'', CHECK_POLICY = OFF')");
		execute(admin, "IF DATABASE_PRINCIPAL_ID(N'" + login + "') IS NULL "
				+ "EXEC('CREATE USER [" + login + "] FOR LOGIN [" + login + "]')");
		execute(admin, "IF SCHEMA_ID(N'" + schema + "') IS NULL "
				+ "EXEC('CREATE SCHEMA [" + schema + "] AUTHORIZATION [" + login + "]')");
		execute(admin, "ALTER USER [" + login + "] WITH DEFAULT_SCHEMA = [" + schema + "]");
		execute(admin, "GRANT CREATE TABLE TO [" + login + "]");
		execute(admin, "GRANT CREATE VIEW TO [" + login + "]");
	}

	private static void dropAllTables(String schema) {
		// Views first — they depend on the tables, and a view left behind makes
		// Flyway refuse the NEXT clean migration of this schema with "found
		// non-empty schema but no schema history table". Found by Phase 2's core
		// suites, which are the first to migrate a schema with published views
		// (V102) twice in one build. Then foreign keys, then tables: dropping in
		// an arbitrary order fails and leaves the schema half-cleaned.
		execute(administrative(), """
				DECLARE @sql NVARCHAR(MAX) = N'';
				SELECT @sql = @sql + N'DROP VIEW [%1$s].[' + name + N'];'
				FROM sys.views WHERE schema_id = SCHEMA_ID(N'%1$s');
				SELECT @sql = @sql + N'ALTER TABLE [%1$s].[' + OBJECT_NAME(parent_object_id)
				                   + N'] DROP CONSTRAINT [' + name + N'];'
				FROM sys.foreign_keys WHERE schema_id = SCHEMA_ID(N'%1$s');
				SELECT @sql = @sql + N'DROP TABLE [%1$s].[' + name + N'];'
				FROM sys.tables WHERE schema_id = SCHEMA_ID(N'%1$s');
				IF @sql <> N'' EXEC sp_executesql @sql;
				""".formatted(schema));
	}

	private static void execute(DataSource dataSource, String sql) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed: " + sql, e);
		}
	}
}
