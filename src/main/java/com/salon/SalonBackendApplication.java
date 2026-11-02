package com.salon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SalonBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(SalonBackendApplication.class, args);
	}

}
