package com.lynxis.orca.checks;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Every ORCA class, imported once.
 *
 * <p>These rules have to see all twelve modules at the same time — a service
 * reaching into another service's internals is invisible from inside either one.
 * That is the reason this is a monorepo (ADR-014): in seven repositories these
 * checks degrade into a code-review convention, and a convention is what the
 * current system enforced tenancy with across roughly 816 hand-written scope
 * conditions.
 */
final class OrcaClasses {

	static final String ROOT = "com.lynxis.orca";
	static final String PLATFORM = ROOT + ".platform";

	/** The five modules §C2 names inside orca-runtime. The module wall depends on these names. */
	static final String[] RUNTIME_MODULES = { "execution", "workitem", "integration", "notify", "readmodel" };

	/** The six built services. orca-media is not a module and has no classes here. */
	static final String[] SERVICES = { "core", "runtime", "edge", "portal", "sync", "fleet" };

	/**
	 * Production classes only.
	 *
	 * <p>Tests are excluded on purpose: a test may legitimately construct a query
	 * outside the seam in order to prove the seam withheld something, and a test
	 * fixture may name a domain concept. The rules govern what ships.
	 *
	 * <p>Jars are <strong>not</strong> excluded, and that is load-bearing rather
	 * than incidental. In a Gradle multi-project the other eleven modules arrive on
	 * this classpath as jars; excluding them would leave the importer with almost
	 * nothing, and every rule below would pass by seeing no classes at all. That is
	 * the failure mode {@link ImportedSetGuard} exists to catch.
	 */
	private static final JavaClasses PRODUCTION = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ROOT);

	private OrcaClasses() {
	}

	static JavaClasses production() {
		return PRODUCTION;
	}
}
