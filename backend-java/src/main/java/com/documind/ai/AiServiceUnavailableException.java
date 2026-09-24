package com.documind.ai;

/**
 * Fallo transitorio (conexión rechazada, timeout, 5xx): se reintenta y cuenta para el circuit
 * breaker.
 */
public final class AiServiceUnavailableException extends AiServiceException {

    public AiServiceUnavailableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
