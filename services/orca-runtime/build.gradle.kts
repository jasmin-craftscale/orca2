// orca-runtime — the gate brain (§C2).
//
// The one service the architecture decomposes into modules: execution, workitem,
// integration, notify, readmodel. The module wall depends on those five names.

// Imported rather than written as java.util.zip.ZipFile below, because inside a
// Gradle Kotlin build script `java` resolves to the JavaPluginExtension accessor
// and shadows the package — which fails with "Unresolved reference 'util'".
import java.util.zip.ZipFile

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
	inputSpec = layout.projectDirectory.file("src/main/resources/openapi/orca-runtime.yaml").asFile.path
	outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.path
	apiPackage = "com.lynxis.orca.runtime.api.generated"
	modelPackage = "com.lynxis.orca.runtime.api.generated.model"
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
	inputSpec = layout.projectDirectory.file("src/main/resources/openapi/orca-runtime.yaml").asFile.path
	outputDir = layout.buildDirectory.dir("generated/openapi-bundled").get().asFile.path
	configOptions = mapOf("outputFile" to "static/openapi/orca-runtime.yaml")
	inputs.file(sharedContract)
}

sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/openapi-bundled"))

tasks.named("compileJava") { dependsOn(tasks.named("openApiGenerate")) }
tasks.named("processResources") { dependsOn(bundleOpenApiSpec) }

// --- Flowable's own schema, under Flyway (WP3) -------------------------------
//
// The engine does NOT migrate itself: `flowable.database-schema-update: false`.
// Its ~45 tables are versioned migrations like everything else, because §B7
// requires a schema change to be compatible with both versions for the duration
// of a rolling upgrade — and an engine that silently reshapes its own tables on
// whichever instance starts first cannot offer that.
//
// The DDL is EXTRACTED FROM THE JARS ON THE RUNTIME CLASSPATH, never hand-written
// and never downloaded. The classpath is the version truth: a script fetched from
// a website is a script that can disagree with the engine actually running, and
// the disagreement surfaces as a column that is missing at 3am rather than at
// build time.
val flowableSchemaScripts = mapOf(
	// Order is load-bearing. `common` owns ACT_GE_BYTEARRAY and the job/task/
	// variable tables that `engine`'s thirty foreign keys point at, so it goes
	// first. `identity` and `eventregistry` are self-contained.
	"V110__flowable_common.sql"
			to ("common" to "org/flowable/common/db/create/flowable.mssql.create.common.sql"),
	"V111__flowable_engine.sql"
			to ("engine" to "org/flowable/db/create/flowable.mssql.create.engine.sql"),
	"V112__flowable_history.sql"
			to ("history" to "org/flowable/db/create/flowable.mssql.create.history.sql"),
	"V113__flowable_identity.sql"
			to ("identity (IDM)" to "org/flowable/idm/db/create/flowable.mssql.create.identity.sql"),
	"V114__flowable_eventregistry.sql"
			to ("event registry" to "org/flowable/eventregistry/db/create/flowable.mssql.create.eventregistry.sql"),
)

tasks.register("extractFlowableSchema") {
	group = "orca"
	description = "Re-extracts Flowable's SQL Server DDL from the jars on the runtime classpath into db/migration."

	val classpath = configurations.named("runtimeClasspath")
	val migrations = layout.projectDirectory.dir("src/main/resources/db/migration").asFile
	val flowableVersion = libs.versions.flowable.get()

	doLast {
		val jars = classpath.get().files.filter { it.name.endsWith(".jar") }
		flowableSchemaScripts.forEach { (target, source) ->
			val (component, resource) = source
			val jar = jars.firstOrNull { candidate ->
				ZipFile(candidate).use { it.getEntry(resource) != null }
			} ?: throw GradleException(
				"Flowable resource is not on the runtime classpath: $resource. " +
					"Either the Flowable version changed its layout, or a module was dropped from the starter."
			)

			val body = ZipFile(jar).use { zip ->
				zip.getInputStream(zip.getEntry(resource)).readBytes().toString(Charsets.UTF_8)
			}

			// The header is DETERMINISTIC — no timestamp, no absolute path. Flyway
			// checksums the whole file, and a header that changed on every run would
			// break `validate-on-migrate` on every deployment.
			val header = """
				|-- Flowable $flowableVersion — $component, SQL Server.
				|--
				|-- EXTRACTED, NOT WRITTEN. Do not edit this file by hand.
				|--   source: ${jar.name}!/$resource
				|--   regenerate: ./gradlew :services:orca-runtime:extractFlowableSchema
				|--
				|-- The engine does not migrate itself (flowable.database-schema-update: false),
				|-- because §B7 requires a schema change to be compatible with both versions for
				|-- the duration of a rolling upgrade, and self-migration on whichever instance
				|-- starts first cannot offer that.
				|--
				|-- UPGRADING FLOWABLE: do NOT re-extract this file. It has shipped, and an
				|-- expand-only discipline (§B7) means a migration is never edited after it does.
				|-- Extract that version's UPGRADE STEP scripts — flowable.mssql.upgradestep.*.sql,
				|-- in the same jars — as NEW migrations, in the order Flowable applies them.
				|--
				""".trimMargin() + "\n"

			File(migrations, target).writeText(header + body)
			logger.lifecycle("extracted $resource from ${jar.name} -> $target")
		}
	}
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

	// springdoc was here and was REMOVED by review. Its UI cannot run with
	// api-docs disabled (SwaggerConfig is @ConditionalOnBean(SpringDocConfiguration),
	// which springdoc.api-docs.enabled=false switches off), and enabling api-docs
	// would introspect the code — a second source of truth beside the contract,
	// which §4b forbids. The brief's own fallback applies: serve the file
	// statically and drop springdoc. The bundled, fully resolved contract is
	// served at /openapi/orca-runtime.yaml by Spring's static-resource handling.

	// §3 of the brief permits these two for orca-runtime and nowhere else.
	implementation(libs.flowable.spring.boot.starter.process) // the process engine, embedded (ADR-006)
	implementation(libs.spring.boot.starter.websocket)        // mechanism 5 of §B4: console live updates

	// --- Tests ----------------------------------------------------------
	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(libs.spring.boot.starter.security.test)
	testImplementation(libs.spring.boot.starter.security.oauth2.resource.server.test)

	"integrationTestImplementation"(testFixtures(project(":platform:outbox")))
	"integrationTestImplementation"(libs.spring.boot.starter.test)
	"integrationTestImplementation"(libs.spring.boot.starter.webmvc.test)
}
