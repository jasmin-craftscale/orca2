package com.lynxis.orca.platform.web.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * The credential a service presents when it calls another service.
 *
 * <p>Keycloak authenticates <em>people</em>. A caller acting for an operator, a
 * driver or a customer's own system presents an OIDC token; a service calling
 * another service presents this credential instead, and no identity provider is
 * on the request path.
 *
 * <p><strong>The reason is availability, not simplicity.</strong> Token validation
 * is local signature verification and survives an outage. Token <em>issuing</em>
 * always requires Keycloak to be reachable, and no caching strategy fixes it — so
 * a design in which runtime must mint a token to tell edge to raise a barrier puts
 * the identity provider on the gate path. The gate must keep working while that
 * provider or another remote dependency is unavailable.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "orca.internal")
public class InternalCallProperties {

	/** The header carrying the shared credential. */
	public static final String CREDENTIAL_HEADER = "X-Orca-Internal-Auth";

	/** The header naming the calling service, for attribution in logs and audit. */
	public static final String SERVICE_HEADER = "X-Orca-Service";

	/**
	 * The value {@code .env.example} ships and every developer's machine therefore
	 * has. Recognised by name so it can be refused outside local development —
	 * shipping the fixture to a site is the failure this guards against, and it is
	 * the kind that is never noticed until somebody goes looking.
	 */
	public static final String LOCAL_FIXTURE = "local-dev-internal-credential-not-for-deployment";

	/**
	 * The per-installation shared credential.
	 *
	 * <p>No default. A service with none refuses to start, for the same reason the
	 * lease has no default duration: a credential nobody chose is one nobody will
	 * question.
	 */
	private String sharedCredential;

	/**
	 * Path pattern for the service-to-service surface.
	 *
	 * <p>{@code /internal/**} is the established home of every service-to-service
	 * endpoint: {@code /internal/commands/v1},
	 * {@code /internal/feed/v1}, {@code /internal/apply/v1},
	 * {@code /internal/events/v1}, {@code /internal/tickets/validate}.
	 */
	private String pathPattern = "/internal/**";
}
