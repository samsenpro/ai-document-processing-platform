package com.documind.ai;

/**
 * El servicio de IA respondió, pero el documento no se puede procesar (formato no soportado, sin
 * texto, demasiado grande...). Reintentar no cambiaría el resultado: no se reintenta y no cuenta
 * como fallo para el circuit breaker (el servicio funciona bien).
 */
public final class AiProcessingRejectedException extends AiServiceException {

    public AiProcessingRejectedException(String errorCode, String message) {
        super(errorCode, message, null);
    }
}
