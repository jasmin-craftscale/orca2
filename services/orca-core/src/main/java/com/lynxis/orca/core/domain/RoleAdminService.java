package com.lynxis.orca.core.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.IdentityTables.EntitlementActionItem;
import com.lynxis.orca.core.domain.IdentityTables.Role;
import com.lynxis.orca.core.domain.UserAdminService.RoleUnknownException;
import com.lynxis.orca.core.persistence.EntitlementCatalogRepository;
import com.lynxis.orca.core.persistence.RoleRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * Role management (§C1): customer-defined roles, their entitlement grants and
 * their site scoping.
 *
 * <p>Grants and site scope are declarative — the request's set replaces the
 * stored set. Codes are validated against the seeded catalog and sites against
 * the caller's own scope, each with a distinct domain exception so the contract
 * can answer 422 with a code a console can branch on.
 */
@RequiredArgsConstructor
public class RoleAdminService {

	private final RoleRepository roles;
	private final EntitlementCatalogRepository catalog;
	private final SiteDirectoryRepository sites;
	private final UserAccountRepository users;

	public record RoleView(Role role, List<String> siteExternalIds, List<String> entitlementCodes) {
	}

	public List<RoleView> list() {
		Map<Long, List<String>> sitesByRole = roles.activeSiteMappings().stream()
				.collect(Collectors.groupingBy(IdentityTables.RoleSite::roleId,
						Collectors.mapping(IdentityTables.RoleSite::siteExternalId, Collectors.toList())));
		Map<Long, String> codesById = catalog.actionItems().stream()
				.collect(Collectors.toMap(EntitlementActionItem::actionItemId, EntitlementActionItem::code));
		Map<Long, List<String>> codesByRole = roles.activeGrants().stream()
				.collect(Collectors.groupingBy(IdentityTables.RoleEntitlement::roleId,
						Collectors.mapping(grant -> codesById.get(grant.actionItemId()), Collectors.toList())));
		return roles.all().stream()
				.map(role -> new RoleView(role,
						sitesByRole.getOrDefault(role.roleId(), List.of()),
						codesByRole.getOrDefault(role.roleId(), List.of())))
				.toList();
	}

	@Transactional
	public RoleView create(String name, String description,
			List<String> siteExternalIds, List<String> entitlementCodes) {
		Set<Long> actionItemIds = resolveCodes(entitlementCodes);
		Set<String> siteScope = resolveSites(siteExternalIds);
		if (roles.nameInUse(name, 0)) {
			throw new RoleNameInUseException(name);
		}
		String externalId = "rol-" + UUID.randomUUID();
		long roleId;
		try {
			roleId = roles.insert(externalId, name, description);
		}
		catch (DuplicateKeyException lostTheRace) {
			// Two administrators, one name, same instant: the filtered unique
			// index is the arbiter and this is the loser's answer.
			throw new RoleNameInUseException(name);
		}
		if (!actionItemIds.isEmpty()) {
			roles.replaceGrants(roleId, actionItemIds);
		}
		if (!siteScope.isEmpty()) {
			roles.replaceSiteScope(roleId, siteScope);
		}
		return viewOf(externalId);
	}

	@Transactional
	public RoleView update(String externalId, String name, String description,
			List<String> siteExternalIds, List<String> entitlementCodes, Boolean retired) {
		Role role = roles.byExternalId(externalId)
				.orElseThrow(() -> new RoleUnknownException(externalId));
		if (name != null && roles.nameInUse(name, role.roleId())) {
			throw new RoleNameInUseException(name);
		}
		if (Boolean.TRUE.equals(retired) && users.anyActiveUserHolds(role.roleId())) {
			throw new RoleStillHeldException(externalId);
		}
		Set<Long> actionItemIds = entitlementCodes == null ? null : resolveCodes(entitlementCodes);
		Set<String> siteScope = siteExternalIds == null ? null : resolveSites(siteExternalIds);
		try {
			roles.update(externalId, name, description, retired);
		}
		catch (DuplicateKeyException lostTheRace) {
			throw new RoleNameInUseException(name);
		}
		if (actionItemIds != null) {
			roles.replaceGrants(role.roleId(), actionItemIds);
		}
		if (siteScope != null) {
			roles.replaceSiteScope(role.roleId(), siteScope);
		}
		return viewOf(externalId);
	}

	private RoleView viewOf(String externalId) {
		return list().stream()
				.filter(view -> view.role().externalId().equals(externalId))
				.findFirst()
				.orElseThrow(() -> new RoleUnknownException(externalId));
	}

	private Set<Long> resolveCodes(List<String> entitlementCodes) {
		if (entitlementCodes == null || entitlementCodes.isEmpty()) {
			return Set.of();
		}
		Map<String, Long> idsByCode = catalog.actionItems().stream()
				.collect(Collectors.toMap(EntitlementActionItem::code, EntitlementActionItem::actionItemId));
		Set<Long> resolved = new LinkedHashSet<>();
		for (String code : entitlementCodes) {
			Long id = idsByCode.get(code);
			if (id == null) {
				throw new EntitlementUnknownException(code);
			}
			resolved.add(id);
		}
		return resolved;
	}

	private Set<String> resolveSites(List<String> siteExternalIds) {
		if (siteExternalIds == null || siteExternalIds.isEmpty()) {
			return Set.of();
		}
		Set<String> known = sites.activeSiteExternalIds();
		Set<String> resolved = new LinkedHashSet<>();
		for (String siteExternalId : siteExternalIds) {
			if (!known.contains(siteExternalId)) {
				throw new SiteUnknownException(siteExternalId);
			}
			resolved.add(siteExternalId);
		}
		return resolved;
	}

	public static class RoleNameInUseException extends RuntimeException {
		public RoleNameInUseException(String name) {
			super("An active role is already named '" + name + "'");
		}
	}

	public static class RoleStillHeldException extends RuntimeException {
		public RoleStillHeldException(String externalId) {
			super("Active users still hold role '" + externalId + "'");
		}
	}

	public static class EntitlementUnknownException extends RuntimeException {
		private final String code;

		public EntitlementUnknownException(String code) {
			super("No entitlement '" + code + "' in the catalog");
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	public static class SiteUnknownException extends RuntimeException {
		private final String siteExternalId;

		public SiteUnknownException(String siteExternalId) {
			super("No active site '" + siteExternalId + "' at this installation");
			this.siteExternalId = siteExternalId;
		}

		public String siteExternalId() {
			return siteExternalId;
		}
	}
}
