package com.lynxis.orca.runtime.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.secrets.SealedSecret;
import com.lynxis.orca.platform.secrets.SecretBox;
import com.lynxis.orca.platform.secrets.SecretOpenException;
import com.lynxis.orca.runtime.integration.api.ConnectorPort.ConnectorCall;
import com.lynxis.orca.runtime.integration.api.ConnectorPort.ConnectorUnavailableException;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialAudit.Action;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialException.InvalidState;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialException.StaleVersion;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.Clear;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.Preserve;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.Replace;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.SetBasic;
import com.lynxis.orca.runtime.integration.persistence.ConnectorConfigRepository;
import com.lynxis.orca.runtime.integration.persistence.ConnectorCredentialRepository;
import com.lynxis.orca.runtime.persistence.Utc;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Real-SQL executable specification for credential state, concurrency, transport,
 * rotation, restore mismatch, and redaction.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@ExtendWith(OutputCaptureExtension.class)
class ConnectorCredentialPropertiesIT {

	private static final String SCHEMA = "runtime";
	private static final String SITE = "SITE-CREDENTIAL-IT";
	private static final String SENTINEL = "CRED-IT-SENTINEL-DO-NOT-USE";
	private static final String OLD_KEY = Base64.getEncoder().encodeToString(
			"credential-it-old-key-material-1".getBytes(StandardCharsets.UTF_8));
	private static final String NEW_KEY = Base64.getEncoder().encodeToString(
			"credential-it-new-key-material-2".getBytes(StandardCharsets.UTF_8));
	private static final Scope SITE_SCOPE = Scope.of("site_external_id", Set.of(SITE));

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;
	private static HttpServer receiver;
	private static ExecutorService receiverExecutor;
	private static Logger connectorLogger;
	private static Level connectorLogLevel;
	private static final List<String> receivedAuthorization = new CopyOnWriteArrayList<>();

	private ConnectorCredentialRepository repository;
	private ConnectorCredentialService service;
	private SecretBox oldCurrent;

	@BeforeAll
	static void migrateAndStartReceiver() throws IOException {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		jdbc = new JdbcTemplate(dataSource);
		receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		receiver.createContext("/connector", ConnectorCredentialPropertiesIT::receive);
		receiverExecutor = Executors.newVirtualThreadPerTaskExecutor();
		receiver.setExecutor(receiverExecutor);
		receiver.start();
		connectorLogger = (Logger) LoggerFactory.getLogger(RestConnector.class);
		connectorLogLevel = connectorLogger.getLevel();
		connectorLogger.setLevel(Level.TRACE);
	}

	@AfterAll
	static void stopReceiver() {
		if (receiver != null) {
			receiver.stop(0);
		}
		if (receiverExecutor != null) {
			receiverExecutor.close();
		}
		if (connectorLogger != null) {
			connectorLogger.setLevel(connectorLogLevel);
		}
	}

	@BeforeEach
	void freshState() {
		jdbc.execute("DELETE FROM connector_credential_audit");
		jdbc.execute("DELETE FROM connector_credential");
		jdbc.execute("DELETE FROM connector_route");
		jdbc.execute("DELETE FROM connector_config");
		receivedAuthorization.clear();

		oldCurrent = box("old-v1");
		repository = repository();
		service = service(repository, oldCurrent);
		insertConnector("tos");
	}

