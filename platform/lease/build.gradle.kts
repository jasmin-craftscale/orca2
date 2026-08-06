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

	testImplementation(libs.spring.boot.starter.test)

	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestRuntimeOnly"(libs.mssql.jdbc)
}
