package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

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
import com.lynxis.orca.core.domain.UserAdminService.KeycloakSubjectInUseException;
import com.lynxis.orca.core.domain.UserAdminService.RoleUnknownException;
import com.lynxis.orca.core.domain.UserAdminService.UserChange;
import com.lynxis.orca.core.domain.UserAdminService.UserDraft;
import com.lynxis.orca.core.domain.UserAdminService.UserUnknownException;
import com.lynxis.orca.core.domain.UserAdminService.UserView;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/**
 * The user-management HTTP surface, implementing the generated contract.
 *
 * <p>Scope is established here, at the request boundary, from installation
 * configuration and never from the request: the caller does not choose what it
 * may see.
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
		UserView created = ScopeContext.callIn(scope(), () -> translating(() ->
				service.create(new UserDraft(
						request.getDisplayName(), request.getFirstName(), request.getMiddleName(),
						request.getLastName(), request.getEmail(), request.getProfileImageUrl(),
						request.getLanguageCode(), request.getKeycloakSubject(),
						request.getRoleExternalId()))));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(created));
	}

	@Override
	public ResponseEntity<UserEnvelope> updateUser(String userExternalId, UpdateUserRequest request) {
		UserView updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.update(userExternalId, new UserChange(
						request.getDisplayName(), request.getFirstName(), request.getMiddleName(),
						request.getLastName(), request.getEmail(), request.getProfileImageUrl(),
						request.getLanguageCode(), request.getKeycloakSubject(),
						request.getRoleExternalId(),
						ApiTime.instant(request.getPrivacyAcceptedAt()),
						ApiTime.instant(request.getTermsAcceptedAt()),
						request.getRetired()))));
		return ResponseEntity.ok(envelope(updated));
	}

	/** One translation table for both mutations — the same refusals answer alike. */
	private static UserView translating(Supplier<UserView> work) {
		try {
			return work.get();
		}
		catch (UserUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND,
					"No such user at this installation.");
		}
		catch (RoleUnknownException unknown) {
			throw new ApiException(CoreErrorCode.ROLE_UNKNOWN,
					"No active role '" + unknown.roleExternalId() + "'.");
		}
		catch (KeycloakSubjectInUseException taken) {
			throw new ApiException(PlatformErrorCode.CONFLICT,
					"That identity-provider subject is already linked to an active user.");
		}
	}

	private Scope scope() {
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
				.privacyAcceptedAt(ApiTime.offset(user.privacyAcceptedAt()))
				.termsAcceptedAt(ApiTime.offset(user.termsAcceptedAt()))
				.roleExternalId(view.roleExternalId())
				.retired(user.retiredAt() != null)
				.createdAt(ApiTime.offset(user.createdAt()));
	}
}
