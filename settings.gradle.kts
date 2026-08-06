pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
}

plugins {
	// Resolves and downloads the Java 25 toolchain when the machine does not already have one,
	// so a clean clone builds with nothing installed but a JDK (Phase 0 brief, §7 item 1).
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "orca"

// Twelve modules. The root is an aggregator and holds no code.
// `services/orca-media` is a README placeholder and is deliberately NOT a Gradle module.

include(
	":platform:outbox",
	":platform:lease",
	":platform:scope",
	":platform:idempotency",
	":platform:web",
)

include(
	":services:orca-core",
	":services:orca-runtime",
	":services:orca-edge",
	":services:orca-portal",
	":services:orca-sync",
	":services:orca-fleet",
)

include(":build-checks")
