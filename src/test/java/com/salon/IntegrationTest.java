package com.salon;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context against PostgreSQL in Testcontainers, with MockMvc, recording
 * email/SMS gateways, the {@link TestApi} client and {@link TestBlockingAppointments} (stand-in
 * appointments for schedule-change conflicts until booking exists). All tests using it share one cached context
 * and database container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, RecordingMessagingConfiguration.class, TestApiConfiguration.class,
		TestBlockingAppointments.class })
public @interface IntegrationTest {

}
