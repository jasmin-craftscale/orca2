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

	/** The actor column's width — an over-long IdP subject is clipped, never a 500. */
	private static final int ACTOR_WIDTH = 200;

	public void record(String entityType, String entityExternalId, String action, String detail) {
		events.append(siteExternalId, currentActor(), entityType, entityExternalId, action, detail);
	}

	public String currentActor() {
		Optional<String> subject = caller.subject();
		if (subject.isPresent()) {
			String actor = users.activeByKeycloakSubject(subject.get())
					.map(UserAccount::externalId)
					.orElse("subject:" + subject.get());
			// An IdP is free to mint subjects longer than the column; an audit
			// write must not fail over a caller-shaped string.
			return actor.length() > ACTOR_WIDTH ? actor.substring(0, ACTOR_WIDTH) : actor;
		}
		if (SystemContext.isSystem()) {
			return SystemContext.require().toString();
		}
		// Reachable only from a test harness that established neither; a real
		// request is authenticated before any controller runs (§B6).
		return "unattributed";
	}
}
