package com.lynxis.orca.platform.outbox.testing;

import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one SQL Server container every integration test in the repository shares.
 *
 * <p>This carries forward the Testcontainers wiring Spring Initializr generated at
 * the root ({@code TestcontainersConfiguration}), which was the only thing in the
 * generated application worth keeping: it shows how <em>this</em> Spring Boot
 * version wires a container to a datasource. The root application it lived in is
 * gone; the pattern is not.
 *
 * <p><strong>Microsoft SQL Server is the only database.</strong> There is no
 * PostgreSQL deployment, so there is no second container and no engine switch in
 * the tests. Advice elsewhere that assumes PostgreSQL — particularly about
 * row-level security — does not apply here.
 *
 * <p>The container is a static singleton started once per JVM rather than once per
 * class. SQL Server takes tens of seconds to come up; starting one per test class
 * is what turns an integration suite into something nobody runs.
 */
public final class OrcaSqlServer {

	/**
	 * Pinned deliberately. {@code :latest} is what the generated configuration used
	 * and it is the wrong thing for a suite that has to be reproducible two years
	 * from now — a new image tag would change the engine under the tests silently.
	 */
	private static final DockerImageName IMAGE =
			DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest");

	/** The SA password. Eight or more characters with upper, lower, digit and symbol, or SQL Server will not start. */
	public static final String SA_PASSWORD = "Orca!Local2026";

	@SuppressWarnings("resource") // Deliberately not closed: Testcontainers' Ryuk reaps it at JVM exit.
	private static final MSSQLServerContainer CONTAINER =
			new MSSQLServerContainer(IMAGE).acceptLicense().withPassword(SA_PASSWORD);

	private OrcaSqlServer() {
	}

	/** Starts the shared container on first call, and returns it started thereafter. */
	public static MSSQLServerContainer instance() {
		if (!CONTAINER.isRunning()) {
			synchronized (OrcaSqlServer.class) {
				if (!CONTAINER.isRunning()) {
					CONTAINER.start();
				}
			}
		}
		return CONTAINER;
	}

	/** The JDBC URL of the shared container, with the started-on-demand guarantee. */
	public static String jdbcUrl() {
		return instance().getJdbcUrl();
	}

	/** The administrative login. Tests that need a <em>service</em> login create one; they never reuse this. */
	public static String saUsername() {
		return instance().getUsername();
	}

	public static String saPassword() {
		return instance().getPassword();
	}
}
