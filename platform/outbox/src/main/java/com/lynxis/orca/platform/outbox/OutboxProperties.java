package com.lynxis.orca.platform.outbox;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * What a service must state about its own outbox.
 *
 * <p>Nothing here has a value the platform invented for a service. The consumer
 * list in particular has no default: guessing it would decide, on a service's
 * behalf, whose acknowledgement retention waits for — and getting that wrong in
 * the permissive direction deletes data a peer never received.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "orca.outbox")
public class OutboxProperties {

	/**
	 * Every consumer that must acknowledge before a fact is deletable, running or
	 * not. Empty means this service publishes facts nobody is registered for,
	 * which is legitimate during bring-up and worth noticing in a deployment.
	 */
	private List<String> consumers = List.of();

	/**
	 * How long a claim is held before another instance may take the row.
	 *
	 * <p>Too short and two relays deliver the same fact while the first is still
	 * working; too long and a dead relay's rows wait that long. It is a profile
	 * decision, and the default here is a starting point for local development
	 * rather than a recommendation for a site.
	 */
	private Duration claimDuration = Duration.ofSeconds(30);
}
