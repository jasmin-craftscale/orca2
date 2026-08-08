package com.lynxis.orca.core.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.DeviceTables.Device;
import com.lynxis.orca.core.domain.DeviceTables.DeviceIoAssignment;
import com.lynxis.orca.core.domain.DeviceTables.DeviceIoPortName;
import com.lynxis.orca.core.domain.DeviceTables.DeviceType;
import com.lynxis.orca.core.domain.DeviceTables.PtzPreset;
import com.lynxis.orca.core.persistence.DeviceCatalogRepository;
import com.lynxis.orca.core.persistence.DeviceRepository;
import com.lynxis.orca.core.persistence.DeviceRepository.DevicePatch;
import com.lynxis.orca.core.persistence.DeviceRepository.LaneRef;

import lombok.RequiredArgsConstructor;

/**
 * The device registry's admin operations (§C1): inventory per lane,
 * registration, update, retirement, the IO port layout and PTZ presets.
 *
 * <p>The type vocabulary is the seeded catalog — a device cannot claim a type
 * the catalog does not carry, which is what makes the catalog a contract
 * rather than a suggestion. No credential passes through here, deliberately.
 */
@RequiredArgsConstructor
public class DeviceAdminService {

	private final DeviceRepository devices;
	private final DeviceCatalogRepository catalogs;

	public record DeviceView(Device device, String laneExternalId, String deviceTypeCode,
			List<DeviceIoAssignment> ioAssignments, List<String> portNameCodes,
			List<PtzPreset> perspectives) {
	}

	public List<DeviceView> byLane(String laneExternalId) {
		LaneRef lane = devices.laneByExternalId(laneExternalId)
				.orElseThrow(() -> new LaneUnknownException(laneExternalId));
		return assemble(devices.byLane(lane.laneId()));
	}

	@Transactional
	public DeviceView register(String laneExternalId, Device draft, String deviceTypeCode) {
		LaneRef lane = devices.laneByExternalId(laneExternalId)
				.orElseThrow(() -> new LaneUnknownException(laneExternalId));
		long deviceTypeId = resolveType(deviceTypeCode);
		String externalId = "dev-" + UUID.randomUUID();
		devices.insert(new Device(0, externalId, lane.laneId(),
				ScopeSiteResolver.requireSingleSite(), deviceTypeId,
				draft.name(), draft.address(), draft.deviceMode(), draft.deviceAlias(),
				draft.deviceModel(), draft.firmwareVersion(), draft.description(),
				draft.resolution(), draft.ipAddress(), draft.streamType(), draft.port(),
				draft.protocol(), draft.manufacturer(), draft.deviceUrl(), draft.devicePortalUrl(),
				draft.assemblyName(), draft.className(), draft.dataCaptureMode(), draft.waitTime(),
				null, null));
		return viewOf(externalId);
	}

	@Transactional
	public DeviceView update(String externalId, DevicePatch patch, String deviceTypeCode) {
		devices.byExternalId(externalId).orElseThrow(() -> new DeviceUnknownException(externalId));
		Long deviceTypeId = deviceTypeCode == null ? null : resolveType(deviceTypeCode);
		devices.update(externalId, new DevicePatch(deviceTypeId, patch.name(), patch.address(),
				patch.deviceMode(), patch.deviceAlias(), patch.deviceModel(), patch.firmwareVersion(),
				patch.description(), patch.resolution(), patch.ipAddress(), patch.streamType(),
				patch.port(), patch.protocol(), patch.manufacturer(), patch.deviceUrl(),
				patch.devicePortalUrl(), patch.assemblyName(), patch.className(),
				patch.dataCaptureMode(), patch.waitTime(), patch.retired()));
		return viewOf(externalId);
	}

	@Transactional
	public DeviceView retire(String externalId) {
		devices.byExternalId(externalId).orElseThrow(() -> new DeviceUnknownException(externalId));
		devices.update(externalId, new DevicePatch(null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, true));
		return viewOf(externalId);
	}

	/** PUT semantics: the given layout is the whole layout. Port names resolve by catalog code. */
	@Transactional
	public DeviceView replaceIoAssignments(String externalId, List<IoAssignmentDraft> layout) {
		Device device = devices.byExternalId(externalId)
				.orElseThrow(() -> new DeviceUnknownException(externalId));
		Map<String, Long> portNamesByCode = catalogs.ioPortNames().stream()
				.collect(Collectors.toMap(DeviceIoPortName::code, DeviceIoPortName::portNameId));
		List<DeviceIoAssignment> assignments = layout.stream()
				.map(draft -> {
					Long portNameId = null;
					if (draft.portNameCode() != null) {
						portNameId = portNamesByCode.get(draft.portNameCode());
						if (portNameId == null) {
							throw new PortNameUnknownException(draft.portNameCode());
						}
					}
					return new DeviceIoAssignment(0, device.deviceId(), device.siteExternalId(),
							draft.portType(), draft.ioPort(), portNameId, draft.portAliasName(),
							draft.reverseState(), draft.initialHigh(), draft.dataCapture(),
							draft.gosAudio(), draft.produceTrueMessage(), draft.produceFalseMessage(),
							draft.waitTime(), null, null);
				})
				.toList();
		devices.replaceAssignments(device.deviceId(), device.siteExternalId(), assignments);
		return viewOf(externalId);
	}

