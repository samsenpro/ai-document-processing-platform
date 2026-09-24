package com.documind.ai;

/**
 * Cliente del servicio de IA. La lógica de negocio solo depende de esta interfaz: el transporte
 * (HTTP hoy) se puede cambiar sin tocar el procesamiento.
 */
public interface AiProcessingClient {

    /**
     * Procesa el documento de forma síncrona. Se invoca desde el worker de jobs, nunca desde un
     * controlador: la petición del usuario no espera a este resultado.
     *
     * @throws AiServiceUnavailableException  si el servicio no respondió tras los reintentos
     * @throws AiProcessingRejectedException  si el documento no se puede procesar
     */
    AiProcessingResult process(AiProcessingRequest request);
}
