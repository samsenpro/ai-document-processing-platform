package com.documind.processing.queue;

import com.documind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static com.documind.support.AiServiceStubs.invoiceResult;
import static com.documind.support.AiServiceStubs.processEndpoint;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedisStreamJobQueueIntegrationTest extends IntegrationTest {

    @Autowired
    private RedisStreamJobQueue queue;

    /** Reinicio de la API con el stream y el grupo de consumidores ya creados en Redis. */
    @Test
    void restartsWhenTheConsumerGroupAlreadyExists() throws Exception {
        queue.stop();
        assertThatCode(queue::start).doesNotThrowAnyException();

        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        Session session = register();
        UUID id = uploadDocument(session, true);
        awaitDocumentStatus(session, id, "COMPLETED");
    }
}
