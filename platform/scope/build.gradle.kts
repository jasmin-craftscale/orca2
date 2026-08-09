// One place where a query acquires its scope predicate, and no way around it.
// That requirement is settled; the enforcement mechanism is not. This module is
// the seam and its default-deny behaviour — it deliberately does NOT implement
// database row-level security, which remains a security-design decision.

plugins {
	`java-library`
}

dependencies {
	api(libs.spring.boot.starter)
	// The seam has to cover both query-construction APIs a service could reach for,
	// which is why it depends on both and why the build check names both.
	api(libs.spring.boot.starter.data.jpa)
	api(libs.spring.boot.starter.jdbc)

	testImplementation(libs.spring.boot.starter.test)

	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestRuntimeOnly"(libs.mssql.jdbc)
}
