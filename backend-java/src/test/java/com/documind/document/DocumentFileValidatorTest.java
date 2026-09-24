package com.documind.document;

import com.documind.document.storage.StorageProperties;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileValidatorTest {

    private final DocumentFileValidator validator =
            new DocumentFileValidator(new StorageProperties("/tmp", DataSize.ofKilobytes(1)));

    @Test
    void detectsFormatsByMagicBytes() {
        assertThat(DocumentFormat.detect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "x.bin")).contains(DocumentFormat.PDF);
        assertThat(DocumentFormat.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, "x"))
                .contains(DocumentFormat.PNG);
        assertThat(DocumentFormat.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}, "x"))
                .contains(DocumentFormat.JPEG);
        assertThat(DocumentFormat.detect(new byte[]{'I', 'I', 42, 0}, "scan.tif")).contains(DocumentFormat.TIFF);
        // Texto y ZIP exigen además la extensión, porque sus firmas son genéricas
        assertThat(DocumentFormat.detect("hola".getBytes(StandardCharsets.UTF_8), "notas.txt")).contains(DocumentFormat.TEXT);
        assertThat(DocumentFormat.detect("<script>".getBytes(StandardCharsets.UTF_8), "page.html")).isEmpty();
        assertThat(DocumentFormat.detect(new byte[]{'M', 'Z', 0, 0}, "virus.pdf")).isEmpty();
    }

    @Test
    void acceptsRealDocxAndRejectsOtherZipFiles() throws IOException {
        assertThat(validator.validate(new MockMultipartFile("file", "cv.docx", null, zipWith("word/document.xml"))))
                .isEqualTo(DocumentFormat.DOCX);
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "cv.docx", null, zipWith("evil.sh"))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "a.pdf", null, new byte[0])))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        byte[] big = new byte[2048];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 5);
        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "a.pdf", null, big)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @ParameterizedTest
    @CsvSource({
            "factura.pdf, factura.pdf",
            "../../etc/passwd, passwd",
            "C:\\Users\\ana\\contrato.docx, contrato.docx",
            "'', document",
            "'..', document",
    })
    void sanitizesFilenames(String original, String expected) {
        assertThat(validator.sanitizeFilename(original)).isEqualTo(expected);
    }

    private static byte[] zipWith(String entryName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
