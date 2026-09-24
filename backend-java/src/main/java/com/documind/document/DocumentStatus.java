package com.documind.document;

import java.util.EnumSet;
import java.util.Set;

/**
 * Máquina de estados del documento.
 * <pre>
 * UPLOADED ──► PROCESSING ──► COMPLETED
 *                  │   ▲
 *                  ▼   │ (reintento)
 *                FAILED
 * </pre>
 * COMPLETED es final. Un reintento (RETRY) es la transición FAILED → PROCESSING, que crea un nuevo
 * intento de procesamiento.
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(DocumentStatus target) {
        return allowedTargets().contains(target);
    }

    public Set<DocumentStatus> allowedTargets() {
        return switch (this) {
            case UPLOADED -> EnumSet.of(PROCESSING);
            case PROCESSING -> EnumSet.of(COMPLETED, FAILED);
            case FAILED -> EnumSet.of(PROCESSING);
            case COMPLETED -> EnumSet.noneOf(DocumentStatus.class);
        };
    }
}
