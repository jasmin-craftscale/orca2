package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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

import com.lynxis.orca.core.api.DeviceAdminController;
import com.lynxis.orca.core.api.ResourceConfigurationController;
import com.lynxis.orca.core.api.generated.model.DeviceSummary;
import com.lynxis.orca.core.api.generated.model.IoAssignmentItem;
import com.lynxis.orca.core.api.generated.model.PerspectiveItem;
import com.lynxis.orca.core.api.generated.model.PortType;
import com.lynxis.orca.core.api.generated.model.RegisterDeviceRequest;
import com.lynxis.orca.core.api.generated.model.ResourceConfigurationEntry;
import com.lynxis.orca.core.api.generated.model.ResourceScopeType;
import com.lynxis.orca.core.api.generated.model.UpdateDeviceRequest;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.DeviceAdminService;
import com.lynxis.orca.core.domain.ResourceConfigurationService;
import com.lynxis.orca.core.persistence.DeviceCatalogRepository;
import com.lynxis.orca.core.persistence.DeviceRepository;
import com.lynxis.orca.core.persistence.ResourceConfigurationRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.web.ApiException;

/**
 * <strong>WP3 · the device registry.</strong>
 *
 * <p>The plan's done-when: a device with a full port layout round-trips through
 * the API, the catalogs seed stably (proven beside the entitlement seed in
 * {@link CatalogSeedPropertiesIT}), and {@code topology_device} still serves
 * what runtime and edge already read.
 */
class DeviceRegistryPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String SITE = "SITE-IT";

	private static DataSource owner;

	private JdbcTemplate core;
	private DeviceAdminController deviceApi;
	private ResourceConfigurationController resourceApi;

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
		core.execute("DELETE FROM resource_configuration");
		core.execute("DELETE FROM ptz_preset");
		core.execute("DELETE FROM device_io_assignment");
		core.execute("DELETE FROM device");
		core.execute("DELETE FROM lane");
		core.execute("DELETE FROM area");
		core.execute("DELETE FROM site");
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES (?, 'IT', 'IT Terminal', 1)", SITE);
		core.update("INSERT INTO area (external_id, site_id, code, name) "
				+ "SELECT 'AREA-IT', site_id, 'GATE', 'Main Gate' FROM site WHERE external_id = ?", SITE);
		core.update("INSERT INTO lane (external_id, area_id, code, name) "
				+ "SELECT 'LANE-IT-01', area_id, 'L01', 'Lane 1' FROM area WHERE external_id = 'AREA-IT'");

		ScopeSeam seam = new JdbcScopeSeam(core);
		DeviceRepository devices = new DeviceRepository(seam);
		DeviceCatalogRepository catalogs = new DeviceCatalogRepository(seam);
		deviceApi = new DeviceAdminController(new DeviceAdminService(devices, catalogs), SITE);
		resourceApi = new ResourceConfigurationController(new ResourceConfigurationService(
				new ResourceConfigurationRepository(seam), new SiteDirectoryRepository(seam), devices), SITE);
	}

	// ------------------------------------------------------------------------
	// The done-when: a device with a full port layout round-trips.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a device with the full translated column set, a port layout and PTZ presets round-trips through the API")
	void aFullDeviceRoundTrips() {
		DeviceSummary registered = inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest()
						.name("Lane 1 plate camera")
						.deviceTypeCode("LPR_CAMERA")
						.mode(com.lynxis.orca.core.api.generated.model.DeviceMode.DATA_CAPTURE)
						.alias("cam-1")
						.model("Q1700-LE")
						.firmwareVersion("11.9.60")
						.description("Entry plate camera")
						.resolution("1920x1080")
						.ipAddress("10.0.0.42")
						.streamType("rtsp")
						.port(554)
						.protocol("https")
						.manufacturer("AXIS")
						.deviceUrl("https://10.0.0.42/stream")
						.devicePortalUrl("https://10.0.0.42/portal")
						.assemblyName("Lynxis.Devices.Axis")
						.className("Lynxis.Devices.Axis.LprCamera")
						.dataCaptureMode("continuous")
						.waitTime(1500)).getBody().getData());
		assertThat(registered.getExternalId()).startsWith("dev-");

		inScope(() -> deviceApi.replaceIoAssignments(registered.getExternalId(), List.of(
				new IoAssignmentItem().portType(PortType.INPUT).ioPort(1).portNameCode("DI1")
						.reverseState(true).produceFalseMessage(false).waitTime(200),
				new IoAssignmentItem().portType(PortType.OUTPUT).ioPort(3).portNameCode("DO3"),
				new IoAssignmentItem().portType(PortType.AUDIO).ioPort(1).portNameCode("FRONT_MIC")
						.gosAudio(true).portAliasName("Driver mic"))));

		inScope(() -> deviceApi.replacePerspectives(registered.getExternalId(), List.of(
				new PerspectiveItem().name("Cab view").pan(new BigDecimal("12.500"))
						.tilt(new BigDecimal("-3.250")).zoom(new BigDecimal("2.000")),
				new PerspectiveItem().name("Plate view").zoom(new BigDecimal("8.000")))));

		List<DeviceSummary> inventory = inScope(() ->
				deviceApi.listLaneDevices("LANE-IT-01").getBody().getData());
		assertThat(inventory).hasSize(1);
		DeviceSummary device = inventory.getFirst();

		assertThat(device.getDeviceTypeCode()).isEqualTo("LPR_CAMERA");
		assertThat(device.getAssemblyName())
				.as("the .NET plugin-load parameters are load-bearing for the frozen config poll")
				.isEqualTo("Lynxis.Devices.Axis");
		assertThat(device.getClassName()).isEqualTo("Lynxis.Devices.Axis.LprCamera");
		assertThat(device.getProtocol()).isEqualTo("https");
		assertThat(device.getIoAssignments()).hasSize(3);
		IoAssignmentItem input = device.getIoAssignments().stream()
				.filter(item -> item.getPortType() == PortType.INPUT).findFirst().orElseThrow();
		assertThat(input.getPortNameCode()).isEqualTo("DI1");
		assertThat(input.getReverseState()).isTrue();
		assertThat(input.getProduceFalseMessage())
				.as("edge-suppression semantics survive the round trip")
				.isFalse();
		assertThat(device.getPerspectives())
				.extracting(PerspectiveItem::getName)
				.containsExactlyInAnyOrder("Cab view", "Plate view");
		assertThat(device.getPerspectives().stream()
				.filter(preset -> preset.getName().equals("Cab view")).findFirst().orElseThrow().getPan())
				.as("pan/tilt/zoom are numbers now, not varchar(255)")
				.isEqualByComparingTo("12.5");
	}

	@Test
	@DisplayName("the published view still serves what runtime and edge read, with the catalog's code as device_type")
	void theTopologyViewStillServes() {
		inScope(() -> deviceApi.registerDevice("LANE-IT-01", new RegisterDeviceRequest()
				.name("Barrier").deviceTypeCode("GATE_ARM")));

		var row = core.queryForMap("SELECT device_external_id, device_type, device_name, lane_external_id, "
				+ "site_external_id FROM topology_device WHERE lane_external_id = 'LANE-IT-01'");
		assertThat(row.get("device_type"))
				.as("the view's contract column now carries the catalog code — same column, settled vocabulary")
				.isEqualTo("GATE_ARM");
		assertThat(row.get("site_external_id")).isEqualTo(SITE);
	}

	// ------------------------------------------------------------------------
	// What the schema refuses.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a device cannot claim a type the catalog does not carry — in the API and in the database")
	void theTypeVocabularyIsTheCatalog() {
		assertThatThrownBy(() -> inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest().name("Mystery").deviceTypeCode("FLUX_CAPACITOR"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("DEVICE_TYPE_UNKNOWN"));

		assertThatThrownBy(() -> core.update(
				"INSERT INTO device (external_id, lane_id, site_external_id, device_type_id, name) "
						+ "SELECT 'dev-raw', lane_id, ?, 999999, 'Raw' FROM lane WHERE external_id = 'LANE-IT-01'",
				SITE))
				.as("the FK is the enforcement; the API refusal is just the friendlier answer")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("the port layout's natural key holds, and the unified port-type casing is a database fact")
	void portLayoutConstraints() {
		DeviceSummary device = inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest().name("IO box").deviceTypeCode("EDGE_DEVICE_DISPLAY"))
				.getBody().getData());
		Long deviceId = core.queryForObject(
				"SELECT device_id FROM device WHERE external_id = ?", Long.class, device.getExternalId());

		core.update("INSERT INTO device_io_assignment (device_id, site_external_id, port_type, io_port) "
				+ "VALUES (?, ?, 'INPUT', 1)", deviceId, SITE);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO device_io_assignment (device_id, site_external_id, port_type, io_port) "
						+ "VALUES (?, ?, 'INPUT', 1)", deviceId, SITE))
				.as("one assignment per (device, type, number)")
				.isInstanceOf(DuplicateKeyException.class);

		assertThatThrownBy(() -> core.update(
				"INSERT INTO device_io_assignment (device_id, site_external_id, port_type, io_port) "
						+ "VALUES (?, ?, 'Input', 2)", deviceId, SITE))
				.as("TitleCase was 1.x's other casing; the binary-collated CHECK refuses it")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> inScope(() -> deviceApi.replaceIoAssignments(device.getExternalId(),
				List.of(new IoAssignmentItem().portType(PortType.INPUT).ioPort(9)
						.portNameCode("REAR_MIC_OF_MYSTERY")))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("PORT_NAME_UNKNOWN"));
	}

	@Test
	@DisplayName("PTZ preset names are unique per device, in the database")
	void presetNamesAreUnique() {
		DeviceSummary device = inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest().name("PTZ").deviceTypeCode("AXIS_PTZ_CAMERA"))
				.getBody().getData());
		Long deviceId = core.queryForObject(
				"SELECT device_id FROM device WHERE external_id = ?", Long.class, device.getExternalId());

		core.update("INSERT INTO ptz_preset (device_id, site_external_id, name, pan) VALUES (?, ?, 'Gate', 1.0)",
				deviceId, SITE);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO ptz_preset (device_id, site_external_id, name, pan) VALUES (?, ?, 'Gate', 2.0)",
				deviceId, SITE))
				.as("(device, perspective-name) was app-level-unique only in 1.x")
				.isInstanceOf(DuplicateKeyException.class);
	}

	// ------------------------------------------------------------------------
	// Resource configuration.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("custom variables round-trip at every scope, replacement is declarative, and the per-scope key is unique")
	void resourceConfigurationRoundTrips() {
		DeviceSummary device = inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest().name("Cam").deviceTypeCode("LPR_CAMERA"))
				.getBody().getData());

		inScope(() -> resourceApi.replaceResourceConfiguration(ResourceScopeType.SITE, SITE, List.of(
				new ResourceConfigurationEntry().key("GREETING").value("Welcome"),
				new ResourceConfigurationEntry().key("LANGUAGE").value("en"))));
		inScope(() -> resourceApi.replaceResourceConfiguration(ResourceScopeType.LANE, "LANE-IT-01", List.of(
				new ResourceConfigurationEntry().key("QUEUE_LIMIT").value("4"))));
		inScope(() -> resourceApi.replaceResourceConfiguration(ResourceScopeType.DEVICE,
				device.getExternalId(), List.of(
						new ResourceConfigurationEntry().key("OCR_PROFILE").value("night"))));

		var siteVars = inScope(() -> resourceApi.readResourceConfiguration(ResourceScopeType.SITE, SITE)
				.getBody().getData());
		assertThat(siteVars.getEntries()).hasSize(2);

		// Declarative replacement: the new set is the whole set.
		inScope(() -> resourceApi.replaceResourceConfiguration(ResourceScopeType.SITE, SITE, List.of(
				new ResourceConfigurationEntry().key("GREETING").value("Hello"))));
		var replaced = inScope(() -> resourceApi.readResourceConfiguration(ResourceScopeType.SITE, SITE)
				.getBody().getData());
		assertThat(replaced.getEntries()).hasSize(1);
		assertThat(replaced.getEntries().getFirst().getValue()).isEqualTo("Hello");

		// The natural key is the database's, not the app's.
		assertThatThrownBy(() -> core.update(
				"INSERT INTO resource_configuration (site_external_id, scope_type, resource_external_id, config_key) "
						+ "VALUES (?, 'SITE', ?, 'GREETING')", SITE, SITE))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@DisplayName("variables for a resource that does not exist are 404 — path-identified, like every sibling route")
	void unknownResourcesAreRefused() {
		assertThatThrownBy(() -> inScope(() -> resourceApi.replaceResourceConfiguration(
				ResourceScopeType.LANE, "LANE-NOWHERE",
				List.of(new ResourceConfigurationEntry().key("X").value("1")))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));
	}

	@Test
	@DisplayName("a layout repeating a port, presets repeating a name, and variables repeating a key are refused as validation")
	void duplicateSetEntriesAreRefusedUpFront() {
		DeviceSummary device = inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest().name("Dupes").deviceTypeCode("EDGE_DEVICE_DISPLAY"))
				.getBody().getData());

		assertThatThrownBy(() -> inScope(() -> deviceApi.replaceIoAssignments(device.getExternalId(),
				List.of(new IoAssignmentItem().portType(PortType.INPUT).ioPort(1),
						new IoAssignmentItem().portType(PortType.INPUT).ioPort(1).portAliasName("again")))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));

		assertThatThrownBy(() -> inScope(() -> deviceApi.replacePerspectives(device.getExternalId(),
				List.of(new PerspectiveItem().name("Gate"),
						new PerspectiveItem().name("Gate").zoom(new BigDecimal("2"))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));

		assertThatThrownBy(() -> inScope(() -> resourceApi.replaceResourceConfiguration(
				ResourceScopeType.SITE, SITE,
				List.of(new ResourceConfigurationEntry().key("K").value("1"),
						new ResourceConfigurationEntry().key("K").value("2")))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));

		assertThat(core.queryForObject(
				"SELECT COUNT(*) FROM device_io_assignment WHERE retired_at IS NULL", Long.class))
				.as("nothing half-applied — the refusal came before any row")
				.isZero();
	}

	@Test
	@DisplayName("an unknown lane on the inventory route, and an unknown device on the mutation routes, are 404")
	void unknownLaneAndDeviceAre404() {
		assertThatThrownBy(() -> inScope(() -> deviceApi.listLaneDevices("LANE-NOWHERE")))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));
		assertThatThrownBy(() -> inScope(() -> deviceApi.updateDevice("dev-ghost",
				new UpdateDeviceRequest().name("X"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));
		assertThatThrownBy(() -> inScope(() -> deviceApi.retireDevice("dev-ghost")))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));
	}

	@Test
	@DisplayName("retiring a device empties its lane inventory view of it, and the registry keeps the row")
	void retirementIsRetirementNotDeletion() {
		DeviceSummary device = inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest().name("Old cam").deviceTypeCode("LPR_CAMERA"))
				.getBody().getData());

		DeviceSummary retired = inScope(() -> deviceApi.retireDevice(device.getExternalId())
				.getBody().getData());
		assertThat(retired.getRetired()).isTrue();

		assertThat(core.queryForObject("SELECT COUNT(*) FROM device", Long.class))
				.as("§D3 — retired, never removed")
				.isEqualTo(1);
		assertThat(core.queryForObject("SELECT COUNT(*) FROM topology_device", Long.class))
				.as("but no consumer ever sees it")
				.isZero();
	}

	@Test
	@DisplayName("an updated device keeps its identity and changes only what the patch names")
	void patchSemantics() {
		DeviceSummary device = inScope(() -> deviceApi.registerDevice("LANE-IT-01",
				new RegisterDeviceRequest().name("Cam").deviceTypeCode("LPR_CAMERA")
						.ipAddress("10.0.0.1"))
				.getBody().getData());

		DeviceSummary updated = inScope(() -> deviceApi.updateDevice(device.getExternalId(),
				new UpdateDeviceRequest().firmwareVersion("12.0.1")).getBody().getData());
		assertThat(updated.getFirmwareVersion()).isEqualTo("12.0.1");
		assertThat(updated.getIpAddress()).isEqualTo("10.0.0.1");
		assertThat(updated.getExternalId()).isEqualTo(device.getExternalId());
	}

	// ------------------------------------------------------------------------

	private <T> T inScope(Callable<T> work) {
		return ScopeContext.callIn(CoreScopes.installation(SITE), work);
	}
}
