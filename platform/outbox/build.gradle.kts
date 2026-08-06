// P1 · Transactional outbox and relay.
//
// Replaces a message broker. There is no broker inside a site (ADR-007).
//
// This module knows about transactions, sequences, ordering keys and consumers.
// It does not know what a visit, a lane, a ticket, a driver or a truck is — and
// the platform-purity build check fails if that ever stops being true.

plugins {
	`java-library`
	`java-test-fixtures`
}

dependencies {
	api(libs.spring.boot.starter)
	api(libs.spring.boot.starter.jdbc)
	implementation(project(":platform:web"))

	testImplementation(libs.spring.boot.starter.test)

	// The shared SQL Server Testcontainers wiring, published as a test-fixtures
	// variant rather than a thirteenth Gradle module. It carries the pattern from
	// the generated TestcontainersConfiguration the root application took with it.
	testFixturesApi(platform(libs.spring.boot.dependencies))
	testFixturesApi(libs.spring.boot.starter.test)
	testFixturesApi(libs.spring.boot.testcontainers)
	testFixturesApi(libs.testcontainers.junit.jupiter)
	testFixturesApi(libs.testcontainers.mssqlserver)
	testFixturesApi(libs.spring.boot.starter.jdbc)
	testFixturesApi(libs.flyway.sqlserver)
	testFixturesRuntimeOnly(libs.mssql.jdbc)

	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestRuntimeOnly"(libs.mssql.jdbc)
}
