package com.documind.ai;

/**
 * Fallo al procesar un documento en el servicio de IA. El {@code errorCode} se guarda en el
 * procesamiento para que el usuario sepa por qué falló.
 */
public abstract sealed class AiServiceException extends RuntimeException
        permits AiServiceUnavailableException, AiProcessingRejectedException {

    private final String errorCode;

    protected AiServiceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
