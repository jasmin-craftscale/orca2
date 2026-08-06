// P4 · Idempotency record.
//
// The same key applied twice has the effect of once, and the second caller gets
// the recorded outcome rather than a bare "duplicate" it cannot use.

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
