package com.documind.processing;

/**
 * Etapa detallada del procesamiento en curso. Es información temporal: vive solo en Redis
 * ({@code document:processing:{id}}); el estado duradero está en PostgreSQL.
 */
public enum ProcessingStage {
    QUEUED,
    CALLING_AI_SERVICE,
    SAVING_RESULT,
    COMPLETED,
    FAILED
}
