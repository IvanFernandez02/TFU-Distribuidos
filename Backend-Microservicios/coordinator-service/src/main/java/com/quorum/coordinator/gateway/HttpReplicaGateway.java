package com.quorum.coordinator.gateway;

import com.quorum.coordinator.domain.port.ReplicaGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HttpReplicaGateway (Adaptador de salida / Driven Adapter — HTTP/REST)
 * -----------------------------------------------------------------------
 * Implementación concreta del puerto ReplicaGateway usando RestTemplate de
 * Spring para comunicarse con los endpoints REST del microservicio
 * replica-service. Reemplaza al antiguo TcpReplicaGateway que usaba
 * Sockets TCP con protocolo de texto plano.
 *
 * Esta es la ÚNICA clase que sabe que la comunicación con las réplicas
 * ocurre por HTTP/REST; si se cambiara a gRPC o message queue, solo esta
 * clase cambiaría. El caso de uso (QuorumCoordinatorService) sigue
 * dependiendo únicamente de la interfaz ReplicaGateway.
 */
public class HttpReplicaGateway implements ReplicaGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpReplicaGateway.class);

    private final int id;
    private final String baseUrl;
    private final RestTemplate restTemplate;

    public HttpReplicaGateway(int id, String host, int port, int timeoutMs) {
        this.id = id;
        this.baseUrl = "http://" + host + ":" + port + "/api";
        this.restTemplate = new RestTemplate();
        // Configurar timeout
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate.setRequestFactory(factory);
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public boolean ping() {
        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.getForEntity(baseUrl + "/ping", Map.class);
            return response.getStatusCode().is2xxSuccessful()
                    && "PONG".equals(response.getBody().get("status"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean write(String key, int value, long timestamp) {
        try {
            Map<String, Object> body = Map.of(
                    "key", key,
                    "value", value,
                    "timestamp", timestamp
            );
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl + "/write", body, Map.class);
            return response.getStatusCode().is2xxSuccessful()
                    && "ACK".equals(response.getBody().get("status"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String[] read(String key) {
        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.getForEntity(baseUrl + "/read/" + key, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) return null;
            Map<String, Object> body = response.getBody();
            if (body == null || !"VALUE".equals(body.get("status"))) return null;
            String val = String.valueOf(body.get("value"));
            String ts = String.valueOf(body.get("timestamp"));
            return new String[]{val, ts};
        } catch (Exception e) {
            return null;
        }
    }
}
