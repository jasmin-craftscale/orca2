package com.lynxis.orca.core.persistence;

import java.util.LinkedHashSet;
import java.util.Set;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * The sites this caller may scope things to — read from core's own {@code site}
 * table through the seam.
 *
 * <p>The scope column is the site's own {@code external_id}: for the one table
 * that <em>defines</em> sites, the site dimension and the row's identity are the
 * same thing, so {@code scopedBy("external_id", "site_external_id")} is the
 * honest declaration — a caller sees exactly the sites its scope permits, and
 * listing "all sites" is not expressible. The seam offers no unscoped read.
 */
@RequiredArgsConstructor
public class SiteDirectoryRepository {

	private final ScopeSeam seam;

	/** Active sites within the caller's scope, by external id. */
	public Set<String> activeSiteExternalIds() {
		return new LinkedHashSet<>(seam.select(ScopedSelect.from("site")
						.columns("external_id")
						.scopedBy("external_id", "site_external_id")
						.where("retired_at IS NULL")
						.orderBy("site_id"),
				(rs, row) -> rs.getString("external_id")));
	}
}
