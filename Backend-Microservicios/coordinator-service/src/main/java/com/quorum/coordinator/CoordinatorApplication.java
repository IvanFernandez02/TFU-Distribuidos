package com.quorum.coordinator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CoordinatorApplication (Composition Root del microservicio coordinador)
 * -----------------------------------------------------------------------
 * Punto de entrada Spring Boot para el coordinador de quórum. Arranca el
 * heartbeat, el circuit breaker y el asesor de IA automáticamente al
 * iniciar la aplicación (ver CoordinatorConfig).
 *
 *   java -jar coordinator-service.jar
 */
@SpringBootApplication
public class CoordinatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoordinatorApplication.class, args);
    }
}