	public record IoAssignmentDraft(String portType, int ioPort, String portNameCode,
			String portAliasName, boolean reverseState, boolean initialHigh, boolean dataCapture,
			boolean gosAudio, boolean produceTrueMessage, boolean produceFalseMessage,
			Integer waitTime) {
	}

	/** PUT semantics for named presets. */
	@Transactional
	public DeviceView replacePerspectives(String externalId, List<PtzPreset> presets) {
		Device device = devices.byExternalId(externalId)
				.orElseThrow(() -> new DeviceUnknownException(externalId));
		devices.replacePresets(device.deviceId(), device.siteExternalId(), presets);
		return viewOf(externalId);
	}

	// ------------------------------------------------------------------------

	private DeviceView viewOf(String externalId) {
		Device device = devices.byExternalId(externalId)
				.orElseThrow(() -> new DeviceUnknownException(externalId));
		return assemble(List.of(device)).getFirst();
	}

	private List<DeviceView> assemble(List<Device> all) {
		Map<Long, String> laneExternalIds = devices.publishedLanes().stream()
				.collect(Collectors.toMap(LaneRef::laneId, LaneRef::laneExternalId));
		Map<Long, String> typeCodes = catalogs.deviceTypes().stream()
				.collect(Collectors.toMap(DeviceType::deviceTypeId, DeviceType::code));
		Map<Long, String> portNameCodes = catalogs.ioPortNames().stream()
				.collect(Collectors.toMap(DeviceIoPortName::portNameId, DeviceIoPortName::code));
		Map<Long, List<DeviceIoAssignment>> assignmentsByDevice = devices.activeAssignments().stream()
				.collect(Collectors.groupingBy(DeviceIoAssignment::deviceId));
		Map<Long, List<PtzPreset>> presetsByDevice = devices.activePresets().stream()
				.collect(Collectors.groupingBy(PtzPreset::deviceId));
		return all.stream()
				.map(device -> {
					List<DeviceIoAssignment> layout =
							assignmentsByDevice.getOrDefault(device.deviceId(), List.of());
					return new DeviceView(device,
							laneExternalIds.get(device.laneId()),
							typeCodes.get(device.deviceTypeId()),
							layout,
							layout.stream()
									.map(assignment -> assignment.portNameId() == null
											? null
											: portNameCodes.get(assignment.portNameId()))
									.toList(),
							presetsByDevice.getOrDefault(device.deviceId(), List.of()));
				})
				.toList();
	}

	private long resolveType(String deviceTypeCode) {
		return catalogs.deviceTypes().stream()
				.filter(type -> type.code().equals(deviceTypeCode))
				.map(DeviceType::deviceTypeId)
				.findFirst()
				.orElseThrow(() -> new DeviceTypeUnknownException(deviceTypeCode));
	}

	/**
	 * The site the new device belongs to — the single site the current scope
	 * permits. Kept out of the controller so registration cannot be handed a
	 * site the scope does not hold.
	 */
	private static final class ScopeSiteResolver {
		private ScopeSiteResolver() {
		}

		static String requireSingleSite() {
			var permitted = com.lynxis.orca.platform.scope.ScopeContext.current()
					.permitted("site_external_id");
			if (permitted.size() != 1) {
				throw new IllegalStateException(
						"Device registration needs exactly one permitted site; the scope carries "
								+ permitted.size());
			}
			return permitted.iterator().next();
		}
	}

	public static class LaneUnknownException extends RuntimeException {
		public LaneUnknownException(String laneExternalId) {
			super("No published lane '" + laneExternalId + "'");
		}
	}

	public static class DeviceUnknownException extends RuntimeException {
		public DeviceUnknownException(String externalId) {
			super("No device '" + externalId + "'");
		}
	}

	public static class DeviceTypeUnknownException extends RuntimeException {
		private final String code;

		public DeviceTypeUnknownException(String code) {
			super("No device type '" + code + "' in the catalog");
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	public static class PortNameUnknownException extends RuntimeException {
		private final String code;

		public PortNameUnknownException(String code) {
			super("No IO port name '" + code + "' in the catalog");
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
