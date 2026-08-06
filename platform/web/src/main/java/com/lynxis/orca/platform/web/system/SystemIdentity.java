package com.lynxis.orca.platform.web.system;

/**
 * The identity a piece of work runs under when no user invoked it.
 *
 * @param service the service that is running — {@code orca-runtime}
 * @param task    what it is doing — {@code outbox-relay}, {@code retention-sweep}.
 *                Not a class name: this is what appears in an audit trail, and a
 *                refactor must not rewrite history
 */
public record SystemIdentity(String service, String task) {

	public SystemIdentity {
		if (service == null || service.isBlank()) {
			throw new IllegalArgumentException("A system identity must name its service");
		}
		if (task == null || task.isBlank()) {
			// "the system" is not an identity. If the answer to "who did this" is
			// a single name shared by every background job, the audit trail says
			// nothing, and that is the state this type exists to prevent.
			throw new IllegalArgumentException("A system identity must name its task");
		}
	}

	@Override
	public String toString() {
		return "system:" + service + "/" + task;
	}
}
