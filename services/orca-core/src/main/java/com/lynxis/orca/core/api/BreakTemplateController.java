package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.BreakTemplatesApi;
import com.lynxis.orca.core.api.generated.model.BreakTemplateEnvelope;
import com.lynxis.orca.core.api.generated.model.BreakTemplateSummary;
import com.lynxis.orca.core.api.generated.model.BreakTemplatesEnvelope;
import com.lynxis.orca.core.api.generated.model.BreakTimingItem;
import com.lynxis.orca.core.api.generated.model.CreateBreakTemplateRequest;
import com.lynxis.orca.core.api.generated.model.UpdateBreakTemplateRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.DuplicateRequestEntryException;
import com.lynxis.orca.core.domain.TeamTables.BreakTiming;
import com.lynxis.orca.core.domain.TemplateAdminService;
import com.lynxis.orca.core.domain.TemplateAdminService.BreakTemplateUnknownException;
import com.lynxis.orca.core.domain.TemplateAdminService.BreakTemplateView;
import com.lynxis.orca.core.domain.TemplateAdminService.TemplateInUseException;
import com.lynxis.orca.core.domain.TemplateAdminService.TemplateNameInUseException;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiError;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** §C1's break template catalog. */
@RestController
public class BreakTemplateController implements BreakTemplatesApi {

	private final TemplateAdminService service;
	private final String siteExternalId;

	public BreakTemplateController(TemplateAdminService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<BreakTemplatesEnvelope> listBreakTemplates() {
		List<BreakTemplateView> templates = ScopeContext.callIn(scope(), service::listBreakTemplates);
		return ResponseEntity.ok(new BreakTemplatesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(templates.stream().map(BreakTemplateController::summary).toList()));
	}

	@Override
	public ResponseEntity<BreakTemplateEnvelope> createBreakTemplate(CreateBreakTemplateRequest request) {
		BreakTemplateView created = ScopeContext.callIn(scope(), () -> translating(() ->
				service.createBreakTemplate(request.getName(), request.getDescription(),
						timings(request.getTimings()))));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(created));
	}

	@Override
	public ResponseEntity<BreakTemplateEnvelope> updateBreakTemplate(String breakTemplateExternalId,
			UpdateBreakTemplateRequest request) {
		BreakTemplateView updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.updateBreakTemplate(breakTemplateExternalId,
						request.getName(), request.getDescription(),
						timings(request.getTimings()), request.getRetired())));
		return ResponseEntity.ok(envelope(updated));
	}

	private static BreakTemplateView translating(Supplier<BreakTemplateView> work) {
		try {
			return work.get();
		}
		catch (BreakTemplateUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, "No such break template.");
		}
		catch (TemplateNameInUseException inUse) {
			throw new ApiException(PlatformErrorCode.CONFLICT,
					"An active template already carries that name.");
		}
		catch (TemplateInUseException held) {
			throw new ApiException(CoreErrorCode.TEMPLATE_IN_USE,
					"Active teams still reference this template; detach them first.");
		}
		catch (DuplicateRequestEntryException repeated) {
			throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
					"The request repeats an entry.",
					List.of(ApiError.field(PlatformErrorCode.VALIDATION_FAILED,
							repeated.getField(), "duplicated: " + repeated.getDuplicate())));
		}
	}

	/** {@code null} stays null — for PATCH, an absent list means "unchanged". */
	private static List<BreakTiming> timings(List<BreakTimingItem> items) {
		if (items == null) {
			return null;
		}
		return items.stream()
				.map(item -> new BreakTiming(0, 0, null,
						ApiTime.parseLocalTime(item.getStartTime()),
						item.getDurationMinutes(), null, null))
				.toList();
	}

	private Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static BreakTemplateEnvelope envelope(BreakTemplateView view) {
		return new BreakTemplateEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(view));
	}

	private static BreakTemplateSummary summary(BreakTemplateView view) {
		return new BreakTemplateSummary()
				.externalId(view.template().externalId())
				.name(view.template().name())
				.description(view.template().description())
				.timings(view.timings().stream()
						.map(timing -> new BreakTimingItem()
								.startTime(timing.breakStartTime().toString())
								.durationMinutes(timing.durationMinutes()))
						.toList())
				.retired(view.template().retiredAt() != null)
				.createdAt(ApiTime.offset(view.template().createdAt()));
	}
}
