package com.lynxis.orca.platform.outbox;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who must acknowledge a fact before it is deletable.
 *
 * <p><strong>Registration is configuration, not presence.</strong> A consumer that
 * is currently down is still registered, and its unacknowledged rows still hold
 * retention off. That is the point: retention must not destroy data that has not
 * reached every intended consumer. A registry built from whatever happens to be
 * running would let an outage quietly become data loss — the one failure mode
 * this mechanism exists to make impossible.
 *
 * <p><strong>A consequence worth stating.</strong> Delivery rows are written when
 * the fact is written, so a consumer registered <em>after</em> a fact was recorded
 * has no delivery row for it and will not receive it. Registering a new consumer
 * on a live installation is therefore a deliberate act with a backfill decision
 * attached, not a configuration edit. The architecture does not say which; this
 * implementation does not choose for it.
 */
public class ConsumerRegistry {

	private final Set<String> consumers;

	public ConsumerRegistry(Collection<String> consumers) {
		Set<String> names = new LinkedHashSet<>();
		for (String name : consumers) {
			if (name != null && !name.isBlank()) {
				names.add(name.trim());
			}
		}
		this.consumers = Set.copyOf(names);
	}

	/** Every consumer that must acknowledge, running or not. */
	public Set<String> names() {
		return consumers;
	}

	public boolean isRegistered(String consumer) {
		return consumers.contains(consumer);
	}

	public boolean isEmpty() {
		return consumers.isEmpty();
	}
}
