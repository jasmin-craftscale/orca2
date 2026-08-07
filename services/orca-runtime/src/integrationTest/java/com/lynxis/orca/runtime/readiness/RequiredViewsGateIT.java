package com.lynxis.orca.runtime.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.readiness.MissingRequiredViewException;
import com.lynxis.orca.runtime.RuntimeApplication;

/**
 * <strong>WP1 · the deployment ordering, executed rather than documented.</strong>
 *
 * <p>§6 item 10 of the phase plan: start runtime before core has migrated and it
 * must refuse, naming {@code core.topology_lane} and {@code core.topology_device}.
 * Phase 0 proved this by hand against an empty view list; WP1 gave the list two
 * real entries, so it is worth proving again — automatically, and including the
 * case that is easy to get wrong.
 *
 * <p><strong>The third test is the one worth reading.</strong> The gate does not
 * ask whether a view exists; it asks whether <em>this login can see it</em>,
 * because {@code INFORMATION_SCHEMA.VIEWS} shows only what the caller has some
 * permission on. So a database where core has migrated but the grant was lost
 * fails the same way as one where core never ran — which is right, since a view
 * runtime cannot read is a view runtime does not have.
 *
 * <p>This boots the real {@link RuntimeApplication}, engine and all. A test that
 * instantiated {@code RequiredViewsGate} directly would prove the class works and
 * say nothing about whether it is wired into the service that needs it.
 */
class RequiredViewsGateIT {

	private static final String SCHEMA = "it_readiness";
	private static final String REQUIRED = "core.topology_lane,core.topology_device";

	private static DriverManagerDataSource runtimeSchema;

	@BeforeAll
	static void migrate() {
		// Runtime's own schema. Its contents are irrelevant here — what is under
		// test is whether the service starts at all.
		runtimeSchema = (DriverManagerDataSource)
				PlatformDatabase.migratedSchema(SCHEMA, "db/spike/admission");
	}

	@Test
	@DisplayName("started before core has migrated, orca-runtime refuses and names BOTH missing views")
	void refusesToStartBeforeCoreHasMigrated() {
		dropCorePublications();

		Throwable failure = catchThrowable(() -> boot(REQUIRED).close());

		MissingRequiredViewException missing = missingViewCause(failure);
		assertThat(missing)
				.as("runtime must refuse to start, not start and fail on its first lane lookup")
				.isNotNull();
		assertThat(missing.getMissingViews())
				.as("one restart has to tell an operator the whole problem, not the first third of it")
				.containsExactlyInAnyOrder("core.topology_lane", "core.topology_device");
		assertThat(missing.getMessage())
				.contains("core.topology_lane")
				.contains("core.topology_device")
				.as("the message says whose problem it is")
				.contains("deployment ordering problem");
	}

	@Test
	@DisplayName("once core has published and granted them, orca-runtime starts")
	void startsOnceCoreHasPublishedAndGranted() {
		publishCoreViews(true);

		try (ConfigurableApplicationContext started = boot(REQUIRED)) {
			assertThat(started.isRunning()).isTrue();
		}
	}

	@Test
	@DisplayName("views that exist but were never granted are treated as absent — the gate asks 'can I read it'")
	void aViewThisServiceCannotReadCountsAsMissing() {
		// The views are there. The grant is not. If the gate asked only whether the
		// object exists, this would start — and then every lane lookup would fail
		// with a permission error at the worst possible moment.
		publishCoreViews(false);

		Throwable failure = catchThrowable(() -> boot(REQUIRED).close());

		MissingRequiredViewException missing = missingViewCause(failure);
		assertThat(missing).isNotNull();
		assertThat(missing.getMissingViews())
				.containsExactlyInAnyOrder("core.topology_lane", "core.topology_device");
	}

	@Test
	@DisplayName("a bare view name is refused rather than resolved against this service's own schema")
	void aBareViewNameIsRefused() {
		publishCoreViews(true);

		// Resolving `topology_lane` unqualified would look in `runtime`, the one
		// schema the view is certainly not in — and report success or failure about
		// the wrong object either way.
		assertThatThrownBy(() -> boot("topology_lane").close())
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not fully qualified");
	}

	// ------------------------------------------------------------------------

	private ConfigurableApplicationContext boot(String requiredViews) {
		// Command-line arguments, NOT SpringApplicationBuilder.properties(). The
		// latter sets Boot's *default* properties, which sit below application.yaml
		// — so every one of these would have been silently overridden by the
		// committed configuration, and the test would have proven something about
		// localhost:1433.
		return new SpringApplicationBuilder(RuntimeApplication.class)
				.web(WebApplicationType.NONE)
				.run(
						"--spring.datasource.url=" + runtimeSchema.getUrl(),
						"--spring.datasource.username=" + runtimeSchema.getUsername(),
						"--spring.datasource.password=" + runtimeSchema.getPassword(),
						"--spring.flyway.enabled=false",
						"--spring.jpa.properties.hibernate.default_schema=" + SCHEMA,
						"--flowable.database-schema-update=true",
						"--flowable.async-executor-activate=false",
						"--orca.required-views=" + requiredViews);
	}

	private static MissingRequiredViewException missingViewCause(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof MissingRequiredViewException missing) {
				return missing;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return null;
	}

	/** Stands in for orca-core having migrated. Grants only when asked, so the third case is reachable. */
	private static void publishCoreViews(boolean grant) {
		String consumer = runtimeSchema.getUsername();
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		for (String view : new String[] { "topology_lane", "topology_device" }) {
			admin("IF OBJECT_ID(N'core." + view + "', 'V') IS NOT NULL DROP VIEW core." + view);
			// Shape is irrelevant — the gate checks for the view and nothing else,
			// deliberately, because checking its columns would be this service
			// asserting something about core's internals.
			admin("EXEC('CREATE VIEW core." + view + " AS SELECT CAST(NULL AS BIGINT) AS lane_id WHERE 1 = 0')");
			if (grant) {
				admin("GRANT SELECT ON core." + view + " TO [" + consumer + "]");
			}
		}
	}

	private static void dropCorePublications() {
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("IF OBJECT_ID(N'core.topology_device', 'V') IS NOT NULL DROP VIEW core.topology_device");
	}

	private static void admin(String sql) {
		DataSource administrative = PlatformDatabase.administrative();
		try (Connection connection = administrative.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed: " + sql, e);
		}
	}
}
