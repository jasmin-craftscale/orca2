package com.lynxis.orca.platform.outbox.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A real schema on the shared SQL Server container, migrated by the same Flyway
 * locations a service uses.
 *
 * <p>Integration tests here run against <strong>the engine that ships</strong>.
 * Nothing about the outbox's claim, the lease's guarded update or the idempotency
 * store's duplicate-key path can be tested honestly on another database: skip-locked
 * reads, {@code SYSUTCDATETIME()} and {@code OUTPUT} clauses are the mechanism, not
 * an implementation detail behind it.
 */
public final class PlatformDatabase {

	private PlatformDatabase() {
	}

	/**
	 * Creates {@code schema} on the shared container (dropping it first if present)
	 * and applies the given Flyway locations to it.
	 *
	 * @param schema    a schema name unique to the calling test class, so classes do
	 *                  not tread on each other when the suite runs in parallel
	 * @param locations Flyway classpath locations, e.g. {@code db/platform/outbox}
	 */
	public static DataSource migratedSchema(String schema, String... locations) {
		dropAndCreateSchema(schema);
		DataSource dataSource = dataSource();
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.createSchemas(false)
				.locations(locations)
				.load()
				.migrate();
		return schemaBoundDataSource(schema);
	}

	/** A datasource whose connections default to {@code schema}, as a service's login would. */
	public static DataSource schemaBoundDataSource(String schema) {
		// A service's own login has this schema as its DEFAULT_SCHEMA, so its
		// migrations and queries are unqualified. Tests connect as sa, so the
		// binding is done here instead — otherwise the tests would exercise
		// qualified names the production code never uses.
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				OrcaSqlServer.jdbcUrl(), OrcaSqlServer.saUsername(), OrcaSqlServer.saPassword());
		dataSource.setSchema(schema);
		return dataSource;
	}

	public static DataSource dataSource() {
		return new DriverManagerDataSource(
				OrcaSqlServer.jdbcUrl(), OrcaSqlServer.saUsername(), OrcaSqlServer.saPassword());
	}

	private static void dropAndCreateSchema(String schema) {
		try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DECLARE @sql NVARCHAR(MAX) = N'';
					SELECT @sql = @sql + N'DROP TABLE [%s].[' + name + N'];'
					FROM sys.tables WHERE schema_id = SCHEMA_ID(N'%s');
					EXEC sp_executesql @sql;
					""".formatted(schema, schema));
			statement.execute("IF SCHEMA_ID(N'%s') IS NULL EXEC('CREATE SCHEMA [%s]')".formatted(schema, schema));
		}
		catch (SQLException e) {
			throw new IllegalStateException("Could not prepare schema " + schema, e);
		}
	}
}
