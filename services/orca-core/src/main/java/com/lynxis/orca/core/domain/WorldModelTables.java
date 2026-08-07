package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The four tables {@code V101__world_model.sql} creates, declared where the build
 * check can read them.
 *
 * <p><strong>These are declarations, not a mapping layer.</strong> Nothing in
 * Phase 1 reads core's own tables from core's own Java — the slice reads the
 * published views from other services — so a repository or an entity here would
 * be a persistence design taken before anything needs one. What cannot wait is
 * the growth question: {@code RetentionClassRule} can only see a table that some
 * class declares, and §B10's guarantee is stated over <em>every</em> table that
 * grows with traffic. §C2 records that {@code execution} and
 * {@code execution_context} are how the current system came to miss two.
 *
 * <p>All four are {@link Growth#BOUNDED} and therefore carry no retention class.
 * That is not a convenience: a row appears in one of these when an administrator
 * configures something, never when a truck arrives. The first traffic-growing
 * table core owns will have to answer differently, and the check is what will
 * ask.
 *
 * <p>The records mirror the migration column for column so that the two cannot
 * quietly diverge without somebody noticing while editing one of them. They are
 * deliberately not annotated {@code @Entity}: Hibernate owning this schema is
 * exactly what {@code spring.jpa.hibernate.ddl-auto: none} exists to prevent.
 */
public final class WorldModelTables {

	private WorldModelTables() {
	}

	/** A customer facility with gates. Exactly one per installation is primary (§C1). */
	@PersistentTable(name = "site", growth = Growth.BOUNDED)
	public record Site(
			long siteId,
			String externalId,
			String code,
			String name,
			boolean isPrimary,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** A subdivision of a site. */
	@PersistentTable(name = "area", growth = Growth.BOUNDED)
	public record Area(
			long areaId,
			String externalId,
			long siteId,
			String code,
			String name,
			Instant retiredAt,
			Instant createdAt) {
	}

	/**
	 * Where a truck is processed and where devices live.
	 *
	 * @param deviceHostUrl where this lane's .NET device host answers. Per lane
	 *                      rather than per site, because the frozen contract (§D2)
	 *                      is per lane
	 */
	@PersistentTable(name = "lane", growth = Growth.BOUNDED)
	public record Lane(
			long laneId,
			String externalId,
			long areaId,
			String code,
			String name,
			String deviceHostUrl,
			boolean isOutOfService,
			int lanePriority,
			Instant retiredAt,
			Instant createdAt) {
	}

	/**
	 * A piece of equipment at a lane.
	 *
	 * @param deviceType ⚠️ <strong>provisional vocabulary.</strong> Nothing in the
	 *                   architecture enumerates device types — §C3 enumerates
	 *                   command <em>actions</em>, not device kinds — so this is a
	 *                   free string rather than an enum, and the values this phase
	 *                   uses ({@code LPR_CAMERA}, {@code BARRIER}) are named as
	 *                   provisional in the migration and in the phase report. An
	 *                   enum here would publish a vocabulary the corpus has not
	 *                   settled
	 */
	@PersistentTable(name = "device", growth = Growth.BOUNDED)
	public record Device(
			long deviceId,
			String externalId,
			long laneId,
			String deviceType,
			String name,
			String address,
			Instant retiredAt,
			Instant createdAt) {
	}
}
