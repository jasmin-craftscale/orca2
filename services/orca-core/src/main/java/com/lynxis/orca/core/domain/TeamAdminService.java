package com.lynxis.orca.core.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.lynxis.orca.core.domain.IdentityTables.UserAccount;
import com.lynxis.orca.core.domain.RoleAdminService.SiteUnknownException;
import com.lynxis.orca.core.domain.TeamTables.Team;
import com.lynxis.orca.core.domain.TeamTables.TeamMember;
import com.lynxis.orca.core.persistence.BreakTemplateRepository;
import com.lynxis.orca.core.persistence.ShiftTemplateRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.TeamRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * Manages teams, their members, handling method, and shift/break templates.
 *
 * <p>Membership is declarative — the request's set replaces the stored set —
 * and every reference is validated to a typed refusal: the site against the
 * caller's scope, templates against their catalogs, members against the user
 * directory.
 */
@RequiredArgsConstructor
public class TeamAdminService {

	private final TeamRepository teams;
	private final UserAccountRepository users;
	private final ShiftTemplateRepository shiftTemplates;
	private final BreakTemplateRepository breakTemplates;
	private final SiteDirectoryRepository sites;

	public record TeamView(Team team, String shiftTemplateExternalId, String breakTemplateExternalId,
			List<String> memberUserExternalIds) {
	}

	/** The membership change: {@code null} means unchanged; empty string clears a template. */
	public record TeamChange(
			String name,
			String description,
			String handlingMethod,
			String shiftTemplateExternalId,
			String breakTemplateExternalId,
			List<String> memberUserExternalIds,
			Boolean retired) {
	}

	public List<TeamView> list() {
		return assembleViews(teams.all());
	}

	@Transactional
	public TeamView create(String siteExternalId, String name, String description,
			String handlingMethod, String shiftTemplateExternalId, String breakTemplateExternalId,
			List<String> memberUserExternalIds) {
		if (!sites.activeSiteExternalIds().contains(siteExternalId)) {
			throw new SiteUnknownException(siteExternalId);
		}
		Long shiftTemplateId = resolveShiftTemplate(shiftTemplateExternalId);
		Long breakTemplateId = resolveBreakTemplate(breakTemplateExternalId);
		Set<Long> memberIds = resolveMembers(memberUserExternalIds);
		if (teams.nameInUse(siteExternalId, name, 0)) {
			throw new TeamNameInUseException(name);
		}
		String externalId = "team-" + UUID.randomUUID();
		long teamId;
		try {
			teamId = teams.insert(externalId, siteExternalId, name, description,
					handlingMethod, shiftTemplateId, breakTemplateId);
		}
		catch (DuplicateKeyException lostTheRace) {
			throw new TeamNameInUseException(name);
		}
		if (!memberIds.isEmpty()) {
			teams.replaceMembers(teamId, siteExternalId, memberIds);
		}
		return viewOf(externalId);
	}

	@Transactional
	public TeamView update(String externalId, TeamChange change) {
		Team team = teams.byExternalId(externalId)
				.orElseThrow(() -> new TeamUnknownException(externalId));
		if (change.name() != null
				&& teams.nameInUse(team.siteExternalId(), change.name(), team.teamId())) {
			throw new TeamNameInUseException(change.name());
		}
		boolean clearShift = "".equals(change.shiftTemplateExternalId());
		boolean clearBreak = "".equals(change.breakTemplateExternalId());
		Long shiftTemplateId = clearShift ? null : resolveShiftTemplate(change.shiftTemplateExternalId());
		Long breakTemplateId = clearBreak ? null : resolveBreakTemplate(change.breakTemplateExternalId());
		Set<Long> memberIds = change.memberUserExternalIds() == null
				? null
				: resolveMembers(change.memberUserExternalIds());
		try {
			teams.update(externalId, change.name(), change.description(), change.handlingMethod(),
					shiftTemplateId, clearShift, breakTemplateId, clearBreak, change.retired());
		}
		catch (DuplicateKeyException lostTheRace) {
			throw new TeamNameInUseException(change.name());
		}
		if (memberIds != null) {
			teams.replaceMembers(team.teamId(), team.siteExternalId(), memberIds);
		}
		return viewOf(externalId);
	}

