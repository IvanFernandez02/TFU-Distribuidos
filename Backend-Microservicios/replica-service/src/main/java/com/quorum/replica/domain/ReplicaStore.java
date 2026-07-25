package com.quorum.replica.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.sql.*;

/**
 * ReplicaStore (Entidad de dominio / Agregado con persistencia en PostgreSQL)
 * ---------------------------------------------------------------------------
 * El "qué" de un nodo réplica: gestiona el almacenamiento persistente con la
 * regla de negocio Last-Write-Wins aplicada en write(). Deliberadamente NO
 * sabe cómo llega la petición (eso es responsabilidad del adaptador REST
 * ReplicaController). Esta separación es el principio central de la
 * arquitectura hexagonal: el dominio es agnóstico de la tecnología de transporte.
 *
 * Adaptado del proyecto original para usar la configuración de Spring Boot
 * (application.yml) en vez de leer replicas.properties manualmente.
 */
@Component
public class ReplicaStore {

    private static final Logger log = LoggerFactory.getLogger(ReplicaStore.class);

    @Value("${replica.node-id}")
    private int nodeId;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @PostConstruct
    public void initialize() {
        initializeDatabase();
    }

    private void initializeDatabase() {
        if (dbUrl != null && dbUrl.endsWith("/replica_db_1") && nodeId != 1) {
            dbUrl = dbUrl.replace("/replica_db_1", "/replica_db_" + nodeId);
            log.info("[NODO-{}] Ajustando URL de DB automáticamente a: {}", nodeId, dbUrl);
        }
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            log.error("[NODO-{}] ERROR: Driver de PostgreSQL no encontrado en el classpath.", nodeId);
            return;
        }

        // Obtener la URL de conexión base para intentar crear la base de datos si no existe
        String baseDbUrl = dbUrl.substring(0, dbUrl.lastIndexOf("/")) + "/postgres";
        String targetDbName = dbUrl.substring(dbUrl.lastIndexOf("/") + 1);

        try (Connection conn = DriverManager.getConnection(baseDbUrl, dbUser, dbPassword)) {
            boolean dbExists = false;
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                ps.setString(1, targetDbName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        dbExists = true;
                    }
                }
            }

            if (!dbExists) {
                log.info("[NODO-{}] Base de datos '{}' no existe. Creándola...", nodeId, targetDbName);
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("CREATE DATABASE " + targetDbName);
                    log.info("[NODO-{}] Base de datos '{}' creada exitosamente.", nodeId, targetDbName);
                }
            }
        } catch (SQLException e) {
            log.warn("[NODO-{}] Nota al verificar/crear base de datos '{}': {}", nodeId, targetDbName, e.getMessage());
        }

        // Conectar a la base de datos destino y crear la tabla si no existe
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS likes (" +
                    "key_name VARCHAR(255) PRIMARY KEY, " +
                    "value_count INT NOT NULL, " +
                    "timestamp_val BIGINT NOT NULL)");
            log.info("[NODO-{}] Tabla 'likes' inicializada en PostgreSQL.", nodeId);
        } catch (SQLException e) {
            log.error("[NODO-{}] Error al inicializar la tabla 'likes': {}", nodeId, e.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    /** @return true si la escritura fue aceptada (regla Last-Write-Wins). */
    public boolean write(String key, int value, long timestamp) {
        StoredRecord current = read(key);
        StoredRecord incoming = new StoredRecord(value, timestamp);

        if (incoming.isNewerOrEqualThan(current)) {
            String sql = "INSERT INTO likes (key_name, value_count, timestamp_val) " +
                         "VALUES (?, ?, ?) " +
                         "ON CONFLICT (key_name) DO UPDATE " +
                         "SET value_count = EXCLUDED.value_count, timestamp_val = EXCLUDED.timestamp_val";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setInt(2, value);
                ps.setLong(3, timestamp);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                log.error("[NODO-{}] Error al escribir clave {} en la DB: {}", nodeId, key, e.getMessage());
            }
        }
        return false; // versión obsoleta
    }

    public StoredRecord read(String key) {
        String sql = "SELECT value_count, timestamp_val FROM likes WHERE key_name = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int val = rs.getInt("value_count");
                    long ts = rs.getLong("timestamp_val");
                    return new StoredRecord(val, ts);
                }
            }
        } catch (SQLException e) {
            log.error("[NODO-{}] Error al leer clave {} de la DB: {}", nodeId, key, e.getMessage());
        }
        return null;
    }

    public int getNodeId() {
        return nodeId;
    }
}
