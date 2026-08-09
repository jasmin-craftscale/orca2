package com.lynxis.orca.runtime.workitem.api;

import java.util.Optional;

/**
 * Who is acting — the operator behind the current request.
 *
 * <p>An interface rather than a static read of the security context, for the same
 * reason core's {@code CallerIdentity} is one: the property suites construct
 * controllers directly, with no Spring context, and hand them a caller the same
 * way production's filter chain does.
 *
 * <p>The production bean resolves the identity-provider subject to the platform
 * user's external id through core's published {@code topology_operator} view —
 * so the value here, and everywhere it lands (assignees, the audit trail's actor
 * column), is core's user vocabulary. A subject with no linked user answers
 * empty and the surface refuses with {@code OPERATOR_UNRESOLVED}.
 */
public interface OperatorIdentity {

	Optional<String> operator();
}
