package com.quorum.replica.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * FailSimulator — Simula la caída de un nodo réplica después de N segundos.
 *
 * Uso: java -jar replica-service.jar --replica.fail-after-seconds=15
 *
 * Equivalente al parámetro --fail-after del ReplicaNodeServer original.
 * Pasado el tiempo, cierra la aplicación Spring Boot (simulando un crash).
 */
@Component
public class FailSimulator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FailSimulator.class);

    @Value("${replica.fail-after-seconds:0}")
    private int failAfterSeconds;

    @Value("${replica.node-id}")
    private int nodeId;

    private final ConfigurableApplicationContext context;

    public FailSimulator(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(String... args) {
        if (failAfterSeconds > 0) {
            log.warn("[NODO-{}] Simulación de fallo programada en {} segundos.", nodeId, failAfterSeconds);
            Thread failThread = new Thread(() -> {
                try {
                    Thread.sleep(failAfterSeconds * 1000L);
                    log.error("[NODO-{}] >>> SIMULACION DE FALLO: cerrando la aplicación (caída forzada) <<<", nodeId);
                    context.close();
                } catch (InterruptedException ignored) {}
            }, "fail-simulator-thread");
            failThread.setDaemon(true);
            failThread.start();
        }
    }
}
