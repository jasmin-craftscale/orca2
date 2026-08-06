package com.lynxis.orca.platform.scope.readiness;

import java.util.List;

import lombok.Getter;

/**
 * Thrown during startup when a published view this service requires is absent.
 *
 * <p>The message names every missing view, not just the first, so one restart
 * tells an operator the whole problem.
 */
@Getter
public class MissingRequiredViewException extends IllegalStateException {

	private final transient List<String> missingViews;

	public MissingRequiredViewException(String service, List<String> missingViews) {
		super(build(service, missingViews));
		this.missingViews = List.copyOf(missingViews);
	}

	private static String build(String service, List<String> missing) {
		return service + " will not start: "
				+ missing.size() + " required published view(s) are absent — "
				+ String.join(", ", missing)
				+ ". orca-core publishes these; it must have migrated before this service starts. "
				+ "This is a deployment ordering problem, not a fault in this service.";
	}
}
