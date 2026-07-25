package com.quorum.coordinator.domain.port;

/**
 * ReplicaGateway (Puerto de salida / Driven Port)
 * ---------------------------------------------------
 * Contrato que la capa de aplicación (QuorumCoordinatorService) usa para
 * hablar con una réplica, SIN saber si por debajo hay HTTP, gRPC o TCP.
 * El adaptador concreto que implementa este puerto ahora es
 * com.quorum.coordinator.gateway.HttpReplicaGateway (antes era TcpReplicaGateway).
 *
 * Interfaz reutilizada del proyecto original sin cambios.
 */
public interface ReplicaGateway {
    int id();
    boolean ping();
    boolean write(String key, int value, long timestamp);
    /** @return {value, timestamp} como strings, o null si no existe / falló. */
    String[] read(String key);
}
