package com.documind.document;

import com.documind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static com.documind.support.AiServiceStubs.invoiceResult;
import static com.documind.support.AiServiceStubs.processEndpoint;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint interno desde el que el servicio de IA descarga el archivo. */
class InternalEndpointIntegrationTest extends IntegrationTest {

    @Test
    void requiresTheInternalKeyAndNeverAcceptsUserTokens() throws Exception {
        Session session = register();
        UUID id = uploadDocument(session, false);
        String url = "/internal/v1/documents/" + id + "/content";

        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).header("X-Internal-Api-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void onlyServesDocumentsThatAreBeingProcessed() throws Exception {
        Session session = register();
        UUID id = uploadDocument(session, false);

        mockMvc.perform(get("/internal/v1/documents/" + id + "/content").header("X-Internal-Api-Key", INTERNAL_API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("UPLOADED"));
        mockMvc.perform(get("/internal/v1/documents/" + UUID.randomUUID() + "/content")
                        .header("X-Internal-Api-Key", INTERNAL_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void servesTheOriginalBytesWhileProcessing() throws Exception {
        // El servicio de IA tarda: el documento sigue en PROCESSING mientras se descarga
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult().withFixedDelay(1_500)));
        Session session = register();
        UUID id = uploadDocument(session, true);
        byte[] original = pdf("invoice.pdf").getBytes();

        mockMvc.perform(get("/internal/v1/documents/" + id + "/content").header("X-Internal-Api-Key", INTERNAL_API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(original));
        awaitDocumentStatus(session, id, "COMPLETED");
    }
}
