package com.lynxis.orca.fleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * orca-fleet — licences and releases.
 *
 * <p>Its own entry point, its own configuration, its own image. One of six
 * bootable applications in this repository; the root project holds none.
 *
 * <p>The current on-site scope gives this service a skeleton, a health endpoint and its module
 * boundaries. There is deliberately no business logic here.
 */
@SpringBootApplication
public class FleetApplication {

	public static void main(String[] args) {
		SpringApplication.run(FleetApplication.class, args);
	}
}
