package com.lynxis.orca.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lynxis.orca.core.api.EntitlementCatalogController;
import com.lynxis.orca.core.api.RoleAdminController;
import com.lynxis.orca.core.api.UserAdminController;
import com.lynxis.orca.core.domain.RoleAdminService;
import com.lynxis.orca.core.domain.UserAdminService;
import com.lynxis.orca.core.persistence.EntitlementCatalogRepository;
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
}
