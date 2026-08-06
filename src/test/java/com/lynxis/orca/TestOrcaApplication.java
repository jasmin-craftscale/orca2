package com.lynxis.orca;

import org.springframework.boot.SpringApplication;

public class TestOrcaApplication {

	public static void main(String[] args) {
		SpringApplication.from(OrcaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
