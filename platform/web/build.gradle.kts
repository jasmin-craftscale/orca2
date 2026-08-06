// P5 · Web envelope and system context.
//
// One response shape for every service, machine-readable error codes, no internal
// detail on any path — and an explicit identity for every entry point no user
// invoked (§B6, §D3 "System context").
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
	// The default filter chain lives here rather than six times over: §B6 states
	// one platform-wide property ("every service validates tokens by signature
	// locally"), and six copies is six chances for one to differ.
	api(libs.spring.boot.starter.security.oauth2.resource.server)

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(libs.spring.boot.starter.validation.test)
	testImplementation(libs.spring.boot.starter.security.test)
	testImplementation(libs.spring.boot.starter.security.oauth2.resource.server.test)
}
