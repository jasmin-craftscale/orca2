// Recoverable values sealed with purpose-bound authenticated encryption.
// Code only: each consumer owns its credential rows in its own schema.

plugins {
	`java-library`
}

dependencies {
	api(libs.spring.boot.starter)
	testImplementation(libs.spring.boot.starter.test)
}
