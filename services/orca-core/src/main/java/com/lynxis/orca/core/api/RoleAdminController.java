package com.lynxis.orca.core.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.RolesApi;
import com.lynxis.orca.core.api.generated.model.CreateRoleRequest;
import com.lynxis.orca.core.api.generated.model.RoleEnvelope;
import com.lynxis.orca.core.api.generated.model.RoleSummary;
import com.lynxis.orca.core.api.generated.model.RolesEnvelope;
import com.lynxis.orca.core.api.generated.model.UpdateRoleRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.RoleAdminService;
import com.lynxis.orca.core.domain.RoleAdminService.EntitlementUnknownException;
import com.lynxis.orca.core.domain.RoleAdminService.RoleNameInUseException;
import com.lynxis.orca.core.domain.RoleAdminService.RoleStillHeldException;
import com.lynxis.orca.core.domain.RoleAdminService.RoleView;
import com.lynxis.orca.core.domain.RoleAdminService.SiteUnknownException;
import com.lynxis.orca.core.domain.UserAdminService.RoleUnknownException;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** §C1's role surface: roles, entitlement grants and site scoping. */
@RestController
public class RoleAdminController implements RolesApi {

	private final RoleAdminService service;
	private final String siteExternalId;

	public RoleAdminController(RoleAdminService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<RolesEnvelope> listRoles() {
		List<RoleView> views = ScopeContext.callIn(scope(), service::list);
		return ResponseEntity.ok(new RolesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(views.stream().map(RoleAdminController::summary).toList()));
	}

	@Override
	public ResponseEntity<RoleEnvelope> createRole(CreateRoleRequest request) {
		RoleView created = ScopeContext.callIn(scope(), () -> translating(() ->
				service.create(request.getName(), request.getDescription(),
						request.getSiteExternalIds(), request.getEntitlementCodes())));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(created));
	}

	@Override
	public ResponseEntity<RoleEnvelope> updateRole(String roleExternalId, UpdateRoleRequest request) {
		RoleView updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.update(roleExternalId, request.getName(), request.getDescription(),
						request.getSiteExternalIds(), request.getEntitlementCodes(),
						request.getRetired())));
		return ResponseEntity.ok(envelope(updated));
	}

	/**
	 * One translation table for both mutations — the same domain refusals mean
	 * the same contract answers on either route.
	 */
	private static RoleView translating(java.util.function.Supplier<RoleView> work) {
		try {
			return work.get();
		}
		catch (RoleUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND,
					"No role '" + unknown.roleExternalId() + "' at this installation.");
		}
		catch (RoleNameInUseException inUse) {
			throw new ApiException(PlatformErrorCode.CONFLICT,
					"An active role already carries that name.");
		}
		catch (RoleStillHeldException held) {
			throw new ApiException(CoreErrorCode.ROLE_IN_USE,
					"Active users still hold this role; move them first.");
		}
		catch (EntitlementUnknownException unknown) {
			throw new ApiException(CoreErrorCode.ENTITLEMENT_UNKNOWN,
					"No entitlement '" + unknown.code() + "' in the catalog.");
		}
		catch (SiteUnknownException unknown) {
			throw new ApiException(CoreErrorCode.SITE_UNKNOWN,
					"No active site '" + unknown.siteExternalId() + "' at this installation.");
		}
	}

	private com.lynxis.orca.platform.scope.Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static RoleEnvelope envelope(RoleView view) {
		return new RoleEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(view));
	}

	private static RoleSummary summary(RoleView view) {
		var role = view.role();
		return new RoleSummary()
				.externalId(role.externalId())
				.name(role.name())
				.description(role.description())
				.siteExternalIds(view.siteExternalIds())
				.entitlementCodes(view.entitlementCodes())
				.retired(role.retiredAt() != null)
				.createdAt(offset(role.createdAt()));
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
