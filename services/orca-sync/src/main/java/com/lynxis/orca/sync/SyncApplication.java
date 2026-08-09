package com.lynxis.orca.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * orca-sync — replication.
 *
 * <p>Its own entry point, its own configuration, its own image. One of six
 * bootable applications in this repository; the root project holds none.
 *
 * <p>The current on-site scope gives this service a skeleton, a health endpoint and its module
 * boundaries. There is deliberately no business logic here.
 */
@SpringBootApplication
public class SyncApplication {

	public static void main(String[] args) {
		SpringApplication.run(SyncApplication.class, args);
	}
}
