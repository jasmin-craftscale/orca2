// build-checks — the enforcement.
//
// TESTS ONLY. No production code. Its entire output is build failures.
//
// These rules have to see every module at once, which is the reason the
// repository is a monorepo (ADR-014). In seven repositories they would degrade
// into a code-review convention — and a convention is what the current system
// enforced tenancy with, across roughly 816 hand-written scope conditions.

plugins {
	java
}

dependencies {
	testImplementation(libs.archunit.junit5)
	testImplementation(libs.spring.boot.starter.test)

	// Every module the rules inspect. ArchUnit reads compiled classes, so each
	// one must be on this classpath or its violations are simply not seen.
	testImplementation(project(":platform:outbox"))
	testImplementation(project(":platform:lease"))
	testImplementation(project(":platform:scope"))
	testImplementation(project(":platform:idempotency"))
	testImplementation(project(":platform:web"))
	testImplementation(project(":services:orca-core"))
	testImplementation(project(":services:orca-runtime"))
	testImplementation(project(":services:orca-edge"))
	testImplementation(project(":services:orca-portal"))
	testImplementation(project(":services:orca-sync"))
	testImplementation(project(":services:orca-fleet"))
}
