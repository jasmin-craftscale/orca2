package com.lynxis.orca.runtime.admission;

/**
 * A seam for injecting a failure between the row insert and the engine start.
 *
 * <p>It exists because the interesting half of the atomicity claim cannot be
 * observed from outside: "the insert and the engine start are one transaction" is
 * only meaningful if something can die in the gap, and there is no gap to aim at
 * unless the operation offers one.
 *
 * <p>Test-only, and it lives beside the operation rather than inside it so that
 * the production shape carries a no-op field instead of a test flag.
 */
@FunctionalInterface
public interface AdmissionFault {

	AdmissionFault NONE = executionId -> {
	};

	/**
	 * Called after the visit row is inserted and before the process instance is
	 * started, inside the admission transaction.
	 */
	void afterInsertBeforeEngineStart(long executionId);
}
