package com.lynxis.orca.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import com.lynxis.orca.platform.web.internal.InternalCallAuthenticationFilter;
import com.lynxis.orca.platform.web.internal.InternalCallProperties;
import com.lynxis.orca.platform.web.internal.InternalCredentialValidator;

// Spring Boot 4 ships JACKSON 3. The databind package moved to `tools.jackson`;
// only the annotations stayed at `com.fasterxml.jackson.annotation`. Copying an
// ObjectMapper import from a Boot 3 example does not compile — which is the cheap
// version of this discovery, and the reason it is written down here.
import tools.jackson.databind.json.JsonMapper;

/**
 * The default HTTP security shape, once, for all six services.
 *
 * <p><strong>This is a decision the architecture did not dictate, and it is here
 * rather than in six services for one reason:</strong> §B6 states a single
 * platform-wide property — "every service validates tokens by signature locally"
 * — and six copies of a filter chain is six chances for one of them to be subtly
 * different. That is the defect class this phase exists to remove.
 *
 * <p>What it decides, and nothing more:
 *
 * <ul>
 *   <li>Every route requires an authenticated caller, <em>except</em>
 *       {@code /actuator/health}, which is the orchestrator's probe (§C1–C6) and
 *       is called by something that holds no token.</li>
 *   <li>Tokens are validated as JWTs against the configured issuer's published
 *       keys — locally, by signature, with no call-out per request.</li>
 *   <li>No session. A service that keeps a session has state an instance can lose,
 *       and §A5 says coordination lives in the database rather than in a running
 *       program.</li>
 *   <li>CSRF is off because there is no cookie-borne credential to forge: these
 *       are bearer-token APIs.</li>
 *   <li>Authentication and authorization failures are answered in the ENVELOPE,
 *       so the two responses a caller is most likely to meet first are not the two
 *       that come back in a different shape.</li>
 * </ul>
 *
 * <p>What it deliberately does NOT decide: any authorization rule. No role
 * mapping, no scope-to-entitlement translation, no per-route policy. Those belong
 * to the security design, and open register items NEW-1a and U3 are exactly that
 * conversation. A service that needs a different chain defines its own
 * {@link SecurityFilterChain} bean and this one steps aside.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@org.springframework.boot.context.properties.EnableConfigurationProperties(InternalCallProperties.class)
public class PlatformSecurityAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public InternalCredentialValidator internalCredentialValidator(InternalCallProperties properties,
			org.springframework.core.env.Environment environment) {
		return new InternalCredentialValidator(properties, environment);
	}

	/**
	 * Whether the served OpenAPI document ({@code /openapi/**}) is readable without
	 * a token.
	 *
	 * <p><strong>False by default, and that is a deliberate non-decision.</strong>
	 * Whether an installation publishes its API surface is a security question with
	 * a named owner, not a convenience setting — so nothing here turns it on. Each
	 * service's `local` profile does, because a developer with docker compose
	 * running is not an installation.
	 */
	@Bean
	@ConditionalOnMissingBean(SecurityFilterChain.class)
	public SecurityFilterChain orcaSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper,
			org.springframework.core.env.Environment environment,
			InternalCallProperties internalCallProperties) throws Exception {
		boolean publicDocs = environment.getProperty("orca.web.public-docs", Boolean.class, false);
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// The service-to-service surface (§B6, ADR-011). The filter
				// authenticates /internal/** against the per-installation shared
				// credential and grants ROLE_ORCA_SERVICE; the matcher below then
				// requires exactly that authority there — so a USER token, however
				// valid, cannot call an internal endpoint, and the service
				// credential means nothing anywhere else.
				.addFilterBefore(new InternalCallAuthenticationFilter(internalCallProperties, jsonMapper),
						BasicAuthenticationFilter.class)
				.authorizeHttpRequests(requests -> {
					requests.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
					if (publicDocs) {
						requests.requestMatchers("/openapi/**").permitAll();
					}
					requests.requestMatchers(internalCallProperties.getPathPattern())
							.hasAuthority(InternalCallAuthenticationFilter.SERVICE_AUTHORITY);
					requests.anyRequest().authenticated();
				})
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint((request, response, ex) ->
								write(jsonMapper, response, PlatformErrorCode.UNAUTHENTICATED,
										"Authentication is required."))
						.accessDeniedHandler((request, response, ex) ->
								write(jsonMapper, response, PlatformErrorCode.FORBIDDEN,
										"You are not entitled to this.")))
				.build();
	}

	/**
	 * Security rejects a request before any controller advice can see it, so the
	 * envelope has to be written here too. The message is a fixed string and says
	 * nothing about WHY the token failed — "expired", "bad signature" and "wrong
	 * issuer" are three facts an attacker probing a token would like to have.
	 */
	private static void write(JsonMapper jsonMapper, jakarta.servlet.http.HttpServletResponse response,
			ErrorCode code, String message) throws java.io.IOException {
		response.setStatus(HttpStatus.valueOf(code.httpStatus()).value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
	}
}
