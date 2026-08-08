package com.lynxis.orca.core.domain;

import java.util.Optional;

import com.lynxis.orca.core.domain.IdentityTables.UserAccount;
import com.lynxis.orca.core.persistence.AuditEventRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.platform.web.system.SystemContext;

import lombok.RequiredArgsConstructor;

/**
 * The audit writer: one call per config mutation, in the mutation's own
 * transaction — an audit row that could miss its mutation (or the reverse)
 * would be worse than none.
 *
 * <p>The actor is resolved once, here: the calling user's external id when the
 * token maps to one, the raw subject when it does not (a fact worth auditing
 * verbatim), or the system identity for §B6's user-less paths. There is no
 * anonymous branch to reach — no path runs with no identity.
 *
 * <p>This phase wires it into the settings and branding mutations it lands
 * with; instrumenting the WP1–WP3 mutation paths is recorded in the report as
 * deliberate follow-up, not assumed done.
 */
@RequiredArgsConstructor
public class AuditTrail {

	private final AuditEventRepository events;
	private final UserAccountRepository users;
	private final CallerIdentity caller;
	private final String siteExternalId;

	public void record(String entityType, String entityExternalId, String action, String detail) {
		events.append(siteExternalId, currentActor(), entityType, entityExternalId, action, detail);
	}

	public String currentActor() {
		Optional<String> subject = caller.subject();
		if (subject.isPresent()) {
			return users.all().stream()
					.filter(user -> subject.get().equals(user.keycloakSubject()))
					.findFirst()
					.map(UserAccount::externalId)
					.orElse("subject:" + subject.get());
		}
		if (SystemContext.isSystem()) {
			return SystemContext.require().toString();
		}
		// Reachable only from a test harness that established neither; a real
		// request is authenticated before any controller runs (§B6).
		return "unattributed";
	}
}
