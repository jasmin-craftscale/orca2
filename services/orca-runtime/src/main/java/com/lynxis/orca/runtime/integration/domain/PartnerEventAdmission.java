package com.lynxis.orca.runtime.integration.domain;

import java.util.Set;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.api.PartnerEventAdmissionPort;

/**
 * The partner event path's legal crossing into {@code execution}.
 *
 * <p>The scope is the installation's configured site, never a value supplied by
 * the partner. The port owns the lane lock and correlate-or-start operation; this
 * module does not reach into {@code execution.domain} or
 * {@code execution.persistence}.
 */
public final class PartnerEventAdmission {

	private final PartnerEventAdmissionPort admission;
	private final Scope installationScope;

	public PartnerEventAdmission(PartnerEventAdmissionPort admission, String siteExternalId) {
		this.admission = admission;
		this.installationScope = Scope.of("site_external_id", Set.of(siteExternalId));
	}

	/** Admits the event and preserves the port's complete outcome. */
	public PartnerEventAdmissionPort.Outcome admit(PartnerEventAdmissionPort.Event event) {
		return ScopeContext.callIn(installationScope, () -> admission.admit(event));
	}
}
