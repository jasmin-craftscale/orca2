package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.DevicesApi;
import com.lynxis.orca.core.api.generated.model.DeviceEnvelope;
import com.lynxis.orca.core.api.generated.model.DeviceMode;
import com.lynxis.orca.core.api.generated.model.DeviceSummary;
import com.lynxis.orca.core.api.generated.model.DevicesEnvelope;
import com.lynxis.orca.core.api.generated.model.IoAssignmentItem;
import com.lynxis.orca.core.api.generated.model.PerspectiveItem;
import com.lynxis.orca.core.api.generated.model.PortType;
import com.lynxis.orca.core.api.generated.model.RegisterDeviceRequest;
import com.lynxis.orca.core.api.generated.model.UpdateDeviceRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.DeviceAdminService;
import com.lynxis.orca.core.domain.DeviceAdminService.DeviceTypeUnknownException;
import com.lynxis.orca.core.domain.DeviceAdminService.DeviceUnknownException;
import com.lynxis.orca.core.domain.DeviceAdminService.DeviceView;
import com.lynxis.orca.core.domain.DeviceAdminService.IoAssignmentDraft;
import com.lynxis.orca.core.domain.DeviceAdminService.LaneUnknownException;
import com.lynxis.orca.core.domain.DeviceAdminService.PortNameUnknownException;
import com.lynxis.orca.core.domain.DuplicateRequestEntryException;
import com.lynxis.orca.core.domain.DeviceTables.Device;
import com.lynxis.orca.core.domain.DeviceTables.PtzPreset;
import com.lynxis.orca.core.persistence.DeviceRepository.DevicePatch;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiError;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** §C1's device registry surface. No credential crosses this boundary. */
@RestController
public class DeviceAdminController implements DevicesApi {

	private final DeviceAdminService service;
	private final String siteExternalId;

	public DeviceAdminController(DeviceAdminService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<DevicesEnvelope> listLaneDevices(String laneExternalId) {
		List<DeviceView> inventory = ScopeContext.callIn(scope(), () -> translating(() ->
				service.byLane(laneExternalId)));
		return ResponseEntity.ok(new DevicesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(inventory.stream().map(DeviceAdminController::summary).toList()));
	}

	@Override
	public ResponseEntity<DeviceEnvelope> registerDevice(String laneExternalId,
			RegisterDeviceRequest request) {
		DeviceView registered = ScopeContext.callIn(scope(), () -> translating(() ->
				service.register(laneExternalId, draftOf(request), request.getDeviceTypeCode())));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(registered));
	}

