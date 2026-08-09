// One response shape for every service, machine-readable error codes, no internal
// detail on any path — and an explicit identity for every entry point no user
// invoked. The envelope makes failures safe to consume; the system context makes
// unattended work attributable and authorisable.
//
// It also owns `src/main/resources/openapi/_shared.yaml`: the envelope, the error
// object and pagination are DEFINED by this module and IMPLEMENTED by it. Every
// service contract $refs that one file.

plugins {
	`java-library`
}

dependencies {
	api(libs.spring.boot.starter)
	api(libs.spring.boot.starter.webmvc)
	api(libs.spring.boot.starter.validation)
	// The default filter chain lives here rather than six times over because every
	// service must validate tokens locally by signature. Six copies would create
	// six chances for that platform-wide security property to differ.
	api(libs.spring.boot.starter.security.oauth2.resource.server)

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(libs.spring.boot.starter.validation.test)
	testImplementation(libs.spring.boot.starter.security.test)
	testImplementation(libs.spring.boot.starter.security.oauth2.resource.server.test)
}
