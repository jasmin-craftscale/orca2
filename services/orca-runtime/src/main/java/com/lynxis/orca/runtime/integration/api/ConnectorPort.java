package com.lynxis.orca.runtime.integration.api;

/**
 * The way out to a customer system, as the process sees it.
 *
 * <p>An interface rather than a call, so that {@code ConnectorCallDelegate} — which
 * the compiler's output binds to by name and must therefore stay still — does not
 * change when the transport does. WP7 puts a {@code RestClient} with a deadline, a
 * circuit breaker and a bulkhead behind it; the delegate does not learn about any
 * of that.
 */
public interface ConnectorPort {

	/**
	 * Invokes the named connector for this visit.
	 *
	 * @return the branch discriminator the process routes on. A short token, never
	 *         a response body: §C2 keeps business data in platform tables keyed by
	 *         execution id, and the engine's history tables bounded
	 * @throws ConnectorUnavailableException when the call could not be made or
	 *                                       produced no usable answer. The delegate
	 *                                       turns that into a BPMN error, and the
	 *                                       process takes its failure branch
	 */
	String call(ConnectorCall call);

	/**
	 * Everything the connector needs, and nothing that is business data.
	 *
	 * @param visitExternalId  the visit this call belongs to — the key everything
	 *                         else is looked up by
	 * @param laneExternalId   which lane, in core's published vocabulary
	 * @param connectorName    which configured connector to invoke. A name rather
	 *                         than an endpoint: the endpoint, its authentication and
	 *                         its certificate trust are configuration (§C2), and a
	 *                         process that carried a URL would have to be
	 *                         republished to change one
	 */
	record ConnectorCall(String visitExternalId, String laneExternalId, String connectorName) {
	}

	/** The call could not be completed. Not "the customer said no" — that is an outcome, and it has a branch. */
	class ConnectorUnavailableException extends RuntimeException {

		public ConnectorUnavailableException(String message) {
			super(message);
		}

		public ConnectorUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
