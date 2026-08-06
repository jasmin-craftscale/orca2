package com.lynxis.orca.platform.lease;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the lease and its guarded write into any service that depends on this module. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(LeaseProperties.class)
public class LeaseAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public LeaseConfigurationValidator leaseConfigurationValidator(LeaseProperties properties) {
		return new LeaseConfigurationValidator(properties);
	}

	@Bean
	@ConditionalOnBean(DataSource.class)
	@ConditionalOnMissingBean
	public LeaseManager leaseManager(JdbcTemplate jdbcTemplate, Environment environment) {
		return new JdbcLeaseManager(jdbcTemplate, serviceName(environment));
	}

	@Bean
	@ConditionalOnBean(DataSource.class)
	@ConditionalOnMissingBean
	public FencedWrite fencedWrite(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
			Environment environment) {
		return new FencedWrite(jdbcTemplate, new TransactionTemplate(transactionManager), serviceName(environment));
	}

	private static String serviceName(Environment environment) {
		return environment.getProperty("spring.application.name", "unknown-service");
	}
}
