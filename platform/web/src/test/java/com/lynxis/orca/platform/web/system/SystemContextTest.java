package com.lynxis.orca.platform.web.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P5 property 2, the runtime half — <strong>no path runs with no identity.</strong>
 *
 * <p>The other half is a build check: {@code SystemContextRule} in
 * {@code build-checks} fails the build when a scheduled entry point does not enter
 * this context. A test can only prove the mechanism works; only the build check
 * can prove nobody skipped it.
 *
 * <p>What is tested here is the part that fails silently in production: an
 * identity left behind on a pooled thread, so the next piece of work runs as
 * something it is not.
 */
class SystemContextTest {

	private static final SystemIdentity RELAY = new SystemIdentity("orca-runtime", "outbox-relay");

	@AfterEach
	void noLeakBetweenTests() {
		assertThat(SystemContext.current()).isEmpty();
	}

	@Test
	@DisplayName("work inside runAs has an identity; work outside it has none")
	void identityIsScopedToTheWork() {
		assertThat(SystemContext.isSystem()).isFalse();

		AtomicReference<SystemIdentity> seen = new AtomicReference<>();
		SystemContext.runAs(RELAY, () -> seen.set(SystemContext.require()));

		assertThat(seen.get()).isEqualTo(RELAY);
		assertThat(SystemContext.isSystem()).isFalse();
	}

	@Test
	@DisplayName("the identity is cleared even when the work throws — a pooled thread does not inherit it")
	void identityIsClearedOnFailure() {
		assertThatThrownBy(() -> SystemContext.runAs(RELAY, () -> {
			throw new IllegalStateException("the relay failed");
		})).isInstanceOf(IllegalStateException.class);

		// This is the assertion that matters. Without the finally, the NEXT task on
		// this thread would run as the outbox relay, and nothing would say so.
		assertThat(SystemContext.current()).isEmpty();
	}

	@Test
	@DisplayName("the identity is cleared when the work throws a checked exception too")
	void identityIsClearedOnCheckedFailure() {
		assertThatThrownBy(() -> SystemContext.callAs(RELAY, () -> {
			throw new java.io.IOException("the feed was unreachable");
		})).isInstanceOf(SystemTaskFailedException.class)
				.hasCauseInstanceOf(java.io.IOException.class);

		assertThat(SystemContext.current()).isEmpty();
	}

	@Test
	@DisplayName("require() refuses work that has no identity, rather than inventing one")
	void requireRefusesAnonymousWork() {
		assertThatThrownBy(SystemContext::require)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("requires a system identity and has none");
	}

	@Test
	@DisplayName("nesting is refused, not stacked — an unexpected call path is not hidden")
	void nestingIsRefused() {
		SystemContext.runAs(RELAY, () ->
				assertThatThrownBy(() -> SystemContext.runAs(
						new SystemIdentity("orca-runtime", "retention-sweep"), () -> {
						}))
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("refusing to nest"));

		assertThat(SystemContext.current()).isEmpty();
	}

	@Test
	@DisplayName("an identity must name both the service and the task — 'the system' is not an identity")
	void anIdentityMustBeSpecific() {
		assertThatThrownBy(() -> new SystemIdentity("orca-runtime", " "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must name its task");
		assertThatThrownBy(() -> new SystemIdentity("", "outbox-relay"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must name its service");
	}

	@Test
	@DisplayName("one thread's identity is not visible to another")
	void identityDoesNotEscapeTheThread() throws Exception {
		AtomicReference<Boolean> otherThreadSawIt = new AtomicReference<>();
		SystemContext.runAs(RELAY, () -> {
			Thread other = new Thread(() -> otherThreadSawIt.set(SystemContext.isSystem()));
			other.start();
			try {
				other.join();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertThat(otherThreadSawIt.get()).isFalse();
	}
}
