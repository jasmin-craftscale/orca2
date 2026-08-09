package com.lynxis.orca.core.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;

import com.lynxis.orca.core.domain.RoutingTables.Screen;
import com.lynxis.orca.core.domain.RoutingTables.TeamRouting;
import com.lynxis.orca.core.domain.TeamTables.Team;
import com.lynxis.orca.core.persistence.DeviceRepository;
import com.lynxis.orca.core.persistence.ScreenRepository;
import com.lynxis.orca.core.persistence.TeamRepository;
import com.lynxis.orca.core.persistence.TeamRoutingRepository;

/**
 * Screens (the identity, not the renderer) and the team routing rules — Phase 3
 * WP2, the piece Phase 2's WP2 deferred because it references a screen.
 *
 * <p>Rule sets are declarative, like a role's grants and a team's members: the
 * submitted set becomes the team's rules. The unique tuple is the database's —
 * the request-level duplicate check exists for a friendly 422, and the filtered
 * unique index is the guard.
 */
public class RoutingAdminService {

	private final ScreenRepository screens;
	private final TeamRoutingRepository routing;
	private final TeamRepository teams;
	private final DeviceRepository devices;
	private final AuditTrail audit;

	public RoutingAdminService(ScreenRepository screens, TeamRoutingRepository routing,
			TeamRepository teams, DeviceRepository devices, AuditTrail audit) {
		this.screens = screens;
		this.routing = routing;
		this.teams = teams;
		this.devices = devices;
		this.audit = audit;
	}

	// --- screens ------------------------------------------------------------

	public List<Screen> allScreens() {
		return screens.all();
	}

	public Screen createScreen(String siteExternalId, String name, String processDefinitionKey,
			String nodeReference, Integer belowExpectedSec, Integer expectedSec, Integer maxSec) {
		String externalId = "scr-" + UUID.randomUUID();
		try {
			screens.insert(externalId, siteExternalId, name, processDefinitionKey, nodeReference,
					belowExpectedSec, expectedSec, maxSec);
		}
		catch (DuplicateKeyException taken) {
			throw new ScreenNodeTakenException(processDefinitionKey, nodeReference);
		}
		audit.record("screen", externalId, "CREATED", "name=" + name + " node="
				+ processDefinitionKey + ":" + nodeReference);
		return screens.byExternalId(externalId).orElseThrow();
	}

	public Screen updateScreen(String externalId, String name, String processDefinitionKey,
			String nodeReference, Integer belowExpectedSec, Integer expectedSec, Integer maxSec,
			Boolean retired) {
		screens.byExternalId(externalId).orElseThrow(() -> new ScreenUnknownException(externalId));
		try {
			screens.update(externalId, name, processDefinitionKey, nodeReference,
					belowExpectedSec != null && belowExpectedSec > 0 ? belowExpectedSec : null,
					isClear(belowExpectedSec),
					expectedSec != null && expectedSec > 0 ? expectedSec : null, isClear(expectedSec),
					maxSec != null && maxSec > 0 ? maxSec : null, isClear(maxSec),
					retired);
		}
		catch (DuplicateKeyException taken) {
			throw new ScreenNodeTakenException(processDefinitionKey, nodeReference);
		}
		audit.record("screen", externalId, "UPDATED", null);
		return screens.byExternalId(externalId).orElseThrow();
	}

	private static boolean isClear(Integer threshold) {
		return threshold != null && threshold == 0;
	}

	// --- routing rules ------------------------------------------------------

	public record Rule(String screenExternalId, String laneExternalId, Integer priority) {
	}

	public List<Rule> rulesOfTeam(String teamExternalId) {
		Team team = teams.byExternalId(teamExternalId)
				.orElseThrow(() -> new TeamUnknownException(teamExternalId));
		return resolved(routing.activeOfTeam(team.teamId()));
	}

	/** The submitted set becomes the team's rules — retire the old, insert the new, one transaction expected of the caller's boundary (each write is scoped and small; the controller runs this per request). */
	public List<Rule> replaceRules(String teamExternalId, List<Rule> rules) {
		Team team = teams.byExternalId(teamExternalId)
				.orElseThrow(() -> new TeamUnknownException(teamExternalId));

		Set<String> seen = new HashSet<>();
		record Resolved(long screenId, long laneId, Integer priority, String pair) {
		}
		List<Resolved> resolved = rules.stream().map(rule -> {
			Screen screen = screens.byExternalId(rule.screenExternalId())
					.orElseThrow(() -> new ScreenUnknownException(rule.screenExternalId()));
			DeviceRepository.LaneRef lane = devices.laneByExternalId(rule.laneExternalId())
					.orElseThrow(() -> new LaneUnknownException(rule.laneExternalId()));
			String pair = screen.screenId() + ":" + lane.laneId();
			if (!seen.add(pair)) {
				throw new RoutingRuleDuplicateException(rule.screenExternalId(), rule.laneExternalId());
			}
			return new Resolved(screen.screenId(), lane.laneId(), rule.priority(), pair);
		}).toList();

		routing.retireOfTeam(team.teamId());
		for (Resolved rule : resolved) {
			routing.insert("rt-" + UUID.randomUUID(), team.siteExternalId(), team.teamId(),
					rule.screenId(), rule.laneId(), rule.priority());
		}
		audit.record("team_routing", teamExternalId, "REPLACED", rules.size() + " rule(s)");
		return resolved(routing.activeOfTeam(team.teamId()));
	}

	private List<Rule> resolved(List<TeamRouting> rows) {
		// Small sets, admin path: resolving ids back to external vocabulary by
		// per-row lookups is bounded by a team's rule count.
		return rows.stream().map(row -> new Rule(
				screens.all().stream().filter(s -> s.screenId() == row.screenId()).findFirst()
						.map(Screen::externalId).orElse(null),
				devices.publishedLanes().stream().filter(l -> l.laneId() == row.laneId()).findFirst()
						.map(DeviceRepository.LaneRef::laneExternalId).orElse(null),
				row.priority())).toList();
	}

	// --- typed failures -----------------------------------------------------

	public static class ScreenUnknownException extends RuntimeException {
		public ScreenUnknownException(String externalId) {
			super("No screen '" + externalId + "' exists at this installation.");
		}
	}

	public static class TeamUnknownException extends RuntimeException {
		public TeamUnknownException(String externalId) {
			super("No team '" + externalId + "' exists at this installation.");
		}
	}

	public static class LaneUnknownException extends RuntimeException {
		public LaneUnknownException(String externalId) {
			super("No lane '" + externalId + "' exists at this installation.");
		}
	}

	public static class ScreenNodeTakenException extends RuntimeException {
		public ScreenNodeTakenException(String processDefinitionKey, String nodeReference) {
			super("An active screen already fronts node '" + processDefinitionKey + ":"
					+ nodeReference + "' at this site. Routing must resolve to ONE screen.");
		}
	}

	public static class RoutingRuleDuplicateException extends RuntimeException {
		public RoutingRuleDuplicateException(String screenExternalId, String laneExternalId) {
			super("The submitted set names (screen '" + screenExternalId + "', lane '"
					+ laneExternalId + "') more than once.");
		}
	}
}
