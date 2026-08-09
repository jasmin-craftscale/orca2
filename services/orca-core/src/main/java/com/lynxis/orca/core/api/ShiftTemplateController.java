package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.ShiftTemplatesApi;
import com.lynxis.orca.core.api.generated.model.CreateShiftTemplateRequest;
import com.lynxis.orca.core.api.generated.model.ShiftTemplateEnvelope;
import com.lynxis.orca.core.api.generated.model.ShiftTemplateSummary;
import com.lynxis.orca.core.api.generated.model.ShiftTemplatesEnvelope;
import com.lynxis.orca.core.api.generated.model.UpdateShiftTemplateRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.TeamTables.ShiftTemplate;
import com.lynxis.orca.core.domain.TemplateAdminService;
import com.lynxis.orca.core.domain.TemplateAdminService.ShiftTemplateUnknownException;
import com.lynxis.orca.core.domain.TemplateAdminService.TemplateInUseException;
import com.lynxis.orca.core.domain.TemplateAdminService.TemplateNameInUseException;
import com.lynxis.orca.core.domain.TemplateAdminService.TimeZoneUnknownException;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** The shift-template catalog's HTTP surface. */
@RestController
public class ShiftTemplateController implements ShiftTemplatesApi {

	private final TemplateAdminService service;
	private final String siteExternalId;

	public ShiftTemplateController(TemplateAdminService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<ShiftTemplatesEnvelope> listShiftTemplates() {
		List<ShiftTemplate> templates = ScopeContext.callIn(scope(), service::listShiftTemplates);
		return ResponseEntity.ok(new ShiftTemplatesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(templates.stream().map(ShiftTemplateController::summary).toList()));
	}

	@Override
	public ResponseEntity<ShiftTemplateEnvelope> createShiftTemplate(CreateShiftTemplateRequest request) {
		ShiftTemplate created = ScopeContext.callIn(scope(), () -> translating(() ->
				service.createShiftTemplate(request.getName(), request.getTimeZone(),
						ApiTime.parseLocalTime(request.getStartTime()),
						ApiTime.parseLocalTime(request.getEndTime()))));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(created));
	}

	@Override
	public ResponseEntity<ShiftTemplateEnvelope> updateShiftTemplate(String shiftTemplateExternalId,
			UpdateShiftTemplateRequest request) {
		ShiftTemplate updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.updateShiftTemplate(shiftTemplateExternalId,
						request.getName(), request.getTimeZone(),
						ApiTime.parseLocalTime(request.getStartTime()),
						ApiTime.parseLocalTime(request.getEndTime()),
						request.getRetired())));
		return ResponseEntity.ok(envelope(updated));
	}

	private static ShiftTemplate translating(Supplier<ShiftTemplate> work) {
		try {
			return work.get();
		}
		catch (ShiftTemplateUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, "No such shift template.");
		}
		catch (TimeZoneUnknownException unknown) {
			throw new ApiException(CoreErrorCode.TIME_ZONE_UNKNOWN,
					"'" + unknown.timeZone() + "' is not an IANA zone this platform knows.");
		}
		catch (TemplateNameInUseException inUse) {
			throw new ApiException(PlatformErrorCode.CONFLICT,
					"An active template already carries that name.");
		}
		catch (TemplateInUseException held) {
			throw new ApiException(CoreErrorCode.TEMPLATE_IN_USE,
					"Active teams still reference this template; detach them first.");
		}
	}

	private Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static ShiftTemplateEnvelope envelope(ShiftTemplate template) {
		return new ShiftTemplateEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(template));
	}

	private static ShiftTemplateSummary summary(ShiftTemplate template) {
		return new ShiftTemplateSummary()
				.externalId(template.externalId())
				.name(template.name())
				.timeZone(template.timeZone())
				.startTime(template.startTime().toString())
				.endTime(template.endTime().toString())
				.overnight(template.isOvernight())
				.durationMinutes(TemplateAdminService.durationMinutes(
						template.startTime(), template.endTime()))
				.retired(template.retiredAt() != null)
				.createdAt(ApiTime.offset(template.createdAt()));
	}
}
