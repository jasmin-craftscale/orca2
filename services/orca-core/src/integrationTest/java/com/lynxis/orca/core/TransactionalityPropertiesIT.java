package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.TeamTables.BreakTiming;
import com.lynxis.orca.core.domain.TemplateAdminService;
import com.lynxis.orca.core.persistence.BreakTemplateRepository;
import com.lynxis.orca.core.persistence.ShiftTemplateRepository;
import com.lynxis.orca.core.persistence.TeamRepository;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;

/**
 * <strong>Phase 2 review addendum · the {@code @Transactional} wiring, proven.</strong>
 *
 * <p>Every other suite constructs the domain services with {@code new}, which
 * is exactly the blind spot the corpus warns about: annotations are inert
 * without the container, so a green suite proves nothing about atomicity, and
 * a multi-statement replace that half-applied would be asserted nowhere. This
 * suite builds the services THROUGH a transaction-managing context — the same
 * proxying production gets — and proves the property that matters: a failure
 * midway through a multi-statement mutation leaves nothing behind.
 */
class TransactionalityPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String SITE = "SITE-IT";

	private static DataSource owner;
	private static AnnotationConfigApplicationContext context;

	private JdbcTemplate core;

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class TxConfig {

		@Bean
		DataSource dataSource() {
			return owner;
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new JdbcTransactionManager(dataSource);
		}

		@Bean
		ScopeSeam scopeSeam(DataSource dataSource) {
			return new JdbcScopeSeam(new JdbcTemplate(dataSource));
		}

		@Bean
		ShiftTemplateRepository shiftTemplateRepository(ScopeSeam seam) {
			return new ShiftTemplateRepository(seam);
		}

		@Bean
		BreakTemplateRepository breakTemplateRepository(ScopeSeam seam) {
			return new BreakTemplateRepository(seam);
		}

		@Bean
		TeamRepository teamRepository(ScopeSeam seam) {
			return new TeamRepository(seam);
		}

		@Bean
		TemplateAdminService templateAdminService(ShiftTemplateRepository shiftTemplates,
				BreakTemplateRepository breakTemplates, TeamRepository teams) {
			return new TemplateAdminService(shiftTemplates, breakTemplates, teams);
		}
	}

	@BeforeAll
	static void migrateAndBuildContext() {
		PlatformDatabase.consumerLogin("orca_runtime");
		PlatformDatabase.consumerLogin("orca_edge");
		owner = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease");
		context = new AnnotationConfigApplicationContext(TxConfig.class);
	}

	@AfterAll
	static void closeContext() {
		if (context != null) {
			context.close();
		}
	}

	@BeforeEach
	void freshWorld() {
		core = new JdbcTemplate(owner);
		core.execute("DELETE FROM team_member");
		core.execute("DELETE FROM team");
		core.execute("DELETE FROM break_timing");
		core.execute("DELETE FROM break_template");
		core.execute("DELETE FROM shift_template");
		core.execute("DELETE FROM site");
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES (?, 'IT', 'IT', 1)", SITE);
	}

	@Test
	@DisplayName("the container proxies the service — a failure mid-replace rolls back the template AND its first timing")
	void aMidMutationFailureLeavesNothing() {
		TemplateAdminService service = context.getBean(TemplateAdminService.class);
		assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service))
				.as("the bean under test is the proxy production gets, not a bare new")
				.isTrue();

		// The second timing violates ck_break_timing_duration INSIDE the
		// transaction, after the template row and the first timing were written.
		// Duration 0 passes the request-shape checks (they test distinctness,
		// not range — range is the database's) so the failure lands mid-write,
		// which is the point.
		assertThatThrownBy(() -> ScopeContext.callIn(CoreScopes.installation(SITE), () ->
				service.createBreakTemplate("Doomed", null, List.of(
						timing("12:00", 30),
						timing("15:00", 0)))))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(core.queryForObject("SELECT COUNT(*) FROM break_template", Long.class))
				.as("the template row written before the failure must not survive it")
				.isZero();
		assertThat(core.queryForObject("SELECT COUNT(*) FROM break_timing", Long.class))
				.as("nor the first timing — killing the operation between the writes leaves neither")
				.isZero();
	}

	@Test
	@DisplayName("and the commit path through the same proxy persists the whole set")
	void theCommitPathPersists() {
		TemplateAdminService service = context.getBean(TemplateAdminService.class);

		ScopeContext.runIn(CoreScopes.installation(SITE), () ->
				service.createBreakTemplate("Standard", "Two breaks", List.of(
						timing("12:00", 30),
						timing("15:30", 15))));

		assertThat(core.queryForObject("SELECT COUNT(*) FROM break_template", Long.class)).isEqualTo(1);
		assertThat(core.queryForObject(
				"SELECT COUNT(*) FROM break_timing WHERE retired_at IS NULL", Long.class)).isEqualTo(2);
	}

	private static BreakTiming timing(String start, int minutes) {
		return new BreakTiming(0, 0, null, java.time.LocalTime.parse(start), minutes, null, null);
	}
}
