package com.lynxis.orca.platform.outbox.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * The shared Spring test configuration: a SQL Server container wired to the
 * application's datasource by {@code @ServiceConnection}.
 *
 * <p>This is the generated {@code TestcontainersConfiguration} pattern, moved out
 * of the deleted root application and made shared rather than per-module. Import
 * it from a {@code @SpringBootTest} and the context talks to a real SQL Server.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OrcaSqlServerConfiguration {

	@Bean
	@ServiceConnection
	MSSQLServerContainer sqlServerContainer() {
		return OrcaSqlServer.instance();
	}
}
