package com.lynxis.orca.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts orca-runtime, the gate's process-execution service.
 *
 * <p>Its own entry point, its own configuration, its own image. One of six
 * bootable applications in this repository; the root project holds none.
 *
 * <p>This entry point deliberately contains no business logic. The execution,
 * work-item and integration packages hold it behind their enforced module walls.
 */
@SpringBootApplication
public class RuntimeApplication {

	public static void main(String[] args) {
		SpringApplication.run(RuntimeApplication.class, args);
	}
}
