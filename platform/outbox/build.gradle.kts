// Transactional outbox and relay.
//
// Replaces a message broker: an on-site installation deliberately has no broker.
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

	// The table-declaration annotations (@PersistentTable, @RetentionClass) live in
	// platform/scope, the data-access primitive. compileOnly, because they are
	// metadata: the annotation is written into the class file for the build check to
	// read, without dragging scope's JPA dependencies into this module at runtime.
	compileOnly(project(":platform:scope"))

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

	"integrationTestImplementation"(project(":platform:scope"))
	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestRuntimeOnly"(libs.mssql.jdbc)
}
