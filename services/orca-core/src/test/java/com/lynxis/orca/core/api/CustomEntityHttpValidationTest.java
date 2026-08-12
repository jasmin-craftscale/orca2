package com.lynxis.orca.core.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.lynxis.orca.core.domain.AuditTrail;
import com.lynxis.orca.core.domain.CustomEntityDeclaration;
import com.lynxis.orca.core.domain.CustomEntityService;
import com.lynxis.orca.core.domain.CustomEntityTables.CustomEntity;
import com.lynxis.orca.core.persistence.CustomEntityRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.platform.web.ApiExceptionHandler;

/** The generated request schema and the domain validator are distinct HTTP boundaries. */
@ExtendWith(MockitoExtension.class)
class CustomEntityHttpValidationTest {

	private static final String SITE = "SITE-HTTP-IT";

	@Mock
	private CustomEntityRepository entities;
	@Mock
	private SiteDirectoryRepository sites;
	@Mock
	private AuditTrail audit;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		CustomEntityService service = new CustomEntityService(entities, sites, audit);
		mvc = MockMvcBuilders.standaloneSetup(new CustomEntityController(service, SITE))
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("generated POST and PATCH validation reject uppercase identifiers as VALIDATION_FAILED")
	void uppercaseIdentifiersAreHttp400BeforeTheDomain() throws Exception {
		assertValidationFailed(post("/api/v1/custom-entities").content(declareBody("UpperCase")));
		assertValidationFailed(patch("/api/v1/custom-entities/ce-one")
				.content(evolveBody("UpperCase")));

		verifyNoInteractions(entities, sites, audit);
	}

	@Test
	@DisplayName("schema-valid reserved identifiers reach POST and PATCH domain validation as 422")
	void reservedIdentifiersAreDomain422() throws Exception {
		when(sites.activeSiteExternalIds()).thenReturn(Set.of(SITE));
		when(entities.byExternalId("ce-one")).thenReturn(Optional.of(existingDeclaration()));

		assertDeclarationInvalid(post("/api/v1/custom-entities").content(declareBody("row_id")));
		assertDeclarationInvalid(patch("/api/v1/custom-entities/ce-one")
				.content(evolveBody("site_external_id")));

		verify(sites).activeSiteExternalIds();
		verify(entities).byExternalId("ce-one");
		verifyNoMoreInteractions(entities, sites);
		verifyNoInteractions(audit);
	}

	private void assertValidationFailed(MockHttpServletRequestBuilder request) throws Exception {
		mvc.perform(request.contentType(APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value("ERROR"))
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	private void assertDeclarationInvalid(MockHttpServletRequestBuilder request) throws Exception {
		mvc.perform(request.contentType(APPLICATION_JSON))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.status").value("ERROR"))
				.andExpect(jsonPath("$.code").value("CUSTOM_ENTITY_DECLARATION_INVALID"));
	}

	private static String declareBody(String identifier) {
		return """
				{
				  "name": "HTTP declaration",
				  "kind": "REFERENCE",
				  "fields": [{
				    "identifier": "%s",
				    "displayName": "Code",
				    "type": "TEXT",
				    "maxLength": 32,
				    "nullable": false,
				    "businessKey": true,
				    "ordinal": 1
				  }]
				}
				""".formatted(identifier);
	}

	private static String evolveBody(String identifier) {
		return """
				{
				  "addFields": [{
				    "identifier": "%s",
				    "displayName": "Notes",
				    "type": "TEXT",
				    "maxLength": 32,
				    "nullable": true,
				    "ordinal": 2
				  }]
				}
				""".formatted(identifier);
	}

	private static CustomEntityDeclaration existingDeclaration() {
		Instant now = Instant.parse("2026-08-12T00:00:00Z");
		return new CustomEntityDeclaration(new CustomEntity(1L, "ce-one", SITE, "REFERENCE",
				"HTTP declaration", "ce_11111111111111111111111111111111", 1L, now, now),
				List.of());
	}
}
