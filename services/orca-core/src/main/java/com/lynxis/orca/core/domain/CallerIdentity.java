package com.lynxis.orca.core.domain;

import java.util.Optional;

/**
 * Who is calling — the identity-provider subject of the current request, when
 * a person is behind it.
 *
 * <p>An interface rather than a static read of the security context, so the
 * property suites (which run without a Spring context) can hand a controller a
 * caller the same way production's filter chain does. The production bean
 * reads the JWT; §B6's system paths have no subject and answer empty.
 */
public interface CallerIdentity {

	Optional<String> subject();
}
