package com.quorum.loadbalancer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${loadbalancer.coordinators[0].url}")
    private String coord1Url;

    @Value("${loadbalancer.coordinators[1].url}")
    private String coord2Url;

    // Almacena el estado más reciente de cada coordinador
    private final Map<String, CoordinatorMetrics> metricsMap = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 5000) // Cada 5 segundos
    public void collectMetrics() {
        updateMetrics("Node-1", coord1Url);
        updateMetrics("Node-2", coord2Url);
        log.debug("Métricas actualizadas: {}", metricsMap);
    }

    private void updateMetrics(String nodeName, String baseUrl) {
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    baseUrl + "/api/metrics",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );

            long latency = System.currentTimeMillis() - start;
            Map<String, Object> body = response.getBody();
            
            if (response.getStatusCode().is2xxSuccessful() && body != null) {
                int activeConns = (Integer) body.getOrDefault("active_connections", 0);
                double cpu = Double.parseDouble(body.getOrDefault("cpu_usage", "0").toString());
                
                metricsMap.put(nodeName, new CoordinatorMetrics(
                        nodeName, baseUrl, "healthy", cpu, latency, activeConns
                ));
            } else {
                metricsMap.put(nodeName, new CoordinatorMetrics(
                        nodeName, baseUrl, "degraded", 100.0, latency, 0
                ));
            }
        } catch (Exception e) {
            metricsMap.put(nodeName, new CoordinatorMetrics(
                    nodeName, baseUrl, "down", 100.0, 5000, 0
            ));
        }
    }

    public List<CoordinatorMetrics> getHealthyNodes() {
        return metricsMap.values().stream()
                .filter(m -> "healthy".equals(m.status()))
                .toList();
    }

    public List<CoordinatorMetrics> getAllNodes() {
        return new ArrayList<>(metricsMap.values());
    }

    public record CoordinatorMetrics(
            String name, String url, String status, double cpu, long latencyMs, int activeConnections
    ) {}
}
