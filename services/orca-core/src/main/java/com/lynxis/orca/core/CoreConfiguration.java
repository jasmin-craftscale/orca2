package com.lynxis.orca.core;

import java.util.Optional;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.lynxis.orca.core.api.AuditEventController;
import com.lynxis.orca.core.api.BreakTemplateController;
import com.lynxis.orca.core.api.CustomEntityController;
import com.lynxis.orca.core.api.DeviceAdminController;
import com.lynxis.orca.core.api.DeviceCatalogController;
import com.lynxis.orca.core.api.EntitlementCatalogController;
import com.lynxis.orca.core.api.ResourceConfigurationController;
import com.lynxis.orca.core.api.RoleAdminController;
import com.lynxis.orca.core.api.SettingsController;
import com.lynxis.orca.core.api.ShiftTemplateController;
import com.lynxis.orca.core.api.SiteBrandingController;
import com.lynxis.orca.core.api.TeamAdminController;
import com.lynxis.orca.core.api.UserAdminController;
import com.lynxis.orca.core.api.WorkspaceController;
import com.lynxis.orca.core.domain.AuditTrail;
import com.lynxis.orca.core.domain.CallerIdentity;
import com.lynxis.orca.core.domain.CustomEntityService;
import com.lynxis.orca.core.domain.DeviceAdminService;
import com.lynxis.orca.core.domain.ResourceConfigurationService;
import com.lynxis.orca.core.domain.RoleAdminService;
import com.lynxis.orca.core.domain.SettingsService;
import com.lynxis.orca.core.domain.SiteBrandingService;
import com.lynxis.orca.core.domain.TeamAdminService;
import com.lynxis.orca.core.domain.TemplateAdminService;
import com.lynxis.orca.core.domain.UserAdminService;
import com.lynxis.orca.core.domain.WorkspaceService;
import com.lynxis.orca.core.persistence.AuditEventRepository;
import com.lynxis.orca.core.persistence.BreakTemplateRepository;
import com.lynxis.orca.core.persistence.CustomEntityRepository;
import com.lynxis.orca.core.persistence.DeviceCatalogRepository;
import com.lynxis.orca.core.persistence.DeviceRepository;
import com.lynxis.orca.core.persistence.EntitlementCatalogRepository;
import com.lynxis.orca.core.persistence.ResourceConfigurationRepository;
import com.lynxis.orca.core.persistence.RoleRepository;
import com.lynxis.orca.core.persistence.SettingRepository;
import com.lynxis.orca.core.persistence.ShiftTemplateRepository;
import com.lynxis.orca.core.persistence.SiteBrandingRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.TeamRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.core.persistence.WorkspaceRepository;
import com.lynxis.orca.platform.scope.ScopeSeam;

