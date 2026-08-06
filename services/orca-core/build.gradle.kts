// orca-core — the world as configured (§C1).
//
// Owns schema `core`, its own migrations, its own contract and its own database
// login. Publisher of the read-only views the other services consume, which is
// why it migrates first.

plugins {
	java
	id("org.springframework.boot")
	id("org.openapi.generator")
}

// --- Contract-first, smoke-tested here first (brief, Package 4b) ------------
//
// The generator is proved against Spring Boot 4 on ONE service before the other
// six depend on it. The `spring` generator has historically lagged Spring major
// versions; discovering a gap here is cheap and discovering it in Phase 1 is not.
// If it could not emit Boot 4-compatible code, the instruction is to stop and
// report — not to hand-write the API layer, which would silently reverse ADR-014.
openApiGenerate {
	generatorName = "spring"
	inputSpec = layout.projectDirectory.file("src/main/resources/openapi/orca-core.yaml").asFile.path
	outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.path
	apiPackage = "com.lynxis.orca.core.api.generated"
	modelPackage = "com.lynxis.orca.core.api.generated.model"
	configOptions = mapOf(
		// Generate the API INTERFACE and its DTOs. The controller is hand-written
		// and implements it, so a contract change breaks the build until the
		// implementation matches. That is what makes contract-first a constraint
		// rather than documentation.
		"interfaceOnly" to "true",
		"skipDefaultInterface" to "true",
		"useJakartaEe" to "true",
		"useSpringBoot3" to "true",
		"documentationProvider" to "none",
		"annotationLibrary" to "none",
		"openApiNullable" to "false",
		"useTags" to "true",
		"hideGenerationTimestamp" to "true",
	)
}

// Generated sources are never committed: in version control they drift from the
// spec and nobody notices.
sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
tasks.named("compileJava") { dependsOn(tasks.named("openApiGenerate")) }

dependencies {
	// --- The primitives this service uses, and why ---------------------
	implementation(project(":platform:web")) // the envelope, the error codes and the system context
	implementation(project(":platform:scope")) // the query seam; every service serves scoped reads
	implementation(project(":platform:outbox")) // this service publishes facts (§C: it has an outbox pair)
	implementation(project(":platform:lease")) // one `service_lease` per service schema (§C2)

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
