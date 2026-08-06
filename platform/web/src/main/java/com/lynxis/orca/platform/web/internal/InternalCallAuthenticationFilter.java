package com.lynxis.orca.platform.web.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.PlatformErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates a service-to-service call against the per-installation shared
 * credential.
 *
 * <p>Runs only on the {@code /internal/**} surface. Everything else is a caller
 * acting for a person and is authenticated by Keycloak, unchanged.
 *
 * <p><strong>The granted authority is {@code ROLE_ORCA_SERVICE} and nothing
 * else.</strong> This establishes that the caller is one of ours; it does not
 * establish <em>which</em> one in any way a service should trust for a decision.
 * The {@code X-Orca-Service} header is recorded for attribution and is
 * deliberately not turned into an authority: every service holds the same
 * credential, so any of them could claim to be any other, and a permission that
 * rested on that header would be a permission anyone holding the credential has.
 */
@Slf4j
@RequiredArgsConstructor
public class InternalCallAuthenticationFilter extends OncePerRequestFilter {

	/** Held by an authenticated service caller. Not a role a person can ever have. */
	public static final String SERVICE_AUTHORITY = "ROLE_ORCA_SERVICE";

	private final InternalCallProperties properties;
	private final JsonMapper jsonMapper;
	private final org.springframework.util.AntPathMatcher paths = new org.springframework.util.AntPathMatcher();

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !paths.match(properties.getPathPattern(), request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String presented = request.getHeader(InternalCallProperties.CREDENTIAL_HEADER);

		if (presented == null || !matches(presented, properties.getSharedCredential())) {
			// Never says which of the two it was. "No credential" and "wrong
			// credential" are two facts a prober would like to be able to tell apart.
			log.warn("internal call to {} refused: credential absent or wrong (claimed service: {})",
					request.getRequestURI(), claimedService(request));
			write(response);
			return;
		}

		String caller = claimedService(request);
		var authentication = new UsernamePasswordAuthenticationToken(
				"service:" + caller, null, List.of(new SimpleGrantedAuthority(SERVICE_AUTHORITY)));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		try {
			chain.doFilter(request, response);
		}
		finally {
			// Pooled request thread: a context left behind is a context the next
			// request inherits, and it would be an authenticated one.
			SecurityContextHolder.clearContext();
		}
	}

	private static String claimedService(HttpServletRequest request) {
		String claimed = request.getHeader(InternalCallProperties.SERVICE_HEADER);
		return claimed == null || claimed.isBlank() ? "unnamed" : claimed;
	}

	/**
	 * Constant-time comparison.
	 *
	 * <p>{@code String.equals} returns as soon as two characters differ, which
	 * leaks the length of the matching prefix to anyone able to time the response.
	 * That is a slow attack and a real one, and avoiding it costs one method call.
	 */
	private static boolean matches(String presented, String expected) {
		if (expected == null) {
			return false;
		}
		return MessageDigest.isEqual(
				presented.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}

	private void write(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(),
				ApiResponse.error(PlatformErrorCode.UNAUTHENTICATED, "Authentication is required."));
	}
}
