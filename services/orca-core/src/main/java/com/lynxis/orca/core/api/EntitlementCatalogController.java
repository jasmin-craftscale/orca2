package com.lynxis.orca.core.api;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.EntitlementsApi;
import com.lynxis.orca.core.api.generated.model.EntitlementActionItem;
import com.lynxis.orca.core.api.generated.model.EntitlementApplication;
import com.lynxis.orca.core.api.generated.model.EntitlementModule;
import com.lynxis.orca.core.api.generated.model.EntitlementSubModule;
import com.lynxis.orca.core.api.generated.model.EntitlementTree;
import com.lynxis.orca.core.api.generated.model.EntitlementTreeEnvelope;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.persistence.EntitlementCatalogRepository;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;

/**
 * §C1's catalog route: the four-level tree, assembled from the seeded tables.
 *
 * <p>Unfiltered by licence — deliberately: licence verification is a later
 * phase's feature (register U4/fleet), and filtering by a licence that cannot
 * yet be read would be inventing the licence model here. Recorded in the
 * phase report.
 */
@RestController
public class EntitlementCatalogController implements EntitlementsApi {

	private final EntitlementCatalogRepository catalog;
	private final String siteExternalId;

	public EntitlementCatalogController(EntitlementCatalogRepository catalog, String siteExternalId) {
		this.catalog = catalog;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<EntitlementTreeEnvelope> entitlementCatalog() {
		EntitlementTree tree = ScopeContext.callIn(
				CoreScopes.installation(siteExternalId), this::assemble);
		return ResponseEntity.ok(new EntitlementTreeEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(tree));
	}

	private EntitlementTree assemble() {
		Map<Long, List<IdentityTables.EntitlementActionItem>> itemsBySubModule =
				catalog.actionItems().stream()
						.collect(Collectors.groupingBy(IdentityTables.EntitlementActionItem::subModuleId));
		Map<Long, List<IdentityTables.EntitlementSubModule>> subModulesByModule =
				catalog.subModules().stream()
						.collect(Collectors.groupingBy(IdentityTables.EntitlementSubModule::moduleId));
		Map<Long, List<IdentityTables.EntitlementModule>> modulesByApplication =
				catalog.modules().stream()
						.collect(Collectors.groupingBy(IdentityTables.EntitlementModule::applicationId));

		return new EntitlementTree().applications(catalog.applications().stream()
				.map(application -> new EntitlementApplication()
						.externalId(application.externalId())
						.code(application.code())
						.name(application.name())
						.modules(modulesByApplication
								.getOrDefault(application.applicationId(), List.of()).stream()
								.map(module -> new EntitlementModule()
										.externalId(module.externalId())
										.code(module.code())
										.name(module.name())
										.subModules(subModulesByModule
												.getOrDefault(module.moduleId(), List.of()).stream()
												.map(subModule -> new EntitlementSubModule()
														.externalId(subModule.externalId())
														.code(subModule.code())
														.name(subModule.name())
														.actionItems(itemsBySubModule
																.getOrDefault(subModule.subModuleId(), List.of())
																.stream()
																.map(item -> new EntitlementActionItem()
																		.externalId(item.externalId())
																		.code(item.code())
																		.name(item.name())
																		.licenceRoute(item.licenceRoute()))
																.toList()))
												.toList()))
										.toList()))
				.toList());
	}
}
