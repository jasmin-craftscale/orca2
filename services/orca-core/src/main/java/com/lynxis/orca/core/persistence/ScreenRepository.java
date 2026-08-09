package com.lynxis.orca.core.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.RoutingTables.Screen;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** {@code screen} through the seam — site-dimensional like {@code team}. */
@RequiredArgsConstructor
public class ScreenRepository {

	private static final String SCOPE = "site_external_id";

	private static final String[] COLUMNS = { "screen_id", "external_id", "site_external_id", "name",
			"process_definition_key", "node_reference", "below_expected_sec", "expected_sec", "max_sec",
			"retired_at", "created_at" };

	private static final RowMapper<Screen> MAPPER = (rs, row) -> new Screen(
			rs.getLong("screen_id"),
			rs.getString("external_id"),
			rs.getString("site_external_id"),
			rs.getString("name"),
			rs.getString("process_definition_key"),
			rs.getString("node_reference"),
			(Integer) rs.getObject("below_expected_sec"),
			(Integer) rs.getObject("expected_sec"),
			(Integer) rs.getObject("max_sec"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<Screen> all() {
		return seam.select(ScopedSelect.from("screen")
						.columns(COLUMNS)
						.scopedBy(SCOPE)
						.orderBy("screen_id"),
				MAPPER);
	}

	public Optional<Screen> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from("screen")
						.columns(COLUMNS)
						.scopedBy(SCOPE)
						.where("external_id = ?", externalId),
				MAPPER).stream().findFirst();
	}

	public long insert(String externalId, String siteExternalId, String name,
			String processDefinitionKey, String nodeReference, Integer belowExpectedSec,
			Integer expectedSec, Integer maxSec) {
		return seam.insertReturningKey(ScopedInsert.into("screen")
						.scopedBy(SCOPE)
						.value("external_id", externalId)
						.value(SCOPE, siteExternalId)
						.value("name", name)
						.value("process_definition_key", processDefinitionKey)
						.value("node_reference", nodeReference)
						.value("below_expected_sec", belowExpectedSec)
						.value("expected_sec", expectedSec)
						.value("max_sec", maxSec),
				"screen_id");
	}

	/**
	 * Applies the non-null changes. Thresholds are tri-state: the API's 0 means
	 * "clear back to the fallback", arriving here as an explicit clear — the same
	 * shape as the team's template references.
	 */
	public int update(String externalId, String name, String processDefinitionKey,
			String nodeReference, Integer belowExpectedSec, boolean clearBelowExpected,
			Integer expectedSec, boolean clearExpected, Integer maxSec, boolean clearMax,
			Boolean retired) {
		ScopedUpdate update = ScopedUpdate.table("screen")
				.scopedBy(SCOPE)
				.where("external_id = ?", externalId);
		boolean touched = false;
		if (name != null) {
			update.set("name", name);
			touched = true;
		}
		if (processDefinitionKey != null) {
			update.set("process_definition_key", processDefinitionKey);
			touched = true;
		}
		if (nodeReference != null) {
			update.set("node_reference", nodeReference);
			touched = true;
		}
		if (belowExpectedSec != null || clearBelowExpected) {
			update.set("below_expected_sec", clearBelowExpected ? null : belowExpectedSec);
			touched = true;
		}
		if (expectedSec != null || clearExpected) {
			update.set("expected_sec", clearExpected ? null : expectedSec);
			touched = true;
		}
		if (maxSec != null || clearMax) {
			update.set("max_sec", clearMax ? null : maxSec);
			touched = true;
		}
		if (retired != null) {
			update.set("retired_at", retired ? Utc.now() : null);
			touched = true;
		}
		return touched ? seam.update(update) : 0;
	}
}
