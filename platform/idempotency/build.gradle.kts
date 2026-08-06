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
