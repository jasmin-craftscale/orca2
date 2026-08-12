package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.lynxis.orca.core.api.CustomEntityController;
import com.lynxis.orca.core.api.generated.model.AddCustomEntityFieldRequest;
import com.lynxis.orca.core.api.generated.model.CustomEntityFieldType;
import com.lynxis.orca.core.api.generated.model.CustomEntityKind;
import com.lynxis.orca.core.api.generated.model.CustomEntitySummary;
import com.lynxis.orca.core.api.generated.model.DeclareCustomEntityFieldRequest;
import com.lynxis.orca.core.api.generated.model.DeclareCustomEntityRequest;
import com.lynxis.orca.core.api.generated.model.EvolveCustomEntityDeclarationRequest;
import com.lynxis.orca.core.domain.AuditTrail;
import com.lynxis.orca.core.domain.CallerIdentity;
import com.lynxis.orca.core.domain.CustomEntityService;
import com.lynxis.orca.core.persistence.AuditEventRepository;
import com.lynxis.orca.core.persistence.CustomEntityRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiExceptionHandler;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;

/** Properties of the declared custom-entity model against real SQL Server. */
class CustomEntityPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String SITE_A = "SITE-CE-A";
	private static final String SITE_B = "SITE-CE-B";

	private static DataSource owner;
	private static DataSource asRuntime;
	private static AnnotationConfigApplicationContext context;

	private JdbcTemplate core;
	private CustomEntityController apiA;

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
		CustomEntityRepository customEntityRepository(ScopeSeam seam) {
			return new CustomEntityRepository(seam);
		}

		@Bean
		SiteDirectoryRepository siteDirectoryRepository(ScopeSeam seam) {
			return new SiteDirectoryRepository(seam);
		}

		@Bean
		UserAccountRepository userAccountRepository(ScopeSeam seam) {
			return new UserAccountRepository(seam);
		}

		@Bean
		AuditEventRepository auditEventRepository(ScopeSeam seam) {
			return new AuditEventRepository(seam);
		}

		@Bean
		CallerIdentity callerIdentity() {
			return Optional::empty;
		}

		@Bean
		AuditTrail auditTrail(AuditEventRepository events, UserAccountRepository users,
				CallerIdentity caller) {
			return new AuditTrail(events, users, caller, SITE_A);
		}

		@Bean
		CustomEntityService customEntityService(CustomEntityRepository entities,
				SiteDirectoryRepository sites, AuditTrail audit) {
			return new CustomEntityService(entities, sites, audit);
		}
	}

	@BeforeAll
	static void migrateAndBuildContext() {
		asRuntime = PlatformDatabase.consumerLogin("orca_runtime");
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
	void freshSitesAndApi() {
		core = new JdbcTemplate(owner);
		core.execute("DELETE FROM audit_event");
		core.execute("DELETE FROM custom_entity_field");
		core.execute("DELETE FROM custom_entity");
		core.execute("DELETE FROM site");
		core.update("INSERT INTO site (external_id, code, name, is_primary) "
				+ "VALUES (?, 'CEA', 'Custom Entity A', 1)", SITE_A);
		core.update("INSERT INTO site (external_id, code, name, is_primary) "
				+ "VALUES (?, 'CEB', 'Custom Entity B', 0)", SITE_B);
		apiA = new CustomEntityController(context.getBean(CustomEntityService.class), SITE_A);
	}

	@Test
	@DisplayName("a declaration and additive evolution round-trip every closed field shape without creating a table")
	void declarationRoundTripAndAdditiveEvolution() {
		CustomEntitySummary declared = apiA.declareCustomEntity(new DeclareCustomEntityRequest()
				.name("Vehicle class")
				.kind(CustomEntityKind.REFERENCE)
				.fields(List.of(
						text("code", "Code", 40, false, true, 1),
						number("weight", "Weight", 12, 3, true, false, 2),
						plain("active", "Active", CustomEntityFieldType.BOOLEAN, true, false, 3),
						plain("effective_date", "Effective date", CustomEntityFieldType.DATE,
								true, false, 4))))
				.getBody().getData();

		assertThat(declared.getExternalId()).startsWith("ce-");
		assertThat(declared.getTableIdentifier()).matches("ce_[0-9a-f]{32}");
		assertThat(declared.getRowIdColumn()).isEqualTo("row_id");
		assertThat(declared.getRowExternalIdColumn()).isEqualTo("external_id");
		assertThat(declared.getRowSiteExternalIdColumn()).isEqualTo("site_external_id");
		assertThat(declared.getDeclarationVersion()).isEqualTo(1);
		assertThat(declared.getFields()).extracting(field -> field.getType().getValue())
				.containsExactly("TEXT", "NUMBER", "BOOLEAN", "DATE");
		assertThat(core.queryForObject("SELECT OBJECT_ID(?, 'U')",
				Long.class, declared.getTableIdentifier()))
				.as("a reserved identifier is metadata, not an applied table")
				.isNull();

		CustomEntitySummary evolved = apiA.evolveCustomEntityDeclaration(declared.getExternalId(),
				new EvolveCustomEntityDeclarationRequest()
						.name("Vehicle category")
						.addFields(List.of(new AddCustomEntityFieldRequest()
								.identifier("notes").displayName("Notes")
								.type(CustomEntityFieldType.TEXT).maxLength(4000)
								.nullable(true).ordinal(5))))
				.getBody().getData();

		assertThat(evolved.getName()).isEqualTo("Vehicle category");
		assertThat(evolved.getTableIdentifier()).isEqualTo(declared.getTableIdentifier());
		assertThat(evolved.getDeclarationVersion()).isEqualTo(2);
		assertThat(evolved.getFields()).extracting(field -> field.getIdentifier())
				.containsExactly("code", "weight", "active", "effective_date", "notes");
		assertThat(apiA.listCustomEntities().getBody().getData()).singleElement()
				.extracting(CustomEntitySummary::getName).isEqualTo("Vehicle category");
		assertThat(core.query(
				"SELECT action, entity_external_id FROM audit_event ORDER BY audit_event_id",
				(rs, row) -> rs.getString("action") + ":" + rs.getString("entity_external_id")))
				.as("declaration and evolution are configuration mutations")
				.containsExactly("CREATED:" + declared.getExternalId(),
						"UPDATED:" + declared.getExternalId());
	}

	@Test
	@DisplayName("identifier, business-key and type-shape faults are typed 422 refusals before storage")
	void invalidDeclarationsAreTyped() {
		assertInvalid(text("UpperCase", "Bad", 20, false, true, 1));
		assertInvalid(text("row_id", "Reserved", 20, false, true, 1));
		assertInvalid(text("site_external_id", "Reserved scope", 20, false, true, 1));
		assertInvalid(new DeclareCustomEntityFieldRequest()
				.identifier("amount").displayName("Amount").type(CustomEntityFieldType.NUMBER)
				.precision(4).scale(5).nullable(false).businessKey(true).ordinal(1));
		assertInvalid(text("code", "Code", 20, true, true, 1));

		ApiException noKey = catchThrowableOfType(() -> apiA.declareCustomEntity(new DeclareCustomEntityRequest()
				.name("No key").kind(CustomEntityKind.EVENT)
				.fields(List.of(text("value", "Value", 20, false, false, 1)))), ApiException.class);
		assertTypedInvalidEnvelope(noKey);
		assertThat(core.queryForObject("SELECT COUNT(*) FROM custom_entity", Long.class)).isZero();
	}

	@Test
	@DisplayName("the database independently refuses unsafe identifiers and impossible field shapes")
	void databaseChecksBackstopTheBoundary() {
		long entityId = directEntity("ce-direct", "Direct", "ce_11111111111111111111111111111111");

		assertThatThrownBy(() -> directField(entityId, "cef-upper", "Upper", "TEXT",
				20, null, null, false, true, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-reserved", "external_id", "TEXT",
				20, null, null, false, true, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-scope", "site_external_id", "TEXT",
				20, null, null, false, true, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-shape", "amount", "NUMBER",
				null, 3, 4, false, true, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-null-key", "code", "TEXT",
				20, null, null, true, true, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> directEntity("ce-kind", "Wrong kind",
				"ce_22222222222222222222222222222222", "reference"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("every external id, entity name, table id, field name, identifier, ordinal and business key is unique")
	void databaseUniquenessRulesHold() {
		long entityId = directEntity("ce-one", "One", "ce_11111111111111111111111111111111");
		directField(entityId, "cef-one", "code", "Code", "TEXT",
				20, null, null, false, true, 1);

		assertThatThrownBy(() -> directEntity("ce-one", "Two",
				"ce_22222222222222222222222222222222")).isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> directEntity("ce-two", "One",
				"ce_22222222222222222222222222222222")).isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> directEntity("ce-two", "Two",
				"ce_11111111111111111111111111111111")).isInstanceOf(DuplicateKeyException.class);

		assertThatThrownBy(() -> directField(entityId, "cef-one", "description", "Description", "TEXT",
				20, null, null, true, false, 2)).isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-two", "code", "Other code", "TEXT",
				20, null, null, true, false, 2)).isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-three", "description", "Description", "TEXT",
				20, null, null, true, false, 1)).isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-four", "second_key", "Second key", "TEXT",
				20, null, null, false, true, 2)).isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> directField(entityId, "cef-five", "label", "code", "TEXT",
				20, null, null, true, false, 2)).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@DisplayName("site scope prevents cross-site list, read and mutation of declarations")
	void crossSiteAccessIsAbsent() {
		CustomEntitySummary a = apiA.declareCustomEntity(declaration("A model", "a_code"))
				.getBody().getData();
		core.update("INSERT INTO custom_entity "
				+ "(external_id, site_external_id, entity_kind, name, table_identifier) "
				+ "VALUES ('ce-b', ?, 'REFERENCE', 'B model', "
				+ "'ce_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb')", SITE_B);
		long bId = core.queryForObject(
				"SELECT custom_entity_id FROM custom_entity WHERE external_id = 'ce-b'", Long.class);
		core.update("INSERT INTO custom_entity_field "
				+ "(external_id, custom_entity_id, site_external_id, identifier, display_name, "
				+ "field_type, max_length, is_nullable, is_business_key, ordinal) "
				+ "VALUES ('cef-b', ?, ?, 'b_code', 'B code', 'TEXT', 32, 0, 1, 1)", bId, SITE_B);

		assertThat(apiA.listCustomEntities().getBody().getData())
				.extracting(CustomEntitySummary::getExternalId).containsExactly(a.getExternalId());
		assertThatThrownBy(() -> apiA.evolveCustomEntityDeclaration("ce-b",
				new EvolveCustomEntityDeclarationRequest().name("Stolen")))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));
		assertThat(core.queryForObject(
				"SELECT name FROM custom_entity WHERE external_id = 'ce-b'", String.class))
				.isEqualTo("B model");
	}

	@Test
	@DisplayName("runtime reads the declaration view but cannot read tables or write the view")
	void runtimeGetsOnlyThePublishedReadOnlyView() {
		CustomEntitySummary declared = apiA.declareCustomEntity(declaration("Runtime model", "code"))
				.getBody().getData();
		JdbcTemplate runtime = new JdbcTemplate(asRuntime);

		var row = runtime.queryForMap(
				"SELECT * FROM core.topology_custom_entity WHERE custom_entity_external_id = ?",
				declared.getExternalId());
		assertThat(row).containsEntry("site_external_id", SITE_A)
				.containsEntry("table_identifier", declared.getTableIdentifier())
				.containsEntry("row_id_column", "row_id")
				.containsEntry("row_external_id_column", "external_id")
				.containsEntry("row_site_external_id_column", "site_external_id")
				.containsEntry("field_identifier", "code");

		assertThatThrownBy(() -> runtime.queryForObject(
				"SELECT COUNT(*) FROM core.custom_entity", Long.class))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("permission was denied");
		assertThatThrownBy(() -> runtime.update(
				"UPDATE core.topology_custom_entity SET custom_entity_name = 'Changed'"))
				.isInstanceOf(DataAccessException.class);

		core.update("UPDATE site SET retired_at = SYSUTCDATETIME() WHERE external_id = ?", SITE_A);
		assertThat(runtime.queryForObject(
				"SELECT COUNT(*) FROM core.topology_custom_entity", Long.class))
				.as("published topology views hide a retired site's configuration by convention")
				.isZero();
		assertThat(apiA.listCustomEntities().getBody().getData())
				.as("the owning API reads the same one-snapshot declared topology")
				.isEmpty();
		assertThatThrownBy(() -> apiA.evolveCustomEntityDeclaration(declared.getExternalId(),
				new EvolveCustomEntityDeclarationRequest().name("Retired mutation")))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));
	}

	@Test
	@DisplayName("a database failure after the parent and first field leaves neither behind")
	void midDeclarationFailureRollsBackEverything() {
		CustomEntityService service = context.getBean(CustomEntityService.class);
		assertThat(AopUtils.isAopProxy(service)).isTrue();
		core.execute("ALTER TABLE custom_entity_field ADD CONSTRAINT ck_custom_entity_field_test_poison "
				+ "CHECK (identifier <> 'poison')");
		try {
			assertThatThrownBy(() -> apiA.declareCustomEntity(new DeclareCustomEntityRequest()
					.name("Doomed").kind(CustomEntityKind.EVENT)
					.fields(List.of(
							text("code", "Code", 20, false, true, 1),
							text("poison", "Poison", 20, true, false, 2)))))
					.as("the second field fails after the parent and first field were written")
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		finally {
			core.execute("ALTER TABLE custom_entity_field DROP CONSTRAINT ck_custom_entity_field_test_poison");
		}

		assertThat(core.queryForObject("SELECT COUNT(*) FROM custom_entity", Long.class)).isZero();
		assertThat(core.queryForObject("SELECT COUNT(*) FROM custom_entity_field", Long.class)).isZero();
		assertThat(core.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
	}

	@Test
	@DisplayName("an audit failure rolls back the declaration, so mutation and audit cannot diverge")
	void auditFailureRollsBackDeclaration() {
		core.execute("ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_test_poison "
				+ "CHECK (entity_type <> 'CUSTOM_ENTITY')");
		try {
			assertThatThrownBy(() -> apiA.declareCustomEntity(declaration("Unaudited", "code")))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		finally {
			core.execute("ALTER TABLE audit_event DROP CONSTRAINT ck_audit_event_test_poison");
		}

		assertThat(core.queryForObject("SELECT COUNT(*) FROM custom_entity", Long.class)).isZero();
		assertThat(core.queryForObject("SELECT COUNT(*) FROM custom_entity_field", Long.class)).isZero();
		assertThat(core.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
	}

	private void assertInvalid(DeclareCustomEntityFieldRequest field) {
		ApiException refusal = catchThrowableOfType(() -> apiA.declareCustomEntity(
				new DeclareCustomEntityRequest().name("Invalid")
						.kind(CustomEntityKind.REFERENCE).fields(List.of(field))), ApiException.class);
		assertTypedInvalidEnvelope(refusal);
	}

	private static void assertTypedInvalidEnvelope(ApiException refusal) {
		var response = new ApiExceptionHandler().handleApi(refusal);
		assertThat(response.getStatusCode().value()).isEqualTo(422);
		ApiResponse<Void> body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.status()).isEqualTo(ApiStatus.ERROR);
		assertThat(body.code()).isEqualTo("CUSTOM_ENTITY_DECLARATION_INVALID");
		assertThat(body.data()).isNull();
	}

	private static DeclareCustomEntityRequest declaration(String name, String identifier) {
		return new DeclareCustomEntityRequest().name(name).kind(CustomEntityKind.REFERENCE)
				.fields(List.of(text(identifier, "Code", 32, false, true, 1)));
	}

	private static DeclareCustomEntityFieldRequest text(String identifier, String displayName,
			int maxLength, boolean nullable, boolean businessKey, int ordinal) {
		return new DeclareCustomEntityFieldRequest().identifier(identifier).displayName(displayName)
				.type(CustomEntityFieldType.TEXT).maxLength(maxLength).nullable(nullable)
				.businessKey(businessKey).ordinal(ordinal);
	}

	private static DeclareCustomEntityFieldRequest number(String identifier, String displayName,
			int precision, int scale, boolean nullable, boolean businessKey, int ordinal) {
		return new DeclareCustomEntityFieldRequest().identifier(identifier).displayName(displayName)
				.type(CustomEntityFieldType.NUMBER).precision(precision).scale(scale).nullable(nullable)
				.businessKey(businessKey).ordinal(ordinal);
	}

	private static DeclareCustomEntityFieldRequest plain(String identifier, String displayName,
			CustomEntityFieldType type, boolean nullable, boolean businessKey, int ordinal) {
		return new DeclareCustomEntityFieldRequest().identifier(identifier).displayName(displayName)
				.type(type).nullable(nullable).businessKey(businessKey).ordinal(ordinal);
	}

	private long directEntity(String externalId, String name, String tableIdentifier) {
		return directEntity(externalId, name, tableIdentifier, "REFERENCE");
	}

	private long directEntity(String externalId, String name, String tableIdentifier, String kind) {
		core.update("INSERT INTO custom_entity "
				+ "(external_id, site_external_id, entity_kind, name, table_identifier) "
				+ "VALUES (?, ?, ?, ?, ?)", externalId, SITE_A, kind, name, tableIdentifier);
		return core.queryForObject("SELECT custom_entity_id FROM custom_entity WHERE external_id = ?",
				Long.class, externalId);
	}

	private void directField(long entityId, String externalId, String identifier, String type,
			Integer maxLength, Integer precision, Integer scale, boolean nullable,
			boolean businessKey, int ordinal) {
		directField(entityId, externalId, identifier, "Field", type, maxLength, precision,
				scale, nullable, businessKey, ordinal);
	}

	private void directField(long entityId, String externalId, String identifier, String displayName,
			String type, Integer maxLength, Integer precision, Integer scale, boolean nullable,
			boolean businessKey, int ordinal) {
		core.update("INSERT INTO custom_entity_field "
				+ "(external_id, custom_entity_id, site_external_id, identifier, display_name, "
				+ "field_type, max_length, number_precision, number_scale, is_nullable, "
				+ "is_business_key, ordinal) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				externalId, entityId, SITE_A, identifier, displayName, type, maxLength, precision, scale,
				nullable, businessKey, ordinal);
	}
}
