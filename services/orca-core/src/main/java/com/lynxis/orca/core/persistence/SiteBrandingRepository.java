package com.lynxis.orca.core.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.WorkspaceTables.SiteColor;
import com.lynxis.orca.core.domain.WorkspaceTables.SiteLanguage;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** Per-site branding colors and languages — site-dimensional. */
@RequiredArgsConstructor
public class SiteBrandingRepository {

	private static final String SCOPE = "site_external_id";

	private static final RowMapper<SiteColor> COLOR_MAPPER = (rs, row) -> new SiteColor(
			rs.getLong("site_color_id"),
			rs.getString("site_external_id"),
			rs.getString("code"),
			rs.getString("hex_value"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<SiteLanguage> LANGUAGE_MAPPER = (rs, row) -> new SiteLanguage(
			rs.getLong("site_language_id"),
			rs.getString("site_external_id"),
			rs.getString("code"),
			rs.getString("name"),
			rs.getString("resource_path"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<SiteColor> activeColors(String siteExternalId) {
		return seam.select(ScopedSelect.from("site_color")
						.columns("site_color_id", "site_external_id", "code", "hex_value",
								"retired_at", "created_at")
						.scopedBy(SCOPE)
						.where("site_external_id = ? AND retired_at IS NULL", siteExternalId)
						.orderBy("site_color_id"),
				COLOR_MAPPER);
	}

	public void replaceColors(String siteExternalId, Collection<SiteColor> colors) {
		seam.update(ScopedUpdate.table("site_color")
				.set("retired_at", Utc.now())
				.scopedBy(SCOPE)
				.where("site_external_id = ? AND retired_at IS NULL", siteExternalId));
		for (SiteColor color : colors) {
			seam.insert(ScopedInsert.into("site_color")
					.scopedBy(SCOPE)
					.value(SCOPE, siteExternalId)
					.value("code", color.code())
					.value("hex_value", color.hexValue()));
		}
	}

	public List<SiteLanguage> activeLanguages(String siteExternalId) {
		return seam.select(ScopedSelect.from("site_language")
						.columns("site_language_id", "site_external_id", "code", "name",
								"resource_path", "retired_at", "created_at")
						.scopedBy(SCOPE)
						.where("site_external_id = ? AND retired_at IS NULL", siteExternalId)
						.orderBy("site_language_id"),
				LANGUAGE_MAPPER);
	}

	public void replaceLanguages(String siteExternalId, Collection<SiteLanguage> languages) {
		seam.update(ScopedUpdate.table("site_language")
				.set("retired_at", Utc.now())
				.scopedBy(SCOPE)
				.where("site_external_id = ? AND retired_at IS NULL", siteExternalId));
		for (SiteLanguage language : languages) {
			seam.insert(ScopedInsert.into("site_language")
					.scopedBy(SCOPE)
					.value(SCOPE, siteExternalId)
					.value("code", language.code())
					.value("name", language.name())
					.value("resource_path", language.resourcePath()));
		}
	}
}