	// ------------------------------------------------------------------------

	private TeamView viewOf(String externalId) {
		Team team = teams.byExternalId(externalId)
				.orElseThrow(() -> new TeamUnknownException(externalId));
		return assembleViews(List.of(team)).getFirst();
	}

	private List<TeamView> assembleViews(List<Team> all) {
		Map<Long, String> shiftExternalIds = shiftTemplates.all().stream()
				.collect(Collectors.toMap(TeamTables.ShiftTemplate::shiftTemplateId,
						TeamTables.ShiftTemplate::externalId));
		Map<Long, String> breakExternalIds = breakTemplates.all().stream()
				.collect(Collectors.toMap(TeamTables.BreakTemplate::breakTemplateId,
						TeamTables.BreakTemplate::externalId));
		Map<Long, String> userExternalIds = users.all().stream()
				.collect(Collectors.toMap(UserAccount::userId, UserAccount::externalId));
		Map<Long, List<String>> membersByTeam = teams.activeMembers().stream()
				.collect(Collectors.groupingBy(TeamMember::teamId,
						Collectors.mapping(member -> userExternalIds.get(member.userId()),
								Collectors.toList())));
		return all.stream()
				.map(team -> new TeamView(team,
						team.shiftTemplateId() == null ? null : shiftExternalIds.get(team.shiftTemplateId()),
						team.breakTemplateId() == null ? null : breakExternalIds.get(team.breakTemplateId()),
						membersByTeam.getOrDefault(team.teamId(), List.of())))
				.toList();
	}

	private Long resolveShiftTemplate(String externalId) {
		if (externalId == null || externalId.isEmpty()) {
			return null;
		}
		return shiftTemplates.byExternalId(externalId)
				.filter(template -> template.retiredAt() == null)
				.map(TeamTables.ShiftTemplate::shiftTemplateId)
				.orElseThrow(() -> new TemplateAdminService.ShiftTemplateUnknownException(externalId));
	}

	private Long resolveBreakTemplate(String externalId) {
		if (externalId == null || externalId.isEmpty()) {
			return null;
		}
		return breakTemplates.byExternalId(externalId)
				.filter(template -> template.retiredAt() == null)
				.map(TeamTables.BreakTemplate::breakTemplateId)
				.orElseThrow(() -> new TemplateAdminService.BreakTemplateUnknownException(externalId));
	}

	private Set<Long> resolveMembers(List<String> memberUserExternalIds) {
		if (memberUserExternalIds == null || memberUserExternalIds.isEmpty()) {
			return Set.of();
		}
		Map<String, UserAccount> byExternalId = users.all().stream()
				.collect(Collectors.toMap(UserAccount::externalId, user -> user));
		Set<Long> resolved = new LinkedHashSet<>();
		for (String userExternalId : memberUserExternalIds) {
			UserAccount user = byExternalId.get(userExternalId);
			if (user == null || user.retiredAt() != null) {
				throw new MemberUnknownException(userExternalId);
			}
			resolved.add(user.userId());
		}
		return resolved;
	}

	public static class TeamUnknownException extends RuntimeException {
		public TeamUnknownException(String externalId) {
			super("No team '" + externalId + "'");
		}
	}

	public static class TeamNameInUseException extends RuntimeException {
		public TeamNameInUseException(String name) {
			super("An active team at this site is already named '" + name + "'");
		}
	}

	public static class MemberUnknownException extends RuntimeException {
		private final String userExternalId;

		public MemberUnknownException(String userExternalId) {
			super("No active user '" + userExternalId + "'");
			this.userExternalId = userExternalId;
		}

		public String userExternalId() {
			return userExternalId;
		}
	}
}
