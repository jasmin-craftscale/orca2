package com.lynxis.orca.core.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.TeamRoutingApi;
import com.lynxis.orca.core.api.generated.model.ReplaceRoutingRulesRequest;
import com.lynxis.orca.core.api.generated.model.RoutingRule;
import com.lynxis.orca.core.api.generated.model.RoutingRulesEnvelope;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.RoutingAdminService;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** The team routing rules' admin surface — a declarative set per team. */
@RestController
public class TeamRoutingController implements TeamRoutingApi {

	private final RoutingAdminService routing;
	private final String siteExternalId;

	public TeamRoutingController(RoutingAdminService routing, String siteExternalId) {
		this.routing = routing;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<RoutingRulesEnvelope> listTeamRoutingRules(String teamExternalId) {
		return ResponseEntity.ok(envelope(inScope(() -> routing.rulesOfTeam(teamExternalId))));
	}

	@Override
	public ResponseEntity<RoutingRulesEnvelope> replaceTeamRoutingRules(String teamExternalId,
			ReplaceRoutingRulesRequest request) {
		List<RoutingAdminService.Rule> submitted = request.getRules().stream()
				.map(rule -> new RoutingAdminService.Rule(rule.getScreenExternalId(),
						rule.getLaneExternalId(), rule.getPriority()))
				.toList();
		return ResponseEntity.ok(envelope(
				inScope(() -> routing.replaceRules(teamExternalId, submitted))));
	}

	private <T> T inScope(java.util.function.Supplier<T> action) {
		try {
			return ScopeContext.callIn(CoreScopes.installation(siteExternalId), action::get);
		}
		catch (RoutingAdminService.TeamUnknownException notFound) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, notFound.getMessage());
		}
		catch (RoutingAdminService.ScreenUnknownException unknown) {
			throw new ApiException(CoreErrorCode.SCREEN_UNKNOWN, unknown.getMessage());
		}
		catch (RoutingAdminService.LaneUnknownException unknown) {
			throw new ApiException(CoreErrorCode.LANE_UNKNOWN, unknown.getMessage());
		}
		catch (RoutingAdminService.RoutingRuleDuplicateException duplicate) {
			throw new ApiException(CoreErrorCode.ROUTING_RULE_DUPLICATE, duplicate.getMessage());
		}
		catch (RoutingAdminService.ConcurrentRuleChangeException raced) {
			throw new ApiException(com.lynxis.orca.platform.web.PlatformErrorCode.CONFLICT,
					raced.getMessage());
		}
	}

	private static RoutingRulesEnvelope envelope(List<RoutingAdminService.Rule> rules) {
		return new RoutingRulesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(rules.stream().map(rule -> new RoutingRule()
						.screenExternalId(rule.screenExternalId())
						.laneExternalId(rule.laneExternalId())
						.priority(rule.priority())).toList());
	}
}
