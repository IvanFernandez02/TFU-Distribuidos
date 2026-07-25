package com.quorum.coordinator.domain;

import java.util.Map;
import java.util.Set;

/**
 * AiDecision (Objeto de valor / DTO de dominio)
 * -------------------------------------------------
 * Resultado de consultar al AiAdvisorPort: qué nodos priorizar y qué
 * recomienda el modelo para el circuit breaker de cada uno.
 *
 * Clase reutilizada del proyecto original sin cambios.
 */
public class AiDecision {
    public final Set<Integer> priorityNodeIds;
    public final Map<Integer, String> breakerRecommendation;
    public final String raw;

    public AiDecision(Set<Integer> priorityNodeIds, Map<Integer, String> breakerRecommendation, String raw) {
        this.priorityNodeIds = priorityNodeIds;
        this.breakerRecommendation = breakerRecommendation;
        this.raw = raw;
    }
}
