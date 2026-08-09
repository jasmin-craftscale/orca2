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
 * <p>⚠️ <strong>Slice shape, stated openly:</strong> until the routing work
 * package publishes core's operator directory, the production bean answers the
 * identity-provider <em>subject</em>, not core's user external id. The two become
 * the same value the moment the directory view exists and the bean resolves
 * through it — one bean changes, no caller does. Recorded in the phase report.
 */
public interface OperatorIdentity {

	Optional<String> operator();
}
