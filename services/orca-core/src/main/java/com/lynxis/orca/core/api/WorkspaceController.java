package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.WorkspaceApi;
import com.lynxis.orca.core.api.generated.model.ColumnPreference;
import com.lynxis.orca.core.api.generated.model.CreateFilterRequest;
import com.lynxis.orca.core.api.generated.model.GridPreferences;
import com.lynxis.orca.core.api.generated.model.SavedFilterEnvelope;
import com.lynxis.orca.core.api.generated.model.SavedFilterSummary;
import com.lynxis.orca.core.api.generated.model.SavedFiltersEnvelope;
import com.lynxis.orca.core.api.generated.model.WorkspaceGrid;
import com.lynxis.orca.core.api.generated.model.WorkspaceGridsEnvelope;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.DuplicateRequestEntryException;
import com.lynxis.orca.core.domain.WorkspaceService;
import com.lynxis.orca.core.domain.WorkspaceService.FilterNameInUseException;
import com.lynxis.orca.core.domain.WorkspaceService.FilterUnknownException;
import com.lynxis.orca.core.domain.WorkspaceService.FilterView;
import com.lynxis.orca.core.domain.WorkspaceService.GridReplacement;
import com.lynxis.orca.core.domain.WorkspaceService.GridUnknownException;
import com.lynxis.orca.core.domain.WorkspaceService.GridView;
import com.lynxis.orca.core.domain.WorkspaceService.UserNotLinkedException;
import com.lynxis.orca.core.domain.WorkspaceTables.UserGridColumnPreference;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiError;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** §C1's `/me/workspace/*` — the calling user's grids and filters. */
@RestController
public class WorkspaceController implements WorkspaceApi {

	private final WorkspaceService service;
	private final String siteExternalId;

	public WorkspaceController(WorkspaceService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<WorkspaceGridsEnvelope> myGrids() {
		List<GridView> grids = ScopeContext.callIn(scope(), () -> translating(service::grids));
		return ResponseEntity.ok(gridsEnvelope(grids));
	}

	@Override
	public ResponseEntity<WorkspaceGridsEnvelope> replaceMyGridPreferences(
			List<GridPreferences> request) {
		// One service call, one transaction — the whole PUT applies or none of
		// it does (review finding: the per-grid loop was N transactions).
		List<GridView> grids = ScopeContext.callIn(scope(), () -> translating(() ->
				service.replacePreferences(request.stream()
						.map(preferences -> new GridReplacement(preferences.getGridCode(),
								preferences.getColumns().stream()
										.map(column -> new UserGridColumnPreference(0, 0, 0, null,
												column.getColumnCode(), column.getDisplayOrder(),
												column.getWidthPx(),
												column.getVisible() == null || column.getVisible(),
												null, null))
										.toList()))
						.toList())));
		return ResponseEntity.ok(gridsEnvelope(grids));
	}

	@Override
	public ResponseEntity<SavedFiltersEnvelope> myFilters() {
		List<FilterView> filters = ScopeContext.callIn(scope(), () -> translating(service::filters));
		return ResponseEntity.ok(filtersEnvelope(filters));
	}

	@Override
	public ResponseEntity<SavedFilterEnvelope> createMyFilter(CreateFilterRequest request) {
		FilterView created = ScopeContext.callIn(scope(), () -> translating(() ->
				service.createFilter(request.getGridCode(), request.getName(),
						request.getFilterJson(), Boolean.TRUE.equals(request.getIsDefault()))));
		return ResponseEntity.status(HttpStatus.CREATED).body(new SavedFilterEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(created)));
	}

	@Override
	public ResponseEntity<SavedFiltersEnvelope> deleteMyFilter(String filterExternalId) {
		List<FilterView> remaining = ScopeContext.callIn(scope(), () -> translating(() -> {
			service.deleteFilter(filterExternalId);
			return service.filters();
		}));
		return ResponseEntity.ok(filtersEnvelope(remaining));
	}

	// ------------------------------------------------------------------------

	private static <T> T translating(Supplier<T> work) {
		try {
			return work.get();
		}
		catch (UserNotLinkedException notLinked) {
			throw new ApiException(CoreErrorCode.USER_NOT_LINKED,
					"This token maps to no active platform user.");
		}
		catch (GridUnknownException unknown) {
			throw new ApiException(CoreErrorCode.GRID_UNKNOWN,
					"No grid '" + unknown.gridCode() + "' in the catalog.");
		}
		catch (FilterNameInUseException inUse) {
			throw new ApiException(PlatformErrorCode.CONFLICT,
					"A filter by that name already exists on that grid.");
		}
		catch (FilterUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND,
					"No such filter owned by the caller.");
		}
		catch (DuplicateRequestEntryException repeated) {
			throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
					"The request repeats an entry.",
					List.of(ApiError.field(PlatformErrorCode.VALIDATION_FAILED,
							repeated.getField(), "duplicated: " + repeated.getDuplicate())));
		}
	}

	private Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static WorkspaceGridsEnvelope gridsEnvelope(List<GridView> grids) {
		return new WorkspaceGridsEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(grids.stream()
						.map(view -> new WorkspaceGrid()
								.gridCode(view.grid().code())
								.title(view.grid().title())
								.defaultColumns(view.grid().defaultColumns())
								.columns(view.columns().stream()
										.map(column -> new ColumnPreference()
												.columnCode(column.columnCode())
												.displayOrder(column.displayOrder())
												.widthPx(column.widthPx())
												.visible(column.isVisible()))
										.toList()))
						.toList());
	}

	private static SavedFiltersEnvelope filtersEnvelope(List<FilterView> filters) {
		return new SavedFiltersEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(filters.stream().map(WorkspaceController::summary).toList());
	}

	private static SavedFilterSummary summary(FilterView view) {
		return new SavedFilterSummary()
				.externalId(view.filter().externalId())
				.gridCode(view.gridCode())
				.name(view.filter().name())
				.filterJson(view.filter().filterJson())
				.filterSchemaVersion(view.filter().filterSchemaVersion())
				.isDefault(view.filter().isDefault())
				.createdAt(ApiTime.offset(view.filter().createdAt()));
	}
}
