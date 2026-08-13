package com.lynxis.orca.runtime.readmodel.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.runtime.persistence.Utc;
import com.lynxis.orca.runtime.readmodel.domain.GridExportTables.GridExportJob;

import lombok.RequiredArgsConstructor;

/** Persistence for readmodel's bounded grid exports. */
@RequiredArgsConstructor
public class GridExportRepository {

	private static final String SCOPE_COLUMN = "site_external_id";
	private static final String[] COLUMNS = { "grid_export_job_id", "external_id",
			"site_external_id", "grid_name", "status", "content_type", "file_name", "row_count",
			"body", "error_message", "created_at", "completed_at" };

	private final ScopeSeam seam;

	public GridExportJob insertCompleted(String externalId, String siteExternalId, String gridName,
			String fileName, int rowCount, String body) {
		Instant now = Instant.now();
		seam.insert(ScopedInsert.into("grid_export_job")
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", externalId)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("grid_name", gridName)
				.value("status", GridExportJob.COMPLETED)
				.value("content_type", "text/csv; charset=utf-8")
				.value("file_name", fileName)
				.value("row_count", rowCount)
				.value("body", body)
				.value("created_at", Utc.timestampOf(now))
				.value("completed_at", Utc.timestampOf(now)));
		return byExternalId(externalId)
				.orElseThrow(() -> new IllegalStateException("Inserted export was not visible: " + externalId));
	}

	public Optional<GridExportJob> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from("grid_export_job")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("external_id = ?", externalId),
				(rs, row) -> map(rs)).stream().findFirst();
	}

	private static GridExportJob map(ResultSet rs) throws SQLException {
		return new GridExportJob(
				rs.getLong("grid_export_job_id"),
				rs.getString("external_id"),
				rs.getString("site_external_id"),
				rs.getString("grid_name"),
				rs.getString("status"),
				rs.getString("content_type"),
				rs.getString("file_name"),
				rs.getInt("row_count"),
				rs.getString("body"),
				rs.getString("error_message"),
				Utc.instantAt(rs, "created_at"),
				Utc.instantAt(rs, "completed_at"));
	}
}
