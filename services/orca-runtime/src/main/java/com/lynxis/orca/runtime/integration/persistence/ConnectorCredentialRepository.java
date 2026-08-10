package com.lynxis.orca.runtime.integration.persistence;

import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;
import com.lynxis.orca.platform.secrets.SealedSecret;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredential;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialAudit;
import com.lynxis.orca.runtime.integration.domain.CredentialMode;
import com.lynxis.orca.runtime.persistence.Utc;

import lombok.RequiredArgsConstructor;

/** Scoped current-state and append-only audit writes for connector credentials. */
@RequiredArgsConstructor
public class ConnectorCredentialRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	private final ScopeSeam seam;
	private final String siteExternalId;

	/** Locks and returns the parent's stored identity, including when disabled. Transaction required. */
	public Optional<ConnectorIdentity> lockConnector(String connectorName) {
		return seam.select(ScopedSelect.from("connector_config")
					.columns(SCOPE_COLUMN, "connector_name")
					.scopedBy(SCOPE_COLUMN)
					// SQL Server's JDBC driver sends String parameters as NVARCHAR by
					// default. Cast the parameter, not the indexed VARCHAR column: a
					// converted column is scanned and an UPDLOCK scan can wait behind an
					// unrelated connector before it reaches its own row.
					.where("site_external_id = CAST(? AS VARCHAR(64)) "
							+ "AND connector_name = CAST(? AS VARCHAR(64))",
								siteExternalId, connectorName)
					.lockMatchedRows(),
				(rs, row) -> new ConnectorIdentity(
						rs.getString(SCOPE_COLUMN), rs.getString("connector_name")))
				.stream().findFirst();
	}

	public boolean connectorExists(String connectorName) {
		return !seam.select(ScopedSelect.from("connector_config")
					.columns("connector_name")
					.scopedBy(SCOPE_COLUMN)
					.where("connector_name = ?", connectorName),
				(rs, row) -> rs.getString("connector_name")).isEmpty();
	}

	public Optional<ConnectorCredential> byName(String connectorName) {
		return seam.select(currentSelect().where("connector_name = ?", connectorName), this::map)
				.stream().findFirst();
	}

	public void insert(ConnectorCredential credential) {
		seam.insert(ScopedInsert.into("connector_credential")
				.scopedBy(SCOPE_COLUMN)
				.value(SCOPE_COLUMN, credential.siteExternalId())
				.value("connector_name", credential.connectorName())
				.value("auth_mode", credential.mode().name())
				.value("auth_principal", credential.principal())
				.value("secret_ciphertext", ciphertext(credential))
				.value("secret_nonce", nonce(credential))
				.value("key_id", keyId(credential))
				.value("credential_version", credential.version())
				.value("updated_at", Utc.timestampOf(credential.changedAt()))
				.value("updated_by", credential.changedBy()));
	}

	/** Guarded current-state mutation; zero means the expected version lost. */
	public int update(ConnectorCredential credential, long expectedVersion) {
		return seam.update(ScopedUpdate.table("connector_credential")
				.set("auth_mode", credential.mode().name())
				.set("auth_principal", credential.principal())
				.set("secret_ciphertext", ciphertext(credential))
				.set("secret_nonce", nonce(credential))
				.set("key_id", keyId(credential))
				.set("updated_at", Utc.timestampOf(credential.changedAt()))
				.set("updated_by", credential.changedBy())
				.increment("credential_version", 1)
				.scopedBy(SCOPE_COLUMN)
				.where("connector_name = ? AND credential_version = ?",
						credential.connectorName(), expectedVersion));
	}

	public void appendAudit(String storedSiteExternalId, ConnectorCredentialAudit audit) {
		seam.insert(ScopedInsert.into("connector_credential_audit")
				.scopedBy(SCOPE_COLUMN)
				.value(SCOPE_COLUMN, storedSiteExternalId)
				.value("connector_name", audit.connectorName())
				.value("credential_version", audit.version())
				.value("auth_mode", audit.mode().name())
				.value("action", audit.action().name())
				.value("occurred_at", Utc.timestampOf(audit.occurredAt()))
				.value("actor", audit.actor()));
	}

	public List<ConnectorCredential> needingRewrap(String currentKeyId, int limit) {
		return seam.select(currentSelect()
				.where("auth_mode = ? AND key_id <> ?", CredentialMode.BASIC.name(), currentKeyId)
				.orderBy("connector_name")
				.limit(limit)
				.lockMatchedRows(), this::map);
	}

	public long countNeedingRewrap(String currentKeyId) {
		return seam.count(ScopedSelect.from("connector_credential")
				.scopedBy(SCOPE_COLUMN)
				.where("auth_mode = ? AND key_id <> ?", CredentialMode.BASIC.name(), currentKeyId));
	}

	/** Guarded by both optimistic version and the generation selected for rewrap. */
	public int updateRewrapped(ConnectorCredential credential, long expectedVersion, String oldKeyId) {
		return seam.update(ScopedUpdate.table("connector_credential")
				.set("secret_ciphertext", ciphertext(credential))
				.set("secret_nonce", nonce(credential))
				.set("key_id", keyId(credential))
				.set("updated_at", Utc.timestampOf(credential.changedAt()))
				.set("updated_by", credential.changedBy())
				.increment("credential_version", 1)
				.scopedBy(SCOPE_COLUMN)
				.where("connector_name = ? AND credential_version = ? AND key_id = ?",
						credential.connectorName(), expectedVersion, oldKeyId));
	}

	private ScopedSelect currentSelect() {
		return ScopedSelect.from("connector_credential")
				.columns(SCOPE_COLUMN, "connector_name", "auth_mode", "auth_principal",
						"secret_ciphertext", "secret_nonce", "key_id", "credential_version",
						"updated_at", "updated_by")
				.scopedBy(SCOPE_COLUMN);
	}

	private ConnectorCredential map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		CredentialMode mode = CredentialMode.valueOf(rs.getString("auth_mode"));
		SealedSecret sealed = mode == CredentialMode.BASIC
				? new SealedSecret(rs.getString("key_id"), rs.getString("secret_nonce"),
						rs.getString("secret_ciphertext"))
				: null;
		return new ConnectorCredential(
				rs.getString(SCOPE_COLUMN),
				rs.getString("connector_name"),
				mode,
				rs.getString("auth_principal"),
				sealed,
				rs.getLong("credential_version"),
				Utc.instantAt(rs, "updated_at"),
				rs.getString("updated_by"));
	}

	private static String ciphertext(ConnectorCredential credential) {
		return credential.sealedSecret() == null ? null : credential.sealedSecret().ciphertextBase64();
	}

	private static String nonce(ConnectorCredential credential) {
		return credential.sealedSecret() == null ? null : credential.sealedSecret().nonceBase64();
	}

	private static String keyId(ConnectorCredential credential) {
		return credential.sealedSecret() == null ? null : credential.sealedSecret().keyId();
	}

	/** Exact parent spelling read under the installation's already-established scope. */
	public record ConnectorIdentity(String siteExternalId, String connectorName) {
	}
}
