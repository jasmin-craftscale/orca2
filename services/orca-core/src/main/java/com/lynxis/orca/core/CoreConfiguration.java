package com.lynxis.orca.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lynxis.orca.core.api.DeviceAdminController;
import com.lynxis.orca.core.api.DeviceCatalogController;
import com.lynxis.orca.core.api.ResourceConfigurationController;
import com.lynxis.orca.core.domain.DeviceAdminService;
import com.lynxis.orca.core.domain.ResourceConfigurationService;
import com.lynxis.orca.core.persistence.DeviceCatalogRepository;
import com.lynxis.orca.core.persistence.DeviceRepository;
import com.lynxis.orca.core.persistence.ResourceConfigurationRepository;
import com.lynxis.orca.core.api.EntitlementCatalogController;
import com.lynxis.orca.core.api.RoleAdminController;
import com.lynxis.orca.core.api.UserAdminController;
import com.lynxis.orca.core.domain.RoleAdminService;
import com.lynxis.orca.core.domain.UserAdminService;
import com.lynxis.orca.core.api.BreakTemplateController;
import com.lynxis.orca.core.api.ShiftTemplateController;
import com.lynxis.orca.core.api.TeamAdminController;
import com.lynxis.orca.core.domain.TeamAdminService;
import com.lynxis.orca.core.domain.TemplateAdminService;
import com.lynxis.orca.core.persistence.BreakTemplateRepository;
import com.lynxis.orca.core.persistence.EntitlementCatalogRepository;
import com.lynxis.orca.core.persistence.ShiftTemplateRepository;
import com.lynxis.orca.core.persistence.TeamRepository;
import com.lynxis.orca.core.persistence.RoleRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;

/**
 * Wires core's configuration-world beans.
 *
 * <p>Controllers that need configuration values are declared here by name so
 * the {@code @Bean} definition supplies the {@code String} their constructors
 * take — the same shape edge uses, and the reason is the same: a plain
 * {@code String} constructor parameter cannot be component-scanned.
 *
 * <p>{@code orca.installation.site-external-id} is new to core in Phase 2: the
 * installation's site, from configuration, never from the request — the scope
 * pattern the fielded services already follow (phase-1 decision 7).
 */
@Configuration(proxyBeanMethods = false)
public class CoreConfiguration {

