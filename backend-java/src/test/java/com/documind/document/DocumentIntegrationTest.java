package com.documind.document;

import com.documind.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentIntegrationTest extends IntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void uploadStoresTheFileOutsideTheDatabaseAndReturnsMetadata() throws Exception {
        Session session = register();
        MockMultipartFile file = pdf("../../etc/Factura marzo.pdf");

        JsonNode body = json(upload(session, file, false)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/documents/"))));

        assertThat(body.path("filename").asText()).isEqualTo("Factura marzo.pdf");
        assertThat(body.path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(body.path("status").asText()).isEqualTo("UPLOADED");
        assertThat(body.path("requestedType").asText()).isEqualTo("AUTO");
        assertThat(body.path("checksumSha256").asText()).hasSize(64);
        assertThat(body.path("actions").path("process").asBoolean()).isTrue();

        Document document = documentRepository.findById(UUID.fromString(body.path("id").asText())).orElseThrow();
        Path stored = STORAGE.resolve(document.getStorageKey());
        assertThat(stored).exists().startsWith(STORAGE);
        assertThat(Files.readAllBytes(stored)).isEqualTo(file.getBytes());
    }

    @Test
    void contentIsValidatedByItsBytesNotByTheDeclaredType() throws Exception {
        Session session = register();
        MockMultipartFile disguised = new MockMultipartFile("file", "invoice.pdf", "application/pdf",
                "MZ\u0090\u0000 not really a pdf".getBytes(StandardCharsets.ISO_8859_1));
        upload(session, disguised, false)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        upload(session, empty, false).andExpect(status().isBadRequest());

        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain",
                "Contrato de arrendamiento".getBytes(StandardCharsets.UTF_8));
        upload(session, text, false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("text/plain"));
    }

    @Test
    void usersOnlySeeTheirOwnDocumentsAndAdminsSeeTheirOrganization() throws Exception {
        Session admin = register();
        Session member = createMember(admin, "USER");
        Session outsider = register();
        UUID adminDocument = uploadDocument(admin, false);
        UUID memberDocument = uploadDocument(member, false);

        // Un documento ajeno responde 404 (no 403) para no revelar que existe
        mockMvc.perform(get("/api/v1/documents/" + adminDocument).header(HttpHeaders.AUTHORIZATION, member.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/" + memberDocument).header(HttpHeaders.AUTHORIZATION, outsider.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/documents/" + memberDocument).header(HttpHeaders.AUTHORIZATION, outsider.bearer()))
                .andExpect(status().isNotFound());

        assertThat(getJson(member, "/api/v1/documents").path("totalElements").asInt()).isEqualTo(1);
        assertThat(getJson(admin, "/api/v1/documents").path("totalElements").asInt()).isEqualTo(2);
        assertThat(getJson(admin, "/api/v1/documents/" + memberDocument).path("ownerId").asText())
                .isEqualTo(member.userId().toString());
        assertThat(getJson(outsider, "/api/v1/documents").path("totalElements").asInt()).isZero();
    }

    @Test
    void listIsPaginatedFilteredAndFreeOfNPlusOneQueries() throws Exception {
        Session session = register();
        for (int i = 0; i < 3; i++) {
            uploadDocument(session, false);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        // Página de 20 en ambas mediciones: sin llenarla, Spring Data no necesita la consulta count
        statistics.clear();
        getJson(session, "/api/v1/documents?size=20");
        long queriesWithThree = statistics.getPrepareStatementCount();

        for (int i = 0; i < 7; i++) {
            uploadDocument(session, false);
        }
        statistics.clear();
        JsonNode page = getJson(session, "/api/v1/documents?size=20");
        assertThat(page.path("content").size()).isEqualTo(10);
        // El número de consultas no crece con el número de documentos listados
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(queriesWithThree);

        JsonNode firstPage = getJson(session, "/api/v1/documents?size=4&page=0");
        assertThat(firstPage.path("totalElements").asInt()).isEqualTo(10);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(3);
        assertThat(getJson(session, "/api/v1/documents?status=COMPLETED").path("totalElements").asInt()).isZero();
        assertThat(getJson(session, "/api/v1/documents?status=UPLOADED").path("totalElements").asInt()).isEqualTo(10);

        mockMvc.perform(get("/api/v1/documents?sort=passwordHash").header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRemovesTheDocumentAndItsFileButKeepsTheAuditTrail() throws Exception {
        Session session = register();
        UUID id = uploadDocument(session, false);
        Path stored = STORAGE.resolve(documentRepository.findById(id).orElseThrow().getStorageKey());

        mockMvc.perform(delete("/api/v1/documents/" + id).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/documents/" + id).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNotFound());
        assertThat(stored).doesNotExist();
        JsonNode audit = getJson(session, "/api/v1/audit-logs?documentId=" + id);
        assertThat(audit.path("content").findValuesAsText("event")).containsExactlyInAnyOrder(
                "DOCUMENT_UPLOADED", "DOCUMENT_DELETED");
    }

    @Test
    void statsSummarizeTheUsersDocuments() throws Exception {
        Session session = register();
        uploadDocument(session, false);
        uploadDocument(session, false);

        JsonNode stats = getJson(session, "/api/v1/documents/stats");
        assertThat(stats.path("total").asInt()).isEqualTo(2);
        assertThat(stats.path("uploaded").asInt()).isEqualTo(2);
        assertThat(stats.path("completed").asInt()).isZero();
        assertThat(stats.path("averageProcessingTimeMs").isNull()).isTrue();
    }

    @Test
    void uploadWithTheSameIdempotencyKeyCreatesASingleDocument() throws Exception {
        Session session = register();
        var request = multipart("/api/v1/documents")
                .file(pdf("invoice.pdf"))
                .param("autoProcess", "false")
                .header(HttpHeaders.AUTHORIZATION, session.bearer())
                .header("X-Idempotency-Key", "upload-key-0001");

        JsonNode first = json(mockMvc.perform(request).andExpect(header().string("Idempotent-Replayed", "false")));
        JsonNode second = json(mockMvc.perform(request).andExpect(header().string("Idempotent-Replayed", "true")));

        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(getJson(session, "/api/v1/documents").path("totalElements").asInt()).isEqualTo(1);

        // La misma clave con otro archivo es un error del cliente
        MockMultipartFile other = new MockMultipartFile("file", "other.txt", "text/plain",
                "otro contenido".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(other)
                        .param("autoProcess", "false")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .header("X-Idempotency-Key", "upload-key-0001"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
}
