package com.lynxis.orca.core.domain;

import java.util.Optional;

/**
 * Who is calling — the identity-provider subject of the current request, when
 * a person is behind it.
 *
 * <p>An interface rather than a static read of the security context, so the
 * property suites (which run without a Spring context) can hand a controller a
 * caller the same way production's filter chain does. The production bean reads
 * the JWT; work running under a system identity has no user subject and answers
 * empty.
 */
public interface CallerIdentity {

	Optional<String> subject();
}
