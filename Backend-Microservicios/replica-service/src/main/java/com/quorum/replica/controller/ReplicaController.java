package com.quorum.replica.controller;

import com.quorum.replica.domain.ReplicaStore;
import com.quorum.replica.domain.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ReplicaController (Adaptador de entrada / Driving Adapter — REST)
 * -------------------------------------------------------------------
 * Punto de entrada HTTP del microservicio réplica. Traduce peticiones REST
 * (GET /api/ping, POST /api/write, GET /api/read/{key}) en llamadas al
 * dominio (ReplicaStore). No contiene ninguna regla de negocio: eso vive
 * en com.quorum.replica.domain.
 *
 * Reemplaza al antiguo ReplicaNodeServer (que usaba ServerSocket + protocolo
 * de texto plano). La interfaz pública cambia de TCP a HTTP/JSON, pero la
 * lógica interna es idéntica.
 */
@RestController
@RequestMapping("/api")
public class ReplicaController {

    private static final Logger log = LoggerFactory.getLogger(ReplicaController.class);

    private final ReplicaStore store;

    public ReplicaController(ReplicaStore store) {
        this.store = store;
    }

    // ==================== GET /api/ping ====================
    // Equivalente TCP anterior: "PING" → "PONG|nodeId"
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of(
                "status", "PONG",
                "nodeId", store.getNodeId()
        ));
    }

    // ==================== POST /api/write ====================
    // Equivalente TCP anterior: "WRITE|key|value|ts" → "ACK|key|ts"
    @PostMapping("/write")
    public ResponseEntity<Map<String, Object>> write(@RequestBody WriteRequest request) {
        store.write(request.key(), request.value(), request.timestamp());
        StoredRecord current = store.read(request.key());
        log.info("WRITE -> {}={} (ts={})", request.key(),
                current != null ? current.value : request.value(),
                current != null ? current.timestamp : request.timestamp());

        return ResponseEntity.ok(Map.of(
                "status", "ACK",
                "key", request.key(),
                "timestamp", current != null ? current.timestamp : request.timestamp()
        ));
    }

    // ==================== GET /api/read/{key} ====================
    // Equivalente TCP anterior: "READ|key" → "VALUE|key|value|ts" o "NOTFOUND|key"
    @GetMapping("/read/{key}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String key) {
        StoredRecord rec = store.read(key);
        if (rec == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "NOTFOUND",
                    "key", key
            ));
        }
        return ResponseEntity.ok(Map.of(
                "status", "VALUE",
                "key", key,
                "value", rec.value,
                "timestamp", rec.timestamp
        ));
    }

    // ==================== DTO para el body de POST /api/write ====================
    public record WriteRequest(String key, int value, long timestamp) {}
}
