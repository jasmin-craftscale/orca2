package com.lynxis.orca.core.domain;

import java.util.List;

import com.lynxis.orca.core.domain.CustomEntityTables.CustomEntity;
import com.lynxis.orca.core.domain.CustomEntityTables.CustomEntityField;

/** Entity metadata and its ordered fields, assembled under one site scope. */
public record CustomEntityDeclaration(CustomEntity entity, List<CustomEntityField> fields) {

	public CustomEntityDeclaration {
		fields = List.copyOf(fields);
	}
}
