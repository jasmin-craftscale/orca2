package com.lynxis.orca.platform.scope;

import java.util.concurrent.Callable;

/**
 * The scope in force for the current unit of work.
 *
 * <p>Ambient rather than a parameter, and that is the design. A scope threaded
 * through every signature is a scope somebody eventually forgets to pass, and the
 * result of forgetting is a query that returns another tenant's rows with no
 * error, no exception and no log line. The current system's position is roughly
 * 816 hand-written scope conditions and no single place to fix them.
 *
 * <p>When nothing has established a scope, the current scope is {@link Scope#DENY}
 * — not "unscoped". Reading nothing is a bug somebody reports; reading everything
 * is a breach nobody notices.
 */
public final class ScopeContext {

	private static final ThreadLocal<Scope> CURRENT = ThreadLocal.withInitial(() -> Scope.DENY);

	private ScopeContext() {
	}

	/** The scope in force. Never null, and {@link Scope#DENY} when none was set. */
	public static Scope current() {
		return CURRENT.get();
	}

	public static void runIn(Scope scope, Runnable work) {
		callIn(scope, () -> {
			work.run();
			return null;
		});
	}

	/**
	 * Runs work under a scope and restores the previous one afterwards.
	 *
	 * <p>The {@code finally} is the load-bearing line: this runs on a pooled
	 * request thread, and a scope left behind is a scope the next request inherits.
	 */
	public static <T> T callIn(Scope scope, Callable<T> work) {
		if (scope == null) {
			throw new IllegalArgumentException("Scope must not be null. Use Scope.DENY to mean nothing visible.");
		}
		Scope previous = CURRENT.get();
		CURRENT.set(scope);
		try {
			return work.call();
		}
		catch (RuntimeException | Error e) {
			throw e;
		}
		catch (Exception e) {
			throw new IllegalStateException("Scoped work failed", e);
		}
		finally {
			CURRENT.set(previous);
		}
	}
}
