package com.quorum.coordinator.domain.port;

import com.quorum.coordinator.domain.AiDecision;
import com.quorum.coordinator.domain.NodeHealth;
import java.util.List;

/**
 * AiAdvisorPort (Puerto de salida / Driven Port)
 * ---------------------------------------------------
 * Contrato para consultar a un asesor de decisiones (hoy implementado por
 * Ollama/Llama3). La capa de aplicación depende solo de esta interfaz.
 *
 * Interfaz reutilizada del proyecto original sin cambios.
 */
public interface AiAdvisorPort {
    boolean isEnabled();
    /** @return una recomendación, o null si el asesor no está disponible. */
    AiDecision consult(List<NodeHealth> nodes, int wQuorum, int rQuorum);
}
