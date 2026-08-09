package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The four tables {@code V101__world_model.sql} creates, declared where the build
 * check can read them.
 *
 * <p><strong>These are declarations, not a mapping layer.</strong> They were added
 * before core needed to read its own tables, so introducing repositories or JPA
 * entities here would have taken a persistence decision prematurely. The growth
 * question could not wait: {@code RetentionClassRule} can inspect only tables a
 * class declares, and the current production system missed retention for
 * {@code execution} and {@code execution_context} precisely because no enforced
 * declaration made somebody answer that question.
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

	/** A customer facility with gates. Exactly one per installation is primary. */
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
	 * @param deviceHostUrl where this lane's .NET device host answers. It is per
	 *                      lane rather than per site because the frozen device-host
	 *                      contract addresses hosts by lane
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

	// The device record moved to DeviceTables when V106 completed the registry:
	// V101's provisional free-VARCHAR device_type was settled by the
	// seeded catalog, and the table outgrew this file's slice-sized shape.
}
