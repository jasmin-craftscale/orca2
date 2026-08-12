package com.lynxis.orca.runtime.execution.internal;

import java.util.Optional;
import java.util.Set;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.delegate.spi.VisitIdentity;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;

/**
 * Trades the engine's instance id for the identifiers ORCA selectors are written
 * against — the {@code execution} row's key and its stable external identifier.
 *
 * <p>Runs under the installation's own scope: the delegates that ask arrive on the
 * engine's job threads, which carry no request and therefore no scope of their own.
 */
public final class RuntimeVisitIdentity implements VisitIdentity {

	private final AdmissionRepository visits;
	private final Scope installationScope;

	public RuntimeVisitIdentity(AdmissionRepository visits, String siteExternalId) {
		this.visits = visits;
		this.installationScope = Scope.of("site_external_id", Set.of(siteExternalId));
	}

	@Override
	public Optional<Visit> forInstance(String processInstanceId) {
		if (processInstanceId == null) {
			return Optional.empty();
		}
		return ScopeContext.callIn(installationScope,
						() -> visits.visitByProcessInstance(processInstanceId))
				.map(row -> new Visit(row.executionId(), row.externalId()));
	}
}
