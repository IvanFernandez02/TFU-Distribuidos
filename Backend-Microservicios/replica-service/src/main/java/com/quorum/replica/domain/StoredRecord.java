package com.quorum.replica.domain;

/**
 * StoredRecord (Entidad de dominio)
 * -----------------------------------
 * Representa un valor replicado (el contador de likes de un post) junto con
 * su marca de tiempo lógica. No conoce nada de sockets, TCP, HTTP ni formatos
 * de mensaje: es una regla de negocio pura (Last-Write-Wins).
 *
 * Clase reutilizada del proyecto original sin cambios en la lógica.
 */
public class StoredRecord {
    public final int value;
    public final long timestamp;

    public StoredRecord(int value, long timestamp) {
        this.value = value;
        this.timestamp = timestamp;
    }

    /** Regla de negocio: solo se acepta una escritura si su timestamp es igual o más reciente. */
    public boolean isNewerOrEqualThan(StoredRecord other) {
        return other == null || this.timestamp >= other.timestamp;
    }

    @Override
    public String toString() {
        return value + "|" + timestamp;
    }
}
