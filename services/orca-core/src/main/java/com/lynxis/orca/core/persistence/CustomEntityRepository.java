package com.lynxis.orca.core.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.CustomEntityTables.CustomEntity;
import com.lynxis.orca.core.domain.CustomEntityTables.CustomEntityField;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** The declared custom-entity model through the mandatory site scope seam. */
@RequiredArgsConstructor
public class CustomEntityRepository {

	private static final String ENTITY_TABLE = "custom_entity";
	private static final String FIELD_TABLE = "custom_entity_field";
	private static final String SCOPE = "site_external_id";

	private static final String[] ENTITY_COLUMNS = {
		"custom_entity_id", "external_id", SCOPE, "entity_kind", "name",
		"table_identifier", "declaration_version", "created_at", "updated_at" };

	private static final String[] FIELD_COLUMNS = {
		"custom_entity_field_id", "external_id", "custom_entity_id", SCOPE,
		"identifier", "display_name", "field_type", "max_length",
		"number_precision", "number_scale", "is_nullable", "is_business_key",
		"ordinal", "created_at" };

	private static final RowMapper<CustomEntity> ENTITY_MAPPER = (rs, row) -> new CustomEntity(
			rs.getLong("custom_entity_id"),
			rs.getString("external_id"),
			rs.getString(SCOPE),
			rs.getString("entity_kind"),
			rs.getString("name"),
			rs.getString("table_identifier"),
			rs.getLong("declaration_version"),
			Utc.instantAt(rs, "created_at"),
			Utc.instantAt(rs, "updated_at"));

	private static final RowMapper<CustomEntityField> FIELD_MAPPER = (rs, row) -> new CustomEntityField(
			rs.getLong("custom_entity_field_id"),
			rs.getString("external_id"),
			rs.getLong("custom_entity_id"),
			rs.getString(SCOPE),
			rs.getString("identifier"),
			rs.getString("display_name"),
			rs.getString("field_type"),
			(Integer) rs.getObject("max_length"),
			(Integer) rs.getObject("number_precision"),
			(Integer) rs.getObject("number_scale"),
			rs.getBoolean("is_nullable"),
			rs.getBoolean("is_business_key"),
			rs.getInt("ordinal"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<CustomEntity> all() {
		return seam.select(ScopedSelect.from(ENTITY_TABLE)
				.columns(ENTITY_COLUMNS)
				.scopedBy(SCOPE)
				.orderBy("custom_entity_id"), ENTITY_MAPPER);
	}

	public Optional<CustomEntity> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from(ENTITY_TABLE)
				.columns(ENTITY_COLUMNS)
				.scopedBy(SCOPE)
				.where("external_id = ?", externalId), ENTITY_MAPPER)
				.stream().findFirst();
	}

	public List<CustomEntityField> allFields() {
		return seam.select(ScopedSelect.from(FIELD_TABLE)
				.columns(FIELD_COLUMNS)
				.scopedBy(SCOPE)
				.orderBy("custom_entity_field_id"), FIELD_MAPPER);
	}

	public List<CustomEntityField> fieldsFor(long customEntityId) {
		return seam.select(ScopedSelect.from(FIELD_TABLE)
				.columns(FIELD_COLUMNS)
				.scopedBy(SCOPE)
				.where("custom_entity_id = ?", customEntityId)
				.orderBy("ordinal"), FIELD_MAPPER);
	}

	public long insertEntity(String externalId, String siteExternalId, String entityKind,
			String name, String tableIdentifier) {
		return seam.insertReturningKey(ScopedInsert.into(ENTITY_TABLE)
				.scopedBy(SCOPE)
				.value("external_id", externalId)
				.value(SCOPE, siteExternalId)
				.value("entity_kind", entityKind)
				.value("name", name)
				.value("table_identifier", tableIdentifier), "custom_entity_id");
	}

	public void insertField(long customEntityId, String siteExternalId, String externalId,
			String identifier, String displayName, String fieldType, Integer maxLength,
			Integer numberPrecision, Integer numberScale, boolean nullable,
			boolean businessKey, int ordinal) {
		seam.insert(ScopedInsert.into(FIELD_TABLE)
				.scopedBy(SCOPE)
				.value("external_id", externalId)
				.value("custom_entity_id", customEntityId)
				.value(SCOPE, siteExternalId)
				.value("identifier", identifier)
				.value("display_name", displayName)
				.value("field_type", fieldType)
				.value("max_length", maxLength)
				.value("number_precision", numberPrecision)
				.value("number_scale", numberScale)
				.value("is_nullable", nullable)
				.value("is_business_key", businessKey)
				.value("ordinal", ordinal));
	}

	/** One additive evolution advances one version, regardless of how many fields it adds. */
	public int evolve(String externalId, String name) {
		ScopedUpdate update = ScopedUpdate.table(ENTITY_TABLE)
				.scopedBy(SCOPE)
				.increment("declaration_version", 1)
				.set("updated_at", Utc.now())
				.where("external_id = ?", externalId);
		if (name != null) {
			update.set("name", name);
		}
		return seam.update(update);
	}
}
