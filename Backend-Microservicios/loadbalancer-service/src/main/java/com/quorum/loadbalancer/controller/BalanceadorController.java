package com.quorum.loadbalancer.controller;

import com.quorum.loadbalancer.service.AiRoutingService;
import com.quorum.loadbalancer.service.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class BalanceadorController {

    private static final Logger log = LoggerFactory.getLogger(BalanceadorController.class);
    private final AiRoutingService routingService;
    private final RestTemplate restTemplate = new RestTemplate();

    public BalanceadorController(AiRoutingService routingService) {
        this.routingService = routingService;
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<?> proxyRequest(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        MetricsCollector.CoordinatorMetrics targetNode = routingService.chooseBestNode();

        if (targetNode == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "ok", false,
                    "reason", "No hay coordinadores disponibles para balancear la carga"
            ));
        }

        String targetUrl = targetNode.url() + request.getRequestURI();
        if (request.getQueryString() != null) {
            targetUrl += "?" + request.getQueryString();
        }

        log.info("Balanceando petición {} hacia -> {}", request.getRequestURI(), targetNode.name());

        try {
            // Reenviar Headers
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.add("Content-Type", request.getContentType() != null ? request.getContentType() : "application/json");

            HttpEntity<byte[]> httpEntity = new HttpEntity<>(body, headers);

            // Reenviar Petición
            return restTemplate.exchange(
                    targetUrl,
                    HttpMethod.valueOf(request.getMethod()),
                    httpEntity,
                    Object.class
            );
        } catch (Exception e) {
            log.error("Error al reenviar petición al coordinador {}: {}", targetNode.name(), e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "ok", false,
                    "reason", "Bad Gateway: El coordinador falló al procesar la petición"
            ));
        }
    }
}
