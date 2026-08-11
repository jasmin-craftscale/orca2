package com.lynxis.orca.runtime.integration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.secrets.SealedSecret;
import com.lynxis.orca.platform.secrets.SecretBox;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialAudit.Action;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialException.InvalidState;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialException.NotFound;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialException.StaleVersion;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.Clear;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.Preserve;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.Replace;
import com.lynxis.orca.runtime.integration.domain.CredentialMutation.SetBasic;
import com.lynxis.orca.runtime.integration.persistence.ConnectorCredentialRepository;
import com.lynxis.orca.runtime.integration.persistence.ConnectorCredentialRepository.ConnectorIdentity;

/** Exact SetBasic/Replace/Preserve/Clear semantics in one transaction. */
public class ConnectorCredentialService {

	private static final String SCOPE_DIMENSION = "site_external_id";

	private final ConnectorCredentialRepository repository;
	private final SecretBox secretBox;
	private final TransactionTemplate transactions;
	private final String siteExternalId;

	public ConnectorCredentialService(ConnectorCredentialRepository repository, SecretBox secretBox,
			TransactionTemplate transactions, String siteExternalId) {
		this.repository = repository;
		this.secretBox = secretBox;
		this.transactions = transactions;
		this.siteExternalId = siteExternalId;
	}

	public CredentialMetadata mutate(String connectorName, long expectedVersion,
			CredentialMutation mutation, String actor) {
		requireScope();
		requireConnectorName(connectorName);
		requireActor(actor);
		if (expectedVersion < 0) {
			throw new InvalidState("expected version must be zero or positive");
		}
		if (mutation == null) {
			throw new InvalidState("mutation is required");
		}
		return Objects.requireNonNull(transactions.execute(status -> {
			ConnectorIdentity parent = repository.lockConnector(connectorName)
					.orElseThrow(NotFound::new);
			// SQL Server identifiers are case-insensitive, while scope values and AAD
			// are exact. The authorized parent lookup is the one place that resolves
			// database spelling; every child write then uses that stored identity.
			return ScopeContext.callIn(scope(parent.siteExternalId()), () -> {
				ConnectorCredential current = repository.byName(parent.connectorName()).orElse(null);
				long currentVersion = current == null ? 0 : current.version();
				if (expectedVersion != currentVersion) {
					throw new StaleVersion(expectedVersion, currentVersion);
				}
				String storedSiteExternalId = current == null
						? parent.siteExternalId() : current.siteExternalId();
				// A pre-merge row may carry the configuration spelling used by the old
				// writer. Preserve that identity exactly as the AAD which sealed it did.
				return ScopeContext.callIn(scope(storedSiteExternalId),
						() -> apply(parent, current, mutation, actor));
			});
		}));
	}

	public CredentialMetadata metadata(String connectorName) {
		requireScope();
		requireConnectorName(connectorName);
		if (!repository.connectorExists(connectorName)) {
			throw new NotFound();
		}
		return repository.byName(connectorName).map(ConnectorCredentialService::metadataOf)
				.orElseGet(() -> new CredentialMetadata(CredentialMode.NONE, false, 0, null, null));
	}

	private CredentialMetadata apply(ConnectorIdentity parent, ConnectorCredential current,
			CredentialMutation mutation, String actor) {
		Instant changedAt = Instant.now();
		String storedSiteExternalId = current == null ? parent.siteExternalId() : current.siteExternalId();
		String storedConnectorName = current == null ? parent.connectorName() : current.connectorName();
		ConnectorCredential next;
		Action action;

		if (mutation instanceof Clear) {
			if (current == null || current.mode() != CredentialMode.BASIC) {
				throw new InvalidState("clear requires an existing BASIC credential");
			}
			next = new ConnectorCredential(storedSiteExternalId, storedConnectorName, CredentialMode.NONE,
					null, null, current.version() + 1, changedAt, actor);
			action = Action.CLEAR;
		}
		else if (mutation instanceof SetBasic set) {
			requirePrincipal(set.principal());
			if (set.passwordChange() == null) {
				throw new InvalidState("password change is required");
			}
			if (current == null || current.mode() == CredentialMode.NONE) {
				if (!(set.passwordChange() instanceof Replace replace) || replace.plaintext() == null) {
					throw new InvalidState("setting BASIC requires a replacement password");
				}
				SealedSecret sealed = secretBox.seal(replace.plaintext(),
						ConnectorCredential.purpose(storedSiteExternalId, storedConnectorName));
				next = new ConnectorCredential(storedSiteExternalId, storedConnectorName, CredentialMode.BASIC,
						set.principal(), sealed, current == null ? 1 : current.version() + 1,
						changedAt, actor);
				action = Action.SET;
			}
			else if (set.passwordChange() instanceof Preserve) {
				if (set.principal().equals(current.principal())) {
					throw new InvalidState("preserve requires a principal change");
				}
				next = new ConnectorCredential(storedSiteExternalId, storedConnectorName, CredentialMode.BASIC,
						set.principal(), current.sealedSecret(), current.version() + 1,
						changedAt, actor);
				action = Action.REPLACE;
			}
			else if (set.passwordChange() instanceof Replace replace) {
				if (replace.plaintext() == null) {
					throw new InvalidState("replacement password is required");
				}
				SealedSecret sealed = secretBox.seal(replace.plaintext(),
						ConnectorCredential.purpose(storedSiteExternalId, storedConnectorName));
				next = new ConnectorCredential(storedSiteExternalId, storedConnectorName, CredentialMode.BASIC,
						set.principal(), sealed, current.version() + 1, changedAt, actor);
				action = Action.REPLACE;
			}
			else {
				throw new InvalidState("unsupported password change");
			}
		}
		else {
			throw new InvalidState("unsupported mutation");
		}

		if (current == null) {
			repository.insert(next);
		}
		else if (repository.update(next, current.version()) != 1) {
			throw new StaleVersion(current.version(), repository.byName(storedConnectorName)
					.map(ConnectorCredential::version).orElse(0L));
		}
		repository.appendAudit(next.siteExternalId(), new ConnectorCredentialAudit(
				next.connectorName(), next.mode(), next.version(), action, changedAt, actor));
		return metadataOf(next);
	}

	private static Scope scope(String storedSiteExternalId) {
		return Scope.of(SCOPE_DIMENSION, Set.of(storedSiteExternalId));
	}

	private void requireScope() {
		if (ScopeContext.current().isDeny()
				|| !ScopeContext.current().permitted(SCOPE_DIMENSION).contains(siteExternalId)) {
			throw new InvalidState("installation site scope is not established");
		}
	}

	private static void requireConnectorName(String connectorName) {
		if (connectorName == null || connectorName.isBlank() || connectorName.length() > 64) {
			throw new InvalidState("connector name is invalid");
		}
	}

	static void requireActor(String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 128) {
			throw new InvalidState("actor is invalid");
		}
	}

	private static void requirePrincipal(String principal) {
		if (principal == null || principal.isBlank() || principal.length() > 256 || principal.contains(":")) {
			throw new InvalidState("BASIC principal is invalid");
		}
	}

	private static CredentialMetadata metadataOf(ConnectorCredential credential) {
		return new CredentialMetadata(credential.mode(), credential.mode() == CredentialMode.BASIC,
				credential.version(), credential.changedAt(), credential.changedBy());
	}
}
