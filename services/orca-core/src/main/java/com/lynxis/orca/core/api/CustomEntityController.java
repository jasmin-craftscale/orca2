package com.lynxis.orca.core.api;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.CustomEntitiesApi;
import com.lynxis.orca.core.api.generated.model.AddCustomEntityFieldRequest;
import com.lynxis.orca.core.api.generated.model.CustomEntitiesEnvelope;
import com.lynxis.orca.core.api.generated.model.CustomEntityEnvelope;
import com.lynxis.orca.core.api.generated.model.CustomEntityFieldSummary;
import com.lynxis.orca.core.api.generated.model.CustomEntityKind;
import com.lynxis.orca.core.api.generated.model.CustomEntitySummary;
import com.lynxis.orca.core.api.generated.model.DeclareCustomEntityFieldRequest;
import com.lynxis.orca.core.api.generated.model.DeclareCustomEntityRequest;
import com.lynxis.orca.core.api.generated.model.EvolveCustomEntityDeclarationRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.CustomEntityService;
import com.lynxis.orca.core.domain.CustomEntityService.CustomEntityConflictException;
import com.lynxis.orca.core.domain.CustomEntityService.CustomEntitySiteUnknownException;
import com.lynxis.orca.core.domain.CustomEntityService.CustomEntityUnknownException;
import com.lynxis.orca.core.domain.CustomEntityService.CustomEntityValidationException;
import com.lynxis.orca.core.domain.CustomEntityService.CustomEntityView;
import com.lynxis.orca.core.domain.CustomEntityService.EntityKind;
import com.lynxis.orca.core.domain.CustomEntityService.FieldDeclaration;
import com.lynxis.orca.core.domain.CustomEntityService.FieldType;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.PlatformErrorCode;
import com.lynxis.orca.platform.web.RequestId;

/** The metadata-only custom-entity declaration surface. */
@RestController
public class CustomEntityController implements CustomEntitiesApi {

	private final CustomEntityService service;
	private final String siteExternalId;

	public CustomEntityController(CustomEntityService service, String siteExternalId) {
		this.service = service;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<CustomEntitiesEnvelope> listCustomEntities() {
		List<CustomEntityView> declarations = ScopeContext.callIn(scope(), service::list);
		return ResponseEntity.ok(new CustomEntitiesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(declarations.stream().map(CustomEntityController::summary).toList()));
	}

	@Override
	public ResponseEntity<CustomEntityEnvelope> declareCustomEntity(DeclareCustomEntityRequest request) {
		CustomEntityView declared = ScopeContext.callIn(scope(), () -> translating(() ->
				service.declare(siteExternalId, request.getName(),
						EntityKind.valueOf(request.getKind().getValue()),
						request.getFields().stream().map(CustomEntityController::field).toList())));
		return ResponseEntity.status(HttpStatus.CREATED).body(envelope(declared));
	}

	@Override
	public ResponseEntity<CustomEntityEnvelope> evolveCustomEntityDeclaration(
			String customEntityExternalId, EvolveCustomEntityDeclarationRequest request) {
		List<FieldDeclaration> additions = request.getAddFields() == null ? List.of()
				: request.getAddFields().stream().map(CustomEntityController::field).toList();
		CustomEntityView evolved = ScopeContext.callIn(scope(), () -> translating(() ->
				service.evolve(customEntityExternalId, request.getName(), additions)));
		return ResponseEntity.ok(envelope(evolved));
	}

	private static FieldDeclaration field(DeclareCustomEntityFieldRequest field) {
		return new FieldDeclaration(field.getIdentifier(), field.getDisplayName(),
				FieldType.valueOf(field.getType().getValue()), field.getMaxLength(),
				field.getPrecision(), field.getScale(), Boolean.TRUE.equals(field.getNullable()),
				Boolean.TRUE.equals(field.getBusinessKey()), field.getOrdinal());
	}

	private static FieldDeclaration field(AddCustomEntityFieldRequest field) {
		return new FieldDeclaration(field.getIdentifier(), field.getDisplayName(),
				FieldType.valueOf(field.getType().getValue()), field.getMaxLength(),
				field.getPrecision(), field.getScale(), Boolean.TRUE.equals(field.getNullable()),
				false, field.getOrdinal());
	}

	private static <T> T translating(Supplier<T> work) {
		try {
			return work.get();
		}
		catch (CustomEntityUnknownException unknown) {
			throw new ApiException(PlatformErrorCode.NOT_FOUND, "No such custom-entity declaration.");
		}
		catch (CustomEntitySiteUnknownException unknown) {
			throw new ApiException(CoreErrorCode.SITE_UNKNOWN,
					"This installation's active site is not configured in core.");
		}
		catch (CustomEntityConflictException conflict) {
			throw new ApiException(PlatformErrorCode.CONFLICT, conflict.getMessage());
		}
		catch (CustomEntityValidationException invalid) {
			throw new ApiException(CoreErrorCode.CUSTOM_ENTITY_DECLARATION_INVALID,
					invalid.getMessage());
		}
	}

	private Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}

	private static CustomEntityEnvelope envelope(CustomEntityView declaration) {
		return new CustomEntityEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(summary(declaration));
	}

	private static CustomEntitySummary summary(CustomEntityView declaration) {
		var entity = declaration.entity();
		return new CustomEntitySummary()
				.externalId(entity.externalId())
				.siteExternalId(entity.siteExternalId())
				.name(entity.name())
				.kind(CustomEntityKind.fromValue(entity.entityKind()))
				.tableIdentifier(entity.tableIdentifier())
				.rowIdColumn(CustomEntityService.ROW_ID_COLUMN)
				.rowExternalIdColumn(CustomEntityService.ROW_EXTERNAL_ID_COLUMN)
				.declarationVersion(entity.declarationVersion())
				.fields(declaration.fields().stream()
						.map(field -> new CustomEntityFieldSummary()
								.externalId(field.externalId())
								.identifier(field.identifier())
								.displayName(field.displayName())
								.type(com.lynxis.orca.core.api.generated.model.CustomEntityFieldType
										.fromValue(field.fieldType()))
								.maxLength(field.maxLength())
								.precision(field.numberPrecision())
								.scale(field.numberScale())
								.nullable(field.nullable())
								.businessKey(field.businessKey())
								.ordinal(field.ordinal()))
						.toList())
				.createdAt(ApiTime.offset(entity.createdAt()))
				.updatedAt(ApiTime.offset(entity.updatedAt()));
	}
}
