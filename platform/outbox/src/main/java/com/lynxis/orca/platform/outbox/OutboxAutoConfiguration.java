package com.lynxis.orca.platform.outbox;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
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

/**
 * A service adds {@code platform/outbox} and gets a writer, a relay and a
 * retention sweep — configured, not assembled.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public ConsumerRegistry outboxConsumerRegistry(OutboxProperties properties) {
		return new ConsumerRegistry(properties.getConsumers());
	}

	@Bean
	@ConditionalOnBean(DataSource.class)
	@ConditionalOnMissingBean
	public OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate, ConsumerRegistry registry) {
		return new JdbcOutboxWriter(jdbcTemplate, registry);
	}

	@Bean
	@ConditionalOnBean(DataSource.class)
	@ConditionalOnMissingBean
	public OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
			ConsumerRegistry registry, ObjectProvider<OutboxConsumer> consumers, Environment environment,
			OutboxProperties properties) {
		return new OutboxRelay(jdbcTemplate, new TransactionTemplate(transactionManager), registry,
				consumers.orderedStream().toList(), serviceName(environment), holderId(environment),
				properties.getClaimDuration());
	}

	@Bean
	@ConditionalOnBean(DataSource.class)
	@ConditionalOnMissingBean
	public OutboxRetention outboxRetention(JdbcTemplate jdbcTemplate,
			PlatformTransactionManager transactionManager, Environment environment) {
		return new OutboxRetention(jdbcTemplate, new TransactionTemplate(transactionManager),
				serviceName(environment));
	}

	private static String serviceName(Environment environment) {
		return environment.getProperty("spring.application.name", "unknown-service");
	}

	/**
	 * The instance identity a claim is recorded under.
	 *
	 * <p>Per process, not per host: two instances on one machine are two holders,
	 * and a host name would make them indistinguishable in exactly the arrangement
	 * §A5 supports.
	 */
	private static String holderId(Environment environment) {
		return serviceName(environment) + ":" + UUID.randomUUID();
	}

	/** Kept so the class is usable from a plain {@code new} in tests without a context. */
	static List<OutboxConsumer> none() {
		return List.of();
	}
}
