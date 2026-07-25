package com.quorum.coordinator.domain;

/**
 * NodeHealth (Entidad de dominio)
 * ----------------------------------
 * Estado de salud de una réplica desde el punto de vista del coordinador,
 * y máquina de estados del patrón Circuit Breaker (Nygard).
 *
 *  - CLOSED    : el nodo responde con normalidad, se usa en operaciones.
 *  - OPEN      : fallos consecutivos >= umbral; se excluye durante un cooldown.
 *  - HALF_OPEN : pasado el cooldown, se reintenta con el nodo.
 *
 * Clase reutilizada del proyecto original sin cambios.
 */
public class NodeHealth {
    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public final int id;
    public final String host;
    public final int port;

    public volatile boolean alive = false;
    public volatile int consecutiveFailures = 0;
    public volatile CircuitState state = CircuitState.CLOSED;
    public volatile long openedAt = 0;

    public NodeHealth(int id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
    }

    public String address() {
        return host + ":" + port;
    }

    public boolean isAvailable() {
        return alive && state != CircuitState.OPEN;
    }
}
