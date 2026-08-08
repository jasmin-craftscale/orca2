package com.lynxis.orca.core.api;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

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
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** §C1's shift template catalog. */
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
						LocalTime.parse(normalise(request.getStartTime())),
						LocalTime.parse(normalise(request.getEndTime())))));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(created));
	}

	@Override
	public ResponseEntity<ShiftTemplateEnvelope> updateShiftTemplate(String shiftTemplateExternalId,
			UpdateShiftTemplateRequest request) {
		ShiftTemplate updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.updateShiftTemplate(shiftTemplateExternalId,
						request.getName(), request.getTimeZone(),
						request.getStartTime() == null ? null : LocalTime.parse(normalise(request.getStartTime())),
						request.getEndTime() == null ? null : LocalTime.parse(normalise(request.getEndTime())),
						request.getRetired())));
		return ResponseEntity.ok(envelope(updated));
	}

	private static ShiftTemplate translating(java.util.function.Supplier<ShiftTemplate> work) {
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

	private com.lynxis.orca.platform.scope.Scope scope() {
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
				.createdAt(offset(template.createdAt()));
	}

	/** {@code HH:mm} and {@code HH:mm:ss} are both contract-legal; parse both. */
	private static String normalise(String time) {
		return time.length() == 5 ? time + ":00" : time;
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
