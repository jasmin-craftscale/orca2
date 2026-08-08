package com.lynxis.orca.core.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The device-registry tables as of {@code V106__device_registry.sql} — the
 * completed {@code device} plus the port layout, PTZ presets, the three seeded
 * catalogs and scoped custom variables. Declarations for the build check,
 * mirroring the migrations, never {@code @Entity}. All {@link Growth#BOUNDED}:
 * rows appear when somebody registers or catalogs equipment.
 *
 * <p>{@code device}, {@code device_io_assignment}, {@code ptz_preset} and
 * {@code resource_configuration} are site-dimensional; the catalogs are
 * installation-realm.
 */
public final class DeviceTables {

	private DeviceTables() {
	}

	/**
	 * A piece of equipment at a lane — identity, addressing, and the parameters
	 * a device host needs to load its plugins (§C1). Deliberately WITHOUT
	 * credential columns: device-credential storage is security-shaped and
	 * PROPOSED in the WP3 report, not implemented.
	 *
	 * @param deviceTypeId one FK to the catalog — 1.x kept three denormalized
	 *                     copies of the type and all three died in V106
	 * @param assemblyName .NET plugin-load parameter, load-bearing for the
	 *                     frozen config poll (§D2)
	 */
	@PersistentTable(name = "device", growth = Growth.BOUNDED)
	public record Device(
			long deviceId,
			String externalId,
			long laneId,
			String siteExternalId,
			long deviceTypeId,
			String name,
			String address,
			String deviceMode,
			String deviceAlias,
			String deviceModel,
			String firmwareVersion,
			String description,
			String resolution,
			String ipAddress,
			String streamType,
			Integer port,
			String protocol,
			String manufacturer,
			String deviceUrl,
			String devicePortalUrl,
			String assemblyName,
			String className,
			String dataCaptureMode,
			Integer waitTime,
			Instant retiredAt,
			Instant createdAt) {
	}

	/**
	 * One port of a device's IO layout. The suppression flags are edge's to
	 * read; {@code is_output_port} was derivable from the type and did not port.
	 */
	@PersistentTable(name = "device_io_assignment", growth = Growth.BOUNDED)
	public record DeviceIoAssignment(
			long deviceIoAssignmentId,
			long deviceId,
			String siteExternalId,
			String portType,
			int ioPort,
			Long portNameId,
			String portAliasName,
			boolean isReverseState,
			boolean isInitialHigh,
			boolean isDataCapture,
			boolean isGosAudio,
			boolean produceTrueMessage,
			boolean produceFalseMessage,
			Integer waitTime,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** A named PTZ preset — numeric at last; execution is edge's (register NEW-5). */
	@PersistentTable(name = "ptz_preset", growth = Growth.BOUNDED)
	public record PtzPreset(
			long ptzPresetId,
			long deviceId,
			String siteExternalId,
			String name,
			BigDecimal pan,
			BigDecimal tilt,
			BigDecimal zoom,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Catalog: device types, seeded with pinned identity (12 of 16 — the sheet's list is partial). */
	@PersistentTable(name = "device_type", growth = Growth.BOUNDED)
	public record DeviceType(
			long deviceTypeId,
			String externalId,
			String configRealm,
			String code,
			String name,
			String formSchema,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Catalog: IO port names, seeded with pinned identity (36 of 40 — four audio names unextracted). */
	@PersistentTable(name = "device_io_port_name", growth = Growth.BOUNDED)
	public record DeviceIoPortName(
			long portNameId,
			String externalId,
			String configRealm,
			String portType,
			String code,
			String name,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Catalog: IO device kinds — created to shape, UNSEEDED until a row-level 1.x extraction exists. */
	@PersistentTable(name = "io_device_kind", growth = Growth.BOUNDED)
	public record IoDeviceKind(
			long ioDeviceKindId,
			String externalId,
			String configRealm,
			String portType,
			String code,
			String name,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** §C1's custom variables: typed scope, per-scope unique, sized value. */
	@PersistentTable(name = "resource_configuration", growth = Growth.BOUNDED)
	public record ResourceConfiguration(
			long resourceConfigurationId,
			String siteExternalId,
			String scopeType,
			String resourceExternalId,
			String configKey,
			String configValue,
			Instant retiredAt,
			Instant createdAt) {
	}
}
