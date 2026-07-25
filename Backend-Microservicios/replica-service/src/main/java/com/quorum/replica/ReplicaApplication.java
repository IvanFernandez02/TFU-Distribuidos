package com.quorum.replica;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ReplicaApplication (Composition Root del microservicio réplica)
 * ----------------------------------------------------------------
 * Punto de entrada Spring Boot para un nodo réplica. Cada instancia
 * se diferencia por su puerto (server.port) y su ID (replica.node-id),
 * configurables en application.yml o por línea de comandos:
 *
 *   java -jar replica-service.jar --server.port=6002 --replica.node-id=2
 */
@SpringBootApplication
public class ReplicaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReplicaApplication.class, args);
    }
}
