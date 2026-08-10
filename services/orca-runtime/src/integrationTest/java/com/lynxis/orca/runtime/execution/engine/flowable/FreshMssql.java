package com.lynxis.orca.runtime.execution.engine.flowable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;

import com.lynxis.orca.platform.outbox.testing.OrcaSqlServer;

/**
 * Fresh-database provisioning for the engine tests, on the repository's one shared
 * SQL Server container.
 *
 * <p>A fresh database rather than truncated tables is deliberate: Flowable caches
 * deployments in-engine, so a second engine booted onto truncated tables answers
 * from the first engine's cache and the test lies. {@code PlatformDatabase}'s
 * shared {@code orca_it} database is right for schema-shaped tests and wrong for
 * these, which boot real engines and sometimes two of them.
 *
 * <p>{@code rcsi} decides whether {@code READ_COMMITTED_SNAPSHOT} is switched on —
 * production databases have it, and a negative-RCSI test needs one without it.
 */
final class FreshMssql {

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private FreshMssql() {
	}

	/** A database this class provisioned, and the credentials to reach it. */
	record ProvisionedDatabase(String name, String jdbcUrl, String username, String password) {
	}

	/**
	 * Creates a fresh database containing an empty {@code runtime} schema and
	 * returns it with administrative credentials. The schema exists before Flyway
	 * runs because the committed migrations assume it — in production the deploy
	 * bootstrap creates it, never Flyway.
	 */
	static ProvisionedDatabase freshRuntimeDatabase(String namePrefix, boolean rcsi) {
		String name = sanitize(namePrefix) + "_" + SEQUENCE.incrementAndGet();
		try (Connection connection = adminConnection(null); Statement statement = connection.createStatement()) {
			statement.executeUpdate("CREATE DATABASE [" + name + "]");
			if (rcsi) {
				statement.executeUpdate(
						"ALTER DATABASE [" + name + "] SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE");
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not provision test database " + name, e);
		}
		String url = urlFor(name);
		try (Connection connection = adminConnection(url); Statement statement = connection.createStatement()) {
			statement.executeUpdate("EXEC('CREATE SCHEMA runtime')");
		} catch (SQLException e) {
			throw new IllegalStateException("Could not create the runtime schema on " + name, e);
		}
		return new ProvisionedDatabase(name, url, OrcaSqlServer.saUsername(), OrcaSqlServer.saPassword());
	}

	/**
	 * Applies the full committed migration set — the service's own migrations plus
	 * the three platform primitives, the same four locations the service's Flyway
	 * runs — into the {@code runtime} schema of {@code db}.
	 */
	static void migrateRuntime(ProvisionedDatabase db) {
		// Flyway must run as a login whose DEFAULT_SCHEMA is `runtime`: the
		// committed migrations use unqualified DDL that lands in the login's
		// default schema, and an administrative login defaults to dbo — the
		// tables would exist, in the wrong place, and the engine would report
		// them missing. Same discipline as the shared PlatformDatabase fixture.
		ProvisionedDatabase migrator = serviceLogin(db, "orca_flyway_" + db.name(),
				"Fly!Way2026_" + db.name().hashCode(), "runtime");
		Flyway.configure()
				.dataSource(migrator.jdbcUrl(), migrator.username(), migrator.password())
				.schemas("runtime")
				.defaultSchema("runtime")
				.createSchemas(false)
				.locations("classpath:db/migration", "classpath:db/platform/outbox",
						"classpath:db/platform/lease", "classpath:db/platform/idempotency")
				.load()
				.migrate();
	}

	/**
	 * Provisions a service login on {@code db} the way production provisions the
	 * runtime's: a dedicated login whose {@code DEFAULT_SCHEMA} is the service
	 * schema, {@code db_owner} on this database only. Unqualified table names in
	 * the engine's own SQL resolve through the login's default schema — that, not
	 * a table prefix, is what scopes the engine to {@code runtime}.
	 *
	 * @return the same database, connecting as the service login
	 */
	static ProvisionedDatabase serviceLogin(
			ProvisionedDatabase db, String login, String password, String defaultSchema) {
		try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
				Statement statement = connection.createStatement()) {
			statement.executeUpdate("IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = '"
					+ login + "') EXEC('CREATE LOGIN " + login
					+ " WITH PASSWORD = ''" + password + "'', CHECK_POLICY = OFF')");
			statement.executeUpdate("CREATE USER " + login + " FOR LOGIN " + login
					+ " WITH DEFAULT_SCHEMA = " + defaultSchema);
			statement.executeUpdate("ALTER ROLE db_owner ADD MEMBER " + login);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not provision service login " + login + " on " + db.name(), e);
		}
		return new ProvisionedDatabase(db.name(), db.jdbcUrl(), login, password);
	}

	private static Connection adminConnection(String url) throws SQLException {
		return DriverManager.getConnection(
				url != null ? url : OrcaSqlServer.jdbcUrl(), OrcaSqlServer.saUsername(), OrcaSqlServer.saPassword());
	}

	private static String urlFor(String database) {
		String base = OrcaSqlServer.jdbcUrl();
		int semicolon = base.indexOf(';');
		String hostPart = semicolon >= 0 ? base.substring(0, semicolon) : base;
		String options = semicolon >= 0 ? base.substring(semicolon) : "";
		return hostPart + ";databaseName=" + database + options.replaceAll(";databaseName=[^;]*", "");
	}

	private static String sanitize(String prefix) {
		return prefix.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
	}
}
