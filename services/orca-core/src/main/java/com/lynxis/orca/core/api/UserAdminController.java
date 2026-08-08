package com.lynxis.orca.core.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.UsersApi;
import com.lynxis.orca.core.api.generated.model.CreateUserRequest;
import com.lynxis.orca.core.api.generated.model.UpdateUserRequest;
import com.lynxis.orca.core.api.generated.model.UserEnvelope;
import com.lynxis.orca.core.api.generated.model.UserSummary;
import com.lynxis.orca.core.api.generated.model.UsersEnvelope;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.UserAdminService;
import com.lynxis.orca.core.domain.UserAdminService.RoleUnknownException;
import com.lynxis.orca.core.domain.UserAdminService.UserChange;
import com.lynxis.orca.core.domain.UserAdminService.UserDraft;
import com.lynxis.orca.core.domain.UserAdminService.UserUnknownException;
import com.lynxis.orca.core.domain.UserAdminService.UserView;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/**
 * §C1's user-management surface, implementing the generated contract.
 *
 * <p>Scope is established here, at the request boundary, from configuration —
 * never from the request (phase-1 decision 15's reasoning, applied to the
 * public surface: the caller does not choose what it may see).
 */
@RestController
public class UserAdminController implements UsersApi {

	private final UserAdminService service;
	private final String siteExternalId;

	public UserAdminController(UserAdminService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<UsersEnvelope> listUsers() {
		List<UserView> directory = ScopeContext.callIn(scope(), service::list);
		return ResponseEntity.ok(new UsersEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(directory.stream().map(UserAdminController::summary).toList()));
	}

	@Override
	public ResponseEntity<UserEnvelope> createUser(CreateUserRequest request) {
		UserView created = ScopeContext.callIn(scope(), () -> {
			try {
				return service.create(new UserDraft(
						request.getDisplayName(), request.getFirstName(), request.getMiddleName(),
						request.getLastName(), request.getEmail(), request.getProfileImageUrl(),
						request.getLanguageCode(), request.getKeycloakSubject(),
						request.getRoleExternalId()));
			}
			catch (RoleUnknownException unknown) {
				throw new ApiException(CoreErrorCode.ROLE_UNKNOWN,
						"No active role '" + unknown.roleExternalId() + "'.");
			}
		});
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(created));
	}

	@Override
	public ResponseEntity<UserEnvelope> updateUser(String userExternalId, UpdateUserRequest request) {
		UserView updated = ScopeContext.callIn(scope(), () -> {
			try {
				return service.update(userExternalId, new UserChange(
						request.getDisplayName(), request.getFirstName(), request.getMiddleName(),
						request.getLastName(), request.getEmail(), request.getProfileImageUrl(),
						request.getLanguageCode(), request.getKeycloakSubject(),
						request.getRoleExternalId(),
						instant(request.getPrivacyAcceptedAt()), instant(request.getTermsAcceptedAt()),
						request.getRetired()));
			}
			catch (UserUnknownException unknown) {
				throw new ApiException(PlatformErrorCode.NOT_FOUND,
						"No user '" + userExternalId + "' at this installation.");
			}
			catch (RoleUnknownException unknown) {
				throw new ApiException(CoreErrorCode.ROLE_UNKNOWN,
						"No active role '" + unknown.roleExternalId() + "'.");
			}
		});
		return ResponseEntity.ok(envelope(updated));
	}

	private com.lynxis.orca.platform.scope.Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static UserEnvelope envelope(UserView view) {
		return new UserEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(view));
	}

	private static UserSummary summary(UserView view) {
		var user = view.user();
		return new UserSummary()
				.externalId(user.externalId())
				.displayName(user.displayName())
				.firstName(user.firstName())
				.middleName(user.middleName())
				.lastName(user.lastName())
				.email(user.email())
				.profileImageUrl(user.profileImageUrl())
				.languageCode(user.languageCode())
				.keycloakSubject(user.keycloakSubject())
				.privacyAcceptedAt(offset(user.privacyAcceptedAt()))
				.termsAcceptedAt(offset(user.termsAcceptedAt()))
				.roleExternalId(view.roleExternalId())
				.retired(user.retiredAt() != null)
				.createdAt(offset(user.createdAt()));
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(OffsetDateTime offsetDateTime) {
		return offsetDateTime == null ? null : offsetDateTime.toInstant();
	}
}
