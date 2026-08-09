package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.core.api.BreakTemplateController;
import com.lynxis.orca.core.api.ShiftTemplateController;
import com.lynxis.orca.core.api.TeamAdminController;
import com.lynxis.orca.core.api.generated.model.BreakTimingItem;
import com.lynxis.orca.core.api.generated.model.CreateBreakTemplateRequest;
import com.lynxis.orca.core.api.generated.model.CreateShiftTemplateRequest;
import com.lynxis.orca.core.api.generated.model.CreateTeamRequest;
import com.lynxis.orca.core.api.generated.model.HandlingMethod;
import com.lynxis.orca.core.api.generated.model.ShiftTemplateSummary;
import com.lynxis.orca.core.api.generated.model.TeamSummary;
import com.lynxis.orca.core.api.generated.model.UpdateShiftTemplateRequest;
import com.lynxis.orca.core.api.generated.model.UpdateTeamRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.TeamAdminService;
import com.lynxis.orca.core.domain.TemplateAdminService;
import com.lynxis.orca.core.persistence.BreakTemplateRepository;
import com.lynxis.orca.core.persistence.ShiftTemplateRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.TeamRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.web.ApiException;

/**
 * Proves teams and shift/break templates.
 *
 * <p>The suite exercises template CRUD end to end and proves overnight/duration
 * semantics. The legacy 1.x schema stored a duration in a TIME column and carried
 * a flag nothing kept consistent; here duration is computed and a database CHECK
 * keeps the flag consistent with the times.
 */
class TeamsTemplatesPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String SITE = "SITE-IT";

	private static DataSource owner;

	private JdbcTemplate core;
	private ShiftTemplateController shiftApi;
	private BreakTemplateController breakApi;
	private TeamAdminController teamApi;
	private long clerkRoleId;

	@BeforeAll
	static void migrate() {
		PlatformDatabase.consumerLogin("orca_runtime");
		PlatformDatabase.consumerLogin("orca_edge");
		owner = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease");
	}

	@BeforeEach
	void freshWorldAndBeans() {
		core = new JdbcTemplate(owner);
		core.execute("DELETE FROM team_member");
		core.execute("DELETE FROM team");
		core.execute("DELETE FROM break_timing");
		core.execute("DELETE FROM break_template");
		core.execute("DELETE FROM shift_template");
		core.execute("DELETE FROM role_entitlement");
		core.execute("DELETE FROM role_site");
		core.execute("DELETE FROM user_account");
		core.execute("DELETE FROM role");
		core.execute("DELETE FROM device");
		core.execute("DELETE FROM lane");
		core.execute("DELETE FROM area");
		core.execute("DELETE FROM site");
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES (?, 'IT', 'IT Terminal', 1)", SITE);
		core.update("INSERT INTO role (external_id, name) VALUES ('rol-clerk', 'Clerk')");
		clerkRoleId = core.queryForObject("SELECT role_id FROM role WHERE external_id = 'rol-clerk'", Long.class);

		ScopeSeam seam = new JdbcScopeSeam(core);
		ShiftTemplateRepository shiftTemplates = new ShiftTemplateRepository(seam);
		BreakTemplateRepository breakTemplates = new BreakTemplateRepository(seam);
		TeamRepository teams = new TeamRepository(seam);
		UserAccountRepository users = new UserAccountRepository(seam);
		SiteDirectoryRepository sites = new SiteDirectoryRepository(seam);
		TemplateAdminService templates = new TemplateAdminService(shiftTemplates, breakTemplates, teams);
		shiftApi = new ShiftTemplateController(templates, SITE);
		breakApi = new BreakTemplateController(templates, SITE);
		teamApi = new TeamAdminController(
				new TeamAdminService(teams, users, shiftTemplates, breakTemplates, sites), SITE);
	}

	// ------------------------------------------------------------------------
	// The overnight/duration semantics — the WP's named property.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a day shift, a night shift and the 24-hour shift each carry the computed truth — duration is never stored")
	void overnightAndDurationSemantics() {
		ShiftTemplateSummary day = inScope(() -> shiftApi.createShiftTemplate(new CreateShiftTemplateRequest()
				.name("Day").timeZone("Europe/Berlin").startTime("08:00").endTime("16:00"))
				.getBody().getData());
		assertThat(day.getOvernight()).isFalse();
		assertThat(day.getDurationMinutes()).isEqualTo(480);

		ShiftTemplateSummary night = inScope(() -> shiftApi.createShiftTemplate(new CreateShiftTemplateRequest()
				.name("Night").timeZone("Europe/Berlin").startTime("22:00").endTime("06:00"))
				.getBody().getData());
		assertThat(night.getOvernight())
				.as("end at or before start crosses midnight — the flag is computed, not client-supplied")
				.isTrue();
		assertThat(night.getDurationMinutes())
				.as("eight hours on the clock face, across midnight — the 1.x duration-in-a-TIME-column defect, done right")
				.isEqualTo(480);

		ShiftTemplateSummary continuous = inScope(() -> shiftApi.createShiftTemplate(new CreateShiftTemplateRequest()
				.name("Continuous").timeZone("UTC").startTime("06:00").endTime("06:00"))
				.getBody().getData());
		assertThat(continuous.getOvernight()).isTrue();
		assertThat(continuous.getDurationMinutes()).isEqualTo(1440);
	}

	@Test
	@DisplayName("changing one end of a shift recomputes the flag against the stored other end")
	void patchingOneTimeRecomputesOvernight() {
		ShiftTemplateSummary shift = inScope(() -> shiftApi.createShiftTemplate(new CreateShiftTemplateRequest()
				.name("Swing").timeZone("UTC").startTime("14:00").endTime("22:00"))
				.getBody().getData());
		assertThat(shift.getOvernight()).isFalse();

		ShiftTemplateSummary moved = inScope(() -> shiftApi.updateShiftTemplate(shift.getExternalId(),
				new UpdateShiftTemplateRequest().endTime("02:00")).getBody().getData());
		assertThat(moved.getOvernight())
				.as("14:00→02:00 crosses midnight; only endTime was patched")
				.isTrue();
		assertThat(moved.getDurationMinutes()).isEqualTo(720);
	}

	@Test
	@DisplayName("the database refuses a stored flag that contradicts the times — the 1.x inconsistency cannot exist here")
	void theOvernightCheckHolds() {
		assertThatThrownBy(() -> core.update(
				"INSERT INTO shift_template (external_id, name, time_zone, start_time, end_time, is_overnight) "
						+ "VALUES ('shf-bad', 'Bad', 'UTC', '08:00', '16:00', 1)"))
				.as("a flag nothing keeps consistent is 1.x's shape; here the CHECK ties it to the times")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("a timezone the JVM's tz database does not know is a typed 422, not a stored string")
	void unknownTimeZoneIsRefused() {
		assertThatThrownBy(() -> inScope(() -> shiftApi.createShiftTemplate(new CreateShiftTemplateRequest()
				.name("Nowhere").timeZone("Mars/Olympus_Mons").startTime("08:00").endTime("16:00"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("TIME_ZONE_UNKNOWN"));
	}

	// ------------------------------------------------------------------------
	// What the schema refuses.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("break timings port the 1.x INTENT: no null start, no null duration, no non-positive duration")
	void breakTimingIntentIsEnforced() {
		core.update("INSERT INTO break_template (external_id, name) VALUES ('brk-t', 'Lunch plan')");
		Long templateId = core.queryForObject(
				"SELECT break_template_id FROM break_template WHERE external_id = 'brk-t'", Long.class);

		assertThatThrownBy(() -> core.update(
				"INSERT INTO break_timing (break_template_id, break_start_time, duration_minutes) "
						+ "VALUES (?, NULL, 30)", templateId))
				.as("the 1.x column was nullable only because the GORM tag was malformed (rule 4)")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> core.update(
				"INSERT INTO break_timing (break_template_id, break_start_time, duration_minutes) "
						+ "VALUES (?, '12:00', 0)", templateId))
				.isInstanceOf(DataIntegrityViolationException.class);

		core.update("INSERT INTO break_timing (break_template_id, break_start_time, duration_minutes) "
				+ "VALUES (?, '12:00', 30)", templateId);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO break_timing (break_template_id, break_start_time, duration_minutes) "
						+ "VALUES (?, '12:00', 45)", templateId))
				.as("two breaks cannot start at the same instant in one template")
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@DisplayName("the handling method is one casing, checked — 'push' is not 'PUSH' on any collation")
	void handlingMethodCasingIsSettled() {
		assertThatThrownBy(() -> core.update(
				"INSERT INTO team (external_id, site_external_id, name, handling_method) "
						+ "VALUES ('team-bad', ?, 'Bad', 'push')", SITE))
				.as("1.x carried the same enum in two casings and leaned on collation; the CHECK settles it")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("team names are unique per site among active teams, and the member pair is unique")
	void teamUniquenessRules() {
		core.update("INSERT INTO team (external_id, site_external_id, name, handling_method) "
				+ "VALUES ('team-1', ?, 'Gate Crew', 'PUSH')", SITE);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO team (external_id, site_external_id, name, handling_method) "
						+ "VALUES ('team-2', ?, 'Gate Crew', 'PROMPT')", SITE))
				.isInstanceOf(DuplicateKeyException.class);

		Long teamId = core.queryForObject("SELECT team_id FROM team WHERE external_id = 'team-1'", Long.class);
		core.update("INSERT INTO user_account (external_id, display_name, email, role_id) "
				+ "VALUES ('usr-m', 'M', 'm@example.test', ?)", clerkRoleId);
		Long userId = core.queryForObject(
				"SELECT user_id FROM user_account WHERE external_id = 'usr-m'", Long.class);

		core.update("INSERT INTO team_member (team_id, user_id, site_external_id) VALUES (?, ?, ?)",
				teamId, userId, SITE);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO team_member (team_id, user_id, site_external_id) VALUES (?, ?, ?)",
				teamId, userId, SITE))
				.as("1.x had two single-column indexes and no unique pair — the racy check is not ported")
				.isInstanceOf(DuplicateKeyException.class);
	}

	// ------------------------------------------------------------------------
	// CRUD end to end.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a team round-trips with its members, templates and handling method, and membership replacement is declarative")
	void teamCrudEndToEnd() {
		ShiftTemplateSummary shift = inScope(() -> shiftApi.createShiftTemplate(new CreateShiftTemplateRequest()
				.name("Day").timeZone("Europe/Berlin").startTime("08:00").endTime("16:00"))
				.getBody().getData());
		var breakTemplate = inScope(() -> breakApi.createBreakTemplate(new CreateBreakTemplateRequest()
				.name("Standard breaks")
				.addTimingsItem(new BreakTimingItem().startTime("12:00").durationMinutes(30))
				.addTimingsItem(new BreakTimingItem().startTime("15:30").durationMinutes(15)))
				.getBody().getData());
		assertThat(breakTemplate.getTimings()).hasSize(2);

		core.update("INSERT INTO user_account (external_id, display_name, email, role_id) "
				+ "VALUES ('usr-a', 'A', 'a@example.test', ?)", clerkRoleId);
		core.update("INSERT INTO user_account (external_id, display_name, email, role_id) "
				+ "VALUES ('usr-b', 'B', 'b@example.test', ?)", clerkRoleId);

		TeamSummary created = inScope(() -> teamApi.createTeam(new CreateTeamRequest()
				.siteExternalId(SITE)
				.name("Gate Crew")
				.handlingMethod(HandlingMethod.PROMPT)
				.shiftTemplateExternalId(shift.getExternalId())
				.breakTemplateExternalId(breakTemplate.getExternalId())
				.memberUserExternalIds(List.of("usr-a", "usr-b"))).getBody().getData());

		assertThat(created.getHandlingMethod()).isEqualTo(HandlingMethod.PROMPT);
		assertThat(created.getMemberUserExternalIds()).containsExactlyInAnyOrder("usr-a", "usr-b");
		assertThat(created.getShiftTemplateExternalId()).isEqualTo(shift.getExternalId());

		// Declarative replacement: the new set is the whole membership.
		TeamSummary rearranged = inScope(() -> teamApi.updateTeam(created.getExternalId(),
				new UpdateTeamRequest().memberUserExternalIds(List.of("usr-b"))).getBody().getData());
		assertThat(rearranged.getMemberUserExternalIds()).containsExactly("usr-b");

		// And clearing a template reference is the documented empty-string act.
		TeamSummary cleared = inScope(() -> teamApi.updateTeam(created.getExternalId(),
				new UpdateTeamRequest().shiftTemplateExternalId("")).getBody().getData());
		assertThat(cleared.getShiftTemplateExternalId()).isNull();
	}

	@Test
	@DisplayName("a template referenced by an active team cannot be retired; detach, then it can")
	void templateRetirementGuard() {
		ShiftTemplateSummary shift = inScope(() -> shiftApi.createShiftTemplate(new CreateShiftTemplateRequest()
				.name("Held").timeZone("UTC").startTime("06:00").endTime("14:00")).getBody().getData());
		TeamSummary team = inScope(() -> teamApi.createTeam(new CreateTeamRequest()
				.siteExternalId(SITE).name("Holders").handlingMethod(HandlingMethod.PUSH)
				.shiftTemplateExternalId(shift.getExternalId())).getBody().getData());

		assertThatThrownBy(() -> inScope(() -> shiftApi.updateShiftTemplate(shift.getExternalId(),
				new UpdateShiftTemplateRequest().retired(true))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("TEMPLATE_IN_USE"));

		inScope(() -> teamApi.updateTeam(team.getExternalId(),
				new UpdateTeamRequest().shiftTemplateExternalId("")));
		ShiftTemplateSummary retired = inScope(() -> shiftApi.updateShiftTemplate(shift.getExternalId(),
				new UpdateShiftTemplateRequest().retired(true)).getBody().getData());
		assertThat(retired.getRetired()).isTrue();
	}

	@Test
	@DisplayName("a break plan repeating a start time is refused as validation, before any row — not a 500 from the index")
	void duplicateTimingsAreRefusedUpFront() {
		assertThatThrownBy(() -> inScope(() -> breakApi.createBreakTemplate(new CreateBreakTemplateRequest()
				.name("Echo")
				.addTimingsItem(new BreakTimingItem().startTime("12:00").durationMinutes(30))
				.addTimingsItem(new BreakTimingItem().startTime("12:00").durationMinutes(15)))))
				.as("the review's finding: request-shaped faults must answer 4xx, the index is the backstop")
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));
		assertThat(core.queryForObject("SELECT COUNT(*) FROM break_template", Long.class))
				.as("refused before the template row, not after it")
				.isZero();
	}

	@Test
	@DisplayName("a team naming an unknown template, and a patch on a team nobody has, are typed refusals")
	void unknownTemplatesAndTeamsAreTyped() {
		assertThatThrownBy(() -> inScope(() -> teamApi.createTeam(new CreateTeamRequest()
				.siteExternalId(SITE).name("Lost shift").handlingMethod(HandlingMethod.PUSH)
				.shiftTemplateExternalId("shf-ghost"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("SHIFT_TEMPLATE_UNKNOWN"));
		assertThatThrownBy(() -> inScope(() -> teamApi.createTeam(new CreateTeamRequest()
				.siteExternalId(SITE).name("Lost break").handlingMethod(HandlingMethod.PUSH)
				.breakTemplateExternalId("brk-ghost"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("BREAK_TEMPLATE_UNKNOWN"));
		assertThatThrownBy(() -> inScope(() -> teamApi.updateTeam("team-ghost",
				new UpdateTeamRequest().name("X"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));
	}

	@Test
	@DisplayName("a team for an unknown site and a membership naming an unknown user are typed refusals")
	void unknownReferencesAreTypedRefusals() {
		assertThatThrownBy(() -> inScope(() -> teamApi.createTeam(new CreateTeamRequest()
				.siteExternalId("SITE-NOWHERE").name("Lost").handlingMethod(HandlingMethod.PUSH))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("SITE_UNKNOWN"));

		assertThatThrownBy(() -> inScope(() -> teamApi.createTeam(new CreateTeamRequest()
				.siteExternalId(SITE).name("Ghosts").handlingMethod(HandlingMethod.PUSH)
				.memberUserExternalIds(List.of("usr-ghost")))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("USER_UNKNOWN"));
	}

	// ------------------------------------------------------------------------

	private <T> T inScope(Callable<T> work) {
		return ScopeContext.callIn(CoreScopes.installation(SITE), work);
	}
}
