package com.documind.processing;

import com.documind.document.Document;
import com.documind.document.DocumentRepository;
import com.documind.document.DocumentStatus;
import com.documind.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.documind.support.AiServiceStubs.invoiceResult;
import static com.documind.support.AiServiceStubs.processEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Garantías de la base de datos y recuperación de procesamientos atascados. */
class ProcessingPersistenceIntegrationTest extends IntegrationTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentProcessingRepository processingRepository;

    @Autowired
    private StaleProcessingReaper reaper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theDatabaseRejectsTwoActiveProcessingsForTheSameDocument() throws Exception {
        Session session = register();
        UUID id = uploadDocument(session, false);

        transactionTemplate.executeWithoutResult(status -> {
            Document document = documentRepository.findById(id).orElseThrow();
            document.transitionTo(DocumentStatus.PROCESSING);
            processingRepository.saveAndFlush(new DocumentProcessing(document, 1, session.userId(), "c1", Instant.now()));
        });

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> processingRepository.saveAndFlush(
                new DocumentProcessing(documentRepository.getReferenceById(id), 2, session.userId(), "c2", Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleProcessingsAreFailedSoTheyCanBeRetried() throws Exception {
        Session session = register();
        UUID id = uploadDocument(session, false);
        // Simula un procesamiento que quedó en cola hace una hora (mensaje perdido, instancia caída...)
        UUID processingId = transactionTemplate.execute(status -> {
            Document document = documentRepository.findById(id).orElseThrow();
            document.transitionTo(DocumentStatus.PROCESSING);
            return processingRepository.save(new DocumentProcessing(document, 1, session.userId(), "c1",
                    Instant.now().minus(Duration.ofHours(1)))).getId();
        });

        reaper.failStaleProcessings();

        JsonNode document = getJson(session, "/api/v1/documents/" + id);
        assertThat(document.path("status").asText()).isEqualTo("FAILED");
        assertThat(document.path("latestProcessing").path("id").asText()).isEqualTo(processingId.toString());
        assertThat(document.path("latestProcessing").path("errorCode").asText()).isEqualTo("PROCESSING_TIMEOUT");
        assertThat(document.path("actions").path("retry").asBoolean()).isTrue();
    }

    @Test
    void resultEntitiesAreStoredAsQueryableJsonb() throws Exception {
        AI_SERVICE.stubFor(processEndpoint().willReturn(invoiceResult()));
        Session session = register();
        UUID id = uploadDocument(session, true);
        awaitDocumentStatus(session, id, "COMPLETED");

        // Los campos de cada tipo de documento se pueden consultar con los operadores JSONB de PostgreSQL
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select jsonb_typeof(entities) as kind, entities ->> 'invoice_number' as number,
                       (entities ->> 'total')::numeric as total
                from document_results where document_id = ?""", id);
        assertThat(row).containsEntry("kind", "object").containsEntry("number", "FE-10234");
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("4165000");

        JsonNode stats = getJson(session, "/api/v1/documents/stats");
        assertThat(stats.path("completed").asInt()).isEqualTo(1);
        assertThat(stats.path("byType").path("INVOICE").asInt()).isEqualTo(1);
        assertThat(stats.path("averageProcessingTimeMs").asLong()).isPositive();
    }
}
