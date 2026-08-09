package com.lynxis.orca.edge.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.edge.api.generated.InternalCommandsApi;
import com.lynxis.orca.edge.api.generated.model.DeviceCommand;
import com.lynxis.orca.edge.api.generated.model.DeviceCommandEnvelope;
import com.lynxis.orca.edge.api.generated.model.DeviceCommandResult;
import com.lynxis.orca.edge.domain.DeviceCommandService;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

import lombok.extern.slf4j.Slf4j;

/**
 * Where a command to move a barrier enters the site.
 *
 * <p>Hand-written, implementing the generated contract interface.
 *
 * <p><strong>Scope comes from configuration, not from the request</strong>, for the
 * same reason it does on runtime's events endpoint: the credential on
 * {@code /internal/**} is a per-installation shared secret and cannot
 * prove which peer is calling, so a site identifier on the wire would be a value
 * the caller chose.
 *
 * <p><strong>The deadline is measured from when the caller issued the command</strong>,
 * and the caller says when that was. Measuring from arrival instead would make
 * every command look fresh however long it spent in a queue or a retry loop —
 * which is precisely the command this endpoint exists to refuse.
 */
@Slf4j
@RestController
public class DeviceCommandController implements InternalCommandsApi {

	private final DeviceCommandService commands;
	private final String siteExternalId;

	public DeviceCommandController(DeviceCommandService commands, String siteExternalId) {
		this.commands = commands;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<DeviceCommandEnvelope> issueDeviceCommand(DeviceCommand command) {
		DeviceCommandService.Result result = ScopeContext.callIn(installationScope(), () -> {
			try {
				return commands.issue(new DeviceCommandService.Command(
						command.getCommandId(),
						command.getLaneExternalId(),
						command.getDeviceExternalId(),
						command.getAction(),
						command.getParams(),
						command.getDeadlineMs() == null ? 0L : command.getDeadlineMs()),
						// The clock starts when the request arrives here, because the
						// contract carries no issued-at. ⚠️ That understates the elapsed
						// time by the network hop. The caller's own issue time belongs on the
						// wire, but the current contract cannot carry it.
						Instant.now());
			}
			catch (DeviceCommandService.LaneHasNoDeviceHostException unknownLane) {
				log.warn("refusing a command: {}", unknownLane.getMessage());
				throw new ApiException(EdgeErrorCode.LANE_HAS_NO_DEVICE_HOST,
						"Lane '" + unknownLane.laneExternalId() + "' has no device host at this "
								+ "installation.");
			}
		});

		return ResponseEntity.ok(envelope(result));
	}

	@Override
	public ResponseEntity<DeviceCommandEnvelope> readDeviceCommand(String commandId) {
		DeviceCommandService.Result recorded = ScopeContext.callIn(installationScope(),
						() -> commands.recorded(commandId))
				.orElseThrow(() -> new ApiException(PlatformErrorCode.NOT_FOUND,
						"No command by that id has been issued at this installation."));

		return ResponseEntity.ok(envelope(recorded));
	}

	private static DeviceCommandEnvelope envelope(DeviceCommandService.Result result) {
		return new DeviceCommandEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(new DeviceCommandResult()
						.commandId(result.commandId())
						.status(DeviceCommandResult.StatusEnum.fromValue(result.status()))
						.deviceResponse(result.deviceResponse())
						.detail(result.detail())
						.ackedAt(result.ackedAt() == null ? null
								: OffsetDateTime.ofInstant(result.ackedAt(), ZoneOffset.UTC)));
	}

	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}
}
