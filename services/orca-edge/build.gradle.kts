// orca-edge — the hardware boundary (§C3).
//
// Owns every hardware contract, the durable capture buffer and the command log.
// The only service a thin-edge site needs.

plugins {
	java
	id("org.springframework.boot")
}

dependencies {
	// --- The primitives this service uses, and why ---------------------
	implementation(project(":platform:web")) // the envelope, the error codes and the system context
	implementation(project(":platform:scope")) // the query seam; every service serves scoped reads
	implementation(project(":platform:outbox")) // this service publishes facts (§C: it has an outbox pair)
	implementation(project(":platform:lease")) // one `service_lease` per service schema (§C2)
	implementation(project(":platform:idempotency")) // recorded keys, not assumed ones

	// --- Spring Boot ----------------------------------------------------
	implementation(libs.spring.boot.starter.webmvc)
	implementation(libs.spring.boot.starter.actuator)
	implementation(libs.spring.boot.starter.security)
	implementation(libs.spring.boot.starter.security.oauth2.resource.server)
	implementation(libs.spring.boot.starter.validation)
	implementation(libs.spring.boot.starter.data.jpa)
	implementation(libs.spring.boot.starter.flyway)
	implementation(libs.flyway.sqlserver)
	runtimeOnly(libs.mssql.jdbc)

	// Swagger UI only. springdoc does NOT introspect the code — the checked-in
	// contract is the source of truth and code-first would be a second one.
	implementation(libs.springdoc.openapi.starter.webmvc.ui)

	// --- Tests ----------------------------------------------------------
	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(libs.spring.boot.starter.security.test)
	testImplementation(libs.spring.boot.starter.security.oauth2.resource.server.test)

	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestImplementation"(libs.spring.boot.starter.webmvc.test)
}
