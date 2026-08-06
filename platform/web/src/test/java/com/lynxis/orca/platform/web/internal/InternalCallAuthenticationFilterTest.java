package com.lynxis.orca.platform.web.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.json.JsonMapper;

/**
 * The inter-service filter: right credential in, wrong credential out, and no
 * authority a caller could escalate with.
 */
class InternalCallAuthenticationFilterTest {

	private static final String CREDENTIAL = "k3H9mQ2vX8pL5wN7cR4tY6uB1sD0fG9jA2eZ";

	private InternalCallAuthenticationFilter filter;

	@BeforeEach
	void filter() {
		InternalCallProperties properties = new InternalCallProperties();
		properties.setSharedCredential(CREDENTIAL);
		filter = new InternalCallAuthenticationFilter(properties, JsonMapper.builder().build());
	}

	@AfterEach
	void noContextLeaks() {
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("the right credential authenticates, with ROLE_ORCA_SERVICE and nothing more")
	void theRightCredentialAuthenticates() throws Exception {
		MockHttpServletRequest request = internalRequest();
		request.addHeader(InternalCallProperties.CREDENTIAL_HEADER, CREDENTIAL);
		request.addHeader(InternalCallProperties.SERVICE_HEADER, "orca-runtime");
		MockHttpServletResponse response = new MockHttpServletResponse();

		var seen = new Authentication[1];
		filter.doFilter(request, response, new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				seen[0] = SecurityContextHolder.getContext().getAuthentication();
			}
		});

		assertThat(seen[0]).isNotNull();
		assertThat(seen[0].getPrincipal()).isEqualTo("service:orca-runtime");
		assertThat(seen[0].getAuthorities())
				.extracting(Object::toString)
				.containsExactly(InternalCallAuthenticationFilter.SERVICE_AUTHORITY);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("a wrong credential is refused with the envelope, and the reason is not distinguishable from absence")
	void aWrongCredentialIsRefused() throws Exception {
		MockHttpServletRequest wrong = internalRequest();
		wrong.addHeader(InternalCallProperties.CREDENTIAL_HEADER, "k3H9mQ2vX8pL5wN7cR4tY6uB1sD0fG9jA2eX");
		MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
		filter.doFilter(wrong, wrongResponse, new MockFilterChain());

		MockHttpServletRequest absent = internalRequest();
		MockHttpServletResponse absentResponse = new MockHttpServletResponse();
		filter.doFilter(absent, absentResponse, new MockFilterChain());

		for (MockHttpServletResponse response : new MockHttpServletResponse[] { wrongResponse, absentResponse }) {
			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getContentAsString()).contains("UNAUTHENTICATED");
			assertThat(response.getContentAsString()).doesNotContain("credential");
		}
		// Identical bodies: "no credential" and "wrong credential" are two facts a
		// prober would like to tell apart.
		assertThat(wrongResponse.getContentAsString().replaceAll("\"requestId\":\"[^\"]*\"", ""))
				.isEqualTo(absentResponse.getContentAsString().replaceAll("\"requestId\":\"[^\"]*\"", ""));
	}

	@Test
	@DisplayName("the filter only runs on the internal surface")
	void userRoutesAreNotTouched() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
		request.setRequestURI("/api/v1/health");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		// Untouched: no 401 written, no authentication established. The JWT half of
		// the chain owns this route.
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("the claimed service name is attribution only — an unnamed caller still gets no extra or fewer rights")
	void theClaimedNameCarriesNoAuthority() throws Exception {
		MockHttpServletRequest request = internalRequest();
		request.addHeader(InternalCallProperties.CREDENTIAL_HEADER, CREDENTIAL);
		// No X-Orca-Service header at all.
		MockHttpServletResponse response = new MockHttpServletResponse();

		var seen = new Authentication[1];
		filter.doFilter(request, response, new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				seen[0] = SecurityContextHolder.getContext().getAuthentication();
			}
		});

		assertThat(seen[0].getPrincipal()).isEqualTo("service:unnamed");
		assertThat(seen[0].getAuthorities())
				.extracting(Object::toString)
				.containsExactly(InternalCallAuthenticationFilter.SERVICE_AUTHORITY);
	}

	private static MockHttpServletRequest internalRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/commands/v1");
		request.setRequestURI("/internal/commands/v1");
		return request;
	}
}