	@Override
	public ResponseEntity<DeviceEnvelope> updateDevice(String deviceExternalId,
			UpdateDeviceRequest request) {
		DeviceView updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.update(deviceExternalId, patchOf(request), request.getDeviceTypeCode())));
		return ResponseEntity.ok(envelope(updated));
	}

	@Override
	public ResponseEntity<DeviceEnvelope> retireDevice(String deviceExternalId) {
		DeviceView retired = ScopeContext.callIn(scope(), () -> translating(() ->
				service.retire(deviceExternalId)));
		return ResponseEntity.ok(envelope(retired));
	}

	@Override
	public ResponseEntity<DeviceEnvelope> replaceIoAssignments(String deviceExternalId,
			List<IoAssignmentItem> layout) {
		DeviceView updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.replaceIoAssignments(deviceExternalId, layout.stream()
						.map(item -> new IoAssignmentDraft(
								item.getPortType().getValue(),
								item.getIoPort(),
								item.getPortNameCode(),
								item.getPortAliasName(),
								Boolean.TRUE.equals(item.getReverseState()),
								Boolean.TRUE.equals(item.getInitialHigh()),
								Boolean.TRUE.equals(item.getDataCapture()),
								Boolean.TRUE.equals(item.getGosAudio()),
								item.getProduceTrueMessage() == null || item.getProduceTrueMessage(),
								item.getProduceFalseMessage() == null || item.getProduceFalseMessage(),
								item.getWaitTime()))
						.toList())));
		return ResponseEntity.ok(envelope(updated));
	}

	@Override
	public ResponseEntity<DeviceEnvelope> replacePerspectives(String deviceExternalId,
			List<PerspectiveItem> presets) {
		DeviceView updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.replacePerspectives(deviceExternalId, presets.stream()
						.map(item -> new PtzPreset(0, 0, null, item.getName(),
								item.getPan(), item.getTilt(), item.getZoom(),
								null, null))
						.toList())));
		return ResponseEntity.ok(envelope(updated));
	}

	// ------------------------------------------------------------------------

	private static <T> T translating(Supplier<T> work) {
		try {
			return work.get();
		}
		catch (LaneUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, "No such lane at this installation.");
		}
		catch (DeviceUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, "No such device at this installation.");
		}
		catch (DeviceTypeUnknownException unknown) {
			throw new ApiException(CoreErrorCode.DEVICE_TYPE_UNKNOWN,
					"No device type '" + unknown.code() + "' in the catalog.");
		}
		catch (PortNameUnknownException unknown) {
			throw new ApiException(CoreErrorCode.PORT_NAME_UNKNOWN,
					"No IO port name '" + unknown.code() + "' in the catalog.");
		}
		catch (DuplicateRequestEntryException repeated) {
			throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
					"The request repeats an entry.",
					List.of(ApiError.field(PlatformErrorCode.VALIDATION_FAILED,
							repeated.getField(), "duplicated: " + repeated.getDuplicate())));
		}
	}

	private Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static Device draftOf(RegisterDeviceRequest request) {
		return new Device(0, null, 0, null, 0,
				request.getName(), request.getAddress(),
				request.getMode() == null ? null : request.getMode().getValue(),
				request.getAlias(), request.getModel(), request.getFirmwareVersion(),
				request.getDescription(), request.getResolution(), request.getIpAddress(),
				request.getStreamType(), request.getPort(), request.getProtocol(),
				request.getManufacturer(), request.getDeviceUrl(), request.getDevicePortalUrl(),
				request.getAssemblyName(), request.getClassName(), request.getDataCaptureMode(),
				request.getWaitTime(), null, null);
	}

	private static DevicePatch patchOf(UpdateDeviceRequest request) {
		return new DevicePatch(null, request.getName(), request.getAddress(),
				request.getMode() == null ? null : request.getMode().getValue(),
				request.getAlias(), request.getModel(), request.getFirmwareVersion(),
				request.getDescription(), request.getResolution(), request.getIpAddress(),
				request.getStreamType(), request.getPort(), request.getProtocol(),
				request.getManufacturer(), request.getDeviceUrl(), request.getDevicePortalUrl(),
				request.getAssemblyName(), request.getClassName(), request.getDataCaptureMode(),
				request.getWaitTime(), null);
	}

	private static DeviceEnvelope envelope(DeviceView view) {
		return new DeviceEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(view));
	}

	private static DeviceSummary summary(DeviceView view) {
		var device = view.device();
		DeviceSummary summary = new DeviceSummary()
				.externalId(device.externalId())
				.laneExternalId(view.laneExternalId())
				.siteExternalId(device.siteExternalId())
				.deviceTypeCode(view.deviceTypeCode())
				.name(device.name())
				.address(device.address())
				.mode(device.deviceMode() == null ? null : DeviceMode.fromValue(device.deviceMode()))
				.alias(device.deviceAlias())
				.model(device.deviceModel())
				.firmwareVersion(device.firmwareVersion())
				.description(device.description())
				.resolution(device.resolution())
				.ipAddress(device.ipAddress())
				.streamType(device.streamType())
				.port(device.port())
				.protocol(device.protocol())
				.manufacturer(device.manufacturer())
				.deviceUrl(device.deviceUrl())
				.devicePortalUrl(device.devicePortalUrl())
				.assemblyName(device.assemblyName())
				.className(device.className())
				.dataCaptureMode(device.dataCaptureMode())
				.waitTime(device.waitTime())
				.retired(device.retiredAt() != null)
				.createdAt(ApiTime.offset(device.createdAt()));
		for (int i = 0; i < view.ioAssignments().size(); i++) {
			var assignment = view.ioAssignments().get(i);
			summary.addIoAssignmentsItem(new IoAssignmentItem()
					.portType(PortType.fromValue(assignment.portType()))
					.ioPort(assignment.ioPort())
					.portNameCode(view.portNameCodes().get(i))
					.portAliasName(assignment.portAliasName())
					.reverseState(assignment.isReverseState())
					.initialHigh(assignment.isInitialHigh())
					.dataCapture(assignment.isDataCapture())
					.gosAudio(assignment.isGosAudio())
					.produceTrueMessage(assignment.produceTrueMessage())
					.produceFalseMessage(assignment.produceFalseMessage())
					.waitTime(assignment.waitTime()));
		}
		summary.perspectives(view.perspectives().stream()
				.map(preset -> new PerspectiveItem()
						.name(preset.name())
						.pan(preset.pan())
						.tilt(preset.tilt())
						.zoom(preset.zoom()))
				.toList());
		if (summary.getIoAssignments() == null) {
			summary.ioAssignments(List.of());
		}
		return summary;
	}
}
