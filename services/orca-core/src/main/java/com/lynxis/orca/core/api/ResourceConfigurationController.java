package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.ResourceConfigurationsApi;
import com.lynxis.orca.core.api.generated.model.ResourceConfigurationEntry;
import com.lynxis.orca.core.api.generated.model.ResourceConfigurationEnvelope;
import com.lynxis.orca.core.api.generated.model.ResourceConfigurationView;
import com.lynxis.orca.core.api.generated.model.ResourceScopeType;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.DuplicateRequestEntryException;
import com.lynxis.orca.core.domain.ResourceConfigurationService;
import com.lynxis.orca.core.domain.ResourceConfigurationService.ResourceUnknownException;
import com.lynxis.orca.core.persistence.ResourceConfigurationRepository.Entry;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiError;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** Serves custom selector variables at {@code /resource-configurations/{scope}/{id}}. */
@RestController
public class ResourceConfigurationController implements ResourceConfigurationsApi {

	private final ResourceConfigurationService service;
	private final String siteExternalId;

	public ResourceConfigurationController(ResourceConfigurationService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<ResourceConfigurationEnvelope> readResourceConfiguration(
			ResourceScopeType scopeType, String resourceExternalId) {
		var view = ScopeContext.callIn(scope(), () -> translating(() ->
				service.read(scopeType.getValue(), resourceExternalId)));
		return ResponseEntity.ok(envelope(view));
	}

	@Override
	public ResponseEntity<ResourceConfigurationEnvelope> replaceResourceConfiguration(
			ResourceScopeType scopeType, String resourceExternalId,
			List<ResourceConfigurationEntry> entries) {
		var view = ScopeContext.callIn(scope(), () -> translating(() ->
				service.replace(scopeType.getValue(), resourceExternalId, entries.stream()
						.map(entry -> new Entry(entry.getKey(), entry.getValue()))
						.toList())));
		return ResponseEntity.ok(envelope(view));
	}

	private static ResourceConfigurationService.ResourceView translating(
			Supplier<ResourceConfigurationService.ResourceView> work) {
		try {
			return work.get();
		}
		catch (ResourceUnknownException unknown) {
			// 404, not 422: the resource is path-identified, and every sibling
			// route answers a path miss with NOT_FOUND (review finding).
			throw new ApiException(PlatformErrorCode.NOT_FOUND,
					"No such resource at this installation.");
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

	private static ResourceConfigurationEnvelope envelope(ResourceConfigurationService.ResourceView view) {
		return new ResourceConfigurationEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(new ResourceConfigurationView()
						.scopeType(ResourceScopeType.fromValue(view.scopeType()))
						.resourceExternalId(view.resourceExternalId())
						.entries(view.entries().stream()
								.map(entry -> new ResourceConfigurationEntry()
										.key(entry.configKey())
										.value(entry.configValue()))
								.toList()));
	}
}
