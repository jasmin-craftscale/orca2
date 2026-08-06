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
	alias(libs.plugins.spring.boot) apply false
	alias(libs.plugins.openapi.generator) apply false
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
