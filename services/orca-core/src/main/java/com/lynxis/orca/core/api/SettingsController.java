package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.SettingsApi;
import com.lynxis.orca.core.api.generated.model.SettingEnvelope;
import com.lynxis.orca.core.api.generated.model.SettingSummary;
import com.lynxis.orca.core.api.generated.model.SettingsEnvelope;
import com.lynxis.orca.core.api.generated.model.WriteSettingRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.SettingsService;
import com.lynxis.orca.core.domain.SettingsService.SecretSettingRejectedException;
import com.lynxis.orca.core.domain.SettingsService.SettingInvalidException;
import com.lynxis.orca.core.domain.SettingsService.SettingUnknownException;
import com.lynxis.orca.core.domain.SettingsService.SettingView;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiError;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** §C1's settings surface. */
@RestController
public class SettingsController implements SettingsApi {

	private final SettingsService service;
	private final String siteExternalId;

	public SettingsController(SettingsService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<SettingsEnvelope> listSettings() {
		List<SettingView> settings = ScopeContext.callIn(scope(), service::list);
		return ResponseEntity.ok(new SettingsEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(settings.stream().map(SettingsController::summary).toList()));
	}

	@Override
	public ResponseEntity<SettingEnvelope> writeSetting(String settingKey, WriteSettingRequest request) {
		SettingView written = ScopeContext.callIn(scope(), () -> translating(() ->
				service.write(settingKey, request.getValue())));
		return ResponseEntity.ok(new SettingEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(written)));
	}

	private static SettingView translating(Supplier<SettingView> work) {
		try {
			return work.get();
		}
		catch (SecretSettingRejectedException secret) {
			throw new ApiException(CoreErrorCode.SETTING_SECRET_REJECTED,
					"Secrets never enter the settings table. Route '" + secret.settingKey()
							+ "' to the installation's configuration or keystore.");
		}
		catch (SettingUnknownException unknown) {
			throw new ApiException(CoreErrorCode.SETTING_UNKNOWN,
					"No setting '" + unknown.settingKey() + "' in the registry of known keys.");
		}
		catch (SettingInvalidException invalid) {
			throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
					"The value does not fit the key's type.",
					List.of(ApiError.field(PlatformErrorCode.VALIDATION_FAILED, "value", invalid.reason())));
		}
	}

	private Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static SettingSummary summary(SettingView view) {
		var definition = view.definition();
		SettingSummary summary = new SettingSummary()
				.key(definition.settingKey())
				.valueType(SettingSummary.ValueTypeEnum.fromValue(definition.valueType()))
				.description(definition.description())
				.defaultValue(definition.defaultValue());
		if (view.current() != null) {
			summary.value(view.current().settingValue())
					.updatedBy(view.current().updatedBy())
					.updatedAt(ApiTime.offset(view.current().updatedAt()));
		}
		return summary;
	}
}
