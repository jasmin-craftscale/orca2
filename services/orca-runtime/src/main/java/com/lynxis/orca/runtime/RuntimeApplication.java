package com.lynxis.orca.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * orca-runtime — the gate brain (§C2).
 *
 * <p>Its own entry point, its own configuration, its own image. One of six
 * bootable applications in this repository; the root project holds none.
 *
 * <p>Phase 0 gives this service a skeleton, a health endpoint and its module
 * boundaries. There is deliberately no business logic here.
 */
@SpringBootApplication
public class RuntimeApplication {

	public static void main(String[] args) {
		SpringApplication.run(RuntimeApplication.class, args);
	}
}
