// ORCA — root build.
//
// The root project is an AGGREGATOR. It holds no code, no `src/` and no bootable
// application. Spring Initializr generated one at the root; Package 1 removed it,
// because an eighth Spring Boot application that does nothing would claim the
// default port and mislead everyone who clones the repository.
//
// What the root owns: the Java toolchain, the version catalog, the repositories,
// and the shared test wiring. What it does not own: dependencies. Every module
// declares only its own.

plugins {
	jacoco
	alias(libs.plugins.spring.boot) apply false
	alias(libs.plugins.openapi.generator) apply false
}

// The root resolves nothing of its own except JaCoCo's reporting tool, which the
// aggregated coverage report needs.
repositories {
	mavenCentral()
}

// Type-safe `libs.` accessors are only generated for a project's own build script,
// so shared configuration reaches the catalog through the extension instead.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String) = findLibrary(alias).orElseThrow {
	IllegalStateException("No library '$alias' in the version catalog")
}

subprojects {
	group = "com.lynxis"
	version = "0.0.1-SNAPSHOT"

	apply(plugin = "java")
	apply(plugin = "jacoco")

	repositories {
		mavenCentral()
	}

	extensions.configure<JavaPluginExtension> {
		toolchain {
			languageVersion = JavaLanguageVersion.of(25)
		}
	}

	// Every module gets the Boot BOM as a platform, so no module names a version.
	dependencies {
		val bom = catalog.lib("spring-boot-dependencies")
		add("implementation", platform(bom))
		add("compileOnly", platform(bom))
		add("annotationProcessor", platform(bom))
		add("testImplementation", platform(bom))
		add("testCompileOnly", platform(bom))
		add("testAnnotationProcessor", platform(bom))

		// Lombok is in, and used consistently (brief §3).
		val lombok = catalog.lib("lombok")
		add("compileOnly", lombok)
		add("annotationProcessor", lombok)
		add("testCompileOnly", lombok)
		add("testAnnotationProcessor", lombok)

		add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
	}

	tasks.withType<JavaCompile>().configureEach {
		// Spring binds constructor and handler-method parameters by name.
		options.compilerArgs.add("-parameters")
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
		testLogging {
			events("failed")
			exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		}
	}

	// --- integrationTest -------------------------------------------------
	// A separate source set and a separate task, deliberately NOT wired into
	// `check`. `./gradlew build` must succeed on a clean machine with nothing
	// installed but a JDK (brief §7 item 1); integration tests need a Docker
	// daemon, so they run on their own command and in their own CI step.
	val sourceSets = extensions.getByType<SourceSetContainer>()
	val main = sourceSets["main"]
	val integrationTest: SourceSet = sourceSets.create("integrationTest") {
		compileClasspath += main.output
		runtimeClasspath += main.output
	}

	configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
	configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
	configurations["integrationTestCompileOnly"].extendsFrom(configurations["testCompileOnly"])
	configurations["integrationTestAnnotationProcessor"].extendsFrom(configurations["testAnnotationProcessor"])

	tasks.register<Test>("integrationTest") {
		description = "Runs integration tests against real infrastructure (Testcontainers)."
		group = "verification"
		testClassesDirs = integrationTest.output.classesDirs
		classpath = integrationTest.runtimeClasspath
		shouldRunAfter(tasks.named("test"))
	}
}

// --- Coverage ---------------------------------------------------------------
// One report over every module, because per-module numbers hide the module that
// has none. Reported, not thresholded: a coverage gate set before there is
// anything to cover is a number that gets lowered rather than met.
tasks.register<JacocoReport>("jacocoRootReport") {
	group = "verification"
	description = "Aggregates coverage across every module."

	val covered = subprojects.filter { it.plugins.hasPlugin("java") }
	dependsOn(covered.map { it.tasks.named("test") })

	// A module with no tests produces no exec file, and JaCoCo refuses to run over a
	// path that is not there. Filtering at configuration time is wrong (the file
	// does not exist yet) and at execution time is too late (the property is
	// final), so the set is resolved lazily instead.
	// EVERY exec file, not just test.exec. Most of what proves the primitives is in
	// integrationTest — the kill-mid-transaction case, the lease handover, the
	// concurrent idempotency race — and a coverage report that ignored them would
	// report the primitives as almost untested, which is the opposite of true.
	executionData.setFrom(project.files({
		covered.flatMap { module ->
			val dir = module.layout.buildDirectory.dir("jacoco").get().asFile
			(dir.listFiles { file -> file.name.endsWith(".exec") } ?: emptyArray()).toList()
		}
	}))
	sourceDirectories.setFrom(covered.map { it.layout.projectDirectory.dir("src/main/java") })

	// GENERATED code is excluded. Not to flatter the number: measuring coverage of
	// the OpenAPI generator's getters and equals() says nothing about this codebase,
	// and it says it loudly enough to drown out what the number is for.
	classDirectories.setFrom(covered.map { module ->
		module.fileTree(module.layout.buildDirectory.dir("classes/java/main")) {
			exclude("**/api/generated/**", "org/openapitools/**", "**/package-info.class")
		}
	})

	reports {
		xml.required = true
		html.required = true
	}
}
