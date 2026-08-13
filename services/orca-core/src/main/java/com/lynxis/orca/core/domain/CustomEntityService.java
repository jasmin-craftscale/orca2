package com.lynxis.orca.core.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.CustomEntityTables.CustomEntityField;
import com.lynxis.orca.core.persistence.CustomEntityRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;

import lombok.RequiredArgsConstructor;

/** Declares site-owned custom entities without applying their shape as DDL. */
@RequiredArgsConstructor
public class CustomEntityService {

	public static final String ROW_ID_COLUMN = "row_id";
	public static final String ROW_EXTERNAL_ID_COLUMN = "external_id";
	public static final String ROW_SITE_EXTERNAL_ID_COLUMN = "site_external_id";

	private static final Pattern FIELD_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
	private static final Set<String> RESERVED_FIELDS = Set.of(
			ROW_ID_COLUMN, ROW_EXTERNAL_ID_COLUMN, ROW_SITE_EXTERNAL_ID_COLUMN);

	private final CustomEntityRepository entities;
	private final SiteDirectoryRepository sites;
	private final AuditTrail audit;

	public enum EntityKind {
		REFERENCE, EVENT
	}

	public enum FieldType {
		TEXT, NUMBER, BOOLEAN, DATE
	}

	/** The complete shape of one field at the declaration boundary. */
	public record FieldDeclaration(
			String identifier,
			String displayName,
			FieldType type,
			Integer maxLength,
			Integer precision,
			Integer scale,
			boolean nullable,
			boolean businessKey,
			int ordinal) {
	}

	public List<CustomEntityDeclaration> list() {
		return entities.all();
	}

	@Transactional
	public CustomEntityDeclaration declare(String siteExternalId, String name, EntityKind kind,
			List<FieldDeclaration> fields) {
		String validName = requireDisplayName(name, "Custom-entity name");
		if (kind == null) {
			throw invalid("Entity kind is required.");
		}
		requireSite(siteExternalId);
		requireFields(fields, true);

		String uuid = UUID.randomUUID().toString();
		String externalId = "ce-" + uuid;
		String tableIdentifier = "ce_" + uuid.replace("-", "");
		try {
			long entityId = entities.insertEntity(externalId, siteExternalId, kind.name(),
					validName, tableIdentifier);
			for (FieldDeclaration field : fields) {
				insertField(entityId, siteExternalId, field);
			}
		}
		catch (DuplicateKeyException conflict) {
			throw new CustomEntityConflictException(
					"A declaration of this kind and name, or one of its field identifiers or display names, is already in use.");
		}
		audit.record("CUSTOM_ENTITY", externalId, "CREATED", "kind=" + kind.name());
		return byExternalId(externalId);
	}

	@Transactional
	public CustomEntityDeclaration evolve(String externalId, String name,
			List<FieldDeclaration> addedFields) {
		CustomEntityDeclaration existing = byExternalId(externalId);
		List<FieldDeclaration> additions = addedFields == null ? List.of() : addedFields;
		String validName = name == null ? null : requireDisplayName(name, "Custom-entity name");
		boolean rename = validName != null && !validName.equals(existing.entity().name());
		if (!rename && additions.isEmpty()) {
			throw invalid("An evolution must change the display name or add at least one field.");
		}
		requireFields(additions, false);
		requireNoExistingCollisions(existing.fields(), additions);

		try {
			for (FieldDeclaration field : additions) {
				insertField(existing.entity().customEntityId(), existing.entity().siteExternalId(), field);
			}
			if (entities.evolve(externalId, rename ? validName : null) != 1) {
				throw new CustomEntityUnknownException(externalId);
			}
		}
		catch (DuplicateKeyException conflict) {
			throw new CustomEntityConflictException(
					"The requested name, field identifier, field display name or field ordinal is already in use.");
		}
		audit.record("CUSTOM_ENTITY", externalId, "UPDATED",
				(rename ? "display name; " : "") + additions.size() + " field(s) added");
		return byExternalId(externalId);
	}

	private CustomEntityDeclaration byExternalId(String externalId) {
		return entities.byExternalId(externalId)
				.orElseThrow(() -> new CustomEntityUnknownException(externalId));
	}

	private void requireSite(String siteExternalId) {
		if (!sites.activeSiteExternalIds().contains(siteExternalId)) {
			throw new CustomEntitySiteUnknownException(siteExternalId);
		}
	}

	private void insertField(long entityId, String siteExternalId, FieldDeclaration field) {
		entities.insertField(entityId, siteExternalId, "cef-" + UUID.randomUUID(),
				field.identifier(), requireDisplayName(field.displayName(), "Field display name"),
				field.type().name(), field.maxLength(), field.precision(), field.scale(),
				field.nullable(), field.businessKey(), field.ordinal());
	}