/**
 * Wires core's configuration-world beans.
 *
 * <p>Controllers that need configuration values are declared here by name so
 * the {@code @Bean} definition supplies what their constructors take — the
 * same shape edge uses, and the reason is the same: a plain constructor
 * parameter cannot be component-scanned.
 *
 * <p>The installation's site arrives once, as {@link InstallationProperties},
 * bound and validated at startup ({@link InstallationSiteValidator}) — from
 * configuration, never from the request (phase-1 decision 7).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InstallationProperties.class)
public class CoreConfiguration {

	/** Startup guard: the demo-fixture site is refused outside the {@code local} profile. */
	@Bean
	public InstallationSiteValidator installationSiteValidator(InstallationProperties properties,
			Environment environment) {
		return new InstallationSiteValidator(properties, environment);
	}

	/**
	 * The production caller identity: the JWT's subject when a person is behind
	 * the request, empty otherwise. An interface so the property suites can
	 * hand controllers a caller without a security context.
	 */
	@Bean
	public CallerIdentity callerIdentity() {
		return () -> {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication instanceof JwtAuthenticationToken jwt) {
				return Optional.ofNullable(jwt.getToken().getSubject());
			}
			return Optional.empty();
		};
	}

	// --- identity ------------------------------------------------------------

	@Bean
	public UserAccountRepository userAccountRepository(ScopeSeam seam) {
		return new UserAccountRepository(seam);
	}

	@Bean
	public RoleRepository roleRepository(ScopeSeam seam) {
		return new RoleRepository(seam);
	}

	@Bean
	public EntitlementCatalogRepository entitlementCatalogRepository(ScopeSeam seam) {
		return new EntitlementCatalogRepository(seam);
	}

	@Bean
	public SiteDirectoryRepository siteDirectoryRepository(ScopeSeam seam) {
		return new SiteDirectoryRepository(seam);
	}

	@Bean
	public UserAdminService userAdminService(UserAccountRepository users, RoleRepository roles) {
		return new UserAdminService(users, roles);
	}

	@Bean
	public RoleAdminService roleAdminService(RoleRepository roles, EntitlementCatalogRepository catalog,
			SiteDirectoryRepository sites, UserAccountRepository users) {
		return new RoleAdminService(roles, catalog, sites, users);
	}

	@Bean
	public UserAdminController userAdminController(UserAdminService service,
			InstallationProperties installation) {
		return new UserAdminController(service, installation.siteExternalId());
	}

	@Bean
	public RoleAdminController roleAdminController(RoleAdminService service,
			InstallationProperties installation) {
		return new RoleAdminController(service, installation.siteExternalId());
	}

	@Bean
	public EntitlementCatalogController entitlementCatalogController(EntitlementCatalogRepository catalog,
			InstallationProperties installation) {
		return new EntitlementCatalogController(catalog, installation.siteExternalId());
	}

	// --- custom-entity declarations -----------------------------------------

	@Bean
	public CustomEntityRepository customEntityRepository(ScopeSeam seam) {
		return new CustomEntityRepository(seam);
	}

	@Bean
	public CustomEntityService customEntityService(CustomEntityRepository entities,
			SiteDirectoryRepository sites, AuditTrail audit) {
		return new CustomEntityService(entities, sites, audit);
	}

	@Bean
	public CustomEntityController customEntityController(CustomEntityService service,
			InstallationProperties installation) {
		return new CustomEntityController(service, installation.siteExternalId());
	}

	// --- teams & templates --------------------------------------------------

	@Bean
	public ShiftTemplateRepository shiftTemplateRepository(ScopeSeam seam) {
		return new ShiftTemplateRepository(seam);
	}

	@Bean
	public BreakTemplateRepository breakTemplateRepository(ScopeSeam seam) {
		return new BreakTemplateRepository(seam);
	}

	@Bean
	public TeamRepository teamRepository(ScopeSeam seam) {
		return new TeamRepository(seam);
	}

	@Bean
	public TemplateAdminService templateAdminService(ShiftTemplateRepository shiftTemplates,
			BreakTemplateRepository breakTemplates, TeamRepository teams) {
		return new TemplateAdminService(shiftTemplates, breakTemplates, teams);
	}

	@Bean
	public TeamAdminService teamAdminService(TeamRepository teams, UserAccountRepository users,
			ShiftTemplateRepository shiftTemplates, BreakTemplateRepository breakTemplates,
			SiteDirectoryRepository sites) {
		return new TeamAdminService(teams, users, shiftTemplates, breakTemplates, sites);
	}

	@Bean
	public ShiftTemplateController shiftTemplateController(TemplateAdminService service,
			InstallationProperties installation) {
		return new ShiftTemplateController(service, installation.siteExternalId());
	}

	@Bean
	public BreakTemplateController breakTemplateController(TemplateAdminService service,
			InstallationProperties installation) {
		return new BreakTemplateController(service, installation.siteExternalId());
	}

	@Bean
	public TeamAdminController teamAdminController(TeamAdminService service,
			InstallationProperties installation) {
		return new TeamAdminController(service, installation.siteExternalId());
	}

	// --- screens & routing --------------------------------------------------

	@Bean
	public com.lynxis.orca.core.persistence.ScreenRepository screenRepository(ScopeSeam seam) {
		return new com.lynxis.orca.core.persistence.ScreenRepository(seam);
	}

	@Bean
	public com.lynxis.orca.core.persistence.TeamRoutingRepository teamRoutingRepository(ScopeSeam seam) {
		return new com.lynxis.orca.core.persistence.TeamRoutingRepository(seam);
	}

	@Bean
	public com.lynxis.orca.core.domain.RoutingAdminService routingAdminService(
			com.lynxis.orca.core.persistence.ScreenRepository screens,
			com.lynxis.orca.core.persistence.TeamRoutingRepository routing,
			TeamRepository teams, DeviceRepository devices, AuditTrail audit) {
		return new com.lynxis.orca.core.domain.RoutingAdminService(screens, routing, teams, devices, audit);
	}

	@Bean
	public com.lynxis.orca.core.api.ScreenAdminController screenAdminController(
			com.lynxis.orca.core.domain.RoutingAdminService service, InstallationProperties installation) {
		return new com.lynxis.orca.core.api.ScreenAdminController(service, installation.siteExternalId());
	}

	@Bean
	public com.lynxis.orca.core.api.TeamRoutingController teamRoutingController(
			com.lynxis.orca.core.domain.RoutingAdminService service, InstallationProperties installation) {
		return new com.lynxis.orca.core.api.TeamRoutingController(service, installation.siteExternalId());
	}

	// --- device registry ----------------------------------------------------

	@Bean
	public DeviceCatalogRepository deviceCatalogRepository(ScopeSeam seam) {
		return new DeviceCatalogRepository(seam);
	}

	@Bean
	public DeviceRepository deviceRepository(ScopeSeam seam) {
		return new DeviceRepository(seam);
	}

	@Bean
	public ResourceConfigurationRepository resourceConfigurationRepository(ScopeSeam seam) {
		return new ResourceConfigurationRepository(seam);
	}

	@Bean
	public DeviceAdminService deviceAdminService(DeviceRepository devices,
			DeviceCatalogRepository catalogs) {
		return new DeviceAdminService(devices, catalogs);
	}

	@Bean
	public ResourceConfigurationService resourceConfigurationService(
			ResourceConfigurationRepository configurations, SiteDirectoryRepository sites,
			DeviceRepository devices) {
		return new ResourceConfigurationService(configurations, sites, devices);
	}

	@Bean
	public DeviceAdminController deviceAdminController(DeviceAdminService service,
			InstallationProperties installation) {
		return new DeviceAdminController(service, installation.siteExternalId());
	}

	@Bean
	public DeviceCatalogController deviceCatalogController(DeviceCatalogRepository catalogs,
			InstallationProperties installation) {
		return new DeviceCatalogController(catalogs, installation.siteExternalId());
	}

	@Bean
	public ResourceConfigurationController resourceConfigurationController(
			ResourceConfigurationService service, InstallationProperties installation) {
		return new ResourceConfigurationController(service, installation.siteExternalId());
	}

	// --- settings · workspace · audit ---------------------------------------

	@Bean
	public SettingRepository settingRepository(ScopeSeam seam) {
		return new SettingRepository(seam);
	}

	@Bean
	public WorkspaceRepository workspaceRepository(ScopeSeam seam) {
		return new WorkspaceRepository(seam);
	}

	@Bean
	public SiteBrandingRepository siteBrandingRepository(ScopeSeam seam) {
		return new SiteBrandingRepository(seam);
	}

	@Bean
	public AuditEventRepository auditEventRepository(ScopeSeam seam) {
		return new AuditEventRepository(seam);
	}

	@Bean
	public AuditTrail auditTrail(AuditEventRepository events, UserAccountRepository users,
			CallerIdentity caller, InstallationProperties installation) {
		return new AuditTrail(events, users, caller, installation.siteExternalId());
	}

	@Bean
	public SettingsService settingsService(SettingRepository settings, AuditTrail audit) {
		return new SettingsService(settings, audit);
	}

	@Bean
	public WorkspaceService workspaceService(WorkspaceRepository workspace, UserAccountRepository users,
			CallerIdentity caller) {
		return new WorkspaceService(workspace, users, caller);
	}

	@Bean
	public SiteBrandingService siteBrandingService(SiteBrandingRepository branding,
			SiteDirectoryRepository sites, AuditTrail audit) {
		return new SiteBrandingService(branding, sites, audit);
	}

	@Bean
	public SettingsController settingsController(SettingsService service,
			InstallationProperties installation) {
		return new SettingsController(service, installation.siteExternalId());
	}

	@Bean
	public WorkspaceController workspaceController(WorkspaceService service,
			InstallationProperties installation) {
		return new WorkspaceController(service, installation.siteExternalId());
	}

	@Bean
	public SiteBrandingController siteBrandingController(SiteBrandingService service,
			InstallationProperties installation) {
		return new SiteBrandingController(service, installation.siteExternalId());
	}

	@Bean
	public AuditEventController auditEventController(AuditEventRepository events,
			InstallationProperties installation) {
		return new AuditEventController(events, installation.siteExternalId());
	}
}
