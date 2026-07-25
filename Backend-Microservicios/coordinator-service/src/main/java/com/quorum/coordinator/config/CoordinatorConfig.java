package com.quorum.coordinator.config;

import com.quorum.coordinator.ai.OllamaAiAdapter;
import com.quorum.coordinator.domain.NodeHealth;
import com.quorum.coordinator.domain.port.AiAdvisorPort;
import com.quorum.coordinator.domain.port.ReplicaGateway;
import com.quorum.coordinator.gateway.HttpReplicaGateway;
import com.quorum.coordinator.service.QuorumCoordinatorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * CoordinatorConfig (Composition Root de Spring)
 * ------------------------------------------------
 * Equivalente al antiguo CoordinatorMain: lee la configuración y "cablea"
 * los adaptadores concretos (HttpReplicaGateway, OllamaAiAdapter) al caso
 * de uso (QuorumCoordinatorService) a través de sus puertos (ReplicaGateway,
 * AiAdvisorPort). En Spring Boot, esto se hace con @Bean en vez de con
 * "new" en el método main().
 *
 * Patrón: Composition Root / Dependency Injection (ahora gestionado por Spring).
 */
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties({
    CoordinatorConfig.QuorumProperties.class,
    CoordinatorConfig.HeartbeatProperties.class,
    CoordinatorConfig.CircuitBreakerProperties.class,
    CoordinatorConfig.AiProperties.class,
    CoordinatorConfig.SocketProperties.class
})
public class CoordinatorConfig {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorConfig.class);

    // --- Propiedades de configuración ---

    @ConfigurationProperties(prefix = "quorum")
    public record QuorumProperties(int N, int W, int R, List<NodeProperties> nodes) {
        public record NodeProperties(int id, String host, int port) {}
    }

    @ConfigurationProperties(prefix = "heartbeat")
    public record HeartbeatProperties(long intervalMs) {}

    @ConfigurationProperties(prefix = "circuit-breaker")
    public record CircuitBreakerProperties(int failThreshold, long cooldownMs) {}

    @ConfigurationProperties(prefix = "ai")
    public record AiProperties(boolean enabled, String ollamaUrl, String model,
                                long timeoutMs, long consultIntervalMs) {}

    @ConfigurationProperties(prefix = "socket")
    public record SocketProperties(int timeoutMs) {}

    // --- Construir los beans principales ---

    @Bean
    public AiAdvisorPort aiAdvisor(AiProperties ai) {
        return new OllamaAiAdapter(ai.enabled(), ai.ollamaUrl(), ai.model(), ai.timeoutMs());
    }

    @Bean
    public QuorumCoordinatorService coordinatorService(
            QuorumProperties quorum,
            CircuitBreakerProperties cb,
            SocketProperties socket,
            AiAdvisorPort aiAdvisor) {

        List<NodeHealth> healthByNode = new ArrayList<>();
        Map<Integer, ReplicaGateway> gateways = new LinkedHashMap<>();

        for (QuorumProperties.NodeProperties node : quorum.nodes()) {
            healthByNode.add(new NodeHealth(node.id(), node.host(), node.port()));
            gateways.put(node.id(), new HttpReplicaGateway(node.id(), node.host(), node.port(), socket.timeoutMs()));
            log.info("Réplica registrada: nodo {} en {}:{}", node.id(), node.host(), node.port());
        }

        if (healthByNode.size() < quorum.N()) {
            throw new IllegalStateException("Se configuraron " + healthByNode.size()
                    + " réplicas pero N=" + quorum.N());
        }

        return new QuorumCoordinatorService(
                healthByNode, gateways, quorum.W(), quorum.R(),
                cb.failThreshold(), cb.cooldownMs(), aiAdvisor);
    }

    /** Arranca el heartbeat y el asesor de IA al iniciar la aplicación. */
    @Bean
    public CommandLineRunner startBackgroundTasks(
            QuorumCoordinatorService service,
            HeartbeatProperties hb,
            AiProperties ai,
            QuorumProperties quorum) {
        return args -> {
            log.info("=== COORDINADOR iniciado | N={} W={} R={} ===", quorum.N(), quorum.W(), quorum.R());
            service.startHeartbeat(hb.intervalMs());
            service.startAiAdvisor(ai.consultIntervalMs());
        };
    }
}