	private static void requireFields(List<FieldDeclaration> fields, boolean newDeclaration) {
		if (fields == null || (newDeclaration && fields.isEmpty())) {
			throw invalid("A new declaration requires at least one field.");
		}
		Set<String> identifiers = new HashSet<>();
		Set<String> displayNames = new HashSet<>();
		Set<Integer> ordinals = new HashSet<>();
		long businessKeys = 0;
		for (FieldDeclaration field : fields) {
			if (field == null) {
				throw invalid("A field declaration cannot be null.");
			}
			requireField(field);
			if (!identifiers.add(field.identifier())) {
				throw invalid("Field identifier '" + field.identifier() + "' appears more than once.");
			}
			if (!displayNames.add(normalizedDisplayName(field.displayName()))) {
				throw invalid("Field display name '" + field.displayName() + "' appears more than once.");
			}
			if (!ordinals.add(field.ordinal())) {
				throw invalid("Field ordinal " + field.ordinal() + " appears more than once.");
			}
			if (field.businessKey()) {
				businessKeys++;
			}
		}
		if (newDeclaration && businessKeys != 1) {
			throw invalid("A new declaration requires exactly one business-key field.");
		}
		if (!newDeclaration && businessKeys != 0) {
			throw invalid("Additive evolution cannot add or replace the business key.");
		}
	}

	private static void requireField(FieldDeclaration field) {
		String identifier = field.identifier();
		if (identifier == null || !FIELD_IDENTIFIER.matcher(identifier).matches()
				|| RESERVED_FIELDS.contains(identifier)) {
			throw invalid("Field identifier '" + identifier
					+ "' is not a permitted lower-case storage identifier.");
		}
		requireDisplayName(field.displayName(), "Field display name");
		if (field.type() == null) {
			throw invalid("Field '" + identifier + "' requires a type.");
		}
		if (field.ordinal() < 1) {
			throw invalid("Field '" + identifier + "' requires a positive ordinal.");
		}
		if (field.businessKey() && field.nullable()) {
			throw invalid("Business-key field '" + identifier + "' cannot be nullable.");
		}
		switch (field.type()) {
			case TEXT -> {
				if (field.maxLength() == null || field.maxLength() < 1 || field.maxLength() > 4000
						|| field.precision() != null || field.scale() != null) {
					throw invalid("TEXT field '" + identifier
							+ "' requires maxLength 1..4000 and no numeric modifiers.");
				}
			}
			case NUMBER -> {
				if (field.maxLength() != null || field.precision() == null || field.scale() == null
						|| field.precision() < 1 || field.precision() > 38
						|| field.scale() < 0 || field.scale() > field.precision()) {
					throw invalid("NUMBER field '" + identifier
							+ "' requires precision 1..38 and scale 0..precision.");
				}
			}
			case BOOLEAN, DATE -> {
				if (field.maxLength() != null || field.precision() != null || field.scale() != null) {
					throw invalid(field.type() + " field '" + identifier + "' accepts no modifiers.");
				}
			}
		}
	}

	private static void requireNoExistingCollisions(List<CustomEntityField> existing,
			List<FieldDeclaration> additions) {
		Set<String> identifiers = existing.stream().map(CustomEntityField::identifier)
				.collect(Collectors.toSet());
		Set<String> displayNames = existing.stream().map(CustomEntityField::displayName)
				.map(CustomEntityService::normalizedDisplayName)
				.collect(Collectors.toSet());
		Set<Integer> ordinals = existing.stream().map(CustomEntityField::ordinal)
				.collect(Collectors.toSet());
		for (FieldDeclaration field : additions) {
			if (identifiers.contains(field.identifier())) {
				throw new CustomEntityConflictException(
						"Field identifier '" + field.identifier() + "' is already declared.");
			}
			if (displayNames.contains(normalizedDisplayName(field.displayName()))) {
				throw new CustomEntityConflictException(
						"Field display name '" + field.displayName() + "' is already declared.");
			}
			if (ordinals.contains(field.ordinal())) {
				throw new CustomEntityConflictException(
						"Field ordinal " + field.ordinal() + " is already declared.");
			}
		}
	}

	private static String requireDisplayName(String value, String what) {
		if (value == null) {
			throw invalid(what + " must contain 1..100 characters.");
		}
		String normalized = value.strip();
		if (normalized.isEmpty() || normalized.length() > 100) {
			throw invalid(what + " must contain 1..100 characters.");
		}
		return normalized;
	}

	private static String normalizedDisplayName(String value) {
		return requireDisplayName(value, "Field display name").toLowerCase(Locale.ROOT);
	}

	private static CustomEntityValidationException invalid(String reason) {
		return new CustomEntityValidationException(reason);
	}

	public static class CustomEntityValidationException extends RuntimeException {
		public CustomEntityValidationException(String reason) {
			super(reason);
		}
	}

	public static class CustomEntityConflictException extends RuntimeException {
		public CustomEntityConflictException(String reason) {
			super(reason);
		}
	}

	public static class CustomEntityUnknownException extends RuntimeException {
		public CustomEntityUnknownException(String externalId) {
			super("No custom-entity declaration '" + externalId + "'.");
		}
	}

	public static class CustomEntitySiteUnknownException extends RuntimeException {
		public CustomEntitySiteUnknownException(String siteExternalId) {
			super("No active site '" + siteExternalId + "'.");
		}
	}
}
