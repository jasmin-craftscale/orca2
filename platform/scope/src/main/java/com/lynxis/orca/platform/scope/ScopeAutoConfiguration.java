package com.lynxis.orca.platform.scope;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the seam into any service that depends on this module. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
public class ScopeAutoConfiguration {

	@Bean
	@ConditionalOnBean(DataSource.class)
	@ConditionalOnMissingBean
	public ScopeSeam scopeSeam(JdbcTemplate jdbcTemplate) {
		return new JdbcScopeSeam(jdbcTemplate);
	}
}
