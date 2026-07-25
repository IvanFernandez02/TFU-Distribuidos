package com.quorum.coordinator.domain;

/**
 * StoredRecord (Entidad de dominio)
 * -----------------------------------
 * Copia del dominio compartido. Representa un valor replicado (el contador
 * de likes de un post) junto con su marca de tiempo lógica.
 */
public class StoredRecord {
    public final int value;
    public final long timestamp;

    public StoredRecord(int value, long timestamp) {
        this.value = value;
        this.timestamp = timestamp;
    }

    public boolean isNewerOrEqualThan(StoredRecord other) {
        return other == null || this.timestamp >= other.timestamp;
    }

    @Override
    public String toString() {
        return value + "|" + timestamp;
    }
}
