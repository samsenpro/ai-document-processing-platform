package com.documind.processing;

import com.documind.processing.queue.ProcessingJob;

/** Se publica dentro de la transacción que crea el procesamiento; el job se encola tras el commit. */
public record ProcessingRequestedEvent(ProcessingJob job) {
}