	@Bean
	public UserAccountRepository userAccountRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new UserAccountRepository(seam);
	}

	@Bean
	public RoleRepository roleRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new RoleRepository(seam);
	}

	@Bean
	public EntitlementCatalogRepository entitlementCatalogRepository(
			com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new EntitlementCatalogRepository(seam);
	}

	@Bean
	public SiteDirectoryRepository siteDirectoryRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
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
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new UserAdminController(service, siteExternalId);
	}

	@Bean
	public RoleAdminController roleAdminController(RoleAdminService service,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new RoleAdminController(service, siteExternalId);
	}

	@Bean
	public EntitlementCatalogController entitlementCatalogController(EntitlementCatalogRepository catalog,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new EntitlementCatalogController(catalog, siteExternalId);
	}

	// --- WP2 · teams & templates --------------------------------------------

	@Bean
	public ShiftTemplateRepository shiftTemplateRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new ShiftTemplateRepository(seam);
	}

	@Bean
	public BreakTemplateRepository breakTemplateRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new BreakTemplateRepository(seam);
	}

	@Bean
	public TeamRepository teamRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
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
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new ShiftTemplateController(service, siteExternalId);
	}

	@Bean
	public BreakTemplateController breakTemplateController(TemplateAdminService service,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new BreakTemplateController(service, siteExternalId);
	}

	@Bean
	public TeamAdminController teamAdminController(TeamAdminService service,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new TeamAdminController(service, siteExternalId);
	}

	// --- WP3 · device registry ----------------------------------------------

	@Bean
	public DeviceCatalogRepository deviceCatalogRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new DeviceCatalogRepository(seam);
	}

	@Bean
	public DeviceRepository deviceRepository(com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new DeviceRepository(seam);
	}

	@Bean
	public ResourceConfigurationRepository resourceConfigurationRepository(
			com.lynxis.orca.platform.scope.ScopeSeam seam) {
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
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new DeviceAdminController(service, siteExternalId);
	}

	@Bean
	public DeviceCatalogController deviceCatalogController(DeviceCatalogRepository catalogs,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new DeviceCatalogController(catalogs, siteExternalId);
	}

	// --- WP4 · settings · workspace · audit ---------------------------------

	/**
	 * The production caller identity: the JWT's subject when a person is behind
	 * the request, empty otherwise. An interface so the property suites can
	 * hand controllers a caller without a security context.
	 */
	@Bean
	public com.lynxis.orca.core.domain.CallerIdentity callerIdentity() {
		return () -> {
			var authentication = org.springframework.security.core.context.SecurityContextHolder
					.getContext().getAuthentication();
			if (authentication instanceof org.springframework.security.oauth2.server.resource
					.authentication.JwtAuthenticationToken jwt) {
				return java.util.Optional.ofNullable(jwt.getToken().getSubject());
			}
			return java.util.Optional.empty();
		};
	}

	@Bean
	public com.lynxis.orca.core.persistence.SettingRepository settingRepository(
			com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new com.lynxis.orca.core.persistence.SettingRepository(seam);
	}

	@Bean
	public com.lynxis.orca.core.persistence.WorkspaceRepository workspaceRepository(
			com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new com.lynxis.orca.core.persistence.WorkspaceRepository(seam);
	}

	@Bean
	public com.lynxis.orca.core.persistence.SiteBrandingRepository siteBrandingRepository(
			com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new com.lynxis.orca.core.persistence.SiteBrandingRepository(seam);
	}

	@Bean
	public com.lynxis.orca.core.persistence.AuditEventRepository auditEventRepository(
			com.lynxis.orca.platform.scope.ScopeSeam seam) {
		return new com.lynxis.orca.core.persistence.AuditEventRepository(seam);
	}

	@Bean
	public com.lynxis.orca.core.domain.AuditTrail auditTrail(
			com.lynxis.orca.core.persistence.AuditEventRepository events,
			UserAccountRepository users,
			com.lynxis.orca.core.domain.CallerIdentity caller,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.core.domain.AuditTrail(events, users, caller, siteExternalId);
	}

	@Bean
	public com.lynxis.orca.core.domain.SettingsService settingsService(
			com.lynxis.orca.core.persistence.SettingRepository settings,
			com.lynxis.orca.core.domain.AuditTrail audit) {
		return new com.lynxis.orca.core.domain.SettingsService(settings, audit);
	}

	@Bean
	public com.lynxis.orca.core.domain.WorkspaceService workspaceService(
			com.lynxis.orca.core.persistence.WorkspaceRepository workspace,
			UserAccountRepository users,
			com.lynxis.orca.core.domain.CallerIdentity caller) {
		return new com.lynxis.orca.core.domain.WorkspaceService(workspace, users, caller);
	}

	@Bean
	public com.lynxis.orca.core.domain.SiteBrandingService siteBrandingService(
			com.lynxis.orca.core.persistence.SiteBrandingRepository branding,
			SiteDirectoryRepository sites,
			com.lynxis.orca.core.domain.AuditTrail audit) {
		return new com.lynxis.orca.core.domain.SiteBrandingService(branding, sites, audit);
	}

	@Bean
	public com.lynxis.orca.core.api.SettingsController settingsController(
			com.lynxis.orca.core.domain.SettingsService service,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.core.api.SettingsController(service, siteExternalId);
	}

	@Bean
	public com.lynxis.orca.core.api.WorkspaceController workspaceController(
			com.lynxis.orca.core.domain.WorkspaceService service,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.core.api.WorkspaceController(service, siteExternalId);
	}

	@Bean
	public com.lynxis.orca.core.api.SiteBrandingController siteBrandingController(
			com.lynxis.orca.core.domain.SiteBrandingService service,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.core.api.SiteBrandingController(service, siteExternalId);
	}

	@Bean
	public com.lynxis.orca.core.api.AuditEventController auditEventController(
			com.lynxis.orca.core.persistence.AuditEventRepository events,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.core.api.AuditEventController(events, siteExternalId);
	}

	@Bean
	public ResourceConfigurationController resourceConfigurationController(
			ResourceConfigurationService service,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new ResourceConfigurationController(service, siteExternalId);
	}
}
