package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.TeamsApi;
import com.lynxis.orca.core.api.generated.model.CreateTeamRequest;
import com.lynxis.orca.core.api.generated.model.HandlingMethod;
import com.lynxis.orca.core.api.generated.model.TeamEnvelope;
import com.lynxis.orca.core.api.generated.model.TeamSummary;
import com.lynxis.orca.core.api.generated.model.TeamsEnvelope;
import com.lynxis.orca.core.api.generated.model.UpdateTeamRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.RoleAdminService.SiteUnknownException;
import com.lynxis.orca.core.domain.TeamAdminService;
import com.lynxis.orca.core.domain.TeamAdminService.MemberUnknownException;
import com.lynxis.orca.core.domain.TeamAdminService.TeamChange;
import com.lynxis.orca.core.domain.TeamAdminService.TeamNameInUseException;
import com.lynxis.orca.core.domain.TeamAdminService.TeamUnknownException;
import com.lynxis.orca.core.domain.TeamAdminService.TeamView;
import com.lynxis.orca.core.domain.TemplateAdminService.BreakTemplateUnknownException;
import com.lynxis.orca.core.domain.TemplateAdminService.ShiftTemplateUnknownException;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** The team HTTP surface: members, handling method, and shift/break templates. */
@RestController
public class TeamAdminController implements TeamsApi {

	private final TeamAdminService service;
	private final String siteExternalId;

	public TeamAdminController(TeamAdminService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<TeamsEnvelope> listTeams() {
		List<TeamView> views = ScopeContext.callIn(scope(), service::list);
		return ResponseEntity.ok(new TeamsEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(views.stream().map(TeamAdminController::summary).toList()));
	}

	@Override
	public ResponseEntity<TeamEnvelope> createTeam(CreateTeamRequest request) {
		TeamView created = ScopeContext.callIn(scope(), () -> translating(() ->
				service.create(request.getSiteExternalId(), request.getName(),
						request.getDescription(), request.getHandlingMethod().getValue(),
						request.getShiftTemplateExternalId(), request.getBreakTemplateExternalId(),
						request.getMemberUserExternalIds())));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(created));
	}

	@Override
	public ResponseEntity<TeamEnvelope> updateTeam(String teamExternalId, UpdateTeamRequest request) {
		TeamView updated = ScopeContext.callIn(scope(), () -> translating(() ->
				service.update(teamExternalId, new TeamChange(
						request.getName(), request.getDescription(),
						request.getHandlingMethod() == null ? null : request.getHandlingMethod().getValue(),
						request.getShiftTemplateExternalId(), request.getBreakTemplateExternalId(),
						request.getMemberUserExternalIds(), request.getRetired()))));
		return ResponseEntity.ok(envelope(updated));
	}

	private static TeamView translating(Supplier<TeamView> work) {
		try {
			return work.get();
		}
		catch (TeamUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, "No such team at this installation.");
		}
		catch (TeamNameInUseException inUse) {
			throw new ApiException(PlatformErrorCode.CONFLICT,
					"An active team at that site already carries that name.");
		}
		catch (SiteUnknownException unknown) {
			throw new ApiException(CoreErrorCode.SITE_UNKNOWN,
					"No active site '" + unknown.siteExternalId() + "' at this installation.");
		}
		catch (ShiftTemplateUnknownException unknown) {
			throw new ApiException(CoreErrorCode.SHIFT_TEMPLATE_UNKNOWN,
					"No active shift template by that id.");
		}
		catch (BreakTemplateUnknownException unknown) {
			throw new ApiException(CoreErrorCode.BREAK_TEMPLATE_UNKNOWN,
					"No active break template by that id.");
		}
		catch (MemberUnknownException unknown) {
			throw new ApiException(CoreErrorCode.USER_UNKNOWN,
					"No active user '" + unknown.userExternalId() + "'.");
		}
	}

	private Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static TeamEnvelope envelope(TeamView view) {
		return new TeamEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(view));
	}

	private static TeamSummary summary(TeamView view) {
		var team = view.team();
		return new TeamSummary()
				.externalId(team.externalId())
				.siteExternalId(team.siteExternalId())
				.name(team.name())
				.description(team.description())
				.handlingMethod(HandlingMethod.fromValue(team.handlingMethod()))
				.shiftTemplateExternalId(view.shiftTemplateExternalId())
				.breakTemplateExternalId(view.breakTemplateExternalId())
				.memberUserExternalIds(view.memberUserExternalIds())
				.retired(team.retiredAt() != null)
				.createdAt(ApiTime.offset(team.createdAt()));
	}
}
