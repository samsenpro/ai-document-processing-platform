package com.documind.document;

import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static com.documind.document.DocumentStatus.COMPLETED;
import static com.documind.document.DocumentStatus.FAILED;
import static com.documind.document.DocumentStatus.PROCESSING;
import static com.documind.document.DocumentStatus.UPLOADED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStatusTest {

    @ParameterizedTest
    @CsvSource({
            "UPLOADED, PROCESSING, true",
            "PROCESSING, COMPLETED, true",
            "PROCESSING, FAILED, true",
            "FAILED, PROCESSING, true",
            "UPLOADED, COMPLETED, false",
            "UPLOADED, FAILED, false",
            "PROCESSING, PROCESSING, false",
            "COMPLETED, PROCESSING, false",
            "COMPLETED, FAILED, false",
            "FAILED, COMPLETED, false",
    })
    void onlyTheDocumentedTransitionsAreAllowed(DocumentStatus from, DocumentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void completedIsFinal() {
        assertThat(COMPLETED.allowedTargets()).isEmpty();
    }

    @Test
    void invalidTransitionsOnADocumentAreRejectedWithAConflict() {
        Document document = new Document(java.util.UUID.randomUUID(), null, null, "a.pdf", "application/pdf", 10,
                "0".repeat(64), "key", RequestedDocumentType.AUTO);
        assertThat(document.getStatus()).isEqualTo(UPLOADED);

        assertThatThrownBy(() -> document.transitionTo(COMPLETED))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
                    assertThat(ex.details()).containsEntry("currentStatus", "UPLOADED");
                });

        document.transitionTo(PROCESSING);
        document.markFailed();
        assertThat(document.getStatus()).isEqualTo(FAILED);
        document.transitionTo(PROCESSING); // reintento
        document.markCompleted(DocumentType.INVOICE, 1200);
        assertThat(document.getStatus()).isEqualTo(COMPLETED);
        assertThat(document.getDetectedType()).isEqualTo(DocumentType.INVOICE);
    }
}
