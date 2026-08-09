package com.lynxis.orca.core.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.IdentityTables.Role;
import com.lynxis.orca.core.domain.IdentityTables.UserAccount;
import com.lynxis.orca.core.persistence.RoleRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository.UserPatch;

import lombok.RequiredArgsConstructor;

/**
 * Manages the user directory, account creation and profile patching.
 *
 * <p>Exactly one role per user is held by the schema; this service's part of
 * that invariant is refusing a role that does not exist or is retired, with a
 * domain exception the controller maps to the contract's 422.
 */
@RequiredArgsConstructor
public class UserAdminService {

	private final UserAccountRepository users;
	private final RoleRepository roles;

	/** A user joined to the role's external id — the shape the API speaks. */
	public record UserView(UserAccount user, String roleExternalId) {
	}

	/** The profile fields of a creation request; the external id is minted here. */
	public record UserDraft(
			String displayName,
			String firstName,
			String middleName,
			String lastName,
			String email,
			String profileImageUrl,
			String languageCode,
			String keycloakSubject,
			String roleExternalId) {
	}

	/** A patch; {@code null} = unchanged. {@code retired} is tri-state. */
	public record UserChange(
			String displayName,
			String firstName,
			String middleName,
			String lastName,
			String email,
			String profileImageUrl,
			String languageCode,
			String keycloakSubject,
			String roleExternalId,
			java.time.Instant privacyAcceptedAt,
			java.time.Instant termsAcceptedAt,
			Boolean retired) {
	}

	public List<UserView> list() {
		Map<Long, String> roleExternalIds = roles.all().stream()
				.collect(Collectors.toMap(Role::roleId, Role::externalId));
		return users.all().stream()
				.map(user -> new UserView(user, roleExternalIds.get(user.roleId())))
				.toList();
	}

	@Transactional
	public UserView create(UserDraft draft) {
		Role role = activeRole(draft.roleExternalId());
		String externalId = "usr-" + UUID.randomUUID();
		try {
			users.insert(new UserAccount(0, externalId, IdentityTables.INSTALLATION_REALM,
					draft.keycloakSubject(), draft.firstName(), draft.middleName(), draft.lastName(),
					draft.displayName(), draft.email(), draft.profileImageUrl(), draft.languageCode(),
					null, null, role.roleId(), null, null));
		}
		catch (org.springframework.dao.DuplicateKeyException subjectTaken) {
			// The only unique this insert can violate: the external id is a fresh
			// UUID and email is deliberately non-unique, so this is the filtered
			// one-active-user-per-subject index.
			throw new KeycloakSubjectInUseException(draft.keycloakSubject());
		}
		return viewOf(externalId);
	}

	@Transactional
	public UserView update(String externalId, UserChange change) {
		users.byExternalId(externalId).orElseThrow(() -> new UserUnknownException(externalId));
		Long roleId = null;
		if (change.roleExternalId() != null) {
			roleId = activeRole(change.roleExternalId()).roleId();
		}
		try {
			users.update(externalId, new UserPatch(
					change.keycloakSubject(), change.firstName(), change.middleName(), change.lastName(),
					change.displayName(), change.email(), change.profileImageUrl(), change.languageCode(),
					change.privacyAcceptedAt(), change.termsAcceptedAt(), roleId, change.retired()));
		}
		catch (org.springframework.dao.DuplicateKeyException subjectTaken) {
			// Fires on a subject patch AND on reinstatement (retired: false) of a
			// user whose subject was meanwhile linked to a new active user — the
			// row re-enters the filtered unique index and collides.
			throw new KeycloakSubjectInUseException(change.keycloakSubject());
		}
		return viewOf(externalId);
	}

	private UserView viewOf(String externalId) {
		UserAccount user = users.byExternalId(externalId)
				.orElseThrow(() -> new UserUnknownException(externalId));
		Map<Long, String> roleExternalIds = roles.all().stream()
				.collect(Collectors.toMap(Role::roleId, Role::externalId, (a, b) -> a));
		return new UserView(user, roleExternalIds.get(user.roleId()));
	}

	private Role activeRole(String roleExternalId) {
		return roles.byExternalId(roleExternalId)
				.filter(role -> role.retiredAt() == null)
				.orElseThrow(() -> new RoleUnknownException(roleExternalId));
	}

	/** Used by both admin services; carries the id the caller named. */
	public static class RoleUnknownException extends RuntimeException {
		private final String roleExternalId;

		public RoleUnknownException(String roleExternalId) {
			super("No active role '" + roleExternalId + "'");
			this.roleExternalId = roleExternalId;
		}

		public String roleExternalId() {
			return roleExternalId;
		}
	}

	public static class UserUnknownException extends RuntimeException {
		public UserUnknownException(String externalId) {
			super("No user '" + externalId + "'");
		}
	}

	/**
	 * One identity-provider subject maps to at most one ACTIVE user — the
	 * filtered unique index holds it; this is its typed answer. The subject in
	 * the message may be null when the collision came from reinstatement.
	 */
	public static class KeycloakSubjectInUseException extends RuntimeException {
		public KeycloakSubjectInUseException(String subject) {
			super("Identity-provider subject already linked to an active user"
					+ (subject == null ? " (reinstatement collision)" : ": '" + subject + "'"));
		}
	}
}
