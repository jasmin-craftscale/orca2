package com.lynxis.orca.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * orca-edge — the hardware boundary.
 *
 * <p>Its own entry point, its own configuration, its own image. One of six
 * bootable applications in this repository; the root project holds none.
 *
 * <p>The foundation supplied this service's skeleton, health endpoint and module
 * boundaries. There is deliberately no business logic here.
 */
@SpringBootApplication
public class EdgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(EdgeApplication.class, args);
	}
}
