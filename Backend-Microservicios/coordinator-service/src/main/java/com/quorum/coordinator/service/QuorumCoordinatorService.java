package com.quorum.coordinator.service;

import com.quorum.coordinator.domain.AiDecision;
import com.quorum.coordinator.domain.NodeHealth;
import com.quorum.coordinator.domain.port.AiAdvisorPort;
import com.quorum.coordinator.domain.port.ReplicaGateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QuorumCoordinatorService (Caso de uso / Servicio de aplicación)
 * ---------------------------------------------------------------
 * Este es el corazón del sistema: implementa el algoritmo de cuórum de
 * Gifford, el heartbeat, el circuit breaker y la integración con el
 * asesor de IA. A propósito NO abre ningún ServerSocket ni sabe nada del
 * protocolo de red: solo conoce los puertos ReplicaGateway y AiAdvisorPort
 * (interfaces de dominio).
 *
 * Clase reutilizada del proyecto original. Cambios respecto al original:
 *  - Paquete cambiado a com.quorum.coordinator.service
 *  - Se usa SLF4J Logger en vez de PrintWriter para logging
 *  - Se eliminó la dependencia al PrintWriter del log file
 *  - Anotación @Service no se pone aquí sino que se registra como @Bean
 *    en CoordinatorConfig para poder inyectar parámetros de configuración.
 *
 * Patrones de diseño aplicados:
 *  - Mediator: esta clase es el único punto que conoce a las N réplicas.
 *  - Circuit Breaker (Nygard): máquina de estados por nodo en updateHealth().
 *  - Strategy (implícita): ReplicaGateway y AiAdvisorPort permiten
 *    intercambiar la implementación sin cambiar esta clase.
 */
