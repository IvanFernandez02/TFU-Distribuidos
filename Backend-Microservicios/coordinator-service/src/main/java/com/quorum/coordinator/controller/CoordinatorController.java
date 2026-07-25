package com.quorum.coordinator.controller;

import com.quorum.coordinator.service.QuorumCoordinatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CoordinatorController (Adaptador de entrada / Driving Adapter — REST)
 * -----------------------------------------------------------------------
 * Punto de entrada HTTP para los clientes. Traduce peticiones REST
 * (POST /api/like, GET /api/get/{postId}, GET /api/status, POST /api/burst)
 * en llamadas a los métodos públicos de QuorumCoordinatorService (la capa
 * de aplicación). No contiene lógica de cuórum, heartbeat ni circuit breaker.
 *
 * Reemplaza al antiguo CoordinatorTcpServer que usaba ServerSocket + protocolo
 * de texto plano.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CoordinatorController {

    private final QuorumCoordinatorService service;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public CoordinatorController(QuorumCoordinatorService service) {
        this.service = service;
    }

    // ==================== POST /api/like ====================
    @PostMapping("/like")
    public ResponseEntity<Map<String, Object>> like(@RequestBody(required = false) LikeRequest request) {
        activeRequests.incrementAndGet();
        try {
            QuorumCoordinatorService.LikeResult r = service.like("global_likes");
            if (r.ok) {
                return ResponseEntity.ok(Map.of(
                        "ok", true,
                        "newValue", r.newValue,
                        "targetNodeId", r.targetNodeId,
                        "targetNodeName", r.targetNodeName,
                        "message", "Like enviado al " + r.targetNodeName.toLowerCase()
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "ok", false,
                        "reason", r.reason,
                        "message", r.reason
                ));
            }
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    // ==================== GET /api/get ====================
    @GetMapping({"/get", "/get/{postId}"})
    public ResponseEntity<Map<String, Object>> get() {
        QuorumCoordinatorService.GetResult r = service.get("global_likes");
        if (r.ok) {
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "value", r.value,
                    "timestamp", r.timestamp,
                    "breakdown", r.breakdown
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "ok", false,
                    "reason", r.reason,
                    "message", r.reason
            ));
        }
    }

    // ==================== GET /api/status ====================
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> statusData = service.status();
        statusData.put("ok", true);
        return ResponseEntity.ok(statusData);
    }

    // ==================== POST /api/burst ====================
    @PostMapping("/burst")
    public ResponseEntity<Map<String, Object>> burst(@RequestBody(required = false) BurstRequest request) {
        int count = (request != null && request.count() != null && request.count() > 0) ? request.count() : 100;
        int concurrency = (request != null && request.concurrency() != null && request.concurrency() > 0) ? request.concurrency() : 10;

        AtomicInteger okCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        java.util.concurrent.ConcurrentHashMap<String, Integer> distMap = new java.util.concurrent.ConcurrentHashMap<>();
        for (int i = 1; i <= 3; i++) distMap.put("Nodo " + i, 0);
        
        final String[] lastFailReason = new String[1];

        CompletableFuture<?>[] futures = new CompletableFuture[count];
        var semaphore = new java.util.concurrent.Semaphore(concurrency);

        for (int i = 0; i < count; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    QuorumCoordinatorService.LikeResult r = service.like("global_likes");
                    if (r.ok) {
                        okCount.incrementAndGet();
                        distMap.compute(r.targetNodeName, (k, v) -> (v == null) ? 1 : v + 1);
                    } else {
                        failCount.incrementAndGet();
                        lastFailReason[0] = r.reason;
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    semaphore.release();
                }
            }, service.getPool());
        }
        CompletableFuture.allOf(futures).join();

        QuorumCoordinatorService.GetResult getRes = service.get("global_likes");
        int total = getRes.ok ? getRes.value : 0;
        
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", okCount.get() > 0);
        resp.put("count", count);
        resp.put("success", okCount.get());
        resp.put("fail", failCount.get());
        resp.put("distribution", new LinkedHashMap<>(distMap));
        resp.put("total", total);
        if (failCount.get() > 0 && okCount.get() == 0) {
            resp.put("reason", lastFailReason[0] != null ? lastFailReason[0] : "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente");
            resp.put("message", lastFailReason[0] != null ? lastFailReason[0] : "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente");
        }
        return ResponseEntity.ok(resp);
    }

    // ==================== GET /api/metrics ====================
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        return ResponseEntity.ok(Map.of(
                "active_connections", activeRequests.get(),
                "status", "healthy",
                "cpu_usage", 10 + (activeRequests.get() * 2),
                "ram_usage", 45.0,
                "timestamp", System.currentTimeMillis()
        ));
    }

    // ==================== DTOs ====================
    public record LikeRequest(String postId) {}
    public record BurstRequest(Integer count, Integer concurrency) {}
}
