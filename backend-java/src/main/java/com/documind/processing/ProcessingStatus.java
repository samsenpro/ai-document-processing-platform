package com.documind.processing;

/** Estado de un intento de procesamiento (un documento puede tener varios intentos). */
public enum ProcessingStatus {
    /** Job creado y publicado en la cola, pendiente de un worker. */
    QUEUED,
    /** Un worker lo tomó y está esperando al servicio de IA. */
    RUNNING,
    COMPLETED,
    FAILED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
