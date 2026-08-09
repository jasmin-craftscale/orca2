package com.lynxis.orca.core.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.ScreensApi;
import com.lynxis.orca.core.api.generated.model.CreateScreenRequest;
import com.lynxis.orca.core.api.generated.model.ScreenEnvelope;
import com.lynxis.orca.core.api.generated.model.ScreenSummary;
import com.lynxis.orca.core.api.generated.model.ScreensEnvelope;
import com.lynxis.orca.core.api.generated.model.UpdateScreenRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.RoutingAdminService;
import com.lynxis.orca.core.domain.RoutingTables.Screen;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** The screen-identity admin surface, hand-written against its generated contract interface. */
@RestController
public class ScreenAdminController implements ScreensApi {

	private final RoutingAdminService routing;
	private final String siteExternalId;

	public ScreenAdminController(RoutingAdminService routing, String siteExternalId) {
		this.routing = routing;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<ScreensEnvelope> listScreens() {
		List<Screen> screens = inScope(routing::allScreens);
		return ResponseEntity.ok(new ScreensEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(screens.stream().map(ScreenAdminController::toModel).toList()));
	}

	@Override
	public ResponseEntity<ScreenEnvelope> createScreen(CreateScreenRequest request) {
		Screen screen = inScope(() -> routing.createScreen(siteExternalId, request.getName(),
				request.getProcessDefinitionKey(), request.getNodeReference(),
				request.getBelowExpectedSec(), request.getExpectedSec(), request.getMaxSec()));
		return ResponseEntity.created(URI.create("/api/v1/screens/" + screen.externalId()))
				.body(envelope(screen));
	}

	@Override
	public ResponseEntity<ScreenEnvelope> updateScreen(String screenExternalId,
			UpdateScreenRequest request) {
		Screen screen = inScope(() -> routing.updateScreen(screenExternalId, request.getName(),
				request.getProcessDefinitionKey(), request.getNodeReference(),
				request.getBelowExpectedSec(), request.getExpectedSec(), request.getMaxSec(),
				request.getRetired()));
		return ResponseEntity.ok(envelope(screen));
	}

	private <T> T inScope(java.util.function.Supplier<T> action) {
		try {
			return ScopeContext.callIn(CoreScopes.installation(siteExternalId), action::get);
		}
		catch (RoutingAdminService.ScreenUnknownException notFound) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, notFound.getMessage());
		}
		catch (RoutingAdminService.ScreenNodeTakenException taken) {
			throw new ApiException(CoreErrorCode.SCREEN_NODE_TAKEN, taken.getMessage());
		}
	}

	private static ScreenEnvelope envelope(Screen screen) {
		return new ScreenEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(toModel(screen));
	}

	private static ScreenSummary toModel(Screen screen) {
		return new ScreenSummary()
				.externalId(screen.externalId())
				.name(screen.name())
				.processDefinitionKey(screen.processDefinitionKey())
				.nodeReference(screen.nodeReference())
				.belowExpectedSec(screen.belowExpectedSec())
				.expectedSec(screen.expectedSec())
				.maxSec(screen.maxSec())
				.retired(screen.retiredAt() != null);
	}
}
