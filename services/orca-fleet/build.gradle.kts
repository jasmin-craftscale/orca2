// orca-fleet — licences and releases (§C6).
//
// Cloud only, never at a customer site: the signing keys cannot ship on a
// customer's box.

plugins {
	java
	id("org.springframework.boot")
	id("org.openapi.generator")
}

// --- Contract-first (ADR-014) -----------------------------------------------
//
// The OpenAPI document is the source of truth; this generates the API interface
// and its DTOs from it. The controller is hand-written and implements the
// interface, so a contract change breaks the build until the implementation
// matches.
//
// The cross-module $ref to platform/web's _shared.yaml is RESOLVED here, not
// worked around by copying the file: schemaMappings binds the shared schemas to
// the Java types platform/web already implements, so the generated interface
// speaks in ApiResponse rather than in a seventh copy of it.
val sharedContract = rootProject.layout.projectDirectory
	.file("platform/web/src/main/resources/openapi/_shared.yaml")

val sharedSchemaMappings = mapOf(
	"ApiResponse" to "com.lynxis.orca.platform.web.ApiResponse",
	"ApiError" to "com.lynxis.orca.platform.web.ApiError",
	"PageMeta" to "com.lynxis.orca.platform.web.PageMeta",
	"ApiStatus" to "com.lynxis.orca.platform.web.ApiStatus",
)

val generatorOptions = mapOf(
	"interfaceOnly" to "true",
	"skipDefaultInterface" to "true",
	"useJakartaEe" to "true",
	"useSpringBoot3" to "true",
	"documentationProvider" to "none",
	"annotationLibrary" to "none",
	"openApiNullable" to "false",
	"useTags" to "true",
	"hideGenerationTimestamp" to "true",
	// The generated envelope must serialise IDENTICALLY to platform/web's own
	// ApiResponse, which is annotated NON_NULL. Without this the same envelope
	// comes back with "message":null and "errors":[] from a generated type and
	// without them from the hand-written one — one shape on paper, two on the wire.
	"additionalModelTypeAnnotations" to
		"@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)",
)

openApiGenerate {
	generatorName = "spring"
	inputSpec = layout.projectDirectory.file("src/main/resources/openapi/orca-fleet.yaml").asFile.path
	outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.path
	apiPackage = "com.lynxis.orca.fleet.api.generated"
	modelPackage = "com.lynxis.orca.fleet.api.generated.model"
	configOptions = generatorOptions
	schemaMappings = sharedSchemaMappings
}

// `openApiGenerate` is configured through an extension, so the shared contract is
// declared as an input on the TASK. Without this, editing _shared.yaml leaves the
// generated interface stale and up-to-date — the drift this whole arrangement
// exists to prevent, reintroduced by the build.
tasks.named("openApiGenerate") { inputs.file(sharedContract) }

// A second pass over the SAME source, emitting a fully resolved single-file
// specification. It is what the service serves at runtime: the authored document
// carries a relative $ref into another module, which resolves at build time and
// cannot resolve from inside a jar.
val bundleOpenApiSpec = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("bundleOpenApiSpec") {
	group = "openapi tools"
	description = "Resolves the cross-module \$ref into one self-contained document for the service to serve."
	generatorName = "openapi-yaml"
	inputSpec = layout.projectDirectory.file("src/main/resources/openapi/orca-fleet.yaml").asFile.path
	outputDir = layout.buildDirectory.dir("generated/openapi-bundled").get().asFile.path
	configOptions = mapOf("outputFile" to "static/openapi/orca-fleet.yaml")
	inputs.file(sharedContract)
}

sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/openapi-bundled"))

tasks.named("compileJava") { dependsOn(tasks.named("openApiGenerate")) }
tasks.named("processResources") { dependsOn(bundleOpenApiSpec) }

dependencies {
	// --- The primitives this service uses, and why ---------------------
	implementation(project(":platform:web")) // the envelope, the error codes and the system context
	implementation(project(":platform:scope")) // the query seam; every service serves scoped reads
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

	// springdoc was here and was REMOVED by review. Its UI cannot run with
	// api-docs disabled (SwaggerConfig is @ConditionalOnBean(SpringDocConfiguration),
	// which springdoc.api-docs.enabled=false switches off), and enabling api-docs
	// would introspect the code — a second source of truth beside the contract,
	// which §4b forbids. The brief's own fallback applies: serve the file
	// statically and drop springdoc. The bundled, fully resolved contract is
	// served at /openapi/orca-fleet.yaml by Spring's static-resource handling.

	// --- Tests ----------------------------------------------------------
	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(libs.spring.boot.starter.security.test)
	testImplementation(libs.spring.boot.starter.security.oauth2.resource.server.test)

	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestImplementation"(libs.spring.boot.starter.webmvc.test)
}
