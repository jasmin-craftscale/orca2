/**
 * Idempotency record — the same key applied twice has the effect of once.
 *
 * <p>The second call returns the <em>recorded outcome</em>, never a bare duplicate:
 * a caller that retried because it never saw the first answer needs the answer, not
 * an error. An operation still running reports in-progress, and in-progress is not
 * a terminal state.
 */
package com.lynxis.orca.platform.idempotency;
