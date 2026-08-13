package com.lynxis.orca.core.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lynxis.orca.core.domain.CustomEntityService.CustomEntityValidationException;
import com.lynxis.orca.core.domain.CustomEntityService.EntityKind;
import com.lynxis.orca.core.domain.CustomEntityService.FieldDeclaration;
import com.lynxis.orca.core.domain.CustomEntityService.FieldType;
import com.lynxis.orca.core.persistence.CustomEntityRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;

class CustomEntityServiceTest {

	private static final String SITE = "SITE-UNIT";

	private CustomEntityRepository entities;
	private AuditTrail audit;
	private CustomEntityService service;

	@BeforeEach
	void serviceWithKnownSite() {
		entities = mock(CustomEntityRepository.class);
		audit = mock(AuditTrail.class);
		SiteDirectoryRepository sites = mock(SiteDirectoryRepository.class);
		when(sites.activeSiteExternalIds()).thenReturn(Set.of(SITE));
		service = new CustomEntityService(entities, sites, audit);
	}

	@Test
	void exactlyOneNonNullableBusinessKeyIsRequiredBeforeAnyWrite() {
		assertThatThrownBy(() -> service.declare(SITE, "No key", EntityKind.REFERENCE,
				List.of(text("code", false, false))))
				.isInstanceOf(CustomEntityValidationException.class)
				.hasMessageContaining("exactly one");
		assertThatThrownBy(() -> service.declare(SITE, "Nullable key", EntityKind.REFERENCE,
				List.of(text("code", true, true))))
				.isInstanceOf(CustomEntityValidationException.class)
				.hasMessageContaining("cannot be nullable");

		verifyNoInteractions(entities, audit);
	}

	@Test
	void futureRowKeysAndUnsafeSqlIdentifiersAreRefusedBeforeAnyWrite() {
		for (String identifier : List.of("row_id", "external_id", "site_external_id",
				"UpperCase", "two words", "1starts_wrong")) {
			assertThatThrownBy(() -> service.declare(SITE, "Unsafe", EntityKind.EVENT,
					List.of(text(identifier, false, true))))
					.isInstanceOf(CustomEntityValidationException.class)
					.hasMessageContaining("storage identifier");
		}

		verifyNoInteractions(entities, audit);
	}

	@Test
	void typeModifiersAreAClosedShapeBeforeAnyWrite() {
		assertThatThrownBy(() -> service.declare(SITE, "Bad text", EntityKind.REFERENCE,
				List.of(new FieldDeclaration("code", "Code", FieldType.TEXT,
						null, null, null, false, true, 1))))
				.isInstanceOf(CustomEntityValidationException.class).hasMessageContaining("maxLength");
		assertThatThrownBy(() -> service.declare(SITE, "Bad number", EntityKind.REFERENCE,
				List.of(new FieldDeclaration("amount", "Amount", FieldType.NUMBER,
						null, 4, 5, false, true, 1))))
				.isInstanceOf(CustomEntityValidationException.class).hasMessageContaining("scale");
		assertThatThrownBy(() -> service.declare(SITE, "Bad date", EntityKind.REFERENCE,
				List.of(new FieldDeclaration("day", "Day", FieldType.DATE,
						10, null, null, false, true, 1))))
				.isInstanceOf(CustomEntityValidationException.class).hasMessageContaining("no modifiers");

		verifyNoInteractions(entities, audit);
	}

	@Test
	void fieldDisplayNamesAreUniqueAfterNormalizationBeforeAnyWrite() {
		assertThatThrownBy(() -> service.declare(SITE, "Duplicate labels", EntityKind.REFERENCE,
				List.of(
						new FieldDeclaration("code", " Code ", FieldType.TEXT,
								40, null, null, false, true, 1),
						new FieldDeclaration("description", "code", FieldType.TEXT,
								80, null, null, true, false, 2))))
				.isInstanceOf(CustomEntityValidationException.class)
				.hasMessageContaining("display name");

		verifyNoInteractions(entities, audit);
	}

	private static FieldDeclaration text(String identifier, boolean nullable, boolean businessKey) {
		return new FieldDeclaration(identifier, "Code", FieldType.TEXT, 40, null, null,
				nullable, businessKey, 1);
	}
}
