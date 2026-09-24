package com.documind.processing.queue;

/**
 * Cola de jobs de procesamiento. La lógica de negocio solo publica a través de esta interfaz y los
 * consumidores entregan los mensajes a {@code ProcessingWorker}: sustituir Redis Streams por
 * RabbitMQ o Kafka es añadir otra implementación, sin tocar el procesamiento.
 * <p>
 * Semántica: entrega al menos una vez. El worker es idempotente frente a mensajes repetidos.
 */
public interface ProcessingJobQueue {

    void publish(ProcessingJob job);
}
