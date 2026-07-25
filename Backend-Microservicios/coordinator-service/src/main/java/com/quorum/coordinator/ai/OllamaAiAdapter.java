package com.quorum.coordinator.ai;

import com.quorum.coordinator.domain.AiDecision;
import com.quorum.coordinator.domain.NodeHealth;
import com.quorum.coordinator.domain.port.AiAdvisorPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OllamaAiAdapter (Adaptador de salida / Driven Adapter)
 * -------------------------------------------------------------
 * Implementación concreta del puerto AiAdvisorPort usando la API HTTP local
 * de Ollama (modelo Llama3). Es la única clase del proyecto que sabe que el
 * asesor de decisiones es un LLM servido por Ollama; QuorumCoordinatorService
 * solo conoce la interfaz AiAdvisorPort.
 *
 * Clase reutilizada del proyecto original, solo se cambió el paquete.
 */
public class OllamaAiAdapter implements AiAdvisorPort {

    private final HttpClient client;
    private final String ollamaUrl;
    private final String model;
    private final Duration timeout;
    private final boolean enabled;

    private static final Pattern RESPONSE_FIELD = Pattern.compile("\"response\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"done\"", Pattern.DOTALL);

    public OllamaAiAdapter(boolean enabled, String ollamaUrl, String model, long timeoutMs) {
        this.enabled = enabled;
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public AiDecision consult(List<NodeHealth> nodes, int wQuorum, int rQuorum) {
        if (!enabled) return null;
        try {
            String prompt = buildPrompt(nodes, wQuorum, rQuorum);
            String jsonBody = "{\"model\":\"" + escape(model) + "\",\"prompt\":\"" + escape(prompt) + "\",\"stream\":false}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            String modelText = extractResponseField(response.body());
            return modelText == null ? null : parseDecision(modelText);
        } catch (Exception e) {
            return null; // Ollama no disponible: se degrada de forma segura (sin IA)
        }
    }

    private String buildPrompt(List<NodeHealth> nodes, int wQuorum, int rQuorum) {
        StringBuilder sb = new StringBuilder();
        sb.append("Eres el modulo de decision de un balanceador de carga para un sistema de ")
          .append("replicacion con quorum (N=").append(nodes.size())
          .append(", W=").append(wQuorum).append(", R=").append(rQuorum).append("). ")
          .append("Estado actual de cada nodo replica:\n");
        for (NodeHealth n : nodes) {
            sb.append("Nodo ").append(n.id).append(": alive=").append(n.alive)
              .append(", fallos_consecutivos=").append(n.consecutiveFailures)
              .append(", estado_actual=").append(n.state).append("\n");
        }
        sb.append("Responde UNICAMENTE con una linea en este formato exacto, sin texto adicional:\n")
          .append("PRIORIDAD=<ids separados por coma>;BREAKER_1=<CLOSED|OPEN|HALF_OPEN>;")
          .append("BREAKER_2=<CLOSED|OPEN|HALF_OPEN>;BREAKER_3=<CLOSED|OPEN|HALF_OPEN>\n")
          .append("Debes incluir en PRIORIDAD a TODOS los nodos que tengan alive=true, ordenándolos de menor a mayor cantidad de fallos (ej. si todos están bien, la prioridad debe ser los tres nodos: 1,2,3).");
        return sb.toString();
    }

    private String extractResponseField(String ollamaJsonBody) {
        Matcher m = RESPONSE_FIELD.matcher(ollamaJsonBody);
        if (!m.find()) return null;
        return m.group(1).replace("\\n", " ").replace("\\\"", "\"").trim();
    }

    private AiDecision parseDecision(String text) {
        String line = null;
        for (String candidate : text.split("\\r?\\n")) {
            if (candidate.contains("PRIORIDAD=")) { line = candidate.trim(); break; }
        }
        if (line == null) return null;

        Set<Integer> priority = new LinkedHashSet<>();
        Map<Integer, String> breaker = new LinkedHashMap<>();
        for (String field : line.split(";")) {
            String[] kv = field.split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim(), value = kv[1].trim();
            if (key.equals("PRIORIDAD")) {
                for (String idStr : value.split(",")) {
                    try { priority.add(Integer.parseInt(idStr.trim())); } catch (NumberFormatException ignored) {}
                }
            } else if (key.startsWith("BREAKER_")) {
                try {
                    int nodeId = Integer.parseInt(key.substring("BREAKER_".length()));
                    String state = value.toUpperCase();
                    if (state.equals("CLOSED") || state.equals("OPEN") || state.equals("HALF_OPEN")) breaker.put(nodeId, state);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (priority.isEmpty() && breaker.isEmpty()) return null;
        return new AiDecision(priority, breaker, text);
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
