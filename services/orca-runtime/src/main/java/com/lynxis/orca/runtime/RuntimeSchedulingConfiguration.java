package com.lynxis.orca.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.lynxis.orca.platform.outbox.OutboxRelay;

/**
 * Runtime's background loops.
 *
 * <p>Separate from {@link RuntimeApplication} so that a test can load the
 * application without its schedules — the admission suites measure one
 * transaction's atomicity, and a relay committing on its own schedule beside them
 * is noise in exactly the measurement being taken.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RuntimeSchedulingConfiguration {

	@Bean
	public RuntimeTasks runtimeTasks(OutboxRelay relay,
			@Value("${orca.outbox.relay.batch-size:50}") int batchSize) {
		return new RuntimeTasks(relay, batchSize);
	}
}
