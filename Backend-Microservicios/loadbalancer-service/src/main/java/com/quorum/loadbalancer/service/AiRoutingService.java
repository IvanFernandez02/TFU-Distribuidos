package com.quorum.loadbalancer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class AiRoutingService {
    private static final Logger log = LoggerFactory.getLogger(AiRoutingService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final MetricsCollector metricsCollector;

    @Value("${loadbalancer.ollama.url}")
    private String ollamaUrl;

    @Value("${loadbalancer.ollama.model}")
    private String ollamaModel;

    public AiRoutingService(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    public MetricsCollector.CoordinatorMetrics chooseBestNode() {
        List<MetricsCollector.CoordinatorMetrics> healthyNodes = metricsCollector.getHealthyNodes();
        
        if (healthyNodes.isEmpty()) {
            log.error("No hay coordinadores disponibles (healthy)");
            return null;
        }

        if (healthyNodes.size() == 1) {
            return healthyNodes.get(0);
        }

        // Si Ollama está disponible, preguntarle
        MetricsCollector.CoordinatorMetrics aiChoice = askOllama(healthyNodes);
        if (aiChoice != null) {
            return aiChoice;
        }

        // Fallback: Least Connections
        return healthyNodes.stream()
                .min((n1, n2) -> Integer.compare(n1.activeConnections(), n2.activeConnections()))
                .orElse(healthyNodes.get(0));
    }

    private MetricsCollector.CoordinatorMetrics askOllama(List<MetricsCollector.CoordinatorMetrics> nodes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Eres un balanceador de carga. Elige el MEJOR servidor para la proxima solicitud.\n\nMetricas:\n");
        for (MetricsCollector.CoordinatorMetrics n : nodes) {
            prompt.append(String.format("- %s: CPU=%.1f%%, Latencia=%dms, Conexiones=%d\n",
                    n.name(), n.cpu(), n.latencyMs(), n.activeConnections()));
        }
        prompt.append("\nResponde SOLO con el nombre exacto del servidor (ej: Node-1).\n");

        try {
            Map<String, Object> request = Map.of(
                    "model", ollamaModel,
                    "prompt", prompt.toString(),
                    "stream", false,
                    "options", Map.of("temperature", 0.1, "num_predict", 20)
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(ollamaUrl + "/api/generate", request, Map.class);
            
            if (response != null && response.containsKey("response")) {
                String aiText = response.get("response").toString().trim();
                log.info("Decisión de Ollama: {}", aiText);
                
                for (MetricsCollector.CoordinatorMetrics n : nodes) {
                    if (aiText.contains(n.name())) {
                        return n;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error consultando a Ollama para balanceo: {}", e.getMessage());
        }
        return null;
    }
}
