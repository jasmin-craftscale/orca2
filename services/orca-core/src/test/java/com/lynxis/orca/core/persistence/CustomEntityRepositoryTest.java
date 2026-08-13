package com.lynxis.orca.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

class CustomEntityRepositoryTest {

	@Test
	@DisplayName("one declaration aggregate is assembled by exactly one scoped select")
	void declarationAssemblyUsesOneStatement() throws Exception {
		ScopeSeam seam = mock(ScopeSeam.class);
		ResultSet row = declarationRow();
		when(seam.select(any(ScopedSelect.class), any())).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			RowMapper<Object> mapper = invocation.getArgument(1);
			return List.of(mapper.mapRow(row, 0));
		});

		var declarations = new CustomEntityRepository(seam).all();

		assertThat(declarations).singleElement().satisfies(declaration -> {
			assertThat(declaration.entity().externalId()).isEqualTo("ce-one");
			assertThat(declaration.fields()).singleElement()
					.satisfies(field -> assertThat(field.identifier()).isEqualTo("code"));
		});
		verify(seam).select(any(ScopedSelect.class), any());
		verifyNoMoreInteractions(seam);
	}

	private static ResultSet declarationRow() throws Exception {
		ResultSet row = mock(ResultSet.class);
		when(row.getLong("custom_entity_id")).thenReturn(1L);
		when(row.getLong("declaration_version")).thenReturn(3L);
		when(row.getLong("custom_entity_field_id")).thenReturn(10L);
		when(row.getString("custom_entity_external_id")).thenReturn("ce-one");
		when(row.getString("site_external_id")).thenReturn("SITE-A");
		when(row.getString("entity_kind")).thenReturn("REFERENCE");
		when(row.getString("custom_entity_name")).thenReturn("Vehicle class");
		when(row.getString("table_identifier"))
				.thenReturn("ce_11111111111111111111111111111111");
		when(row.getString("field_external_id")).thenReturn("cef-one");
		when(row.getString("field_identifier")).thenReturn("code");
		when(row.getString("field_display_name")).thenReturn("Code");
		when(row.getString("field_type")).thenReturn("TEXT");
		when(row.getObject("max_length")).thenReturn(32);
		when(row.getBoolean("is_nullable")).thenReturn(false);
		when(row.getBoolean("is_business_key")).thenReturn(true);
		when(row.getInt("field_ordinal")).thenReturn(1);
		LocalDateTime now = LocalDateTime.of(2026, 8, 12, 0, 0);
		when(row.getObject("field_created_at", LocalDateTime.class)).thenReturn(now);
		when(row.getObject("created_at", LocalDateTime.class)).thenReturn(now);
		when(row.getObject("updated_at", LocalDateTime.class)).thenReturn(now);
		return row;
	}
}
