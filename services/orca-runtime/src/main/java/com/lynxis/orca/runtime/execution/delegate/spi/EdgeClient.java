package com.lynxis.orca.runtime.execution.delegate.spi;

/**
 * The edge seam every device and display effect crosses. The integration module
 * provides the real adapter (device host / display fan-out); the delegates never
 * know transport.
 *
 * <p>Contract: the command carries an <b>idempotency key</b> — retries of the same
 * step reuse it and the edge deduplicates; a <b>deadline</b> — the edge answers within it
 * or answers {@code UNKNOWN}; and {@code UNKNOWN} is <b>its own outcome</b> — the command
 * may have reached the device, so it is never coerced to FAILED and never blindly retried
 * as if it hadn't happened.
 */
public interface EdgeClient {

    EdgeOutcome perform(EdgeCommand command);
}
