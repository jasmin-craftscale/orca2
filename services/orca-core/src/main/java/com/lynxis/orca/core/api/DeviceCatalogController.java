package com.lynxis.orca.core.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.DeviceCatalogsApi;
import com.lynxis.orca.core.api.generated.model.DeviceTypeSummary;
import com.lynxis.orca.core.api.generated.model.DeviceTypesEnvelope;
import com.lynxis.orca.core.api.generated.model.IoDeviceKindSummary;
import com.lynxis.orca.core.api.generated.model.IoDeviceKindsEnvelope;
import com.lynxis.orca.core.api.generated.model.IoPortNameSummary;
import com.lynxis.orca.core.api.generated.model.IoPortNamesEnvelope;
import com.lynxis.orca.core.api.generated.model.PortType;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.persistence.DeviceCatalogRepository;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;

/** The three seeded device catalogs, read-only — writes are migrations' (rule 7). */
@RestController
public class DeviceCatalogController implements DeviceCatalogsApi {

	private final DeviceCatalogRepository catalogs;
	private final String siteExternalId;

	public DeviceCatalogController(DeviceCatalogRepository catalogs, String siteExternalId) {
		this.catalogs = catalogs;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<DeviceTypesEnvelope> listDeviceTypes() {
		var types = ScopeContext.callIn(scope(), catalogs::deviceTypes);
		return ResponseEntity.ok(new DeviceTypesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(types.stream()
						.map(type -> new DeviceTypeSummary()
								.externalId(type.externalId())
								.code(type.code())
								.name(type.name())
								.formSchema(type.formSchema()))
						.toList()));
	}

	@Override
	public ResponseEntity<IoPortNamesEnvelope> listIoPortNames() {
		var names = ScopeContext.callIn(scope(), catalogs::ioPortNames);
		return ResponseEntity.ok(new IoPortNamesEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(names.stream()
						.map(name -> new IoPortNameSummary()
								.externalId(name.externalId())
								.portType(PortType.fromValue(name.portType()))
								.code(name.code())
								.name(name.name()))
						.toList()));
	}

	@Override
	public ResponseEntity<IoDeviceKindsEnvelope> listIoDeviceKinds() {
		var kinds = ScopeContext.callIn(scope(), catalogs::ioDeviceKinds);
		return ResponseEntity.ok(new IoDeviceKindsEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(kinds.stream()
						.map(kind -> new IoDeviceKindSummary()
								.externalId(kind.externalId())
								.portType(PortType.fromValue(kind.portType()))
								.code(kind.code())
								.name(kind.name()))
						.toList()));
	}

	private com.lynxis.orca.platform.scope.Scope scope() {
		return CoreScopes.installation(siteExternalId);
	}
}
