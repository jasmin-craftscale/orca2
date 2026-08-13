package com.lynxis.orca.runtime.readmodel.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/** The table {@code V139__grid_exports.sql} creates. */
public final class GridExportTables {

	private GridExportTables() {
	}

	/**
	 * One bounded console-grid export.
	 *
	 * <p>The retention class name is PROVISIONAL. Export jobs are traffic-growing
	 * convenience artifacts rather than a system of record; Stream 4 owns the final
	 * retention catalog and purge job.
	 */
	@PersistentTable(name = "grid_export_job", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("grid_export") // PROVISIONAL
	public record GridExportJob(
			long gridExportJobId,
			String externalId,
			String siteExternalId,
			String gridName,
			String status,
			String contentType,
			String fileName,
			int rowCount,
			String body,
			String errorMessage,
			Instant createdAt,
			Instant completedAt) {

		public static final String COMPLETED = "COMPLETED";
		public static final String FAILED = "FAILED";
	}
}
