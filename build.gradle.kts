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

// --- One integration suite at a time ----------------------------------------
//
// `org.gradle.parallel=true` runs every module's tasks concurrently, and each
// module's integrationTest starts its OWN SQL Server container: the shared
// fixture is a static singleton per JVM, and Gradle gives each module its own
// test JVM.
//
// Phase 0 had four such modules and it fit. Phase 1 added three more — core,
// runtime and edge — and `./gradlew integrationTest` began failing with
// "Container startup failed": seven SQL Server instances at once, emulated
// (the image is amd64-only), on a Docker VM with two CPUs. Each suite passed
// on its own, which is the worst version of this failure — it looks like a
// flaky test rather than a resource limit.
//
// A shared build service with one permit is Gradle's mechanism for exactly this:
// a resource that is machine-wide rather than per-project. Compilation and the
// unit tests stay parallel; only the container-bound suites queue.
abstract class ContainerBoundSuites : BuildService<BuildServiceParameters.None>

val containerBound = gradle.sharedServices.registerIfAbsent(
	"orcaContainerBoundSuites", ContainerBoundSuites::class) {
	maxParallelUsages = 1
}

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

		// One at a time across the whole build — see ContainerBoundSuites above.
		usesService(containerBound)
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

// ---------------------------------------------------------------------------
// sendPlate — the demo camera, cross-platform. Speaks the DERIVED-FROM-1X
// STX/ETX ZapPacket framing (docs/lpr-wire-format-from-1x.md) at orca-edge's
// listener, which runs on THIS host during local dev. A Gradle task rather than
// a container because it must reach a host process, and rather than a Python
// script because Gradle is already present on every developer's machine —
// including Windows, with nothing else installed.
//
//   ./gradlew sendPlate                       # T-DEMO-01 on LANE-DEMO-01
//   ./gradlew sendPlate -Pplate=T-RACE -Pport=9100
//   ./gradlew sendPlate -Pplate=T-D -PeventGuid=evt-fixed -Prepeat=2   # dedup
// ---------------------------------------------------------------------------
tasks.register("sendPlate") {
	group = "orca demo"
	description = "Send a plate read to orca-edge's LPR listener (the camera's wire format)."
	doLast {
		val host = (project.findProperty("host") as String?) ?: "localhost"
		val port = ((project.findProperty("port") as String?) ?: "9100").toInt()
		val lane = (project.findProperty("lane") as String?) ?: "LANE-DEMO-01"
		val plate = (project.findProperty("plate") as String?) ?: "T-DEMO-01"
		val camera = (project.findProperty("camera") as String?) ?: "DEV-DEMO-CAMERA"
		val confidence = (project.findProperty("confidence") as String?) ?: "0.94"
		val repeat = ((project.findProperty("repeat") as String?) ?: "1").toInt()
		val eventGuid = (project.findProperty("eventGuid") as String?)
			?: "evt-${java.util.UUID.randomUUID()}"

		// Two plate hypotheses; the listener must take the HIGHER-confidence second
		// one. A reader that took the first would pass every single-plate test and
		// put the wrong truck through the gate.
		val body = ("""<ZapPacket Type="MSG" Id="pkt-$eventGuid" Version="4.4" """ +
			"""SenderId="$camera" SenderName="$camera"><Event>""" +
			"""<EventId>1</EventId><EventGuid>$eventGuid</EventGuid>""" +
			"""<Online>true</Online><TimeStamp>2026-08-07T09:00:00</TimeStamp>""" +
			"""<LaneId>$lane</LaneId><LaneName>$lane</LaneName>""" +
			"""<LP><AutoLPR>WRONG-1</AutoLPR><Confidence>0.41</Confidence></LP>""" +
			"""<LP><AutoLPR>$plate</AutoLPR><Confidence>$confidence</Confidence>""" +
			"""<CharConfidence>0.93</CharConfidence>""" +
			"""<LPRImage TIN="1" CameraId="$camera"><Path>/var/lpr/demo.jpg</Path></LPRImage>""" +
			"""</LP></Event></ZapPacket>""").toByteArray(Charsets.UTF_8)
		val frame = byteArrayOf(0x02) + body + byteArrayOf(0x03)

		println("→ lane $lane, plate $plate, EventGuid $eventGuid")
		java.net.Socket().use { sock ->
			sock.connect(java.net.InetSocketAddress(host, port), 10_000)
			sock.soTimeout = 10_000
			repeat(repeat) { sock.getOutputStream().write(frame) }
			sock.getOutputStream().flush()
			val buf = java.io.ByteArrayOutputStream()
			val input = sock.getInputStream()
			var started = false
			while (true) {
				val b = input.read()
				if (b == -1) break
				if (b == 0x02) { started = true; buf.reset() }
				else if (b == 0x03 && started) break
				else if (started) buf.write(b)
			}
			val ack = buf.toString("UTF-8")
			if (ack.isNotEmpty()) println("← $ack") else println("← (no acknowledgement)")
		}
	}
}