	@Test
	@DisplayName("V128 creates the bounded current row and append-only redacted audit with scoped keys")
	void migrationShapeIsExactAndScoped() {
		assertThat(columns("connector_credential")).containsExactly(
				"site_external_id", "connector_name", "auth_mode", "auth_principal",
				"secret_ciphertext", "secret_nonce", "key_id", "credential_version",
				"updated_at", "updated_by");
		assertThat(columns("connector_credential_audit")).containsExactly(
				"credential_audit_id", "site_external_id", "connector_name", "credential_version",
				"auth_mode", "action", "occurred_at", "actor");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM sys.foreign_keys
				WHERE parent_object_id = OBJECT_ID('runtime.connector_credential')
				  AND referenced_object_id = OBJECT_ID('runtime.connector_config')
				""", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForList("""
				SELECT c.name FROM sys.indexes i
				JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id
				JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id
				WHERE i.object_id=OBJECT_ID('runtime.connector_credential_audit')
				  AND i.name='ix_connector_credential_audit_scope_connector_time'
				ORDER BY ic.key_ordinal
				""", String.class)).containsExactly("site_external_id", "connector_name", "occurred_at");
		assertThat(jdbc.queryForObject("""
				SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS
				WHERE TABLE_SCHEMA='runtime' AND TABLE_NAME='connector_credential' AND COLUMN_NAME='auth_mode'
				""", String.class)).isEqualTo("Latin1_General_100_BIN2");
		assertThat(jdbc.queryForObject("""
				SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS
				WHERE TABLE_SCHEMA='runtime' AND TABLE_NAME='connector_credential' AND COLUMN_NAME='key_id'
				""", String.class)).isEqualTo("Latin1_General_100_BIN2");
		assertThat(jdbc.queryForList("""
				SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS
				WHERE TABLE_SCHEMA='runtime' AND TABLE_NAME='connector_credential_audit'
				  AND COLUMN_NAME IN ('auth_mode', 'action') ORDER BY COLUMN_NAME
				""", String.class)).containsExactly("Latin1_General_100_BIN2", "Latin1_General_100_BIN2");
	}

	@Test
	@DisplayName("SQL Server independently rejects every invalid credential state")
	void databaseConstraintsRejectInvalidStates() {
		assertDbRejects("basic", "user", "cipher", "nonce", "old-v1", 1, "actor", "tos");
		assertDbRejects("Basic", "user", "cipher", "nonce", "old-v1", 1, "actor", "tos");
		assertDbRejects("NONE", "user", null, null, null, 1, "actor", "tos");
		assertDbRejects("NONE", null, "cipher", null, null, 1, "actor", "tos");
		assertDbRejects("NONE", null, null, "nonce", null, 1, "actor", "tos");
		assertDbRejects("NONE", null, null, null, "old-v1", 1, "actor", "tos");
		assertDbRejects("BASIC", null, "cipher", "nonce", "old-v1", 1, "actor", "tos");
		assertDbRejects("BASIC", "user:name", "cipher", "nonce", "old-v1", 1, "actor", "tos");
		assertDbRejects("BASIC", "user", null, "nonce", "old-v1", 1, "actor", "tos");
		assertDbRejects("BASIC", "user", " ", "nonce", "old-v1", 1, "actor", "tos");
		assertDbRejects("BASIC", "user", "cipher", null, "old-v1", 1, "actor", "tos");
		assertDbRejects("BASIC", "user", "cipher", " ", "old-v1", 1, "actor", "tos");
		assertDbRejects("BASIC", "user", "cipher", "nonce", null, 1, "actor", "tos");
		assertDbRejects("BASIC", "user", "cipher", "nonce", " ", 1, "actor", "tos");
		assertDbRejects("BASIC", "user", "cipher", "nonce", "old-v1", 0, "actor", "tos");
		assertDbRejects("BASIC", "user", "cipher", "nonce", "old-v1", 1, "actor", "missing");
		assertAuditDbRejects("basic", "SET", 1, "actor");
		assertAuditDbRejects("Basic", "SET", 1, "actor");
		assertAuditDbRejects("BASIC", "set", 1, "actor");
		assertAuditDbRejects("BASIC", "Set", 1, "actor");
		assertAuditDbRejects("BASIC", "SET", 0, "actor");
	}

	@Test
	@DisplayName("SQL nonblank backstops reject spaces and control whitespace but permit internal spaces")
	void databaseNonblankChecksMatchTheApplicationBoundary() {
		for (String blank : List.of(
				" ", "\t", "\r", "\n", "\u000B", "\f",
				"\u001C", "\u001D", "\u001E", "\u001F")) {
			assertDbRejects("BASIC", blank, "cipher", "nonce", "old-v1", 1, "actor", "tos");
			assertDbRejects("BASIC", "user", "cipher", "nonce", "old-v1", 1, blank, "tos");
			assertAuditDbRejects("BASIC", "SET", 1, blank);
		}

		assertThat(jdbc.update("""
				INSERT INTO connector_credential
				(site_external_id, connector_name, auth_mode, auth_principal, secret_ciphertext,
				 secret_nonce, key_id, credential_version, updated_at, updated_by)
				VALUES (?, 'tos', 'BASIC', 'user name', 'cipher', 'nonce', 'old-v1', 1,
				 SYSUTCDATETIME(), 'review actor')
				""", SITE)).isEqualTo(1);
		assertThat(jdbc.update("""
				INSERT INTO connector_credential_audit
				(site_external_id, connector_name, credential_version, auth_mode, action, occurred_at, actor)
				VALUES (?, 'tos', 1, 'BASIC', 'SET', SYSUTCDATETIME(), 'audit actor')
				""", SITE)).isEqualTo(1);
	}

	@Test
	@DisplayName("Set, Preserve, Replace and Clear each increment once and retain the exact intended fields")
	void exactMutationSemantics() {
		CredentialMetadata absent = scoped(() -> service.metadata("tos"));
		assertThat(absent).isEqualTo(new CredentialMetadata(CredentialMode.NONE, false, 0, null, null));

		CredentialMetadata set = mutate(service, "tos", 0,
				new SetBasic("first-user", new Replace(SENTINEL)), "setter");
		ConnectorCredential first = credential("tos");
		assertThat(set.mode()).isEqualTo(CredentialMode.BASIC);
		assertThat(set.version()).isEqualTo(1);
		assertThat(first.sealedSecret().ciphertextBase64()).doesNotContain(SENTINEL);
		SealedSecret firstSeal = first.sealedSecret();

		CredentialMetadata preserved = mutate(service, "tos", 1,
				new SetBasic("renamed-user", new Preserve()), "preserver");
		ConnectorCredential second = credential("tos");
		assertThat(preserved.version()).isEqualTo(2);
		assertThat(second.principal()).isEqualTo("renamed-user");
		assertThat(second.sealedSecret()).isEqualTo(firstSeal);

		CredentialMetadata replaced = mutate(service, "tos", 2,
				new SetBasic("renamed-user", new Replace(SENTINEL + "-2")), "replacer");
		ConnectorCredential third = credential("tos");
		assertThat(replaced.version()).isEqualTo(3);
		assertThat(third.sealedSecret()).isNotEqualTo(firstSeal);

		CredentialMetadata cleared = mutate(service, "tos", 3, new Clear(), "clearer");
		assertThat(cleared).extracting(CredentialMetadata::mode,
				CredentialMetadata::configured, CredentialMetadata::version)
				.containsExactly(CredentialMode.NONE, false, 4L);
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT * FROM connector_credential WHERE site_external_id=? AND connector_name='tos'", SITE);
		assertThat(row.get("auth_principal")).isNull();
		assertThat(row.get("secret_ciphertext")).isNull();
		assertThat(row.get("secret_nonce")).isNull();
		assertThat(row.get("key_id")).isNull();

		CredentialMetadata setAfterClear = mutate(service, "tos", 4,
				new SetBasic("restored-user", new Replace(SENTINEL + "-restored")), "restorer");
		assertThat(setAfterClear).extracting(CredentialMetadata::mode,
				CredentialMetadata::configured, CredentialMetadata::version)
				.containsExactly(CredentialMode.BASIC, true, 5L);
		assertThat(audits("tos")).extracting(ConnectorCredentialAudit::action)
				.containsExactly(Action.SET, Action.REPLACE, Action.REPLACE, Action.CLEAR, Action.SET);
	}

	@Test
	@DisplayName("stale and no-op commands change neither current state nor audit")
	void staleAndNoOpCommandsLeaveNoTrace() {
		assertThatThrownBy(() -> mutate(service, "tos", 0, new Clear(), "actor"))
				.isInstanceOf(InvalidState.class);
		assertThat(count("connector_credential")).isZero();
		assertThat(count("connector_credential_audit")).isZero();

		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		assertThatThrownBy(() -> mutate(service, "tos", 1,
				new SetBasic("user", new Preserve()), "actor"))
				.isInstanceOf(InvalidState.class);
		assertThatThrownBy(() -> mutate(service, "tos", 0,
				new SetBasic("user", new Replace("stale")), "actor"))
				.isInstanceOf(StaleVersion.class)
				.hasMessage("Connector credential version conflict.");
		assertThat(credential("tos").version()).isEqualTo(1);
		assertThat(audits("tos")).hasSize(1);

		mutate(service, "tos", 1, new Clear(), "actor");
		assertThatThrownBy(() -> mutate(service, "tos", 2, new Clear(), "actor"))
				.isInstanceOf(InvalidState.class);
		assertThat(credential("tos").version()).isEqualTo(2);
		assertThat(audits("tos")).hasSize(2);
	}

	@Test
	@DisplayName("principal, actor and connector validation refuses values before SQL and stays redacted")
	void applicationValidationIsBoundedAndRedacted() {
		String rejected = "value-that-must-not-be-echoed";
		assertThatThrownBy(() -> mutate(service, "tos", 0,
				new SetBasic("user:name", new Replace(SENTINEL)), "actor"))
				.isInstanceOf(InvalidState.class).hasMessageNotContaining("user:name")
				.hasMessageNotContaining(SENTINEL);
		assertThatThrownBy(() -> mutate(service, "tos", 0,
				new SetBasic(" ", new Replace(SENTINEL)), "actor"))
				.isInstanceOf(InvalidState.class).hasMessageNotContaining(SENTINEL);
		assertThatThrownBy(() -> mutate(service, "tos", 0,
				new SetBasic(rejected.repeat(10), new Replace(SENTINEL)), "actor"))
				.isInstanceOf(InvalidState.class).hasMessageNotContaining(rejected)
				.hasMessageNotContaining(SENTINEL);
		assertThatThrownBy(() -> mutate(service, "tos", 0,
				new SetBasic("user", new Replace(SENTINEL)), rejected.repeat(10)))
				.isInstanceOf(InvalidState.class).hasMessageNotContaining(rejected)
				.hasMessageNotContaining(SENTINEL);
		assertThatThrownBy(() -> mutate(service, "tos", 0,
				new SetBasic("user", new Replace(SENTINEL)), " "))
				.isInstanceOf(InvalidState.class).hasMessageNotContaining(SENTINEL);
		assertThatThrownBy(() -> mutate(service, rejected.repeat(10), 0,
				new Clear(), "actor"))
				.isInstanceOf(InvalidState.class).hasMessageNotContaining(rejected);
		assertThatThrownBy(() -> mutate(service, "missing", 0,
				new SetBasic("user", new Replace(SENTINEL)), "actor"))
				.isInstanceOf(ConnectorCredentialException.NotFound.class)
				.hasMessage("Connector does not exist at this installation.")
				.hasMessageNotContaining("missing").hasMessageNotContaining(SENTINEL);
		assertThat(count("connector_credential")).isZero();
	}

	@Test
	@DisplayName("a disabled connector can be provisioned before it is enabled")
	void disabledConnectorCanBeProvisioned() {
		jdbc.update("UPDATE connector_config SET is_enabled=0 WHERE connector_name='tos'");
		CredentialMetadata metadata = mutate(service, "tos", 0,
				new SetBasic("user", new Replace(SENTINEL)), "actor");
		assertThat(metadata.configured()).isTrue();
		assertThat(metadata.version()).isEqualTo(1);
	}

	@Test
	@DisplayName("current state and redacted audit commit together or neither commits")
	void poisonedAuditRollsBackCurrentState() {
		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		jdbc.execute("ALTER TABLE connector_credential_audit ADD CONSTRAINT ck_it_no_replace CHECK (action <> 'REPLACE')");
		try {
			assertThatThrownBy(() -> mutate(service, "tos", 1,
					new SetBasic("user", new Replace(SENTINEL + "-new")), "actor"))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThat(credential("tos").version()).isEqualTo(1);
			assertThat(audits("tos")).extracting(ConnectorCredentialAudit::action)
					.containsExactly(Action.SET);
		}
		finally {
			jdbc.execute("ALTER TABLE connector_credential_audit DROP CONSTRAINT ck_it_no_replace");
		}
		mutate(service, "tos", 1,
				new SetBasic("user", new Replace(SENTINEL + "-new")), "actor");
		assertThat(credential("tos").version()).isEqualTo(2);
		assertThat(audits("tos")).extracting(ConnectorCredentialAudit::action)
				.containsExactly(Action.SET, Action.REPLACE);
	}

	@Test
	@DisplayName("known plaintext and sealed material never enter the audit or metadata surface")
	void storageAuditAndMetadataAreRedacted() throws Exception {
		CredentialMetadata metadata = mutate(service, "tos", 0,
				new SetBasic("principal-must-also-stay-private", new Replace(SENTINEL)), "actor");
		Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM connector_credential");
		assertThat(stored.get("secret_ciphertext").toString()).doesNotContain(SENTINEL);
		assertThat(stored.values().stream().map(String::valueOf).toList()).noneMatch(SENTINEL::equals);
		ConnectorCredential credential = credential("tos");
		assertThat(credential.toString())
				.doesNotContain(SENTINEL, credential.principal(), credential.sealedSecret().keyId(),
						credential.sealedSecret().nonceBase64(), credential.sealedSecret().ciphertextBase64());
		assertThat(new Replace(SENTINEL).toString()).doesNotContain(SENTINEL);
		assertThat(new SetBasic("principal-must-also-stay-private", new Replace(SENTINEL)).toString())
				.doesNotContain(SENTINEL, "principal-must-also-stay-private");
		assertThat(columns("connector_credential_audit"))
				.noneMatch(name -> name.contains("principal") || name.contains("secret") || name.contains("key"));
		Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM connector_credential_audit");
		assertThat(audit.values().stream().map(String::valueOf).toList())
				.noneMatch(value -> value.contains(SENTINEL) || value.contains("principal-must"));

		CredentialMetadata serializable = new CredentialMetadata(metadata.mode(), metadata.configured(),
				metadata.version(), null, metadata.changedBy());
		String json = new ObjectMapper().writeValueAsString(serializable);
		assertThat(json).contains("\"configured\":true", "\"version\":1")
				.doesNotContain("principal", "sealed", "cipher", "nonce", "keyId", SENTINEL);
		assertThat(CredentialMetadata.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("mode", "configured", "version", "changedAt", "changedBy");
	}

	@Test
	@DisplayName("a copied sealed blob cannot open under another connector row's purpose")
	void copiedBlobFailsAtDestinationPurpose() {
		insertConnector("other");
		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		ConnectorCredential source = credential("tos");
		assertThatThrownBy(() -> oldCurrent.open(source.sealedSecret(),
				ConnectorCredential.purpose(SITE, "other")))
				.isInstanceOf(SecretOpenException.class).hasMessageNotContaining(SENTINEL)
				.hasMessageNotContaining("tos").hasMessageNotContaining("other");
	}

	@Test
	@DisplayName("two concurrent first writes serialize on the parent row and one expected-version zero wins")
	void concurrentFirstWritesHaveOneWinner() throws Exception {
		List<Object> outcomes = race(
				() -> mutate(service, "tos", 0, new SetBasic("user-a", new Replace("password-a")), "actor-a"),
				() -> mutate(service, "tos", 0, new SetBasic("user-b", new Replace("password-b")), "actor-b"));
		assertThat(outcomes.stream().filter(CredentialMetadata.class::isInstance)).hasSize(1);
		assertThat(outcomes.stream().filter(StaleVersion.class::isInstance)).hasSize(1);
		assertThat(credential("tos").version()).isEqualTo(1);
		assertThat(audits("tos")).hasSize(1);
	}

	@Test
	@DisplayName("two concurrent replacements from one version cannot lose an update")
	void concurrentReplacementsHaveOneWinner() throws Exception {
		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		List<Object> outcomes = race(
				() -> mutate(service, "tos", 1, new SetBasic("user", new Replace("password-a")), "actor-a"),
				() -> mutate(service, "tos", 1, new SetBasic("user", new Replace("password-b")), "actor-b"));
		assertThat(outcomes.stream().filter(CredentialMetadata.class::isInstance)).hasSize(1);
		assertThat(outcomes.stream().filter(StaleVersion.class::isInstance)).hasSize(1);
		assertThat(credential("tos").version()).isEqualTo(2);
		assertThat(audits("tos")).hasSize(2);
	}

	@Test
	@DisplayName("the repository update itself refuses a stale expected version")
	void guardedUpdatePredicateCannotBeRemoved() {
		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		ConnectorCredential current = credential("tos");
		ConnectorCredential attempted = new ConnectorCredential(SITE, "tos", CredentialMode.BASIC,
				"other-user", current.sealedSecret(), 2, Instant.now(), "other-actor");
		assertThat(scoped(() -> repository.update(attempted, 0))).isZero();
		assertThat(credential("tos")).extracting(ConnectorCredential::principal, ConnectorCredential::version)
				.containsExactly("user", 1L);
	}

	@Test
	@DisplayName("locking one connector does not globally serialize a different connector")
	void differentConnectorsDoNotShareALock() throws Exception {
		insertConnector("other");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> scoped(() -> {
				transaction().executeWithoutResult(status -> {
					assertThat(repository.lockConnector("tos")).isPresent();
					locked.countDown();
					await(release);
				});
				return null;
			}), executor);
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<CredentialMetadata> other = CompletableFuture.supplyAsync(() ->
					mutate(service, "other", 0,
							new SetBasic("user", new Replace(SENTINEL)), "actor"), executor);
			assertThat(other.get(3, TimeUnit.SECONDS).version()).isEqualTo(1);
			release.countDown();
			holder.get(3, TimeUnit.SECONDS);
		}
		finally {
			release.countDown();
		}
	}

	@Test
	@DisplayName("absence and NONE send no Authorization while BASIC is applied exactly per request")
	void connectorAuthenticationModesReachTheReceiverExactly(CapturedOutput output) {
		RestConnector connector = connector(repository, oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThat(call(connector, "tos")).isEqualTo("APPROVED");
		assertThat(receivedAuthorization).containsExactly("<absent>");

		mutate(service, "tos", 0, new SetBasic("üser", new Replace(SENTINEL)), "actor");
		assertThat(call(connector, "tos")).isEqualTo("APPROVED");
		String expectedAuthorization = basic("üser", SENTINEL);
		assertThat(receivedAuthorization.getLast()).isEqualTo(expectedAuthorization);

		mutate(service, "tos", 1, new Clear(), "actor");
		assertThat(call(connector, "tos")).isEqualTo("APPROVED");
		assertThat(receivedAuthorization.getLast()).isEqualTo("<absent>");
		assertThat(output.getAll()).doesNotContain(SENTINEL, expectedAuthorization);
	}

	@Test
	@DisplayName("a case-insensitive connector lookup stores its parent spelling for call and rewrap AAD")
	void connectorIdentityUsesTheCanonicalParentSpelling() {
		jdbc.execute("DELETE FROM connector_route");
		jdbc.execute("DELETE FROM connector_config");
		insertConnector(SITE, "TOS");

		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		ConnectorCredential stored = credential("tos");
		assertThat(stored.connectorName()).isEqualTo("TOS");
		assertThat(oldCurrent.open(stored.sealedSecret(),
				ConnectorCredential.purpose(SITE, "TOS"))).isEqualTo(SENTINEL);

		RestConnector beforeRewrap = connector(repository, oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThat(call(beforeRewrap, "tos")).isEqualTo("APPROVED");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("user", SENTINEL));

		SecretBox newCurrent = box("new-v2");
		CredentialRewrapService rewrap = new CredentialRewrapService(
				repository, newCurrent, transaction(), SITE);
		assertThat(scoped(() -> rewrap.rewrapBatch(10, "rewrapper")))
				.isEqualTo(new CredentialRewrapService.RewrapResult(1, 1, 0));
		ConnectorCredential rotated = credential("tos");
		assertThat(rotated.connectorName()).isEqualTo("TOS");
		assertThat(rotated.sealedSecret().keyId()).isEqualTo("new-v2");
		assertThat(newCurrent.open(rotated.sealedSecret(),
				ConnectorCredential.purpose(SITE, "TOS"))).isEqualTo(SENTINEL);
		RestConnector afterRewrap = connector(repository, newCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThat(call(afterRewrap, "tos")).isEqualTo("APPROVED");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("user", SENTINEL));
	}

	@Test
	@DisplayName("site spelling canonicalizes new rows and preserves an existing row's exact AAD identity")
	void installationIdentityUsesTheCanonicalParentSpelling() {
		String storedSite = "Site-Credential-It";
		jdbc.execute("DELETE FROM connector_route");
		jdbc.execute("DELETE FROM connector_config");
		insertConnector(storedSite, "tos");

		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		ConnectorCredential stored = credential("tos");
		assertThat(stored.siteExternalId()).isEqualTo(storedSite);

		RestConnector beforeRewrap = connector(repository, oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThat(call(beforeRewrap, "tos")).isEqualTo("APPROVED");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("user", SENTINEL));

		// Simulate a row written before canonical-parent handling: its FK matches
		// case-insensitively, and its sealed purpose used the child row's spelling.
		jdbc.execute("DELETE FROM connector_credential_audit");
		jdbc.execute("DELETE FROM connector_credential");
		SealedSecret existingSeal = oldCurrent.seal(SENTINEL + "-existing",
				ConnectorCredential.purpose(SITE, "tos"));
		jdbc.update("""
				INSERT INTO connector_credential
				(site_external_id, connector_name, auth_mode, auth_principal, secret_ciphertext,
				 secret_nonce, key_id, credential_version, updated_at, updated_by)
				VALUES (?, 'tos', 'BASIC', 'user', ?, ?, ?, 1, SYSUTCDATETIME(), 'old-writer')
				""", SITE, existingSeal.ciphertextBase64(), existingSeal.nonceBase64(), existingSeal.keyId());
		mutate(service, "tos", 1, new SetBasic("renamed-user", new Preserve()), "preserver");
		ConnectorCredential preserved = credential("tos");
		assertThat(preserved.siteExternalId()).isEqualTo(SITE);
		assertThat(preserved.sealedSecret()).isEqualTo(existingSeal);
		RestConnector preservedConnector = connector(repository, oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThat(call(preservedConnector, "tos")).isEqualTo("APPROVED");
		assertThat(receivedAuthorization.getLast())
				.isEqualTo(basic("renamed-user", SENTINEL + "-existing"));

		SecretBox newCurrent = box("new-v2");
		CredentialRewrapService rewrap = new CredentialRewrapService(
				repository, newCurrent, transaction(), SITE);
		assertThat(scoped(() -> rewrap.rewrapBatch(10, "rewrapper")))
				.isEqualTo(new CredentialRewrapService.RewrapResult(1, 1, 0));
		ConnectorCredential rotated = credential("tos");
		assertThat(rotated.siteExternalId()).isEqualTo(SITE);
		assertThat(newCurrent.open(rotated.sealedSecret(),
				ConnectorCredential.purpose(SITE, "tos"))).isEqualTo(SENTINEL + "-existing");
		assertThat(jdbc.queryForList(
				"SELECT site_external_id FROM connector_credential_audit ORDER BY credential_audit_id",
				String.class)).containsExactly(SITE, SITE);
		RestConnector afterRewrap = connector(repository, newCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThat(call(afterRewrap, "tos")).isEqualTo("APPROVED");
		assertThat(receivedAuthorization.getLast())
				.isEqualTo(basic("renamed-user", SENTINEL + "-existing"));
	}

	@Test
	@DisplayName("a second independent instance sees replacement immediately and evicts its obsolete client")
	void independentInstanceSeesReplacementWithoutRestart() {
		ConnectorCredentialRepository repositoryA = repository();
		ConnectorCredentialRepository repositoryB = repository();
		ConnectorCredentialService serviceA = service(repositoryA, oldCurrent);
		RestConnector connectorA = connector(repositoryA, oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		RestConnector connectorB = connector(repositoryB, oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThat(connectorA).isNotSameAs(connectorB);

		mutate(serviceA, "tos", 0, new SetBasic("user", new Replace(SENTINEL + "-v1")), "actor-a");
		call(connectorB, "tos");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("user", SENTINEL + "-v1"));
		assertThat(connectorB.cachedClientCount()).isEqualTo(1);

		mutate(serviceA, "tos", 1, new SetBasic("user", new Replace(SENTINEL + "-v2")), "actor-a");
		call(connectorB, "tos");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("user", SENTINEL + "-v2"));
		assertThat(connectorB.cachedClientCount()).isEqualTo(1);
		assertThat(connectorA.cachedClientCount()).isZero();
	}

	@Test
	@DisplayName("tamper, unknown key and wrong purpose fail closed before the receiver and breaker")
	void credentialFailuresMakeNoCallAndDoNotTouchBreaker(CapturedOutput output) {
		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
		RestConnector connector = connector(repository, oldCurrent, breakers, BulkheadRegistry.ofDefaults());
		CircuitBreaker breaker = breakers.circuitBreaker("tos");

		String originalCiphertext = jdbc.queryForObject(
				"SELECT secret_ciphertext FROM connector_credential WHERE connector_name='tos'", String.class);
		jdbc.update("UPDATE connector_credential SET secret_ciphertext=? WHERE connector_name='tos'",
				flipBase64(originalCiphertext));
		assertFailedBeforeCall(connector, breaker);

		jdbc.update("UPDATE connector_credential SET secret_ciphertext=?, key_id='missing-v9' WHERE connector_name='tos'",
				originalCiphertext);
		assertFailedBeforeCall(connector, breaker);

		insertConnector("other");
		mutate(service, "other", 0, new SetBasic("user", new Replace("other-password")), "actor");
		jdbc.update("""
				UPDATE destination SET destination.secret_ciphertext=source.secret_ciphertext,
				 destination.secret_nonce=source.secret_nonce, destination.key_id=source.key_id
				FROM connector_credential destination
				JOIN connector_credential source ON source.site_external_id=destination.site_external_id
				WHERE destination.connector_name='tos' AND source.connector_name='other'
				""");
		assertFailedBeforeCall(connector, breaker);
		assertThat(output.getAll()).doesNotContain(SENTINEL);
	}

	@Test
	@DisplayName("ordinary transport failure has one fixed public message without nested exception text")
	void transportFailureMessageIsFixedAndRedacted() {
		jdbc.update("UPDATE connector_config SET base_url='http://127.0.0.1:1', deadline_ms=100 "
				+ "WHERE connector_name='tos'");
		RestConnector connector = connector(repository, oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThatThrownBy(() -> call(connector, "tos"))
				.isInstanceOf(ConnectorUnavailableException.class)
				.hasMessage("Connector 'tos' could not be reached.")
				.hasMessageNotContaining("ConnectException").hasMessageNotContaining("127.0.0.1");
	}

	@Test
	@DisplayName("old and new instances read both generations and bounded rewrap converges to zero")
	void rollingRotationCoexistsAndRewrapConverges() {
		insertConnector("other");
		insertConnector("new-writer");
		SecretBox newCurrent = box("new-v2");
		ConnectorCredentialService oldService = service(repository, oldCurrent);
		ConnectorCredentialService newService = service(repository, newCurrent);
		mutate(oldService, "tos", 0, new SetBasic("old-user", new Replace(SENTINEL + "-old")), "old-instance");
		mutate(oldService, "other", 0, new SetBasic("old-user", new Replace(SENTINEL + "-other")), "old-instance");
		mutate(newService, "new-writer", 0,
				new SetBasic("new-user", new Replace(SENTINEL + "-new")), "new-instance");

		RestConnector oldInstance = connector(repository(), oldCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		RestConnector newInstance = connector(repository(), newCurrent,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		call(newInstance, "tos");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("old-user", SENTINEL + "-old"));
		call(oldInstance, "new-writer");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("new-user", SENTINEL + "-new"));

		ConnectorCredential before = credential("tos");
		CredentialRewrapService rewrap = new CredentialRewrapService(repository, newCurrent,
				transaction(), SITE);
		assertThatThrownBy(() -> scoped(() -> rewrap.rewrapBatch(0, "rewrapper")))
				.isInstanceOf(InvalidState.class);
		assertThatThrownBy(() -> scoped(() -> rewrap.rewrapBatch(101, "rewrapper")))
				.isInstanceOf(InvalidState.class);
		assertThat(scoped(rewrap::countNeedingRewrap)).isEqualTo(2);
		CredentialRewrapService.RewrapResult first = scoped(() -> rewrap.rewrapBatch(1, "rewrapper"));
		assertThat(first).isEqualTo(new CredentialRewrapService.RewrapResult(1, 1, 1));
		CredentialRewrapService.RewrapResult second = scoped(() -> rewrap.rewrapBatch(100, "rewrapper"));
		assertThat(second).isEqualTo(new CredentialRewrapService.RewrapResult(1, 1, 0));
		assertThat(scoped(rewrap::countNeedingRewrap)).isZero();

		ConnectorCredential after = credential("tos");
		assertThat(after.sealedSecret().keyId()).isEqualTo("new-v2");
		assertThat(after.sealedSecret().nonceBase64()).isNotEqualTo(before.sealedSecret().nonceBase64());
		assertThat(after.version()).isEqualTo(before.version() + 1);
		assertThat(newCurrent.open(after.sealedSecret(), ConnectorCredential.purpose(SITE, "tos")))
				.isEqualTo(SENTINEL + "-old");
		assertThat(audits("tos")).extracting(ConnectorCredentialAudit::action)
				.containsExactly(Action.SET, Action.REWRAP);
		call(newInstance, "tos");
		assertThat(receivedAuthorization.getLast()).isEqualTo(basic("old-user", SENTINEL + "-old"));
	}

	@Test
	@DisplayName("key generations differing only by case remain distinct and rewrap converges exactly")
	void keyIdsAreCaseSensitiveInSqlAndJava() {
		Map<String, String> keys = Map.of("Key-v1", OLD_KEY, "key-v1", NEW_KEY);
		SecretBox oldCaseCurrent = new SecretBox("Key-v1", keys);
		SecretBox newCaseCurrent = new SecretBox("key-v1", keys);
		ConnectorCredentialService oldCaseService = service(repository, oldCaseCurrent);
		mutate(oldCaseService, "tos", 0,
				new SetBasic("user", new Replace(SENTINEL)), "old-instance");
		assertThat(credential("tos").sealedSecret().keyId()).isEqualTo("Key-v1");

		CredentialRewrapService rewrap = new CredentialRewrapService(
				repository, newCaseCurrent, transaction(), SITE);
		assertThat(scoped(rewrap::countNeedingRewrap)).isEqualTo(1);
		assertThat(scoped(() -> rewrap.rewrapBatch(10, "rewrapper")))
				.isEqualTo(new CredentialRewrapService.RewrapResult(1, 1, 0));
		assertThat(scoped(rewrap::countNeedingRewrap)).isZero();
		ConnectorCredential rotated = credential("tos");
		assertThat(rotated.sealedSecret().keyId()).isEqualTo("key-v1");
		assertThat(newCaseCurrent.open(rotated.sealedSecret(),
				ConnectorCredential.purpose(SITE, "tos"))).isEqualTo(SENTINEL);
	}

	@Test
	@DisplayName("one bad old-key row rolls back the complete rewrap batch")
	void badRowRollsBackRewrapBatch() {
		insertConnector("z-bad");
		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		mutate(service, "z-bad", 0, new SetBasic("user", new Replace("bad-row")), "actor");
		jdbc.update("UPDATE connector_credential SET secret_ciphertext=? WHERE connector_name='z-bad'",
				flipBase64(credential("z-bad").sealedSecret().ciphertextBase64()));

		CredentialRewrapService rewrap = new CredentialRewrapService(repository, box("new-v2"),
				transaction(), SITE);
		assertThatThrownBy(() -> scoped(() -> rewrap.rewrapBatch(100, "rewrapper")))
				.isInstanceOf(SecretOpenException.class).hasMessageNotContaining(SENTINEL)
				.hasMessageNotContaining("z-bad");
		assertThat(credential("tos").sealedSecret().keyId()).isEqualTo("old-v1");
		assertThat(credential("tos").version()).isEqualTo(1);
		assertThat(audits("tos")).extracting(ConnectorCredentialAudit::action)
				.containsExactly(Action.SET);
		assertThat(scoped(rewrap::countNeedingRewrap)).isEqualTo(2);
	}

	@Test
	@DisplayName("a restored database without its recorded key generation fails visibly with no fallback")
	void restoreMismatchFailsClosed() {
		mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
		SecretBox unrelatedKeys = new SecretBox("new-v2", Map.of("new-v2", NEW_KEY));
		RestConnector restoredRuntime = connector(repository(), unrelatedKeys,
				CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults());
		assertThatThrownBy(() -> call(restoredRuntime, "tos"))
				.isInstanceOf(ConnectorUnavailableException.class)
				.hasMessage("Connector 'tos' credential could not be resolved.")
				.hasMessageNotContaining(SENTINEL).hasMessageNotContaining("old-v1")
				.hasMessageNotContaining(NEW_KEY);
		assertThat(receivedAuthorization).isEmpty();
	}

	@Test
	@DisplayName("credential timestamps round-trip as UTC under a non-UTC JVM default")
	void timestampsRemainUtcOutsideUtcDefaultZone() {
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
			Instant before = Instant.now();
			mutate(service, "tos", 0, new SetBasic("user", new Replace(SENTINEL)), "actor");
			ConnectorCredential reloaded = credential("tos");
			assertThat(reloaded.changedAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
			assertThat(audits("tos").getFirst().occurredAt())
					.isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	private static void receive(HttpExchange exchange) throws IOException {
		receivedAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization") == null
				? "<absent>" : exchange.getRequestHeaders().getFirst("Authorization"));
		exchange.getRequestBody().readAllBytes();
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream response = exchange.getResponseBody()) {
			response.write(body);
		}
	}

	private void insertConnector(String name) {
		insertConnector(SITE, name);
	}

	private void insertConnector(String siteExternalId, String name) {
		jdbc.update("""
				INSERT INTO connector_config
				(site_external_id, connector_name, base_url, request_path, deadline_ms, is_enabled)
				VALUES (?, ?, ?, '/connector', 3000, 1)
				""", siteExternalId, name, "http://localhost:" + receiver.getAddress().getPort());
		jdbc.update("""
				INSERT INTO connector_route (site_external_id, connector_name, http_status, outcome)
				VALUES (?, ?, 200, 'APPROVED')
				""", siteExternalId, name);
	}

	private ConnectorCredentialRepository repository() {
		ScopeSeam seam = new JdbcScopeSeam(new JdbcTemplate(dataSource));
		return new ConnectorCredentialRepository(seam, SITE);
	}

	private ConnectorCredentialService service(ConnectorCredentialRepository target, SecretBox box) {
		return new ConnectorCredentialService(target, box, transaction(), SITE);
	}

	private TransactionTemplate transaction() {
		return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	private SecretBox box(String current) {
		return new SecretBox(current, Map.of("old-v1", OLD_KEY, "new-v2", NEW_KEY));
	}

	private RestConnector connector(ConnectorCredentialRepository credentialRepository, SecretBox box,
			CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads) {
		return new RestConnector(new ConnectorConfigRepository(new JdbcScopeSeam(new JdbcTemplate(dataSource))),
				credentialRepository, box, breakers, bulkheads, SITE);
	}

	private String call(RestConnector connector, String name) {
		return connector.call(new ConnectorCall("vis-" + UUID.randomUUID(), "lane-it", name));
	}

	private CredentialMetadata mutate(ConnectorCredentialService target, String name, long version,
			CredentialMutation mutation, String actor) {
		return scoped(() -> target.mutate(name, version, mutation, actor));
	}

	private ConnectorCredential credential(String name) {
		return scoped(() -> repository.byName(name).orElseThrow());
	}

	private List<ConnectorCredentialAudit> audits(String name) {
		return jdbc.query("""
				SELECT connector_name, credential_version, auth_mode, action, occurred_at, actor
				FROM connector_credential_audit
				WHERE site_external_id=? AND connector_name=?
				ORDER BY credential_audit_id
				""", (rs, row) -> new ConnectorCredentialAudit(
					rs.getString("connector_name"),
					CredentialMode.valueOf(rs.getString("auth_mode")),
					rs.getLong("credential_version"),
					Action.valueOf(rs.getString("action")),
					Utc.instantAt(rs, "occurred_at"),
					rs.getString("actor")), SITE, name);
	}

	private static <T> T scoped(java.util.concurrent.Callable<T> action) {
		return ScopeContext.callIn(SITE_SCOPE, action);
	}

	private List<String> columns(String table) {
		return jdbc.queryForList("""
				SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
				WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION
				""", String.class, SCHEMA, table);
	}

	private long count(String table) {
		Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
		return value == null ? 0 : value;
	}

	private void assertDbRejects(String mode, String principal, String ciphertext, String nonce,
			String keyId, long version, String actor, String connector) {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO connector_credential
				(site_external_id, connector_name, auth_mode, auth_principal, secret_ciphertext,
				 secret_nonce, key_id, credential_version, updated_at, updated_by)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, SYSUTCDATETIME(), ?)
				""", SITE, connector, mode, principal, ciphertext, nonce, keyId, version, actor))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void assertAuditDbRejects(String mode, String action, long version, String actor) {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO connector_credential_audit
				(site_external_id, connector_name, credential_version, auth_mode, action, occurred_at, actor)
				VALUES (?, 'tos', ?, ?, ?, SYSUTCDATETIME(), ?)
				""", SITE, version, mode, action, actor))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private List<Object> race(java.util.concurrent.Callable<CredentialMetadata> left,
			java.util.concurrent.Callable<CredentialMetadata> right) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<Object>> attempts = List.of(left, right).stream().map(work ->
					CompletableFuture.supplyAsync(() -> {
						ready.countDown();
						await(start);
						try {
							return work.call();
						}
						catch (Exception failure) {
							return failure;
						}
					}, executor)).toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Object> outcomes = new ArrayList<>();
			for (CompletableFuture<Object> attempt : attempts) {
				outcomes.add(attempt.get(10, TimeUnit.SECONDS));
			}
			return outcomes;
		}
	}

	private void assertFailedBeforeCall(RestConnector connector, CircuitBreaker breaker) {
		int before = receivedAuthorization.size();
		assertThatThrownBy(() -> call(connector, "tos"))
				.isInstanceOf(ConnectorUnavailableException.class)
				.hasMessage("Connector 'tos' credential could not be resolved.")
				.hasMessageNotContaining(SENTINEL).hasMessageNotContaining("ciphertext")
				.hasMessageNotContaining("nonce").hasMessageNotContaining("key");
		assertThat(receivedAuthorization).hasSize(before);
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
		assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
	}

	private static String basic(String principal, String password) {
		return "Basic " + Base64.getEncoder().encodeToString(
				(principal + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private static String flipBase64(String value) {
		byte[] decoded = Base64.getDecoder().decode(value);
		decoded[decoded.length - 1] ^= 1;
		return Base64.getEncoder().encodeToString(decoded);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("timed out waiting for concurrent test gate");
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("concurrent test interrupted", interrupted);
		}
	}
}
