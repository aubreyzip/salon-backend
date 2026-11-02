package com.salon;

import org.springframework.boot.SpringApplication;

public class TestSalonBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(SalonBackendApplication::main)
			.with(TestcontainersConfiguration.class)
			.withAdditionalProfiles("local")
			.run(args);
	}

}
