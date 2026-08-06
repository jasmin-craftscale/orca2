// P2 · Lease with fence token.
//
// Anything only one instance may do at a time. Process-coordination state, not
// tenant data: no site id, no audit quartet, no soft delete (§C2, service_lease).

plugins {
	`java-library`
}

dependencies {
	api(libs.spring.boot.starter)
	api(libs.spring.boot.starter.jdbc)

	// The table-declaration annotations (@PersistentTable, @RetentionClass) live in
	// platform/scope, the data-access primitive. compileOnly, because they are
	// metadata: the annotation is written into the class file for the build check to
	// read, without dragging scope's JPA dependencies into this module at runtime.
	compileOnly(project(":platform:scope"))

	testImplementation(libs.spring.boot.starter.test)

	"integrationTestImplementation"(project(":platform:scope"))
	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestRuntimeOnly"(libs.mssql.jdbc)
}
