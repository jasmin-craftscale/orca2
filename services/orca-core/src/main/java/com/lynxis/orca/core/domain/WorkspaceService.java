package com.lynxis.orca.core.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.IdentityTables.UserAccount;
import com.lynxis.orca.core.domain.WorkspaceTables.GridDefinition;
import com.lynxis.orca.core.domain.WorkspaceTables.SavedFilter;
import com.lynxis.orca.core.domain.WorkspaceTables.UserGridColumnPreference;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.core.persistence.WorkspaceRepository;

import lombok.RequiredArgsConstructor;

/**
 * The calling user's workspace (§C1): grid column preferences and saved
 * filters. Everything here is keyed off the token's subject — a caller can
 * reach exactly their own rows, which is the ownership check NEW-1a describes,
 * in its simplest on-site form.
 */
@RequiredArgsConstructor
public class WorkspaceService {

	private final WorkspaceRepository workspace;
	private final UserAccountRepository users;
	private final CallerIdentity caller;

	public record GridView(GridDefinition grid, List<UserGridColumnPreference> columns) {
	}

	public record FilterView(SavedFilter filter, String gridCode) {
	}

	public List<GridView> grids() {
		UserAccount user = callingUser();
		Map<Long, List<UserGridColumnPreference>> byGrid = workspace.preferencesOf(user.userId())
				.stream().collect(Collectors.groupingBy(UserGridColumnPreference::gridDefinitionId));
		return workspace.grids().stream()
				.map(grid -> new GridView(grid,
						byGrid.getOrDefault(grid.gridDefinitionId(), List.of())))
				.toList();
	}

	/** One grid's replacement, described. */
	public record GridReplacement(String gridCode, List<UserGridColumnPreference> columns) {
	}

	/**
	 * Replaces the caller's preferences for every listed grid in ONE
	 * transaction — the PUT is atomic as the contract presents it, rather than
	 * one transaction per grid with a failure leaving earlier grids applied
	 * (review finding, Phase 2 addendum).
	 */
	@Transactional
	public List<GridView> replacePreferences(List<GridReplacement> replacements) {
		UserAccount user = callingUser();
		DuplicateRequestEntryException.requireDistinct(replacements, "gridCode",
				GridReplacement::gridCode);
		for (GridReplacement replacement : replacements) {
			DuplicateRequestEntryException.requireDistinct(replacement.columns(), "columnCode",
					UserGridColumnPreference::columnCode);
			GridDefinition grid = gridByCode(replacement.gridCode());
			workspace.replacePreferences(user.userId(), grid.gridDefinitionId(), replacement.columns());
		}
		return grids();
	}

	public List<FilterView> filters() {
		UserAccount user = callingUser();
		Map<Long, String> codes = workspace.grids().stream()
				.collect(Collectors.toMap(GridDefinition::gridDefinitionId, GridDefinition::code));
		return workspace.filtersOf(user.userId()).stream()
				.map(filter -> new FilterView(filter, codes.get(filter.gridDefinitionId())))
				.toList();
	}

	@Transactional
	public FilterView createFilter(String gridCode, String name, String filterJson, boolean isDefault) {
		UserAccount user = callingUser();
		GridDefinition grid = gridByCode(gridCode);
		if (isDefault) {
			// One default at most: the previous default steps down in the same
			// transaction, and the filtered unique index is the backstop for
			// the race two browser tabs can produce.
			workspace.clearDefault(user.userId(), grid.gridDefinitionId());
		}
		String externalId = "flt-" + UUID.randomUUID();
		try {
			workspace.insertFilter(externalId, user.userId(), grid.gridDefinitionId(), name,
					filterJson, 1, isDefault);
		}
		catch (DuplicateKeyException constraint) {
			// TWO indexes can fire here, and they mean different things: the
			// name index (this user already filters this grid by that name),
			// or the one-default index (a concurrent tab won the default race
			// after our clearDefault). Diagnose rather than assume — and
			// diagnose in the INDEX's collation: the database compares names
			// case-insensitively, so 'trucks' vs 'Trucks' is the name index
			// firing, not the default race (skeptical review, finding A2).
			String wanted = DuplicateRequestEntryException.collationKey(name);
			boolean nameTaken = workspace.filtersOf(user.userId()).stream()
					.anyMatch(filter -> filter.gridDefinitionId() == grid.gridDefinitionId()
							&& wanted.equals(DuplicateRequestEntryException.collationKey(filter.name())));
			if (nameTaken) {
				throw new FilterNameInUseException(name);
			}
			// The default race: step the concurrent winner down and take the
			// default deliberately — the caller asked for it last. If even the
			// retry collides, the cause left is a name variant the collation
			// approximation missed — answer it as the name conflict it is.
			workspace.clearDefault(user.userId(), grid.gridDefinitionId());
			try {
				workspace.insertFilter(externalId, user.userId(), grid.gridDefinitionId(), name,
						filterJson, 1, isDefault);
			}
			catch (DuplicateKeyException stillColliding) {
				throw new FilterNameInUseException(name);
			}
		}
		return new FilterView(workspace.filterByExternalId(externalId).orElseThrow(), gridCode);
	}

	@Transactional
	public void deleteFilter(String filterExternalId) {
		UserAccount user = callingUser();
		// Retirement scoped to the CALLER's user id: another user's filter is
		// indistinguishable from a missing one, deliberately.
		if (workspace.retireFilter(filterExternalId, user.userId()) == 0) {
			throw new FilterUnknownException(filterExternalId);
		}
	}

	// ------------------------------------------------------------------------

	private UserAccount callingUser() {
		String subject = caller.subject().orElseThrow(UserNotLinkedException::new);
		return users.activeByKeycloakSubject(subject).orElseThrow(UserNotLinkedException::new);
	}

	private GridDefinition gridByCode(String gridCode) {
		return workspace.grids().stream()
				.filter(grid -> grid.code().equals(gridCode))
				.findFirst()
				.orElseThrow(() -> new GridUnknownException(gridCode));
	}

	/** The token authenticated, but maps to no active platform user. */
	public static class UserNotLinkedException extends RuntimeException {
		public UserNotLinkedException() {
			super("The calling token maps to no active platform user");
		}
	}

	public static class GridUnknownException extends RuntimeException {
		private final String gridCode;

		public GridUnknownException(String gridCode) {
			super("No grid '" + gridCode + "' in the catalog");
			this.gridCode = gridCode;
		}

		public String gridCode() {
			return gridCode;
		}
	}

	public static class FilterNameInUseException extends RuntimeException {
		public FilterNameInUseException(String name) {
			super("A filter named '" + name + "' already exists on this grid");
		}
	}

	public static class FilterUnknownException extends RuntimeException {
		public FilterUnknownException(String externalId) {
			super("No filter '" + externalId + "' owned by the caller");
		}
	}
}
