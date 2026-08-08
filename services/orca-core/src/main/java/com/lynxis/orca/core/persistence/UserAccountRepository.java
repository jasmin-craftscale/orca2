package com.lynxis.orca.core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.domain.IdentityTables.UserAccount;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * Reads and writes {@code user_account} through the seam.
 *
 * <p>Installation-realm scoped: the table carries {@code config_realm} rather
 * than a site column, because a user belongs to the installation and not to a
 * site — site visibility is the role's ({@code role_site}). See
 * {@link IdentityTables} for the two-dimension design.
 */
@RequiredArgsConstructor
public class UserAccountRepository {

	private static final String TABLE = "user_account";
	private static final String SCOPE_COLUMN = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final String[] COLUMNS = {
			"user_id", "external_id", "config_realm", "keycloak_subject",
			"first_name", "middle_name", "last_name", "display_name", "email",
			"profile_image_url", "language_code", "privacy_accepted_at",
			"terms_accepted_at", "role_id", "retired_at", "created_at" };

	private static final RowMapper<UserAccount> MAPPER = (rs, row) -> new UserAccount(
			rs.getLong("user_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("keycloak_subject"),
			rs.getString("first_name"),
			rs.getString("middle_name"),
			rs.getString("last_name"),
			rs.getString("display_name"),
			rs.getString("email"),
			rs.getString("profile_image_url"),
			rs.getString("language_code"),
			Utc.instantAt(rs, "privacy_accepted_at"),
			Utc.instantAt(rs, "terms_accepted_at"),
			rs.getLong("role_id"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	/** The whole directory, retired included, in creation order. */
	public List<UserAccount> all() {
		return seam.select(ScopedSelect.from(TABLE)
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.orderBy("user_id"),
				MAPPER);
	}

	public Optional<UserAccount> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from(TABLE)
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("external_id = ?", externalId),
				MAPPER)
				.stream().findFirst();
	}

	public void insert(UserAccount draft) {
		seam.insert(ScopedInsert.into(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", draft.externalId())
				.value(SCOPE_COLUMN, IdentityTables.INSTALLATION_REALM)
				.value("keycloak_subject", draft.keycloakSubject())
				.value("first_name", draft.firstName())
				.value("middle_name", draft.middleName())
				.value("last_name", draft.lastName())
				.value("display_name", draft.displayName())
				.value("email", draft.email())
				.value("profile_image_url", draft.profileImageUrl())
				.value("language_code", draft.languageCode())
				.value("privacy_accepted_at", Utc.timestampOf(draft.privacyAcceptedAt()))
				.value("terms_accepted_at", Utc.timestampOf(draft.termsAcceptedAt()))
				.value("role_id", draft.roleId()));
	}

	/**
	 * Applies the non-null assignments of {@code patch} to one user.
	 *
	 * <p>{@code retired} is tri-state and carried separately: {@code null} leaves
	 * it alone, {@code true} stamps {@code retired_at}, {@code false} clears it
	 * (external ids are never reused, so reinstating is unambiguous).
	 */
	public int update(String externalId, UserPatch patch) {
		ScopedUpdate update = ScopedUpdate.table(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ?", externalId);
		boolean touched = false;
		if (patch.keycloakSubject() != null) {
			update.set("keycloak_subject", patch.keycloakSubject());
			touched = true;
		}
		if (patch.firstName() != null) {
			update.set("first_name", patch.firstName());
			touched = true;
		}
		if (patch.middleName() != null) {
			update.set("middle_name", patch.middleName());
			touched = true;
		}
		if (patch.lastName() != null) {
			update.set("last_name", patch.lastName());
			touched = true;
		}
		if (patch.displayName() != null) {
			update.set("display_name", patch.displayName());
			touched = true;
		}
		if (patch.email() != null) {
			update.set("email", patch.email());
			touched = true;
		}
		if (patch.profileImageUrl() != null) {
			update.set("profile_image_url", patch.profileImageUrl());
			touched = true;
		}
		if (patch.languageCode() != null) {
			update.set("language_code", patch.languageCode());
			touched = true;
		}
		if (patch.privacyAcceptedAt() != null) {
			update.set("privacy_accepted_at", Utc.timestampOf(patch.privacyAcceptedAt()));
			touched = true;
		}
		if (patch.termsAcceptedAt() != null) {
			update.set("terms_accepted_at", Utc.timestampOf(patch.termsAcceptedAt()));
			touched = true;
		}
		if (patch.roleId() != null) {
			update.set("role_id", patch.roleId());
			touched = true;
		}
		if (patch.retired() != null) {
			update.set("retired_at", patch.retired() ? Utc.now() : null);
			touched = true;
		}
		if (!touched) {
			// An empty patch changes nothing; the caller still gets the row back.
			return 0;
		}
		return seam.update(update);
	}

	/** Whether any active user still holds the role — what blocks retiring it. */
	public boolean anyActiveUserHolds(long roleId) {
		return seam.count(ScopedSelect.from(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.where("role_id = ? AND retired_at IS NULL", roleId)) > 0;
	}

	/**
	 * A patch: {@code null} means unchanged. Deliberately a persistence-layer
	 * shape — the API's DTO is the generated model, and mapping between the two
	 * is the controller's job, not this class's.
	 */
	public record UserPatch(
			String keycloakSubject,
			String firstName,
			String middleName,
			String lastName,
			String displayName,
			String email,
			String profileImageUrl,
			String languageCode,
			Instant privacyAcceptedAt,
			Instant termsAcceptedAt,
			Long roleId,
			Boolean retired) {
	}
}
