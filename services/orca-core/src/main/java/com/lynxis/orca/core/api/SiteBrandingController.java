package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.SiteBrandingApi;
import com.lynxis.orca.core.api.generated.model.SiteBranding;
import com.lynxis.orca.core.api.generated.model.SiteBrandingEnvelope;
import com.lynxis.orca.core.api.generated.model.SiteColorItem;
import com.lynxis.orca.core.api.generated.model.SiteLanguageItem;
import com.lynxis.orca.core.api.generated.model.UpdateSiteBrandingRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.DuplicateRequestEntryException;
import com.lynxis.orca.core.domain.RoleAdminService.SiteUnknownException;
import com.lynxis.orca.core.domain.SiteBrandingService;
import com.lynxis.orca.core.domain.SiteBrandingService.BrandingView;
import com.lynxis.orca.core.domain.SiteBrandingService.ColorInvalidException;
import com.lynxis.orca.core.domain.WorkspaceTables.SiteColor;
import com.lynxis.orca.core.domain.WorkspaceTables.SiteLanguage;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiError;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** The branding-and-localization slice of the site-update HTTP surface. */
@RestController
public class SiteBrandingController implements SiteBrandingApi {

	private final SiteBrandingService service;
	private final String siteExternalId;

	public SiteBrandingController(SiteBrandingService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<SiteBrandingEnvelope> updateSiteBranding(String targetSiteExternalId,
			UpdateSiteBrandingRequest request) {
		BrandingView view = ScopeContext.callIn(scope(), () -> translating(() ->
				service.replace(targetSiteExternalId,
						request.getColors() == null ? null : request.getColors().stream()
								.map(color -> new SiteColor(0, targetSiteExternalId, color.getCode(),
										color.getHexValue(), null, null))
								.toList(),
						request.getLanguages() == null ? null : request.getLanguages().stream()
								.map(language -> new SiteLanguage(0, targetSiteExternalId,
										language.getCode(), language.getName(),
										language.getResourcePath(), null, null))
								.toList())));
		return ResponseEntity.ok(new SiteBrandingEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(new SiteBranding()
						.siteExternalId(view.siteExternalId())
						.colors(view.colors().stream()
								.map(color -> new SiteColorItem()
										.code(color.code())
										.hexValue(color.hexValue()))
								.toList())
						.languages(view.languages().stream()
								.map(language -> new SiteLanguageItem()
										.code(language.code())
										.name(language.name())
										.resourcePath(language.resourcePath()))
								.toList())));
	}

	private static BrandingView translating(Supplier<BrandingView> work) {
		try {
			return work.get();
		}
		catch (SiteUnknownException unknown) {
			throw new ApiException(CoreErrorCode.SITE_UNKNOWN,
					"No active site '" + unknown.siteExternalId() + "' at this installation.");
		}
		catch (ColorInvalidException invalid) {
			throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
					"A color value is not hex.",
					List.of(ApiError.field(PlatformErrorCode.VALIDATION_FAILED,
							invalid.code(), invalid.reason())));
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
}
