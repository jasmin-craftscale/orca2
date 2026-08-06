package com.lynxis.orca.platform.scope.readiness;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
// Boot 4 moved the auto-configurations out of one `autoconfigure` package and
// into per-module ones: this is `boot.jdbc.autoconfigure`, not
// `boot.autoconfigure.jdbc`. Copying an import from a Boot 3 example does not
// compile, which is the cheap version of this discovery.
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the readiness gate into every service that depends on {@code platform/scope}.
 *
 * <p>A service adds the dependency and the check is there; it does not have to
 * remember to register it. That is the point of the primitives being
 * auto-configurations rather than utilities — the correct thing is the easy
 * thing.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(RequiredViews.class)
public class RequiredViewsAutoConfiguration {

	@Bean
	@ConditionalOnBean(DataSource.class)
	@ConditionalOnMissingBean
	public RequiredViewsGate requiredViewsGate(DataSource dataSource, RequiredViews requiredViews) {
		return new RequiredViewsGate(dataSource, requiredViews);
	}
}
