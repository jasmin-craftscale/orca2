package com.lynxis.orca.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts orca-core, the service that owns the installation's configured world.
 *
 * <p>Its own entry point, its own configuration, its own image. One of six
 * bootable applications in this repository; the root project holds none.
 *
 * <p>This entry point deliberately contains no business logic. Configuration and
 * domain behavior live in the service's other packages so bootstrapping remains a
 * conventional Spring application boundary.
 */
@SpringBootApplication
public class CoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoreApplication.class, args);
	}
}
