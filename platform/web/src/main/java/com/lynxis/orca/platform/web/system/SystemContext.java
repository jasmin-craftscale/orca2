package com.lynxis.orca.platform.web.system;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The explicit identity for every entry point that runs without a user.
 *
 * <p>A relay, a scheduled job, a reconciler — none of them is invoked by a person,
 * and §B6 and §D3 both say the same thing about them: <strong>no path runs with
 * no identity at all.</strong> Work that runs anonymously cannot be authorised
 * and cannot be attributed, and the first time anyone notices is when they are
 * trying to explain a write nobody can account for.
 *
 * <p>Usage is a wrapper rather than a "set it and remember to unset it" pair,
 * because the {@code finally} is the part that gets forgotten and the failure is
 * silent: a leaked identity on a pooled thread means the next piece of work runs
 * as something it is not.
 *
 * <pre>{@code
 * SystemContext.runAs(new SystemIdentity("orca-runtime", "outbox-relay"),
 *         () -> relay.deliverPending());
 * }</pre>
 *
 * <p>Nesting is refused rather than stacked. A background task that finds itself
 * already inside a system context is a task that has been invoked from somewhere
 * it did not expect, and silently taking the inner identity would hide that.
 */
public final class SystemContext {

	private static final ThreadLocal<SystemIdentity> CURRENT = new ThreadLocal<>();

	private SystemContext() {
	}

	/** The identity of the work on this thread, or empty when a user invoked it. */
	public static Optional<SystemIdentity> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	/** Whether this thread is running system work. */
	public static boolean isSystem() {
		return CURRENT.get() != null;
	}

	public static void runAs(SystemIdentity identity, Runnable work) {
		callAs(identity, () -> {
			work.run();
			return null;
		});
	}

	public static <T> T callAs(SystemIdentity identity, Callable<T> work) {
		if (identity == null) {
			throw new IllegalArgumentException("A system entry point must name its identity");
		}
		SystemIdentity existing = CURRENT.get();
		if (existing != null) {
			throw new IllegalStateException(
					"Already running as " + existing + "; refusing to nest " + identity
							+ ". A system entry point reached from inside another one is a call path "
							+ "that was not expected — taking the inner identity would hide it.");
		}
		CURRENT.set(identity);
		try {
			return work.call();
		}
		catch (RuntimeException | Error e) {
			throw e;
		}
		catch (Exception e) {
			throw new SystemTaskFailedException(identity, e);
		}
		finally {
			CURRENT.remove();
		}
	}

	/**
	 * Asserts that the caller is running under a system identity.
	 *
	 * <p>For the writes that must be attributable. Calling this from a path a user
	 * can reach is a mistake; calling it from a path a user cannot reach and
	 * having it throw means the identity was never established.
	 */
	public static SystemIdentity require() {
		SystemIdentity identity = CURRENT.get();
		if (identity == null) {
			throw new IllegalStateException(
					"This work requires a system identity and has none. "
							+ "Wrap the entry point in SystemContext.runAs(...).");
		}
		return identity;
	}
}
