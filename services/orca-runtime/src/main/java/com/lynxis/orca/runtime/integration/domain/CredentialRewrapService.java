package com.lynxis.orca.runtime.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.secrets.SealedSecret;
import com.lynxis.orca.platform.secrets.SecretBox;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialAudit.Action;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialException.InvalidState;
import com.lynxis.orca.runtime.integration.persistence.ConnectorCredentialRepository;

/** Runtime-owned bounded rotation of old-key rows; no scheduler or public trigger. */
public class CredentialRewrapService {

	private static final String SCOPE_DIMENSION = "site_external_id";

	private final ConnectorCredentialRepository repository;
	private final SecretBox secretBox;
	private final TransactionTemplate transactions;
	private final String siteExternalId;

	public CredentialRewrapService(ConnectorCredentialRepository repository, SecretBox secretBox,
			TransactionTemplate transactions, String siteExternalId) {
		this.repository = repository;
		this.secretBox = secretBox;
		this.transactions = transactions;
		this.siteExternalId = siteExternalId;
	}

	public RewrapResult rewrapBatch(int limit, String actor) {
		requireScope();
		ConnectorCredentialService.requireActor(actor);
		if (limit < 1 || limit > 100) {
			throw new InvalidState("rewrap limit must be between 1 and 100");
		}
		return Objects.requireNonNull(transactions.execute(status -> {
			List<ConnectorCredential> selected = repository.needingRewrap(secretBox.currentKeyId(), limit);
			Instant changedAt = Instant.now();
			for (ConnectorCredential credential : selected) {
				String plaintext = secretBox.open(credential.sealedSecret(),
						ConnectorCredential.purpose(siteExternalId, credential.connectorName()));
				SealedSecret resealed = secretBox.seal(plaintext,
						ConnectorCredential.purpose(siteExternalId, credential.connectorName()));
				ConnectorCredential next = new ConnectorCredential(siteExternalId, credential.connectorName(),
						CredentialMode.BASIC, credential.principal(), resealed,
						credential.version() + 1, changedAt, actor);
				if (repository.updateRewrapped(next, credential.version(), credential.sealedSecret().keyId()) != 1) {
					throw new InvalidState("credential changed during rewrap");
				}
				repository.appendAudit(siteExternalId, new ConnectorCredentialAudit(
						credential.connectorName(), CredentialMode.BASIC, next.version(),
						Action.REWRAP, changedAt, actor));
			}
			return new RewrapResult(selected.size(), selected.size(),
					repository.countNeedingRewrap(secretBox.currentKeyId()));
		}));
	}

	public long countNeedingRewrap() {
		requireScope();
		return repository.countNeedingRewrap(secretBox.currentKeyId());
	}

	private void requireScope() {
		if (ScopeContext.current().isDeny()
				|| !ScopeContext.current().permitted(SCOPE_DIMENSION).contains(siteExternalId)) {
			throw new InvalidState("installation site scope is not established");
		}
	}

	public record RewrapResult(int selected, int rewrapped, long remaining) {
	}
}
