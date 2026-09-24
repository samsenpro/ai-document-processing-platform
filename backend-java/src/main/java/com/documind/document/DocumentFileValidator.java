package com.documind.document;

import com.documind.document.storage.StorageProperties;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Valida el archivo subido antes de guardarlo: tamaño, formato real y nombre. */
@Component
public class DocumentFileValidator {

    private static final int HEADER_BYTES = 8192;
    private static final int MAX_FILENAME_LENGTH = 255;

    private final long maxBytes;

    public DocumentFileValidator(StorageProperties properties) {
        this.maxBytes = properties.maxFileSize().toBytes();
    }

    public DocumentFormat validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "File is empty");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "File exceeds the maximum allowed size", Map.of("maxBytes", maxBytes));
        }
        DocumentFormat format = DocumentFormat.detect(readHeader(file), file.getOriginalFilename())
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "Unsupported file type. Allowed: PDF, PNG, JPEG, TIFF, WEBP, DOCX and TXT"));
        if (format == DocumentFormat.DOCX && !isDocx(file)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "File is not a valid DOCX document");
        }
        return format;
    }

    /** Solo el nombre (sin rutas), sin caracteres de control y con longitud acotada. */
    public String sanitizeFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "document";
        }
        return name.length() <= MAX_FILENAME_LENGTH ? name : name.substring(name.length() - MAX_FILENAME_LENGTH);
    }

    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEADER_BYTES);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Un DOCX es un ZIP que contiene word/document.xml; se recorren solo las entradas, sin extraerlas. */
    private static boolean isDocx(MultipartFile file) {
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null && entries++ < 1000) {
                if ("word/document.xml".equals(entry.getName())) {
                    return true;
                }
            }
            return false;
        } catch (IOException | IllegalArgumentException ex) {
            return false;
        }
    }
}
