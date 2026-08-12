package com.lynxis.orca.core.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.CustomEntityDeclaration;
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
	private static final String DECLARATION_VIEW = "topology_custom_entity";
	private static final String SCOPE = "site_external_id";

	private static final String[] DECLARATION_COLUMNS = {
		SCOPE, "custom_entity_id", "custom_entity_external_id", "entity_kind",
		"custom_entity_name", "table_identifier", "declaration_version",
		"custom_entity_field_id", "field_external_id", "field_identifier",
		"field_display_name", "field_type", "max_length", "number_precision",
		"number_scale", "is_nullable", "is_business_key", "field_ordinal",
		"field_created_at", "created_at", "updated_at" };

	private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, row) -> new DeclarationRow(
			new CustomEntity(
					rs.getLong("custom_entity_id"),
					rs.getString("custom_entity_external_id"),
					rs.getString(SCOPE),
					rs.getString("entity_kind"),
					rs.getString("custom_entity_name"),
					rs.getString("table_identifier"),
					rs.getLong("declaration_version"),
					Utc.instantAt(rs, "created_at"),
					Utc.instantAt(rs, "updated_at")),
			new CustomEntityField(
					rs.getLong("custom_entity_field_id"),
					rs.getString("field_external_id"),
					rs.getLong("custom_entity_id"),
					rs.getString(SCOPE),
					rs.getString("field_identifier"),
					rs.getString("field_display_name"),
					rs.getString("field_type"),
					(Integer) rs.getObject("max_length"),
					(Integer) rs.getObject("number_precision"),
					(Integer) rs.getObject("number_scale"),
					rs.getBoolean("is_nullable"),
					rs.getBoolean("is_business_key"),
					rs.getInt("field_ordinal"),
					Utc.instantAt(rs, "field_created_at")));

	private final ScopeSeam seam;

	/**
	 * Reads each declaration and all of its fields from one statement snapshot.
	 * Separate parent/child selects can pair a new declaration version with the
	 * preceding field list under read-committed snapshot isolation.
	 */
	public List<CustomEntityDeclaration> all() {
		return declarations(ScopedSelect.from(DECLARATION_VIEW)
				.columns(DECLARATION_COLUMNS)
				.scopedBy(SCOPE)
				.orderBy("custom_entity_field_id"));
	}

	public Optional<CustomEntityDeclaration> byExternalId(String externalId) {
		return declarations(ScopedSelect.from(DECLARATION_VIEW)
				.columns(DECLARATION_COLUMNS)
				.scopedBy(SCOPE)
				.where("custom_entity_external_id = ?", externalId)
				.orderBy("custom_entity_field_id"))
				.stream().findFirst();
	}

	private List<CustomEntityDeclaration> declarations(ScopedSelect select) {
		Map<Long, CustomEntity> entities = new LinkedHashMap<>();
		Map<Long, List<CustomEntityField>> fields = new LinkedHashMap<>();
		for (DeclarationRow row : seam.select(select, DECLARATION_MAPPER)) {
			long entityId = row.entity().customEntityId();
			entities.putIfAbsent(entityId, row.entity());
			fields.computeIfAbsent(entityId, ignored -> new ArrayList<>()).add(row.field());
		}
		return entities.values().stream()
				.sorted(java.util.Comparator.comparingLong(CustomEntity::customEntityId))
				.map(entity -> new CustomEntityDeclaration(entity, fields.get(entity.customEntityId()).stream()
						.sorted(java.util.Comparator.comparingInt(CustomEntityField::ordinal))
						.toList()))
				.toList();
	}

	private record DeclarationRow(CustomEntity entity, CustomEntityField field) {
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