public class QuorumCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(QuorumCoordinatorService.class);

    private final List<NodeHealth> healthByNode;
    private final Map<Integer, ReplicaGateway> gateways;
    private final int N, W, R;
    private final int cbFailThreshold;
    private final long cbCooldownMs;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    public ExecutorService getPool() { return pool; }
    private final AtomicLong logicalClock = new AtomicLong(0);
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger roundRobinCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    private final AiAdvisorPort aiAdvisor;
    private volatile AiDecision lastAiDecision = null;

    public QuorumCoordinatorService(List<NodeHealth> healthByNode, Map<Integer, ReplicaGateway> gateways,
                                     int W, int R, int cbFailThreshold, long cbCooldownMs,
                                     AiAdvisorPort aiAdvisor) {
        this.healthByNode = healthByNode;
        this.gateways = gateways;
        this.N = healthByNode.size();
        this.W = W;
        this.R = R;
        this.cbFailThreshold = cbFailThreshold;
        this.cbCooldownMs = cbCooldownMs;
        this.aiAdvisor = aiAdvisor;
    }

    // ------------------------------------------------------------------
    // CASOS DE USO PÚBLICOS (llamados por el CoordinatorController REST)
    // ------------------------------------------------------------------

    public static class LikeResult {
        public final boolean ok; public final int newValue; public final String reason;
        public final int targetNodeId; public final String targetNodeName;
        public LikeResult(boolean ok, int newValue, String reason, int targetNodeId, String targetNodeName) {
            this.ok = ok; this.newValue = newValue; this.reason = reason;
            this.targetNodeId = targetNodeId; this.targetNodeName = targetNodeName;
        }
    }

    public LikeResult like(String postId) {
        String key = "global_likes";
        List<NodeHealth> avail = availableNodes();
        if (avail.size() < W) {
            return new LikeResult(false, 0, "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente", 0, "N/A");
        }
        
        List<NodeHealth> targets = selectTargets(1);
        if (targets.isEmpty()) targets = avail;
        int idx = Math.abs(roundRobinCounter.getAndIncrement()) % targets.size();
        NodeHealth targetNode = targets.get(idx);

        Object lock = keyLocks.computeIfAbsent("node_lock_" + targetNode.id, k -> new Object());
        synchronized (lock) {
            if (availableNodes().size() < W) {
                return new LikeResult(false, 0, "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente", 0, "N/A");
            }
            ReplicaGateway gw = gateways.get(targetNode.id);
            String[] current = gw.read(key);
            int currentValue = (current == null) ? 0 : Integer.parseInt(current[0]);
            int newValue = currentValue + 1;
            long ts = logicalClock.incrementAndGet();
            
            boolean ok = gw.write(key, newValue, ts);
            if (!ok) {
                return new LikeResult(false, currentValue, "Fallo al escribir en " + targetNode.address(), targetNode.id, "Nodo " + targetNode.id);
            }
            return new LikeResult(true, newValue, null, targetNode.id, "Nodo " + targetNode.id);
        }
    }

    public static class GetResult {
        public final boolean ok; public final int value; public final long timestamp; public final String reason;
        public final Map<String, Integer> breakdown;
        public GetResult(boolean ok, int value, long timestamp, String reason, Map<String, Integer> breakdown) {
            this.ok = ok; this.value = value; this.timestamp = timestamp; this.reason = reason;
            this.breakdown = breakdown != null ? breakdown : Map.of();
        }
    }

    public GetResult get(String postId) {
        String key = "global_likes";
        List<NodeHealth> avail = availableNodes();
        if (avail.size() < R) {
            return new GetResult(false, 0, 0, "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente", Map.of());
        }
        
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        int totalSum = 0;
        long maxTs = 0;
        
        for (NodeHealth n : avail) {
            ReplicaGateway gw = gateways.get(n.id);
            String[] r = gw.read(key);
            int val = (r == null) ? 0 : Integer.parseInt(r[0]);
            long ts = (r == null) ? 0 : Long.parseLong(r[1]);
            breakdown.put("Nodo " + n.id, val);
            totalSum += val;
            if (ts > maxTs) maxTs = ts;
        }
        
        return new GetResult(true, totalSum, maxTs, null, breakdown);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        Map<String, Object> nodes = new LinkedHashMap<>();
        int sumOfLikes = 0;
        
        for (NodeHealth n : healthByNode) {
            Map<String, Object> nodeInfo = new LinkedHashMap<>();
            nodeInfo.put("id", n.id);
            nodeInfo.put("host", n.host);
            nodeInfo.put("port", n.port);
            nodeInfo.put("state", n.state.name());
            nodeInfo.put("alive", n.alive);
            nodeInfo.put("consecutiveFailures", n.consecutiveFailures);
            
            int val = 0;
            if (n.isAvailable()) {
                try {
                    String[] r = gateways.get(n.id).read("global_likes");
                    val = (r == null) ? 0 : Integer.parseInt(r[0]);
                } catch (Exception ignored) {}
            }
            nodeInfo.put("likesCount", val);
            sumOfLikes += val;
            
            nodes.put("node" + n.id, nodeInfo);
            nodes.put(String.valueOf(n.id), nodeInfo);
        }
        
        status.put("nodes", nodes);
        status.put("totalLikes", sumOfLikes);
        status.put("aiEnabled", aiAdvisor.isEnabled());
        AiDecision d = lastAiDecision;
        status.put("aiLastPriority", d == null ? "N/A" : d.priorityNodeIds.toString());
        status.put("quorum", Map.of("N", N, "W", W, "R", R));
        boolean sysOk = availableNodes().size() >= W;
        status.put("systemOk", sysOk);
        if (!sysOk) {
            status.put("systemMessage", "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente");
        }
        return status;
    }

    // ------------------------------------------------------------------
    // CUÓRUM (algoritmo de Gifford)
    // ------------------------------------------------------------------

    private String[] quorumRead(String key) {
        List<NodeHealth> targets = selectTargets(R);
        List<Future<String[]>> futures = new ArrayList<>();
        for (NodeHealth node : targets) {
            ReplicaGateway gw = gateways.get(node.id);
            futures.add(pool.submit(() -> gw.read(key)));
        }
        List<String[]> responses = new ArrayList<>();
        for (Future<String[]> f : futures) {
            try {
                String[] r = f.get(2000, TimeUnit.MILLISECONDS);
                if (r != null) responses.add(r);
            } catch (Exception ignored) {}
        }
        log.info("READ-QUORUM key={} -> respuestas={}/{} (se requieren R={}, nodos disponibles={}/{})",
                key, responses.size(), targets.size(), R, targets.size(), N);
        if (responses.size() < R) return null;

        String[] best = null;
        for (String[] r : responses) {
            if (best == null || Long.parseLong(r[1]) > Long.parseLong(best[1])) best = r;
        }
        return best;
    }

    private boolean quorumWrite(String key, int value, long timestamp) {
        List<NodeHealth> targets = selectTargets(W);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (NodeHealth node : targets) {
            ReplicaGateway gw = gateways.get(node.id);
            futures.add(pool.submit(() -> gw.write(key, value, timestamp)));
        }
        int acks = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (Boolean.TRUE.equals(f.get(2000, TimeUnit.MILLISECONDS))) acks++;
            } catch (Exception ignored) {}
        }
        log.info("WRITE-QUORUM key={} value={} ts={} -> ACKs={}/{} (se requieren W={}, nodos disponibles={}/{})",
                key, value, timestamp, acks, targets.size(), W, targets.size(), N);
        return acks >= W;
    }

    private List<NodeHealth> availableNodes() {
        List<NodeHealth> avail = new ArrayList<>();
        for (NodeHealth n : healthByNode) if (n.isAvailable()) avail.add(n);
        return avail;
    }

    /** Enrutamiento: prioriza la recomendación de la IA si alcanza el mínimo requerido. */
    private List<NodeHealth> selectTargets(int minRequired) {
        List<NodeHealth> available = availableNodes();
        AiDecision decision = lastAiDecision;
        if (decision == null || decision.priorityNodeIds.isEmpty()) return available;

        List<NodeHealth> prioritized = new ArrayList<>();
        for (NodeHealth n : available) if (decision.priorityNodeIds.contains(n.id)) prioritized.add(n);
        return prioritized.size() >= minRequired ? prioritized : available;
    }

    // ------------------------------------------------------------------
    // HEARTBEAT + CIRCUIT BREAKER
    // ------------------------------------------------------------------

    public void startHeartbeat(long intervalMs) {
        Thread hb = new Thread(() -> {
            while (true) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (NodeHealth node : healthByNode) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        boolean ok = gateways.get(node.id).ping();
                        updateHealth(node, ok);
                    }, pool));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                try { Thread.sleep(intervalMs); } catch (InterruptedException ignored) {}
            }
        }, "heartbeat-thread");
        hb.setDaemon(true);
        hb.start();
        log.info("Heartbeat iniciado (intervalo={}ms, umbral circuit breaker={})", intervalMs, cbFailThreshold);
    }

    private synchronized void updateHealth(NodeHealth node, boolean ok) {
        boolean wasAlive = node.alive;
        node.alive = ok;
        if (ok) {
            node.consecutiveFailures = 0;
            if (node.state != NodeHealth.CircuitState.CLOSED) {
                node.state = NodeHealth.CircuitState.CLOSED;
                log.info("CIRCUIT BREAKER: nodo {} ({}) -> CLOSED (recuperado)", node.id, node.address());
            }
            if (!wasAlive) log.info("HEARTBEAT: nodo {} respondió PONG (UP)", node.id);
        } else {
            node.consecutiveFailures++;
            if (wasAlive) log.warn("HEARTBEAT: nodo {} ({}) no respondió (posible caída)", node.id, node.address());
            if (node.state == NodeHealth.CircuitState.CLOSED && node.consecutiveFailures >= cbFailThreshold) {
                node.state = NodeHealth.CircuitState.OPEN;
                node.openedAt = System.currentTimeMillis();
                log.warn("CIRCUIT BREAKER: nodo {} ({}) -> OPEN tras {} fallos consecutivos",
                        node.id, node.address(), node.consecutiveFailures);
            } else if (node.state == NodeHealth.CircuitState.OPEN
                    && System.currentTimeMillis() - node.openedAt > cbCooldownMs) {
                node.state = NodeHealth.CircuitState.HALF_OPEN;
                log.info("CIRCUIT BREAKER: nodo {} -> HALF_OPEN (reintentando tras cooldown)", node.id);
            }
        }
    }

    // ------------------------------------------------------------------
    // ASESOR DE IA (opcional, con salvaguarda determinista)
    // ------------------------------------------------------------------

    public void startAiAdvisor(long intervalMs) {
        if (!aiAdvisor.isEnabled()) {
            log.info("Asesor de IA deshabilitado; modo 100% determinista.");
            return;
        }
        Thread ai = new Thread(() -> {
            while (true) {
                AiDecision decision = aiAdvisor.consult(healthByNode, W, R);
                if (decision != null) {
                    lastAiDecision = decision;
                    log.info("Asesor de IA: PRIORIDAD={} BREAKER={}", decision.priorityNodeIds, decision.breakerRecommendation);
                    applyAiSafetyGuard(decision);
                } else {
                    log.info("Asesor de IA no disponible en esta ronda -> usando solo lógica determinista");
                }
                try { Thread.sleep(intervalMs); } catch (InterruptedException ignored) {}
            }
        }, "ai-advisor-thread");
        ai.setDaemon(true);
        ai.start();
        log.info("Asesor de IA iniciado (intervalo={}ms)", intervalMs);
    }

    /** Salvaguarda determinista: la IA nunca puede cerrar un nodo cuyo heartbeat real dice que está caído, ni abrir uno 100% sano. */
    private synchronized void applyAiSafetyGuard(AiDecision decision) {
        for (NodeHealth node : healthByNode) {
            String rec = decision.breakerRecommendation.get(node.id);
            if (rec == null) continue;
            if (rec.equals("OPEN") && node.state == NodeHealth.CircuitState.CLOSED && (!node.alive || node.consecutiveFailures > 0)) {
                node.state = NodeHealth.CircuitState.OPEN;
                node.openedAt = System.currentTimeMillis();
                log.info("CIRCUIT BREAKER (recomendado por IA): nodo {} -> OPEN de forma proactiva (fallos={})", node.id, node.consecutiveFailures);
            } else if (rec.equals("CLOSED") && !node.alive) {
                log.warn("Se IGNORA recomendación de IA (CLOSED para nodo {}) por salvaguarda determinista", node.id);
            }
        }
    }
}
