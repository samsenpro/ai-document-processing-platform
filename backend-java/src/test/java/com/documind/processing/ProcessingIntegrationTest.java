package com.documind.processing;

import com.documind.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.UUID;

import static com.documind.support.AiServiceStubs.error;
import static com.documind.support.AiServiceStubs.invoiceResult;
import static com.documind.support.AiServiceStubs.processEndpoint;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProcessingIntegrationTest extends IntegrationTest {

    @Test
    void uploadIsProcessedAsynchronouslyAndTheResultIsStored() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        Session session = register();

        JsonNode uploaded = json(upload(session, pdf("factura.pdf"), true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.latestProcessing.attempt").value(1)));
        UUID id = UUID.fromString(uploaded.path("id").asText());

        JsonNode document = awaitDocumentStatus(session, id, "COMPLETED");
        assertThat(document.path("detectedType").asText()).isEqualTo("INVOICE");
        assertThat(document.path("processingTimeMs").asLong()).isPositive();
        assertThat(document.path("latestProcessing").path("status").asText()).isEqualTo("COMPLETED");

        JsonNode result = getJson(session, "/api/v1/documents/" + id + "/result");
        assertThat(result.path("documentType").asText()).isEqualTo("INVOICE");
        assertThat(result.path("confidence").decimalValue()).isEqualByComparingTo("0.94");
        assertThat(result.path("entities").path("invoice_number").asText()).isEqualTo("FE-10234");
        assertThat(result.path("entities").path("total").asLong()).isEqualTo(4_165_000);
        assertThat(result.path("summary").asText()).startsWith("Factura FE-10234");

        JsonNode processing = getJson(session, "/api/v1/documents/" + id + "/processing");
        assertThat(processing.path("documentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(processing.path("stage").asText()).isEqualTo("COMPLETED");
        assertThat(processing.path("attempts").size()).isEqualTo(1);

        JsonNode audit = getJson(session, "/api/v1/audit-logs?documentId=" + id);
        assertThat(audit.path("content").findValuesAsText("event")).containsExactlyInAnyOrder(
                "DOCUMENT_UPLOADED", "PROCESSING_STARTED", "PROCESSING_COMPLETED");
    }

    @Test
    void javaCallsThePythonServiceWithTheInternalKeyTheSourceUrlAndTheCorrelationId() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        Session session = register();

        JsonNode uploaded = json(mockMvc.perform(multipart("/api/v1/documents").file(pdf("factura.pdf"))
                .param("documentType", "INVOICE")
                .header(HttpHeaders.AUTHORIZATION, session.bearer())
                .header("X-Correlation-Id", "corr-e2e-42")));
        UUID id = UUID.fromString(uploaded.path("id").asText());
        awaitDocumentStatus(session, id, "COMPLETED");

        LoggedRequest request = AI_SERVICE.findAll(postRequestedFor(urlEqualTo("/api/v1/process"))).getFirst();
        assertThat(request.getHeader("X-Internal-Api-Key")).isEqualTo(INTERNAL_API_KEY);
        assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("corr-e2e-42");
        JsonNode body = objectMapper.readTree(request.getBodyAsString());
        assertThat(body.path("document_id").asText()).isEqualTo(id.toString());
        assertThat(body.path("document_type").asText()).isEqualTo("INVOICE");
        assertThat(body.path("filename").asText()).isEqualTo("factura.pdf");
        assertThat(body.path("file_url").asText()).endsWith("/internal/v1/documents/" + id + "/content");
    }

    @Test
    void unprocessableDocumentsFailWithoutRetrying() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(error(422, "NO_TEXT_EXTRACTED")));
        Session session = register();
        UUID id = uploadDocument(session, true);

        JsonNode document = awaitDocumentStatus(session, id, "FAILED");
        assertThat(document.path("latestProcessing").path("errorCode").asText()).isEqualTo("NO_TEXT_EXTRACTED");
        assertThat(document.path("actions").path("retry").asBoolean()).isTrue();
        AI_SERVICE.verify(1, postRequestedFor(urlEqualTo("/api/v1/process")));

        mockMvc.perform(get("/api/v1/documents/" + id + "/result").header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.documentStatus").value("FAILED"));
    }

    @Test
    void transientErrorsAreRetriedBeforeFailing() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(error(503, "OCR_UNAVAILABLE")));
        Session session = register();
        UUID id = uploadDocument(session, true);

        JsonNode document = awaitDocumentStatus(session, id, "FAILED");
        assertThat(document.path("latestProcessing").path("errorCode").asText()).isEqualTo("OCR_UNAVAILABLE");
        AI_SERVICE.verify(3, postRequestedFor(urlEqualTo("/api/v1/process")));
    }

    @Test
    void slowResponsesTimeOutInsteadOfBlockingTheWorker() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult().withFixedDelay(3_000)));
        Session session = register();
        UUID id = uploadDocument(session, true);

        JsonNode document = awaitDocumentStatus(session, id, "FAILED");
        assertThat(document.path("latestProcessing").path("errorCode").asText()).isEqualTo("AI_SERVICE_TIMEOUT");
    }

    @Test
    void theCircuitBreakerOpensWhenThePythonServiceKeepsFailing() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(error(500, "INTERNAL_ERROR")));
        Session session = register();

        // Con 5 llamadas fallidas (el mínimo configurado) el circuito se abre a mitad del segundo documento
        awaitDocumentStatus(session, uploadDocument(session, true), "FAILED");
        awaitDocumentStatus(session, uploadDocument(session, true), "FAILED");
        int callsBefore = AI_SERVICE.findAll(postRequestedFor(urlEqualTo("/api/v1/process"))).size();

        UUID rejected = uploadDocument(session, true);
        JsonNode document = awaitDocumentStatus(session, rejected, "FAILED");
        assertThat(document.path("latestProcessing").path("errorCode").asText()).isEqualTo("AI_SERVICE_CIRCUIT_OPEN");
        // Con el circuito abierto no se llama al servicio caído
        AI_SERVICE.verify(callsBefore, postRequestedFor(urlEqualTo("/api/v1/process")));
    }

    @Test
    void aFailedDocumentCanBeRetriedAndCompletes() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(error(422, "UNSUPPORTED_FORMAT")));
        Session session = register();
        UUID id = uploadDocument(session, true);
        awaitDocumentStatus(session, id, "FAILED");

        AI_SERVICE.resetAll();
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        mockMvc.perform(post("/api/v1/documents/" + id + "/process").header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/documents/" + id + "/processing"))
                .andExpect(jsonPath("$.current.attempt").value(2));

        awaitDocumentStatus(session, id, "COMPLETED");
        JsonNode processing = getJson(session, "/api/v1/documents/" + id + "/processing");
        List<String> statuses = processing.path("attempts").findValuesAsText("status");
        assertThat(statuses).containsExactly("FAILED", "COMPLETED");
    }

    @Test
    void invalidTransitionsAreRejected() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        Session session = register();
        UUID id = uploadDocument(session, true);
        awaitDocumentStatus(session, id, "COMPLETED");

        mockMvc.perform(post("/api/v1/documents/" + id + "/process").header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("COMPLETED"));
    }

    @Test
    void documentsBeingProcessedCannotBeProcessedAgainOrDeleted() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult().withFixedDelay(1_000)));
        Session session = register();
        UUID id = uploadDocument(session, true);

        mockMvc.perform(post("/api/v1/documents/" + id + "/process").header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/documents/" + id).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isConflict());
        awaitDocumentStatus(session, id, "COMPLETED");
    }

    @Test
    void theSameIdempotencyKeyQueuesTheDocumentOnlyOnce() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        Session session = register();
        UUID id = uploadDocument(session, false);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/documents/" + id + "/process")
                            .header(HttpHeaders.AUTHORIZATION, session.bearer())
                            .header("X-Idempotency-Key", "process-" + id))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotent-Replayed", i == 0 ? "false" : "true"));
        }
        awaitDocumentStatus(session, id, "COMPLETED");
        assertThat(getJson(session, "/api/v1/documents/" + id + "/processing").path("attempts").size()).isEqualTo(1);
        AI_SERVICE.verify(1, postRequestedFor(urlEqualTo("/api/v1/process")));

        mockMvc.perform(post("/api/v1/documents/" + id + "/process")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .header("X-Idempotency-Key", "bad key!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void temporaryProcessingStateAndResultCacheLiveInRedis() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        Session session = register();
        UUID id = uploadDocument(session, true);
        awaitDocumentStatus(session, id, "COMPLETED");

        assertThat(redis.opsForHash().entries("document:processing:" + id))
                .containsEntry("stage", "COMPLETED")
                .containsKey("processingId");
        assertThat(redis.getExpire("document:processing:" + id)).isPositive();

        getJson(session, "/api/v1/documents/" + id + "/result");
        assertThat(redis.hasKey("cache:document-results::" + id)).isTrue();

        mockMvc.perform(delete("/api/v1/documents/" + id).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNoContent());
        assertThat(redis.hasKey("cache:document-results::" + id)).isFalse();
        assertThat(redis.hasKey("document:processing:" + id)).isFalse();
    }

    @Test
    void anInvalidResponseFromTheAiServiceFailsTheProcessing() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(okJson("""
                {"document_id": "x", "status": "COMPLETED", "document_type": "POEM", "confidence": 3}""")));
        Session session = register();
        UUID id = uploadDocument(session, true);

        JsonNode document = awaitDocumentStatus(session, id, "FAILED");
        assertThat(document.path("latestProcessing").path("errorCode").asText()).isEqualTo("AI_INVALID_RESPONSE");
        AI_SERVICE.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/v1/process")));
    }
}
